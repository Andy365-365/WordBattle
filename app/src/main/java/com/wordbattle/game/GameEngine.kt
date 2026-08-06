package com.wordbattle.game

import com.wordbattle.data.Question
import com.wordbattle.debug.DebugLog
import com.wordbattle.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

/** GameEngine 与网络交互的接口，方便单元测试 */
interface GameNetworkBridge {
    suspend fun broadcast(msg: GameMessage)
    suspend fun sendTo(playerId: String, msg: GameMessage)
    val onAnswer: Flow<GameMessage.ANSWER>
    val onClientJoin: Flow<Pair<String, GameMessage.JOIN>>
}

class GameEngine(
    private val network: GameNetworkBridge,
    private val coroutineScope: CoroutineScope,
    private val wordRepo: com.wordbattle.data.WordRepository
) {

    private var state = GameState.WAITING
    private var direction: String = "EN_TO_ZH"
    private var totalRounds: Int = 10
    private var questions: List<Question> = emptyList()

    val players = mutableMapOf<String, PlayerState>()
    var currentRound: RoundState? = null
    private var roundIndex: Int = -1
    private var timeoutJob: Job? = null
    private var answerListenerJob: Job? = null
    private var goJob: Job? = null  // 跟踪延迟 GO 任务

    var onRoundChange: (RoundState, Question) -> Unit = { _, _ -> }
    var onScoreChange: () -> Unit = {}
    var onGameEnd: (List<RankEntry>) -> Unit = {}

    fun init(direction: String, totalRounds: Int, questions: List<Question>) {
        this.direction = direction
        this.totalRounds = totalRounds
        this.questions = questions
        this.state = GameState.WAITING
        roundIndex = -1
    }

    fun playerJoined(playerId: String, joinMsg: GameMessage.JOIN) {
        players[playerId] = PlayerState(id = playerId, name = joinMsg.name)
        DebugLog.i("[Engine] 玩家加入: $playerId name=${joinMsg.name} 共${players.size}人")
    }

    fun startGame() {
        DebugLog.i("[Engine] startGame: ${players.size}人 开始")
        state = GameState.PLAYING
        roundIndex = -1
        players.values.forEach { it.score = 0 }
        answerListenerJob = coroutineScope.launch {
            network.onAnswer.collect { answer ->
                handleAnswer(answer)
            }
        }
    }

    suspend fun nextRound() {
        roundIndex++
        if (roundIndex >= questions.size) {
            DebugLog.i("[Engine] nextRound: 题用完, 结束游戏")
            endGame()
            return
        }
        val question = questions[roundIndex]
        val roundState = RoundState(round = roundIndex + 1)
        currentRound = roundState
        DebugLog.i("[Engine] nextRound: 第${roundState.round}题 question=${question.questionText}")
        onRoundChange(roundState, question)
        network.broadcast(GameMessage.PREPARE(round = roundState.round))
        DebugLog.d("broadcast: PREPARE")
        goJob = coroutineScope.launch {
            delay(500)
            if (state == GameState.PLAYING) {
                DebugLog.i("[Engine] nextRound: 广播 GO 第${roundState.round}题")
                network.broadcast(GameMessage.GO(
                    round = roundState.round,
                    question = question.questionText,
                    options = question.options,
                    page = question.page,
                    timer = 10
                ))
                startTimeout(question)
            }
        }
    }

    private fun handleAnswer(answer: GameMessage.ANSWER) {
        DebugLog.i("[Engine] handleAnswer: playerId=${answer.playerId} choice=${answer.choice}")
        val round = currentRound ?: run { DebugLog.e("[Engine] handleAnswer: no currentRound"); return }
        if (round.claimedBy != null || round.isRevealed) {
            DebugLog.i("[Engine] handleAnswer: 已抢/已揭晓, 忽略")
            return
        }
        if (answer.playerId in round.wrongPlayers) {
            DebugLog.i("[Engine] handleAnswer: 已选错, 忽略")
            return
        }
        if (!players.containsKey(answer.playerId)) {
            DebugLog.w("[Engine] handleAnswer: 未知玩家 ${answer.playerId}")
            return
        }
        val question = questions.getOrNull(roundIndex) ?: run { DebugLog.e("[Engine] handleAnswer: no question"); return }

        if (answer.choice == question.correctIdx) {
            DebugLog.i("[Engine] handleAnswer: 答对! playerId=${answer.playerId}")
            round.claimedBy = answer.playerId
            players[answer.playerId]?.score = (players[answer.playerId]?.score ?: 0) + 1
            timeoutJob?.cancel()
            timeoutJob = null
            coroutineScope.launch {
                network.broadcast(GameMessage.REVEAL(
                    round = round.round,
                    correctIdx = question.correctIdx,
                    winner = answer.playerId,
                    winnerName = players[answer.playerId]?.name
                ))
                network.broadcast(GameMessage.SCORE(
                    scores = players.mapValues { it.value.score }
                ))
                onScoreChange()
                nextRound()
            }
        } else {
            DebugLog.i("[Engine] handleAnswer: 答错! playerId=${answer.playerId}")
            round.wrongPlayers.add(answer.playerId)
            coroutineScope.launch {
                network.broadcast(GameMessage.SCORE(
                    scores = players.mapValues { it.value.score }
                ))
                if (round.isFinished(players.keys.toSet())) {
                    timeoutJob?.cancel()
                    timeoutJob = null
                    revealAndNext(question)
                }
            }
        }
    }

    private fun startTimeout(question: Question) {
        timeoutJob = coroutineScope.launch {
            delay(10_000)
            if (state == GameState.PLAYING && currentRound?.claimedBy == null) {
                revealAndNext(question)
            }
        }
    }

    private suspend fun revealAndNext(question: Question) {
        val round = currentRound ?: return
        round.isRevealed = true
        DebugLog.i("[Engine] revealAndNext: 正确答案=${question.correctIdx}")
        network.broadcast(GameMessage.REVEAL(
            round = round.round,
            correctIdx = question.correctIdx,
            winner = null,
            winnerName = null
        ))
        delay(2000)
        if (state == GameState.PLAYING) nextRound()
    }

    fun endGame() {
        state = GameState.END
        timeoutJob?.cancel()
        timeoutJob = null
        answerListenerJob?.cancel()
        val ranking = players.values.sortedByDescending { it.score }
            .map { RankEntry(name = it.name, score = it.score) }
        coroutineScope.launch {
            network.broadcast(GameMessage.GAMEOVER(ranking = ranking))
        }
        onGameEnd(ranking)
    }

    private var restarting = false

    fun restart() {
        if (restarting) return
        restarting = true
        // Cancel in-flight tasks
        goJob?.cancel()
        goJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        players.values.forEach { it.score = 0 }
        currentRound = null
        roundIndex = -1
        state = GameState.PLAYING
        // Re-create answer listener (cancelled by endGame)
        answerListenerJob?.cancel()
        answerListenerJob = coroutineScope.launch {
            network.onAnswer.collect { answer ->
                handleAnswer(answer)
            }
        }
        questions = wordRepo.generateQuestions(direction, totalRounds)
        DebugLog.i("[Engine] restart: 重新抽题")
        coroutineScope.launch {
            try {
                network.broadcast(GameMessage.RESTART())
                nextRound()
            } finally {
                restarting = false
            }
        }
    }

    fun cleanup() {
        timeoutJob?.cancel()
        timeoutJob = null
        goJob?.cancel()
        goJob = null
        answerListenerJob?.cancel()
        state = GameState.WAITING
    }

    enum class GameState { WAITING, PLAYING, END }
}