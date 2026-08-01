package com.wordbattle.data

import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class WordRepositoryTest {
    private val repo = WordRepository()

    @Test
    fun `load and generate EN_TO_ZH`() = runBlocking {
        val json = """
        [
            {"word":"hello","translation":"你好","distractors":["hi","bye","thanks"],"level":"easy"},
            {"word":"world","translation":"世界","distractors":["earth","universe","nature"],"level":"easy"}
        ]
        """.trimIndent()
        repo.load(json)
        val questions = repo.generateQuestions("EN_TO_ZH", 2)
        assertEquals(2, questions.size)
        assertEquals("hello", questions[0].questionText)
        assertEquals(0, questions[0].correctIdx)
    }

    @Test
    fun `load and generate ZH_TO_EN`() = runBlocking {
        val json = """
        [
            {"word":"猫","translation":"cat","distractors":["dog","bird","fish"],"level":"easy"}
        ]
        """.trimIndent()
        repo.load(json)
        val questions = repo.generateQuestions("ZH_TO_EN", 1)
        assertEquals(1, questions.size)
        assertEquals("猫", questions[0].questionText)
        assertEquals(0, questions[0].correctIdx)
    }

    @Test
    fun `empty word list`() = runBlocking {
        repo.load("[]")
        val questions = repo.generateQuestions("EN_TO_ZH", 5)
        assertEquals(0, questions.size)
    }

    @Test
    fun `request more than available`() = runBlocking {
        val json = """
        [{"word":"a","translation":"one","distractors":["two","three","four"],"level":"easy"}]
        """.trimIndent()
        repo.load(json)
        val questions = repo.generateQuestions("EN_TO_ZH", 10)
        assertEquals(1, questions.size)
    }
}