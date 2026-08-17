package com.wordbattle.ui

import com.wordbattle.data.Question
import com.wordbattle.data.WordRepository
import com.wordbattle.data.WrongWord
import com.wordbattle.data.WrongWordRepository
import com.wordbattle.debug.DebugLog

/**
 * 错题练习会话（纯逻辑，无 Compose 依赖；UI 状态在 PracticeScreen 里）
 * 规则见 docs/requirements/错题本需求文档_v2.md 七、错题练习：
 * - 题池 = 传入的错题记录（含 0 星词），词数 < 题数时允许重复
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

    // 干扰项候选池（每个方向抽一次，复用）
    private var zhPool: List<String> = emptyList()
    private var enPool: List<String> = emptyList()

    fun key(rec: WrongWord) = "${rec.word}|${rec.direction}"

    /** 当前题显示用的星级 */
    fun starOf(index: Int): Int {
        val rec = recordAt.getOrNull(index) ?: return 0
        return stars[key(rec)] ?: rec.starLevel
    }

    /** 抽题开始：词数 < 题数时循环取（重复出现是"连对 3 次归零"的触发途径） */
    fun start(count: Int) {
        val shuffled = records.shuffled()
        if (shuffled.isNotEmpty() && zhPool.isEmpty() && enPool.isEmpty()) {
            try {
                zhPool = wordRepo.generateQuestions("EN_TO_ZH", 300)
                    .map { it.options[it.correctIdx] }
                    .distinct().shuffled()
                enPool = wordRepo.generateQuestions("ZH_TO_EN", 300)
                    .map { it.questionText }
                    .distinct().shuffled()
            } catch (e: Exception) {
                DebugLog.e("[Practice] 干扰项池构建失败", e.message ?: "")
            }
        }
        val picked = if (shuffled.isEmpty()) emptyList() else
            (0 until count).map { i -> shuffled[i % shuffled.size] }
        recordAt = picked
        questions = picked.mapIndexed { i, rec -> buildQuestion(i + 1, rec) }
        questions.forEach { q ->
            DebugLog.i("[Practice] Q${q.round}: 题目=${q.questionText} 正确idx=${q.correctIdx} 选项=${q.options}")
        }
        DebugLog.i("[Practice] 开始: user=$username 题数=${questions.size} 词数=${shuffled.size}")
    }

    private fun buildQuestion(round: Int, rec: WrongWord): Question {
        val enToZh = rec.direction == "EN_TO_ZH"
        val questionText = if (enToZh) rec.word else rec.meaning
        val correctAnswer = if (enToZh) rec.meaning else rec.word
        val pool = (if (enToZh) zhPool else enPool)
            .filter { it != questionText }.shuffled().take(3)
        val options = (listOf(correctAnswer) + pool).shuffled()
        return Question(
            round = round,
            questionText = questionText,
            options = options,
            correctIdx = options.indexOf(correctAnswer)
        )
    }

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
