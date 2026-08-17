package com.wordbattle.data

import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/**
 * WordRepository 单测（单条配对格式，v2 第5步起）。
 * 重点覆盖本次改造的质量点：
 * - 干扰项从全库随机抽取、恰好 4 项、互不重复
 * - 唯一正确：EN_TO_ZH 排除同义词条（选项不出现两个相同释义）
 * - ZH_TO_EN 排除同释义英文词（选项不出现"另一个也正确的英文词"）
 * - 后备干扰项（zhDistractors/enDistractors）在词库过小时补位，再不足 TBD 兜底
 */
class WordRepositoryTest {

    private val repo = WordRepository()

    /** 加载 243 词的真实词库（assets），从 cwd 向上逐级定位仓库根目录 */
    private fun loadRealWords() = runBlocking {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        var f: java.io.File? = null
        while (dir != null) {
            val c = java.io.File(dir, "app/src/main/assets/words.json")
            if (c.isFile) { f = c; break }
            dir = dir.parentFile
        }
        check(f != null) { "找不到 words.json (cwd=${System.getProperty("user.dir")})" }
        repo.load(f!!.readText())
    }

    @Test
    fun `EN_TO_ZH 基本出题`() = runBlocking {
        repo.load("""
            [{"word":"hello","translation":"你好","zhDistractors":["甲"],"enDistractors":["cat"]},
             {"word":"world","translation":"世界","zhDistractors":["乙"],"enDistractors":["dog"]}]
        """.trimIndent())
        val q = repo.generateQuestions("EN_TO_ZH", 2)
        assertEquals(2, q.size)
        q.forEach {
            assertEquals(4, it.options.size)
            assertEquals(1, it.options.count { o -> o == it.options[it.correctIdx] }) // 正确答案唯一出现
            assertTrue(it.questionText in listOf("hello", "world"))                    // 题目是英文
            assertTrue(it.options[it.correctIdx] in listOf("你好", "世界"))             // 正确是中文
        }
    }

    @Test
    fun `ZH_TO_EN 基本出题`() = runBlocking {
        repo.load("""
            [{"word":"hello","translation":"你好","zhDistractors":["甲"],"enDistractors":["cat"]},
             {"word":"world","translation":"世界","zhDistractors":["乙"],"enDistractors":["dog"]}]
        """.trimIndent())
        val q = repo.generateQuestions("ZH_TO_EN", 2)
        assertEquals(2, q.size)
        q.forEach {
            assertEquals(4, it.options.size)
            assertEquals(1, it.options.count { o -> o == it.options[it.correctIdx] })
            assertTrue(it.questionText in listOf("你好", "世界"))                      // 题目是中文
            assertTrue(it.options[it.correctIdx] in listOf("hello", "world"))          // 正确是英文
        }
    }

    @Test
    fun `真实词库 全量两方向出题 唯一正确且无重复选项`() = runBlocking {
        loadRealWords()
        listOf("EN_TO_ZH", "ZH_TO_EN").forEach { dir ->
            val qs = repo.generateQuestions(dir, 243)
            assertEquals(243, qs.size)
            qs.forEach { q ->
                assertEquals(4, q.options.size)
                assertEquals("Q${q.round} 选项重复: ${q.options}",
                    q.options.size, q.options.toSet().size)
                val correct = q.options[q.correctIdx]
                assertEquals(1, q.options.count { it == correct })
                // 干扰项不能与题目同文本
                assertTrue(correct != q.questionText)
            }
        }
    }

    @Test
    fun `真实词库 ZH_TO_EN 同释义词不互为干扰项`() = runBlocking {
        loadRealWords()
        // everyone/everybody 共用"每个人，人人"：中→英出该题时，另一英文词不得出现在选项里
        listOf("everyone", "everybody").forEach { target ->
            val q = repo.buildSingleQuestion("ZH_TO_EN", target, "每个人，人人", round = 1)
            assertEquals("每个人，人人", q.questionText)
            assertEquals(target, q.options[q.correctIdx])
            val other = if (target == "everyone") "everybody" else "everyone"
            if (other in q.options) fail("干扰项里出现了另一个正确词 $other: ${q.options}")
        }
    }

    @Test
    fun `真实词库 EN_TO_ZH 同义释义不产生重复选项`() = runBlocking {
        loadRealWords()
        // 同义词条的释义文本相同，干扰项过滤后选项里不该出现与正确答案同文本的项
        listOf("everyone", "everybody").forEach { target ->
            val q = repo.buildSingleQuestion("EN_TO_ZH", target, "每个人，人人", round = 1)
            assertEquals(target, q.questionText)
            assertEquals("每个人，人人", q.options[q.correctIdx])
            assertEquals(1, q.options.count { it == "每个人，人人" })
        }
    }

    @Test
    fun `干扰项从全库抽 不受unit过滤限制`() = runBlocking {
        loadRealWords()
        val u2 = repo.getWords().filter { it.unit == "Unit 2" }
        val u2Zh = u2.map { it.translation }.toSet()
        val allEn = repo.getWords().map { it.word }.toSet()
        val qs = repo.generateQuestions("ZH_TO_EN", 10, "Unit 2")
        assertEquals(10, qs.size)
        qs.forEach { q ->
            // 题目必须来自 Unit 2
            assertTrue("题目不属于 Unit 2: ${q.questionText}", q.questionText in u2Zh)
            // 选项全部是英文词（全库候选内），无中文混入
            assertTrue("选项含非英文词: ${q.options}", q.options.all { it in allEn })
            // 干扰项确实允许跨 unit 出现（243 词库下必然如此，断言至少 1 个干扰项不在 Unit 2）
            val distractors = q.options.filter { it != q.options[q.correctIdx] }
            if (distractors.all { it in u2.map { w -> w.word } })
                fail("干扰项全部来自 Unit 2，疑似未按全库抽: ${q.options}")
        }
    }

    @Test
    fun `小词库 后备干扰项补位`() = runBlocking {
        // 2 个词，全库只能提供 1 个干扰项，后备（zhDistractors）应补齐到 4
        repo.load("""
            [{"word":"a","translation":"一","zhDistractors":["备甲","备乙","备丙"],"enDistractors":["x1","x2"]},
             {"word":"b","translation":"二","zhDistractors":["备丁"],"enDistractors":["y1"]}]
        """.trimIndent())
        val q = repo.generateQuestions("EN_TO_ZH", 1).first()
        assertEquals(4, q.options.size)
        assertEquals(4, q.options.toSet().size)
        // 全库干扰项只有 1 个，其余必须来自后备（无 TBD）
        if (q.options.any { it.startsWith("TBD_") }) fail("应有后备补位: ${q.options}")
    }

    @Test
    fun `词库过少 TBD兜底`() = runBlocking {
        repo.load("""
            [{"word":"a","translation":"一","zhDistractors":[],"enDistractors":[]}]
        """.trimIndent())
        val q = repo.generateQuestions("EN_TO_ZH", 1).first()
        assertEquals(4, q.options.size)
        assertEquals("一", q.options[q.correctIdx])
        assertEquals(3, q.options.count { it.startsWith("TBD_") })
    }

    @Test
    fun `empty word list`() = runBlocking {
        repo.load("[]")
        assertEquals(0, repo.generateQuestions("EN_TO_ZH", 5).size)
    }

    @Test
    fun `unit不存在返回空`() = runBlocking {
        loadRealWords()
        assertEquals(0, repo.generateQuestions("EN_TO_ZH", 5, "不存在的unit").size)
    }

    @Test
    fun `getUnits 返回全部unit`() = runBlocking {
        loadRealWords()
        val units = repo.getUnits()
        assertTrue(units.contains("Starter"))
        assertEquals(7, units.size)
    }
}
