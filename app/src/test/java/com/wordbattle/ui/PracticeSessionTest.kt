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
 * 边界语义与旧纯随机一致：空池→空、单词必抽、全权重相同→纯随机、0 星词保留在池内。
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

    /** 10 个构造词：wrongCount 1..10，时间错开（新/旧交替） */
    private fun tenRecords(): List<WrongWord> = listOf(
        rec("apple",  3, 1,  1.0),   // w = 2  * 2^(-1/24)
        rec("badger", 5, 2, 24.0),   // w = 3  * 0.5
        rec("crane",  1, 3, 48.0),   // w = 4  * 0.25
        rec("dolphin",6, 4,  0.0),   // w = 5  * 1
        rec("eagle",  2, 5, 72.0),   // w = 6  * 0.125
        rec("fox",    4, 6, 12.0),   // w = 7  * 2^(-1/2)
        rec("goat",   0, 7, 168.0),  // w = 8  * 1/8   （0 星已纠正，仍在池内）
        rec("hen",    3, 8,  6.0),   // w = 9  * 2^(-1/4)
        rec("iguana", 2, 9, 240.0),  // w = 10 * 1/32
        rec("jaguar", 6,10,  2.0)    // w = 11 * 2^(-1/12)，全池最高
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

    /** 卡方拟合优度检验：观察频数 vs 理论权重比例，9 自由度下阈值 30（p>0.99999） */
    private fun chiSquare(records: List<WrongWord>, observed: Map<String, Int>): Double {
        val ws = records.map { session.computeWeight(it, now) }
        val totalW = ws.sum()
        val totalN = observed.values.sum().toDouble()
        var chi = 0.0
        records.forEachIndexed { i, r ->
            val expN = ws[i] / totalW * totalN
            val o = observed.getValue(r.word).toDouble()
            chi += (o - expN) * (o - expN) / expN
        }
        return chi
    }

    @Test
    fun `computeWeight 公式值`() {
        buildSession(tenRecords())
        assertEquals(2.0, session.computeWeight(rec("apple", 3, 1, 0.0), now), 1e-9)
        assertEquals(5.0, session.computeWeight(rec("dolphin", 6, 4, 0.0), now), 1e-9)
        assertEquals(11.0, session.computeWeight(rec("jaguar", 6, 10, 0.0), now), 1e-9)
        assertEquals(0.5, session.computeWeight(rec("crane", 1, 3, 72.0), now), 1e-9) // 4 * 2^(-3)
    }

    @Test
    fun `万次抽样分布与理论权重一致 卡方检验`() {
        buildSession(tenRecords())
        val counts = freqOf(tenRecords(), rounds = 1000, per = 100) // 共 10 万次
        assertEquals(100_000, counts.values.sum())
        val chi = chiSquare(tenRecords(), counts)
        assertTrue("卡方=${"%.2f".format(chi)} 超过 30（9 自由度下 p>0.99999 的阈值）", chi < 30.0)
    }

    @Test
    fun `高频新错词 出现率显著高于 低频老错词`() {
        buildSession(tenRecords())
        val counts = freqOf(tenRecords(), rounds = 200, per = 100)
        val jaguar = counts.getValue("jaguar")   // 权重 11 * 2^(-1/12) ≈ 10.2，全池最高
        val iguana = counts.getValue("iguana")   // 权重 10 * 1/32 = 0.3125，全池最低
        val goat = counts.getValue("goat")       // 0 星已纠正：仍被抽到（不剔除）
        val ratio = jaguar.toDouble() / iguana
        // 理论比值 ≈ 32.7，万次量级下远大于 15；1000 次量级下期望 ~19
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
        val chi = chiSquare(fresh, counts)
        assertTrue("等权重应纯随机，卡方=${"%.2f".format(chi)} 超过 30", chi < 30.0)
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

        val records = tenRecords()
        buildSession(records)
        session.start(30)
        assertEquals(30, session.questions.size)
        assertEquals(30, session.recordAt.size)
        // 题目文本 = 对应词的英文（EN_TO_ZH 方向 questionText=word）
        session.questions.forEachIndexed { i, q ->
            assertEquals("Q${i + 1} 题词与记录不一致", session.recordAt[i].word, q.questionText)
            assertEquals("Q${i + 1} round 不连续", i + 1, q.round)
        }
        // 最高权重词 jaguar（p≈31%）30 题缺席概率 ~1e-5，必须出现；
        // 最低权重 iguana（p≈0.03%）不要求出现——加权语义即低频词少抽
        val seen = session.recordAt.map { it.word }.toSet()
        assertTrue("最高权重词 jaguar 应出现: $seen", seen.contains("jaguar"))
    }
}
