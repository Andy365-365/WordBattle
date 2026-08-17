package com.wordbattle.data

/**
 * 本轮对战中单题的记录（赛后复盘用，仅内存临时保存，不持久化）
 * 星级字段为二期数据层预留，当前为 null
 */
data class RoundRecord(
    val round: Int,
    val word: String,            // 英文单词（按方向从 GO 消息本地提取）
    val question: String,        // 题目文本
    val correctAnswer: String,   // 正确答案文本
    val userAnswer: String?,     // 用户选择的答案文本；null = 超时未答
    val isCorrect: Boolean,
    val timedOut: Boolean,
    val direction: String = "EN_TO_ZH",  // 本题方向（错题练习题池用）
    val starBefore: Int? = null, // 二期：答题前星级
    val starAfter: Int? = null   // 二期：答题后星级
)
