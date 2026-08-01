package com.wordbattle.data

import kotlinx.serialization.Serializable

@Serializable
data class Word(
    val word: String,        // 题目（英或中）
    val translation: String, // 答案（对应方向）
    val distractors: List<String>, // 3个干扰项
    val level: String
)

/**
 * 一道题目的完整数据结构
 */
data class Question(
    val round: Int,          // 第几轮（1-based）
    val questionText: String, // 显示的题目文字
    val options: List<String>, // 4个选项（已打乱）
    val correctIdx: Int       // 正确选项在 options 中的索引
)