package com.wordbattle.ui

import com.wordbattle.data.Question
import com.wordbattle.data.WordRepository
import com.wordbattle.data.WrongWord
import com.wordbattle.data.WrongWordRepository
import com.wordbattle.debug.DebugLog
import kotlin.math.exp
import kotlin.math.pow

/**
 * 错题练习会话（纯逻辑，无 Compose 依赖；UI 状态在 PracticeScreen 里）
 * 规则见 docs/requirements/错题本需求文档_v2.md 七、错题练习：
 * - 题池 = 传入的错题记录（含 0 星词）
 * - 抽题 = 保底 + 加权：词数 ≤ 题数时先给每个词保底 1 题（保证全词覆盖），
 *   剩余名额按权重抽，最后整体洗牌；词数 > 题数时纯加权抽 count 个不同词
 * - 权重 weight = (1 + wrongCount) * 2^(-小时数/24)
 *   （答错越多权重越高；距上次答错越久权重越低，24h 衰减一半——交接文档公式为正指数系笔误，
 *    按已对齐的"时间衰减：最近答错翻倍"语义实现为负指数）
 * - 每题 10 秒倒计时，超时 = 答错
 * - 答错/超时：星级+1（≤6）；答对：星级-1
 * - 同一词会话内连续答对 3 次 → 直接归 0，计数重置；期间答错计数清零
 */
class PracticeSession(
    val username: String,
    val records: List<WrongWord>,
    val wordRepo: WordRepository
) {
    /** 会话开始时的星级（结束页算升/降星用），key = word|direction */
    val starAtStart: Map<String, Int> = records.associate { key(it) to it.starLevel }
    /** 会话内各词当前星级（本地镜像，随答题更新） */
    private val stars: MutableMap<String, Int> = starAtStart.toMutableMap()
    /** 会话内各词连续答对数 */
    private val streaks: MutableMap<String, Int> = mutableMapOf()

    var questions: List<Question> = emptyList()
    var recordAt: List<WrongWord> = emptyList()   // 每题对应的原始记录

    fun key(rec: WrongWord) = "${rec.word}|${rec.direction}"

    /** 当前题显示用的星级 */
    fun starOf(index: Int): Int {
        val rec = recordAt.getOrNull(index) ?: return 0
        return stars[key(rec)] ?: rec.starLevel
    }

    /** 抽题开始：词数 ≤ 题数时每词保底 1 题（全词覆盖），剩余名额加权抽（重复是"连对 3 次归零"的触发途径） */
    fun start(count: Int) {
        val picked = weightedPick(records, count)
        recordAt = picked
        questions = picked.mapIndexed { i, rec -> buildQuestion(i + 1, rec) }
        questions.forEach { q ->
            DebugLog.i("[Practice] Q${q.round}: 题目=${q.questionText} 正确idx=${q.correctIdx} 选项=${q.options}")
        }
        DebugLog.i("[Practice] 开始: user=$username 题数=${questions.size} 词数=${records.size}")
    }

    /** 单条记录权重：频率因子 (1+wrongCount) × 时间衰减 2^(-小时数/24)（lastWrongTime 距现在越久权重越低） */
    fun computeWeight(rec: WrongWord, now: Long = System.currentTimeMillis()): Double {
        val hours = (now - rec.lastWrongTime) / 3_600_000.0
        return (1 + rec.wrongCount) * 2.0.pow(-hours / 24.0)
    }

    /**
     * 保底 + 加权抽样（v2.3 修复：旧实现纯独立加权抽，词数 < 题数时低频词可能整场 0 题）：
     * - 词数 ≤ 题数：每个词保底 1 题（全词覆盖），剩余名额按权重重复抽，最后整体洗牌
     * - 词数 > 题数：维持旧行为——独立加权抽 count 题
     * 重复仍保留（保底词也可能被额外抽中），是"连对 3 次归零"的触发途径。
     * 边界：空池 → 空列表；1 个词 → 必抽它
     */
    fun weightedPick(records: List<WrongWord>, count: Int, now: Long = System.currentTimeMillis()): List<WrongWord> {
        if (records.isEmpty() || count <= 0) return emptyList()
        return if (records.size <= count) {
            val base = records.toMutableList()
            repeat(count - records.size) { base += pickOne(records, now) }
            base.shuffled()
        } else {
            List(count) { pickOne(records, now) }
        }
    }

    /** 在池内按权重独立抽 1 个：[0,total) 取均匀点，按权重前缀和切分区间（标准加权随机，无截断偏差） */
    private fun pickOne(records: List<WrongWord>, now: Long): WrongWord {
        val weights = records.map { computeWeight(it, now) }
        val total = weights.sum()
        val target = Math.random() * total
        var acc = 0.0
        var idx = weights.size - 1
        for (i in weights.indices) {
            acc += weights[i]
            if (target < acc) { idx = i; break }
        }
        return records[idx]
    }

    private fun buildQuestion(round: Int, rec: WrongWord): Question =
        wordRepo.buildSingleQuestion(rec.direction, rec.word, rec.meaning, round)

    data class SubmitResult(val isCorrect: Boolean, val timedOut: Boolean, val starAfter: Int)

    var currentIndex: Int = 0
        private set

    /** 提交答案（choice=null 表示超时）。写库 + 更新本地星级/连对计数 */
    fun submit(index: Int, choice: Int?, repo: WrongWordRepository): SubmitResult {
        val q = questions[index] ?: return SubmitResult(false, false, 0)
        val rec = recordAt[index] ?: return SubmitResult(false, false, 0)
        val k = key(rec)
        val starBefore = stars[k] ?: rec.starLevel
        val isCorrect = choice != null && choice == q.correctIdx

        val starAfter: Int = if (isCorrect) {
            val streak = (streaks[k] ?: 0) + 1
            streaks[k] = streak
            val target = if (streak >= 3) 0 else (starBefore - 1).coerceAtLeast(0)
            stars[k] = target
            // 写库：连对 3 次归零直接设 0；普通降星走 recordAnswer
            if (streak >= 3) {
                repo.setStarLevel(username, rec.word, rec.direction, 0)
                DebugLog.i("[Practice] 连对3次归零: ${rec.word} ${rec.direction} (star $starBefore→0)")
            } else {
                repo.recordAnswer(username, rec.word, rec.meaning, rec.direction, true)
                DebugLog.i("[Practice] 答对降星: ${rec.word} ${rec.direction} (star $starBefore→$target, 连对=$streak)")
            }
            target
        } else {
            streaks[k] = 0
            val r = repo.recordAnswer(username, rec.word, rec.meaning, rec.direction, false)
            val s = r?.starLevel ?: 0
            stars[k] = s
            DebugLog.i("[Practice] ${if (choice == null) "超时" else "答错"}: ${rec.word} ${rec.direction} (star→$s)")
            s
        }

        currentIndex = index + 1   // 推进到下一题（= questions.size 表示结束，UI 侧判断）
        return SubmitResult(isCorrect, choice == null, starAfter)
    }

    // ===== 结束页统计（在 UI 侧用 answers 累积，这里提供星级变化） =====

    /** 升星/降星词数（按 word|direction 去重，会话前后比较） */
    fun starChangeSummary(): Pair<Int, Int> {
        val seen = mutableSetOf<String>()
        var up = 0; var down = 0
        recordAt.forEach { rec ->
            val k = key(rec)
            val now = stars[k] ?: rec.starLevel
            val before = starAtStart[k] ?: rec.starLevel
            if (now != before && seen.add(k)) {
                if (now > before) up++ else down++
            }
        }
        return Pair(up, down)
    }
}
