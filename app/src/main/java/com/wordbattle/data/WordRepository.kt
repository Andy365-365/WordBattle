package com.wordbattle.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 题库加载和出题（单条配对格式，2026-08-17 v2 第5步起）
 *
 * 词库为单条配对：一个英文词一条（word=英文, translation=中文释义），双向通用。
 * 出题方向由调用方现场组装：
 * - EN_TO_ZH：题目=英文词，正确答案=中文释义
 * - ZH_TO_EN：题目=中文释义，正确答案=英文词
 *
 * 干扰项运行时从全库随机抽取（不受 unit 过滤限制），
 * 词条内置的 zhDistractors / enDistractors 仅作抽取不足时的后备。
 */
class WordRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private var words: List<Word> = emptyList()

    /**
     * 从 assets JSON 加载题库
     */
    suspend fun load(content: String) {
        withContext(Dispatchers.Default) {
            words = json.decodeFromString<List<Word>>(content)
        }
    }

    /** 全部词条（只读快照） */
    fun getWords(): List<Word> = words

    /** 按英文词查找词条（找不到返回 null） */
    fun findWord(word: String): Word? = words.firstOrNull { it.word == word }

    /**
     * 获取所有 unit 列表
     */
    fun getUnits(): List<String> {
        return words.map { it.unit }.distinct().filter { it.isNotBlank() }.sorted()
    }

    /**
     * 随机抽 N 题并生成 Question 列表。
     * unit 只过滤"抽哪些词出题"，干扰项仍从全库随机抽取。
     */
    fun generateQuestions(direction: String, count: Int, unit: String = ""): List<Question> {
        val pool = if (unit.isNotBlank()) words.filter { it.unit == unit } else words
        if (pool.isEmpty()) return emptyList()

        return pool.shuffled().take(count).mapIndexed { index, w ->
            val enToZh = direction == "EN_TO_ZH"
            val options = buildOptions(direction, w.word, w.translation, fallbackFor(direction, w))
            val questionText = if (enToZh) w.word else w.translation
            val correct = if (enToZh) w.translation else w.word
            Question(
                round = index + 1,
                questionText = questionText,
                options = options,
                correctIdx = options.indexOf(correct),
                page = w.page
            )
        }
    }

    /**
     * 为单个已知词生成一道题（错题练习用，word/meaning 由错题记录快照提供）。
     * 词库仅用于干扰项候选池与后备抽取；词条缺失（自定义词库未同步）时后备为空。
     */
    fun buildSingleQuestion(direction: String, word: String, meaning: String,
                            round: Int, page: Int = 0): Question {
        val enToZh = direction == "EN_TO_ZH"
        val options = buildOptions(direction, word, meaning, fallbackFor(direction, findWord(word)))
        val questionText = if (enToZh) word else meaning
        val correct = if (enToZh) meaning else word
        return Question(
            round = round,
            questionText = questionText,
            options = options,
            correctIdx = options.indexOf(correct),
            page = page
        )
    }

    /**
     * 组装 4 个选项：正确答案 + 3 个全库随机干扰项（不足时后备补齐，再不足 TBD 兜底）。
     *
     * 干扰项候选 = 全库中其他词的"作答侧语言字段"：
     * - EN_TO_ZH：其他词的中文释义，排除与正确答案同义的词条
     *   （避免选项里出现两个相同释义文本，如同义的 everyone/everybody）
     * - ZH_TO_EN：其他词的英文词，排除释义与题目相同的英文词
     *   （避免同一中文释义出现"另一个也正确的英文词"，即 everyone/everybody 歧义）
     *
     * 保证：恰好 4 项、互不重复、正确答案恰好出现一次（correctIdx 定位唯一）。
     */
    fun buildOptions(direction: String, word: String, meaning: String,
                     fallback: List<String>): List<String> {
        val enToZh = direction == "EN_TO_ZH"
        val questionText = if (enToZh) word else meaning
        val correctAnswer = if (enToZh) meaning else word

        val candidates = words
            .filter { it.word != word }
            .filter {
                if (enToZh) it.translation != meaning      // 排除与正确答案同义的词条
                else it.translation != questionText        // 排除同一释义的另一个英文词
            }
            .map { if (enToZh) it.translation else it.word }
            .filter { it != correctAnswer }
            .distinct()
            .shuffled()

        val options = mutableListOf(correctAnswer)
        candidates.forEach { c -> if (options.size < 4 && c !in options) options.add(c) }
        if (options.size < 4) {
            fallback.forEach { c -> if (options.size < 4 && c.isNotBlank() && c !in options) options.add(c) }
        }
        var i = 0
        while (options.size < 4) { options.add("TBD_$i"); i++ }
        return options.shuffled()
    }

    /** 词条对应的后备干扰项（按方向取中文/英文那套；词条缺失返回空） */
    private fun fallbackFor(direction: String, w: Word?): List<String> = when {
        w == null -> emptyList()
        direction == "EN_TO_ZH" -> w.zhDistractors
        else -> w.enDistractors
    }
}
