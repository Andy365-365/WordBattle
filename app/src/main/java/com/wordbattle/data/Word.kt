package com.wordbattle.data

import kotlinx.serialization.Serializable

@Serializable
data class Word(
    val word: String,
    val translation: String,
    val distractors: List<String>,
    val level: String,
    val page: Int = 0
)

/**
 * 一道题目的完整数据结构
 */
data class Question(
    val round: Int,
    val questionText: String,
    val options: List<String>,
    val correctIdx: Int,
    val page: Int = 0
)