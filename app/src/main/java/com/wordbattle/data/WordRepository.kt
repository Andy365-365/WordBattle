package com.wordbattle.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Collections

/**
 * 题库加载和出题
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

    /**
     * 按方向筛选词：题库中每个词有 EN_TO_ZH / ZH_TO_EN 两条镜像条目，
     * 必须按 level 字段精确选取；用"translation 是否中文"筛选会选到镜像条目，
     * 导致题目/选项语言颠倒。
     */
    private fun filterByDirection(direction: String): List<Word> {
        val byLevel = words.filter { it.level == direction }
        return if (byLevel.isNotEmpty()) byLevel else words
    }

    /**
     * 获取所有 unit 列表
     */
    fun getUnits(): List<String> {
        return words.map { it.unit }.distinct().filter { it.isNotBlank() }.sorted()
    }

    /**
     * 随机抽 N 题并生成 Question 列表
     */
    fun generateQuestions(direction: String, count: Int, unit: String = ""): List<Question> {
        var filtered = filterByDirection(direction)
        if (unit.isNotBlank()) {
            filtered = filtered.filter { it.unit == unit }
        }
        if (filtered.isEmpty()) return emptyList()

        val shuffled = filtered.shuffled().take(count)
        return shuffled.mapIndexed { index, word ->
            // 题库数据结构：word=提问侧语言，translation=作答侧语言（镜像条目由 filterByDirection 按 level 区分）
            val questionText = word.word
            val correctAnswer = word.translation

            val allOptions: MutableList<String> = mutableListOf(correctAnswer)
            allOptions.addAll(word.distractors.take(3))
            while (allOptions.size < 4) allOptions.add("TBD_${allOptions.size}")
            val shuffledOptions = allOptions.shuffled().take(4)
            val correctIdx = shuffledOptions.indexOf(correctAnswer)

            Question(
                round = index + 1,
                questionText = questionText,
                options = shuffledOptions,
                correctIdx = correctIdx,
                page = word.page
            )
        }
    }
}