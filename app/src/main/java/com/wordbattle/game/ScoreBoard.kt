package com.wordbattle.game

/**
 * 玩家状态
 */
data class PlayerState(
    val id: String,
    val name: String,
    var score: Int = 0,
    var isOnline: Boolean = true
)

/**
 * 单轮状态
 */
data class RoundState(
    val round: Int,              // 当前轮次 1-based
    var claimedBy: String? = null,  // 谁抢到了（答对的人）
    val wrongPlayers: MutableSet<String> = mutableSetOf(), // 选错的人（不能再选本题）
    var isRevealed: Boolean = false, // 是否已揭晓
    var isTimedOut: Boolean = false  // 是否超时
) {
    /**
     * 还能回答的玩家数
     */
    fun canAnswer(allPlayers: Set<String>): Int {
        return allPlayers.count { p ->
            p != claimedBy && p !in wrongPlayers
        }
    }

    /**
     * 是否已结束（有人答对 或 所有人都选错 或 超时）
     */
    fun isFinished(allPlayers: Set<String>): Boolean {
        return claimedBy != null || isRevealed || canAnswer(allPlayers) == 0
    }
}