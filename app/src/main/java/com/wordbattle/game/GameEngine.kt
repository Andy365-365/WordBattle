package com.wordbattle.game

import com.wordbattle.data.Question
import com.wordbattle.debug.DebugLog
import com.wordbattle.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow

/** GameEngine 与网络交互的接口，方便单元测试 */
interface GameNetworkBridge {
    suspend fun broadcast(msg: GameMessage)
    suspend fun sendTo(playerId: String, msg: GameMessage)
    val onAnswer: Flow<GameMessage.ANSWER>
    val onClientJoin: Flow<Pair<String, GameMessage.JOIN>>
    val onReady: Channel<GameMessage.READY>
    /** 当前在场观察者数（收信号/回READY，不计分不显示）；>0 时启用 READY 握手 */
    val observerCount: Int
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
    private val rounds = mutableListOf<RoundState>()  // 历史轮次，用于异步 handleAnswer 定位
    private var roundIndex: Int = -1
    private var timeoutJob: Job? = null
    private var answerListenerJob: Job? = null
    private var goJob: Job? = null  // 跟踪延迟 GO 任务
    private var revealJob: Job? = null  // 跟踪 reveal delay 任务

    var onRoundChange: (RoundState, Question) -> Unit = { _, _ -> }
    var onScoreChange: () -> Unit = {}
    var onGameEnd: (List<RankEntry>) -> Unit = {}

    private var answerTimeoutMs: Long = 10_000

    fun init(direction: String, totalRounds: Int, questions: List<Question>, answerTimeout: Int = 10) {
        this.direction = direction
        this.totalRounds = totalRounds
        this.questions = questions
        this.answerTimeoutMs = (answerTimeout * 1000).toLong()
        this.state = GameState.WAITING
        roundIndex = -1
        DebugLog.i("[Engine] init: direction=$direction total=$totalRounds timeout=${answerTimeout}s")
    }

    fun playerJoined(playerId: String, joinMsg: GameMessage.JOIN) {
        if (joinMsg.role == "observer") {
            // 观察者：只收信号/回 READY，不进 players（不计分、不显示、不占答题名额）
            DebugLog.i("[Engine] 观察者加入: $playerId name=${joinMsg.name} (不参与计分)")
            return
        }
        players[playerId] = PlayerState(id = playerId, name = joinMsg.name)
        DebugLog.i("[Engine] 玩家加入: $playerId name=${joinMsg.name} 共${players.size}人")
    }

    fun startGame() {
        DebugLog.i("[Engine] startGame: ${players.size}人 开始")
        state = GameState.PLAYING
        roundIndex = -1
        rounds.clear()
        players.values.forEach { it.score = 0 }
        answerListenerJob?.cancel()
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
        rounds.add(roundState)
        currentRound = roundState
        DebugLog.i("[Engine] ====== 第${roundState.round}题: ${question.questionText} ======")
        onRoundChange(roundState, question)
        network.broadcast(GameMessage.PREPARE(round = roundState.round))
        DebugLog.d("broadcast: PREPARE")
        goJob = coroutineScope.launch {
            // READY 握手：仅当有观察者（自动化测试信号端）在场时，等其 READY 再广播 GO，
            // 保证 GO 到达时终端屏幕已就绪、脚本正在等待 GO，轮次不错位。
            // 真人对局（无观察者）保持原 500ms 节奏不变。等待超时(5s)照常 GO，防止卡死。
            val hasObserver = network.observerCount > 0
            if (hasObserver) {
                val t0 = System.currentTimeMillis()
                // 只接受 round 匹配的 READY；过期的（如脚本连接晚补发的）消费后丢弃
                var readyRound: Int? = null
                withTimeoutOrNull(5000) {
                    while (true) {
                        val r = network.onReady.receiveCatching().getOrNull() ?: break
                        if (r.round == roundState.round) { readyRound = r.round; break }
                        DebugLog.w("[Engine] nextRound: 丢弃过期 READY round=${r.round} (期望 ${roundState.round})")
                    }
                }
                DebugLog.i("[Engine] nextRound: READY 等待 ${System.currentTimeMillis() - t0}ms -> ${readyRound?.let { "收到 round=$it" } ?: "超时"}")
            } else {
                delay(500)
            }
            if (state == GameState.PLAYING) {
                DebugLog.i("[Engine] nextRound: 广播 GO 第${roundState.round}题")
                network.broadcast(GameMessage.GO(
                    round = roundState.round,
                    question = question.questionText,
                    options = question.options,
                    page = question.page,
                    timer = answerTimeoutMs.toInt() / 1000,
                    correctIdx = question.correctIdx
                ))
                startTimeout(question)
            }
        }
    }

    private fun handleAnswer(answer: GameMessage.ANSWER) {
        DebugLog.i("[Engine] handleAnswer: playerId=${answer.playerId} choice=${answer.choice} round=${answer.round}")
        // 用 ANSWER 消息里的 round 定位题目，而非 currentRound（已被 nextRound 更新）
        val round = rounds.find { it.round == answer.round }
            ?: run { DebugLog.e("[Engine] handleAnswer: no round ${answer.round}"); return }
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
        val questionIdx = round.round - 1
        val question = questions.getOrNull(questionIdx) ?: run { DebugLog.e("[Engine] handleAnswer: no question at idx $questionIdx"); return }

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
            delay(answerTimeoutMs)
            if (state == GameState.PLAYING && currentRound?.claimedBy == null) {
                revealAndNext(question)
            }
        }
    }

    private fun revealAndNext(question: Question) {
        revealJob?.cancel()
        revealJob = coroutineScope.launch {
            val round = currentRound ?: return@launch
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
    }

    fun endGame() {
        state = GameState.END
        timeoutJob?.cancel()
        timeoutJob = null
        goJob?.cancel()
        goJob = null
        revealJob?.cancel()
        revealJob = null
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
        revealJob?.cancel()
        revealJob = null
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
        revealJob?.cancel()
        revealJob = null
        answerListenerJob?.cancel()
        try { network.onReady.cancel() } catch (_: Exception) {}
        state = GameState.WAITING
    }

    enum class GameState { WAITING, PLAYING, END }
}