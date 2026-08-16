package com.wordbattle.network

import kotlinx.serialization.*

@Serializable
sealed class GameMessage {
    @Serializable
    @SerialName("ADVERTISE")
    data class ADVERTISE(
        val t: String = "ADVERTISE",
        val ip: String,
        val port: Int,
        val name: String,
        val dir: String,
        val total: Int
    ) : GameMessage()

    @Serializable
    @SerialName("JOIN")
    data class JOIN(
        val t: String = "JOIN",
        val playerId: String,   // 服务器分配
        val name: String
    ) : GameMessage()

    @Serializable
    @SerialName("WELCOME")
    data class WELCOME(
        val t: String = "WELCOME",
        val playerId: String    // 告诉客户端自己的 ID
    ) : GameMessage()

    @Serializable
    @SerialName("ANSWER")
    data class ANSWER(
        val t: String = "ANSWER",
        val playerId: String,
        val round: Int,
        val choice: Int,
        val ts: Long
    ) : GameMessage()

    @Serializable
    @SerialName("PREPARE")
    data class PREPARE(
        val t: String = "PREPARE",
        val round: Int
    ) : GameMessage()

    @Serializable
    @SerialName("READY")
    data class READY(
        val t: String = "READY",
        val playerId: String,
        val round: Int
    ) : GameMessage()

    @Serializable
    @SerialName("GO")
    data class GO(
        val t: String = "GO",
        val round: Int,
        val question: String,
        val options: List<String>,
        val page: Int = 0,
        val timer: Int = 10,
        val correctIdx: Int = -1
    ) : GameMessage()

    @Serializable
    @SerialName("REVEAL")
    data class REVEAL(
        val t: String = "REVEAL",
        val round: Int,
        val correctIdx: Int,
        val winner: String?,
        val winnerName: String?
    ) : GameMessage()

    @Serializable
    @SerialName("SCORES")
    data class SCORE(
        val t: String = "SCORES",
        val scores: Map<String, Int>
    ) : GameMessage()

    @Serializable
    @SerialName("GAME_OVER")
    data class GAMEOVER(
        val t: String = "GAME_OVER",
        val ranking: List<RankEntry>
    ) : GameMessage()

    @Serializable
    @SerialName("RESTART")
    data class RESTART(
        val t: String = "RESTART"
    ) : GameMessage()

    @Serializable
    @SerialName("RESTART_ACK")
    data class RESTART_ACK(
        val t: String = "RESTART_ACK",
        val playerId: String
    ) : GameMessage()
}

@Serializable
data class PlayerInfo(
    val id: String,
    val name: String
)

@Serializable
data class RankEntry(
    val name: String,
    val score: Int
)

@Serializable
data class UdpBroadcast(
    val ip: String,
    val port: Int,
    val name: String,
    val dir: String,
    val total: Int,
    val status: String = ""  // "IDLE" / "ANSWERING" / "GAME_OVER"
)

@Serializable
data class TypeWrapper(val type: String)