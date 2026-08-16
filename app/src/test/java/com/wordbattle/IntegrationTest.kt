package com.wordbattle.game

import com.wordbattle.data.*
import com.wordbattle.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class MockBridge : GameNetworkBridge {
    val broadcasts = mutableListOf<GameMessage>()
    private val _onAnswer = MutableSharedFlow<GameMessage.ANSWER>(extraBufferCapacity = 100)
    private val _onClientJoin = MutableSharedFlow<Pair<String, GameMessage.JOIN>>(extraBufferCapacity = 100)

    override suspend fun broadcast(msg: GameMessage) { broadcasts.add(msg) }
    override suspend fun sendTo(playerId: String, msg: GameMessage) { broadcasts.add(msg) }
    override val onAnswer: Flow<GameMessage.ANSWER> = _onAnswer
    override val onClientJoin: Flow<Pair<String, GameMessage.JOIN>> = _onClientJoin
    override val onReady: Channel<GameMessage.READY> = Channel(Channel.BUFFERED)

    suspend fun injectAnswer(playerId: String, round: Int, choice: Int) {
        _onAnswer.emit(GameMessage.ANSWER(playerId = playerId, round = round, choice = choice, ts = System.currentTimeMillis()))
    }
    suspend fun injectReady(playerId: String, round: Int) {
        onReady.send(GameMessage.READY(playerId = playerId, round = round))
    }
}

class GameEngineTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun makeBridgeAndEngine(questions: List<Question>, playerNames: List<String> = listOf("A")): Pair<MockBridge, GameEngine> {
        val bridge = MockBridge()
        val engine = GameEngine(bridge, scope, WordRepository())
        playerNames.forEachIndexed { i, name ->
            engine.playerJoined("p$i", GameMessage.JOIN(playerId = "", name = name))
        }
        engine.init("EN_TO_ZH", questions.size, questions)
        engine.startGame()
        return bridge to engine
    }

    @Test
    fun `题库加载`() = runBlocking {
        val repo = WordRepository()
        repo.load("[{\"word\":\"hello\",\"meaning\":\"你好\",\"options\":[\"你好\",\"hi\",\"bye\",\"thanks\"]}]")
        val q = repo.generateQuestions("EN_TO_ZH", 1)
        assertEquals(1, q.size)
        assertEquals(0, q[0].correctIdx)
    }

    @Test
    fun `玩家加入`() = runBlocking {
        val repo = WordRepository()
        repo.load("[{\"word\":\"hello\",\"meaning\":\"你好\",\"options\":[\"你好\",\"hi\",\"bye\",\"thanks\"]}]")
        val q = repo.generateQuestions("EN_TO_ZH", 1)
        val (bridge, engine) = makeBridgeAndEngine(q, listOf("A", "B"))
        assertEquals(2, engine.players.size)
        assertEquals("A", engine.players["p0"]?.name)
        assertEquals("B", engine.players["p1"]?.name)
    }

    @Test
    fun `出题广播PREPARE`() = runBlocking {
        val repo = WordRepository()
        repo.load("[{\"word\":\"hello\",\"meaning\":\"你好\",\"options\":[\"你好\",\"hi\",\"bye\",\"thanks\"]}]")
        val q = repo.generateQuestions("EN_TO_ZH", 1)
        val (bridge, engine) = makeBridgeAndEngine(q)
        engine.nextRound()
        delay(200)
        val prepares = bridge.broadcasts.filterIsInstance<GameMessage.PREPARE>()
        assertEquals(1, prepares.size)
        assertEquals(1, prepares[0].round)
    }

    @Test
    fun `出题广播GO`() = runBlocking {
        val repo = WordRepository()
        repo.load("[{\"word\":\"hello\",\"meaning\":\"你好\",\"options\":[\"你好\",\"hi\",\"bye\",\"thanks\"]}]")
        val q = repo.generateQuestions("EN_TO_ZH", 1)
        val (bridge, engine) = makeBridgeAndEngine(q)
        engine.nextRound()
        delay(2200)
        val gos = bridge.broadcasts.filterIsInstance<GameMessage.GO>()
        assertEquals(1, gos.size)
        assertEquals("hello", gos[0].question)
        assertEquals(4, gos[0].options.size)
    }

    @Test
    fun `答对加分`() = runBlocking {
        val repo = WordRepository()
        repo.load("[{\"word\":\"hello\",\"meaning\":\"你好\",\"options\":[\"你好\",\"hi\",\"bye\",\"thanks\"]}]")
        val q = repo.generateQuestions("EN_TO_ZH", 1)
        val (bridge, engine) = makeBridgeAndEngine(q)
        engine.nextRound()
        delay(2200)
        bridge.injectAnswer("p0", 1, 0)
        delay(300)
        assertEquals(1, engine.players["p0"]?.score ?: -1)
        val reveals = bridge.broadcasts.filterIsInstance<GameMessage.REVEAL>()
        assertEquals(1, reveals.size)
        assertEquals(0, reveals[0].correctIdx)
        assertEquals("p0", reveals[0].winner)
    }

    @Test
    fun `答错不加分`() = runBlocking {
        val repo = WordRepository()
        repo.load("[{\"word\":\"hello\",\"meaning\":\"你好\",\"options\":[\"你好\",\"hi\",\"bye\",\"thanks\"]}]")
        val q = repo.generateQuestions("EN_TO_ZH", 1)
        val (bridge, engine) = makeBridgeAndEngine(q)
        engine.nextRound()
        delay(2200)
        bridge.injectAnswer("p0", 1, 1)
        delay(300)
        assertEquals(0, engine.players["p0"]?.score ?: -1)
        assertTrue(engine.currentRound!!.wrongPlayers.contains("p0"))
    }

    @Test
    fun `先答对者优先`() = runBlocking {
        val repo = WordRepository()
        repo.load("[{\"word\":\"hello\",\"meaning\":\"你好\",\"options\":[\"你好\",\"hi\",\"bye\",\"thanks\"]}]")
        val q = repo.generateQuestions("EN_TO_ZH", 1)
        val (bridge, engine) = makeBridgeAndEngine(q, listOf("A", "B"))
        engine.nextRound()
        delay(2200)
        bridge.injectAnswer("p1", 1, 0)
        delay(200)
        bridge.injectAnswer("p0", 1, 0)
        delay(200)
        assertEquals(1, engine.players["p1"]?.score ?: -1)
        assertEquals(0, engine.players["p0"]?.score ?: -1)
    }

    @Test
    fun `游戏结束排名`() = runBlocking {
        val repo = WordRepository()
        repo.load("[{\"word\":\"hello\",\"meaning\":\"你好\",\"options\":[\"你好\",\"hi\",\"bye\",\"thanks\"]}]")
        val q = repo.generateQuestions("EN_TO_ZH", 1)
        val (bridge, engine) = makeBridgeAndEngine(q, listOf("A", "B"))
        var ranking: List<RankEntry>? = null
        engine.onGameEnd = { ranking = it }
        engine.nextRound()
        delay(2200)
        bridge.injectAnswer("p0", 1, 0)
        delay(1500)
        assertNotNull(ranking)
        assertEquals(2, ranking!!.size)
        assertEquals("A", ranking!![0].name)
        assertEquals(1, ranking!![0].score)
    }
}