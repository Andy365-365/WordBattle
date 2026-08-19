package com.wordbattle.ui

import com.wordbattle.data.WordRepository
import com.wordbattle.data.WrongWord
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/**
 * 二期加权出题验证：不靠肉眼，用构造数据跑万次抽样做统计断言。
 * 权重公式：weight = (1 + wrongCount) * 2^(-小时数 / 24)
 * - 频率因子：答错越多权重越高
 * - 时间衰减：距上次答错越久权重越低（24h 衰减一半）
 * 抽题 = 保底 + 加权（v2.3）：词数 ≤ 题数时先每词保底 1 题（全词覆盖），
 * 剩余名额按权重抽，整体洗牌；词数 > 题数时纯加权抽。
 * 重复仍允许（保底词可被额外抽中）——"连对 3 次归零"的触发途径。
 */
class PracticeSessionTest {

    private val repo = WordRepository()
    private lateinit var session: PracticeSession
    /** 固定的"现在"，保证权重可复现 */
    private val now = 1_755_000_000_000L

    private fun rec(word: String, star: Int, wrongCount: Int, hoursAgo: Double): WrongWord =
        WrongWord(
            username = "t", word = word, meaning = word, direction = "EN_TO_ZH",
            starLevel = star, wrongCount = wrongCount,
            lastWrongTime = now - hoursAgo.toLong() * 3_600_000L
        )

    /** 7 个构造词：wrongCount 1..5，时间错开（新/旧交替），权重梯度 jaguar 最高 7.0 / iguana 最低 0.25 */
    private fun sevenRecords(): List<WrongWord> = listOf(
        rec("apple",  3, 1,  1.0),   // w = 2  * 2^(-1/24)
        rec("badger", 5, 2, 24.0),   // w = 3  * 0.5
        rec("crane",  1, 3, 48.0),   // w = 4  * 0.25
        rec("dolphin",6, 4,  0.0),   // w = 5  * 1
        rec("goat",   0, 3, 72.0),   // w = 4  * 0.125 （0 星已纠正，仍在池内，也享保底）
        rec("iguana", 2, 1, 168.0),  // w = 2  * 1/8 = 0.25，全池最低
        rec("jaguar", 6, 6,  0.0)    // w = 7，全池最高
    )

    private fun buildSession(records: List<WrongWord>) {
        session = PracticeSession("t", records, repo)
    }

    private fun freqOf(records: List<WrongWord>, rounds: Int, per: Int = 100): Map<String, Int> {
        val counts = records.associate { it.word to 0 }.toMutableMap()
        repeat(rounds) { i ->
            buildSession(records)
            val picked = session.weightedPick(records, per, now)
            picked.forEach { counts[it.word] = counts.getValue(it.word) + 1 }
        }
        return counts
    }

    /** 卡方拟合优度检验：观察频数 vs 保底后理论频数 exp = rounds + (per-N)*rounds*p_i（N=词数；6 自由度，阈值 25，p>0.9997） */
    private fun chiSquare(records: List<WrongWord>, observed: Map<String, Int>, per: Int, rounds: Int): Double {
        val ws = records.map { session.computeWeight(it, now) }
        val totalW = ws.sum()
        var chi = 0.0
        records.forEachIndexed { i, r ->
            // 每轮保底 N 题（每词 1 题）+ 剩余 (per-N) 名额按权重
            val expN = rounds + (per - records.size) * rounds * ws[i] / totalW
            val o = observed.getValue(r.word).toDouble()
            chi += (o - expN) * (o - expN) / expN
        }
        return chi
    }

    @Test
    fun `computeWeight 公式值`() {
        buildSession(sevenRecords())
        assertEquals(2.0, session.computeWeight(rec("apple", 3, 1, 0.0), now), 1e-9)
        assertEquals(5.0, session.computeWeight(rec("dolphin", 6, 4, 0.0), now), 1e-9)
        assertEquals(7.0, session.computeWeight(rec("jaguar", 6, 6, 0.0), now), 1e-9)
        assertEquals(0.5, session.computeWeight(rec("crane", 1, 3, 72.0), now), 1e-9) // 4 * 2^(-3)
    }

    @Test
    fun `万次抽样分布与理论权重一致 卡方检验`() {
        buildSession(sevenRecords())
        val counts = freqOf(sevenRecords(), rounds = 1000, per = 100) // 共 10 万次
        assertEquals(100_000, counts.values.sum())
        val chi = chiSquare(sevenRecords(), counts, per = 100, rounds = 1000)
        assertTrue("卡方=${"%.2f".format(chi)} 超过 25（6 自由度下 p>0.9997 的阈值）", chi < 25.0)
    }

    @Test
    fun `高频新错词 出现率显著高于 低频老错词`() {
        buildSession(sevenRecords())
        val counts = freqOf(sevenRecords(), rounds = 200, per = 100)
        val jaguar = counts.getValue("jaguar")   // 权重 7，全池最高
        val iguana = counts.getValue("iguana")   // 权重 0.25，全池最低
        val goat = counts.getValue("goat")       // 0 星已纠正：仍被抽到（不剔除）
        val ratio = jaguar.toDouble() / iguana
        // 理论比值 28，10 万次抽样下远大于 15
        assertTrue("jaguar=$jaguar iguana=$iguana 比值=$ratio 应显著大于 15", ratio > 15.0)
        assertTrue("0 星词 goat 应仍在池内被抽到: $goat", goat > 0)
    }

    @Test
    fun `边界 空池 单词 全同权重`() {
        // 空池 → 空
        session = PracticeSession("t", emptyList(), repo)
        assertTrue(session.weightedPick(emptyList(), 10, now).isEmpty())
        // 1 个词 → 必抽它
        val one = listOf(rec("solo", 1, 1, 1.0))
        session = PracticeSession("t", one, repo)
        repeat(50) {
            val picked = session.weightedPick(one, 20, now)
            assertEquals(20, picked.size)
            assertTrue(picked.all { it.word == "solo" })
        }
        // 全权重相同（全新记录）→ 退化纯随机：10 词等权重，10 万次抽样卡方检验
        val fresh = (1..10).map { rec("w$it", 1, 1, 12.0) }
        buildSession(fresh)
        val counts = freqOf(fresh, rounds = 1000, per = 100)
        // 10 词 ≤ 100 题 → 保底生效：理论频数 = 保底 1000/词 + 99 名额按权重（等权 → 纯随机）
        val chi = chiSquare(fresh, counts, per = 100, rounds = 1000)
        assertTrue("等权重应纯随机，卡方=${"%.2f".format(chi)} 超过 40（9 自由度 p>0.9999）", chi < 40.0)
    }

    /** 用户实测场景（08-19 复现：3 词 5 题只出了 2 个词）：保底后必须全词覆盖 */
    @Test
    fun `3 词 5 题 保底后每词至少 1 题 高权重词拿更多额外名额`() {
        // 构造与用户局同构的 3 词梯度：jaguar 权重最高（近期多次错），iguana 最低（久远）
        val three = listOf(
            rec("jaguar", 6, 5, 2.0),   // w = 6 * 2^(-1/12) ≈ 5.36
            rec("goat",   2, 1, 24.0),  // w = 2  * 0.5        = 1.0
            rec("iguana", 1, 1, 72.0)   // w = 2  * 0.125      = 0.25
        )
        buildSession(three)
        repeat(200) {
            val picked = session.weightedPick(three, 5, now)
            assertEquals("每轮 5 题", 5, picked.size)
            assertEquals("保底：3 词必须全覆盖", 3, picked.map { it.word }.toSet().size)
        }
        // 2 个额外名额按权重分配（p: jaguar 0.817 / goat 0.154 / iguana 0.039），
        // 单轮额外名额期望 jaguar 1.63 / iguana 0.08；200 轮累计差期望 ≈310、标准差 ≈7，断言 >1.5 极稳
        var hiExtra = 0; var loExtra = 0
        repeat(200) {
            val c = session.weightedPick(three, 5, now).groupBy { it.word }.mapValues { it.value.size }
            hiExtra += (c.getOrDefault("jaguar", 0) - 1)
            loExtra += (c.getOrDefault("iguana", 0) - 1)
        }
        assertTrue("高权重额外名额应显著多于低权重: hi=$hiExtra lo=$loExtra", (hiExtra - loExtra).toDouble() > 1.5)
    }

    /** start() 全链路：用真实词库构造题目，recordAt/questions 对齐，抽到的词覆盖词池 */
    @Test
    fun `start 全链路 真实词库出题 覆盖词池`() = runBlocking {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        var f: java.io.File? = null
        while (dir != null) {
            val c = java.io.File(dir, "app/src/main/assets/words.json")
            if (c.isFile) { f = c; break }
            dir = dir.parentFile
        }
        check(f != null) { "找不到 words.json (cwd=${System.getProperty("user.dir")})" }
        repo.load(f!!.readText())

        val records = sevenRecords()
        buildSession(records)
        session.start(30)
        assertEquals(30, session.questions.size)
        assertEquals(30, session.recordAt.size)
        // 题目文本 = 对应词的英文（EN_TO_ZH 方向 questionText=word）
        session.questions.forEachIndexed { i, q ->
            assertEquals("Q${i + 1} 题词与记录不一致", session.recordAt[i].word, q.questionText)
            assertEquals("Q${i + 1} round 不连续", i + 1, q.round)
        }
        // 7 词 ≤ 30 题 → 保底生效，每个词至少出现 1 题（全词覆盖，含 0 星 goat 与最低权重 iguana）
        // 最高权重词 jaguar（p≈39%）额外名额期望 11 题，断言 ≥5（远低于期望，极稳）
        val seen = session.recordAt.map { it.word }.toSet()
        assertTrue("保底应全词覆盖: $seen", seen == records.map { it.word }.toSet())
        val jaguarN = session.recordAt.count { it.word == "jaguar" }
        assertTrue("最高权重词 jaguar 额外名额应 >=5，实际 $jaguarN", jaguarN >= 5)
    }
}
