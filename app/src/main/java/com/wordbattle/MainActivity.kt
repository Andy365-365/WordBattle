package com.wordbattle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.wordbattle.data.Question
import com.wordbattle.data.UserRepository
import com.wordbattle.data.WordRepository
import com.wordbattle.game.*
import com.wordbattle.network.*
import com.wordbattle.ui.*
import com.wordbattle.debug.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var userRepo: UserRepository
    private val wordRepo = WordRepository()

    private val _currentScreen = MutableStateFlow<Screen>(Screen.HOME)

    private fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    // 网络
    private lateinit var udpDiscovery: UdpDiscovery
    private var tcpServer: TcpServer? = null
    private var tcpClient: TcpClient? = null

    // 游戏引擎
    private var gameEngine: GameEngine? = null

    // 主机 UI
    private var hostDirection by mutableStateOf("EN_TO_ZH")
    private var hostTotalRounds by mutableIntStateOf(10)
    private var hostUnit by mutableStateOf("")
    private var hostPlayerCount by mutableIntStateOf(0)
    private var hostCurrentRound by mutableStateOf<RoundState?>(null)
    private var hostCurrentQuestion by mutableStateOf<Question?>(null)
    private var hostPlayers by mutableStateOf<List<PlayerState>>(emptyList())
    private var hostRanking by mutableStateOf<List<RankEntry>>(emptyList())

    // 抢答者 UI
    private val _discoveredHosts = MutableStateFlow<List<UdpBroadcast>>(emptyList())
    private val _playerStatus = MutableStateFlow("WAITING")  // WAITING / READY / ANSWERING / SUBMITTED / REVEAL
    private val _playerQuestion = MutableStateFlow("")
    private val _playerPage = MutableStateFlow(0)
    private val _playerOptions = MutableStateFlow<List<String>>(emptyList())
    private val _playerScore = MutableStateFlow(0)
    private var revealDelayJob: Job? = null
    private var myPlayerId by mutableStateOf("")
    private var hostAsPlayer = false
    private var playerRanking by mutableStateOf<List<RankEntry>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 崩溃捕获
        CrashHandler.install()
        DebugLog.init(this)
        DebugLog.i("App started, SDK=${android.os.Build.VERSION.SDK_INT}")
        userRepo = UserRepository(this)
        DebugLog.i("[UserRepo] 当前用户: ${userRepo.getCurrent()?.username}")

        udpDiscovery = UdpDiscovery(appScope)

        // 加载题库
        appScope.launch {
            try {
                val jsonContent = assets.open("words.json").bufferedReader().use { it.readText() }
                wordRepo.load(jsonContent)
                DebugLog.i("题库加载 OK")
            } catch (e: Exception) {
                DebugLog.e("题库加载失败", e.message ?: e.javaClass.simpleName)
                e.printStackTrace()
            }
        }

        // 监听发现的 hosts
        appScope.launch {
            try {
                udpDiscovery.onHostDiscovered.collect { broadcast ->
                    val current = _discoveredHosts.value.toMutableList()
                    val idx = current.indexOfFirst { it.ip == broadcast.ip }
                    if (idx >= 0) current[idx] = broadcast else current.add(broadcast)
                    _discoveredHosts.value = current
                }
            } catch (_: Exception) {}
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val screen by _currentScreen.collectAsState()
                    RenderScreen(screen)
                }
            }
        }
    }

    @Composable
    private fun RenderScreen(screen: Screen) {
        when (screen) {
            Screen.HOME -> HomeScreen(
                userRepository = userRepo,
                onHostClicked = { navigateTo(Screen.HOST_SETUP) },
                onPlayerClicked = { navigateTo(Screen.PLAYER_JOIN); udpDiscovery.startListening() },
                onDebugClicked = { navigateTo(Screen.DEBUG) },
                onUserManageClicked = { navigateTo(Screen.USER_MANAGE) }
            )

            Screen.HOST_SETUP -> HostSetupScreen(
                units = wordRepo.getUnits(),
                onStartWaiting = { dir, total, timeout, unit ->
                    hostDirection = dir
                    hostTotalRounds = total
                    hostUnit = unit
                    setupHostMode(dir, total, timeout, unit)
                    navigateTo(Screen.HOST_WAITING)
                },
                onBack = { navigateTo(Screen.HOME) }
            )

            Screen.HOST_WAITING -> HostWaitingScreen(
                playerName = "我",
                playerCount = hostPlayerCount,
                onStart = {
                    gameEngine?.startGame()
                    udpDiscovery.updateStatus("ANSWERING")
                    appScope.launch {
                        gameEngine?.nextRound()
                        navigateTo(Screen.HOST_GAME)
                    }
                },
                onBack = { stopHostMode(); navigateTo(Screen.HOME) }
            )

            Screen.HOST_GAME -> {
                hostCurrentRound?.let { round ->
                    val pStatus by _playerStatus.collectAsState()
                    val pQuestion by _playerQuestion.collectAsState()
                    val pOpts by _playerOptions.collectAsState()
                    HostGameScreen(
                        roundState = round,
                        totalRounds = hostTotalRounds,
                        players = hostPlayers,
                        playerStatus = pStatus,
                        playerQuestion = pQuestion,
                        playerOptions = pOpts,
                        onAnswer = { choice ->
                            _playerStatus.value = "SUBMITTED"
                            appScope.launch {
                                tcpClient?.send(GameMessage.ANSWER(
                                    playerId = myPlayerId,
                                    round = (hostCurrentRound?.round ?: 0),
                                    choice = choice,
                                    ts = System.currentTimeMillis()
                                ))
                            }
                        },
                        onRestart = { gameEngine?.restart() },
                        onBack = { stopHostMode(); navigateTo(Screen.HOME) }
                    )
                }
            }

            Screen.HOST_RESULT -> {
                ResultScreen(
                    ranking = hostRanking,
                    onRestart = {
                        gameEngine?.restart()
                        udpDiscovery.updateStatus("ANSWERING")
                        navigateTo(Screen.HOST_GAME)
                    },
                    onBack = { stopHostMode(); navigateTo(Screen.HOME) }
                )
            }

            Screen.PLAYER_JOIN -> {
                val hosts by _discoveredHosts.collectAsState()
                PlayerJoinScreen(
                    hosts = hosts,
                    onJoin = { host ->
                        udpDiscovery.stop()
                        joinHost(host)
                        navigateTo(Screen.PLAYER_WAITING)
                    },
                    onBack = {
                        udpDiscovery.stop()
                        navigateTo(Screen.HOME)
                    }
                )
            }

            Screen.PLAYER_WAITING -> PlayerWaitingScreen(
                hostName = "主机",
                onBack = { stopPlayerMode(); navigateTo(Screen.HOME) }
            )

            Screen.PLAYER_GAME -> {
                val questionText by _playerQuestion.collectAsState()
                val page by _playerPage.collectAsState()
                val options by _playerOptions.collectAsState()
                val status by _playerStatus.collectAsState()
                PlayerGameScreen(
                    questionText = questionText,
                    options = options,
                    status = status,
                    page = page,
                    onAnswer = { choice ->
                        _playerStatus.value = "SUBMITTED"
                        appScope.launch {
                            tcpClient?.send(GameMessage.ANSWER(
                                playerId = myPlayerId,
                                round = (hostCurrentRound?.round ?: 0),
                                choice = choice,
                                ts = System.currentTimeMillis()
                            ))
                        }
                    },
                    onBack = { stopPlayerMode(); navigateTo(Screen.HOME) }
                )
            }

            Screen.PLAYER_RESULT -> {
                val score by _playerScore.collectAsState()
                ResultScreen(
                    ranking = playerRanking,
                    onRestart = { appScope.launch { tcpClient?.send(GameMessage.RESTART()) } },
                    onBack = { stopPlayerMode(); navigateTo(Screen.HOME) }
                )
            }

            Screen.DEBUG -> DebugScreen(activity = this, onBack = { navigateTo(Screen.HOME) })
            Screen.USER_MANAGE -> UserManageScreen(
                userRepository = userRepo,
                onBack = { navigateTo(Screen.HOME) }
            )
        }
    }

    // ========== 主机逻辑 ==========

    private fun setupHostMode(direction: String, total: Int, answerTimeout: Int = 10, unit: String = "") {
        stopHostMode()
        val ip = getLocalIp()
        DebugLog.i("设置主机模式: ip=$ip dir=$direction total=$total timeout=${answerTimeout}s")
        // 等端口释放后再启动新服务
        appScope.launch {
            delay(200)
            tcpServer = TcpServer(appScope).apply {
                start()
            }
            val server = tcpServer!!
            gameEngine = GameEngine(server.asBridge(), appScope, wordRepo)

            // 监听客户端加入
            appScope.launch {
                try {
                    server.onClientJoin.collect { (playerId, joinMsg) ->
                        gameEngine?.playerJoined(playerId, joinMsg)
                        hostPlayerCount = gameEngine?.players?.size ?: 0
                        hostPlayers = gameEngine?.players?.values?.toList() ?: emptyList()
                    }
                } catch (_: Exception) {}
            }

            gameEngine?.onGameEnd = { ranking ->
                hostPlayers = gameEngine?.players?.values?.toList() ?: emptyList()
                hostRanking = ranking
                udpDiscovery.updateStatus("IDLE")
                navigateTo(Screen.HOST_RESULT)
            }

            gameEngine?.onRoundChange = { round, question ->
                hostCurrentRound = round
                hostCurrentQuestion = question
                udpDiscovery.updateStatus("WAITING")
            }

            gameEngine?.onScoreChange = {
                hostPlayers = gameEngine?.players?.values?.toList() ?: emptyList()
            }

            udpDiscovery.startAdvertising(ip, 5201, "我的手机", direction, total)
            val questions = wordRepo.generateQuestions(direction, total, unit)
            gameEngine?.init(direction, total, questions, answerTimeout)

            // 主机自己也连自己，可以答题（不跳转页面）
            hostAsPlayer = true
            tcpClient = TcpClient(appScope)
            delay(100)
            try {
                val ok = tcpClient?.connect("127.0.0.1", TcpServer.DEFAULT_PORT) ?: false
                if (ok) {
                    DebugLog.i("[Host] 本地连接成功，可以答题")
                    tcpClient?.send(GameMessage.JOIN(playerId = "", name = (userRepo.getCurrent()?.username ?: "主机")))
                    listenForHostMessages(noNavigate = true)
                } else {
                    DebugLog.e("[Host] 本地连接失败")
                }
            } catch (e: Exception) {
                DebugLog.e("[Host] 本地连接异常: ${e.message}")
            }
        }
    }

    private fun stopHostMode() {
        tcpClient?.disconnect()
        tcpClient = null
        gameEngine?.cleanup()
        tcpServer?.stop()
        tcpServer = null
        udpDiscovery.stop()
        hostAsPlayer = false
        DebugLog.i("主机模式已停止")
    }

    // ========== 抢答者逻辑 ==========

    private fun joinHost(host: UdpBroadcast) {
        DebugLog.i("加入主机: ${host.ip}:${host.port} name=${host.name}")
        tcpClient = TcpClient(appScope)
        appScope.launch {
            DebugLog.d("连接中...")
            val ok = tcpClient?.connect(host.ip, host.port) ?: false
            if (ok) {
                DebugLog.i("TCP 连接成功")
                tcpClient?.send(GameMessage.JOIN(playerId = "", name = (userRepo.getCurrent()?.username ?: "玩家")))
                DebugLog.i("发送 JOIN 消息")
                listenForMessages(host.ip)
            } else {
                DebugLog.e("连接失败: ${host.ip}:${host.port}")
                navigateTo(Screen.HOME)
            }
        }
    }

    private fun listenForMessages(hostIp: String) {
        listenForHostMessages(noNavigate = false)
    }

    private fun listenForHostMessages(noNavigate: Boolean) {
        appScope.launch {
            val json = Json { ignoreUnknownKeys = true }
            DebugLog.i("开始监听主机消息")
            try {
                tcpClient!!.onMessage.collect { jsonStr ->
                    try {
                        val type = json.decodeFromString<TypeWrapper>(jsonStr).type
                        DebugLog.d("收到: $type")
                        // 所有 UI 更新切回主线程
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            when (type) {
                                "WELCOME" -> {
                                    val msg = json.decodeFromString<GameMessage.WELCOME>(jsonStr)
                                    myPlayerId = msg.playerId
                                    DebugLog.i("分配 ID: $myPlayerId")
                                    if (!hostAsPlayer) navigateTo(Screen.PLAYER_GAME) else Unit
                                    _playerStatus.value = "WAITING"
                                }
                                "PREPARE" -> {
                                    revealDelayJob?.cancel()
                                    _playerStatus.value = "READY"
                                }
                                "GO" -> {
                                    val msg = json.decodeFromString<GameMessage.GO>(jsonStr)
                                    _playerQuestion.value = msg.question
                                    _playerPage.value = msg.page
                                    _playerOptions.value = msg.options
                                    _playerStatus.value = "ANSWERING"
                                    udpDiscovery.updateStatus("ANSWERING")
                                    DebugLog.i("GO: 题目=${msg.question}, 选项数=${msg.options.size}")
                                }
                                "REVEAL" -> {
                                    val msg = json.decodeFromString<GameMessage.REVEAL>(jsonStr)
                                    val correctText = _playerOptions.value.getOrNull(msg.correctIdx) ?: ""
                                    _playerStatus.value = "REVEAL:idx=${msg.correctIdx}:text=$correctText"
                                    DebugLog.i("揭晓: 答案=$correctText 胜者=${msg.winnerName}")
                                    revealDelayJob?.cancel()
                                    revealDelayJob = appScope.launch {
                                        delay(1500)
                                        if (_playerStatus.value.startsWith("REVEAL")) {
                                            _playerStatus.value = "WAITING"
                                        }
                                    }
                                }
                                "SCORES" -> {
                                    val msg = json.decodeFromString<GameMessage.SCORE>(jsonStr)
                                    _playerScore.value = msg.scores[myPlayerId] ?: 0
                                }
                                "GAME_OVER" -> {
                                    val msg = json.decodeFromString<GameMessage.GAMEOVER>(jsonStr)
                                    playerRanking = msg.ranking
                                    _playerScore.value = playerRanking.firstOrNull { it.name == "玩家" || it.name == "主机" }?.score ?: 0
                                    DebugLog.i("游戏结束, 排名: ${msg.ranking.map { "${it.name}:${it.score}" }.joinToString()}")
                                    if (!hostAsPlayer) navigateTo(Screen.PLAYER_RESULT) else Unit
                                }
                                "RESTART" -> {
                                    _playerScore.value = 0
                                    _playerStatus.value = "WAITING"
                                    _playerQuestion.value = ""
                                    // 不清空 _playerOptions，等 GO 覆盖
                                    if (!hostAsPlayer) navigateTo(Screen.PLAYER_GAME) else Unit
                                    appScope.launch { tcpClient?.send(GameMessage.RESTART_ACK(playerId = myPlayerId)) }
                                }
                                else -> DebugLog.w("未知消息类型: $type")
                            }
                        }
                    } catch (e: Exception) {
                        DebugLog.e("解析错误", "$jsonStr -> ${e.message}")
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("连接断开", "${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun stopPlayerMode() {
        tcpClient?.disconnect()
        tcpClient = null
        udpDiscovery.stop()
    }

    private fun getLocalIp(): String {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces().asSequence()) {
                if (iface.isUp && !iface.isLoopback) {
                    for (addr in iface.interfaceAddresses) {
                        val a = addr.address
                        if (a is Inet4Address) {
                            val h = a.hostAddress
                            if (h != null && !h.startsWith("127.")) return h
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.1.1"
    }


    override fun onDestroy() {
        super.onDestroy()
        appScope.cancel()
        stopHostMode()
        stopPlayerMode()
    }
}