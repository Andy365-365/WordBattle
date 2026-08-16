package com.wordbattle.network

import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*

class TcpCodecTest {
    @Test
    fun `encode produces correct size`() {
        val json = "hello"
        val encoded = TcpCodec.encode(json)
        assertEquals(9, encoded.size) // 4 byte prefix + 5 bytes
    }

    @Test
    fun `encode longer string`() {
        val json = """{"t":"JOIN","name":"player1"}"""
        val encoded = TcpCodec.encode(json)
        assertTrue(encoded.size > 4)
    }
}

class GameMessageSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `JOIN serializes and deserializes`() {
        val msg = GameMessage.JOIN(playerId = "", name = "p1")
        val str = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString<GameMessage.JOIN>(str)
        assertEquals("p1", decoded.name)
    }

    @Test
    fun `WELCOME serializes and deserializes`() {
        val msg = GameMessage.WELCOME(playerId = "p0")
        val str = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString<GameMessage.WELCOME>(str)
        assertEquals("p0", decoded.playerId)
    }

    @Test
    fun `GO serializes and deserializes`() {
        val msg = GameMessage.GO(round = 1, question = "hello", options = listOf("你好", "hi", "bye", "thanks"), timer = 10)
        val str = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString<GameMessage.GO>(str)
        assertEquals(1, decoded.round)
        assertEquals("hello", decoded.question)
        assertEquals(4, decoded.options.size)
    }

    @Test
    fun `ANSWER serializes and deserializes`() {
        val msg = GameMessage.ANSWER(playerId = "p0", round = 1, choice = 2, ts = 12345L)
        val str = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString<GameMessage.ANSWER>(str)
        assertEquals("p0", decoded.playerId)
        assertEquals(2, decoded.choice)
    }

    @Test
    fun `REVEAL serializes and deserializes`() {
        val msg = GameMessage.REVEAL(round = 1, correctIdx = 0, winner = "p0", winnerName = "p1")
        val str = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString<GameMessage.REVEAL>(str)
        assertEquals(0, decoded.correctIdx)
        assertEquals("p0", decoded.winner)
    }

    @Test
    fun `SCORE serializes and deserializes`() {
        val scores = mapOf("p0" to 5, "p1" to 3)
        val msg = GameMessage.SCORE(scores = scores)
        val str = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString<GameMessage.SCORE>(str)
        assertEquals(5, decoded.scores["p0"])
        assertEquals(3, decoded.scores["p1"])
    }

    @Test
    fun `GAMEOVER serializes and deserializes`() {
        val ranking = listOf(
            RankEntry("A", 10),
            RankEntry("B", 5)
        )
        val msg = GameMessage.GAMEOVER(ranking = ranking)
        val str = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString<GameMessage.GAMEOVER>(str)
        assertEquals(2, decoded.ranking.size)
        assertEquals("A", decoded.ranking[0].name)
        assertEquals(10, decoded.ranking[0].score)
    }

    @Test
    fun `RESTART serializes and deserializes`() {
        val msg = GameMessage.RESTART()
        val str = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString<GameMessage.RESTART>(str)
        // Just verify it roundtrips without exception
    }

    @Test
    fun `RESTART_ACK serializes and deserializes`() {
        val msg = GameMessage.RESTART_ACK(playerId = "p0")
        val str = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString<GameMessage.RESTART_ACK>(str)
        assertEquals("p0", decoded.playerId)
    }

    @Test
    fun `PREPARE serializes and deserializes`() {
        val msg = GameMessage.PREPARE(round = 3)
        val str = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString<GameMessage.PREPARE>(str)
        assertEquals(3, decoded.round)
    }

    @Test
    fun `READY serializes and deserializes`() {
        val msg = GameMessage.READY(playerId = "auto-bot", round = 5)
        val str = json.encodeToString(GameMessage.serializer(), msg)
        val decoded = json.decodeFromString<GameMessage.READY>(str)
        assertEquals("auto-bot", decoded.playerId)
        assertEquals(5, decoded.round)
    }

    @Test
    fun `UdpBroadcast serializes and deserializes`() {
        val msg = UdpBroadcast(ip = "192.168.1.1", port = 5201, name = "host", dir = "EN_TO_ZH", total = 10)
        val str = json.encodeToString(UdpBroadcast.serializer(), msg)
        val decoded = json.decodeFromString<UdpBroadcast>(str)
        assertEquals("192.168.1.1", decoded.ip)
        assertEquals(5201, decoded.port)
    }

    @Test
    fun `GO message contains t field`() {
        val msg = GameMessage.GO(round = 1, question = "q", options = listOf("a","b","c","d"), timer = 10)
        val str = json.encodeToString(GameMessage.serializer(), msg)
        assertTrue(str.contains("\"t\""))
        assertTrue(str.contains("\"GO\""))
    }
}