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
     * 按方向筛选词
     */
    private fun filterByDirection(direction: String): List<Word> {
        return when (direction) {
            "EN_TO_ZH" -> words.filter { word -> isChinese(word.translation) }
            "ZH_TO_EN" -> words.filter { word -> !isChinese(word.translation) }
            else -> words
        }
    }

    private fun isChinese(text: String): Boolean = text.any { it.isChineseChar() }

    private fun Char.isChineseChar(): Boolean = this in '\u4e00'..'\u9fff'

    /**
     * 随机抽 N 题并生成 Question 列表
     */
    fun generateQuestions(direction: String, count: Int): List<Question> {
        val filtered = filterByDirection(direction)
        if (filtered.isEmpty()) return emptyList()

        val shuffled = filtered.shuffled().take(count)
        return shuffled.mapIndexed { index, word ->
            val questionText = if (direction == "EN_TO_ZH") word.word else word.translation
            val correctAnswer = if (direction == "EN_TO_ZH") word.translation else word.word

            val allOptions: MutableList<String> = mutableListOf(correctAnswer)
            allOptions.addAll(word.distractors.take(3))
            while (allOptions.size < 4) allOptions.add("TBD_${allOptions.size}")
            val shuffledOptions = allOptions.shuffled().take(4)
            val correctIdx = shuffledOptions.indexOf(correctAnswer)

            Question(
                round = index + 1,
                questionText = questionText,
                options = shuffledOptions,
                correctIdx = correctIdx
            )
        }
    }
}