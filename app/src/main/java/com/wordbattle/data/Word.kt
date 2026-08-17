package com.wordbattle.data

import kotlinx.serialization.Serializable

/**
 * 词库条目（单条配对，2026-08-17 v2 第5步起）
 * 一个英文词一条：word=英文，translation=中文释义，双向通用。
 * 出题方向由调用方现场组装（EN_TO_ZH: 题目=word 答案=translation；ZH_TO_EN 反之）。
 * zhDistractors / enDistractors 是后备干扰项：全库随机抽取不足 3 个时补位，
 * 分别用于 EN_TO_ZH（补中文）和 ZH_TO_EN（补英文）。
 */
@Serializable
data class Word(
    val word: String,
    val translation: String,
    val zhDistractors: List<String> = emptyList(),
    val enDistractors: List<String> = emptyList(),
    val page: Int = 0,
    val unit: String = ""
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
