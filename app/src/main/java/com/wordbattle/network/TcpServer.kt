package com.wordbattle.network

import com.wordbattle.debug.DebugLog
import com.wordbattle.game.GameNetworkBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import java.net.ServerSocket
import java.net.Socket

class TcpServer(
    private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val port: Int = 5201
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val clients = mutableListOf<ClientSession>()
    private val clientCounter = ClientCounter()

    val onClientJoin = MutableSharedFlow<Pair<String, GameMessage.JOIN>>(extraBufferCapacity = 4)
    val onAnswer = MutableSharedFlow<GameMessage.ANSWER>(extraBufferCapacity = 4)
    val onRestartAck = MutableSharedFlow<GameMessage.RESTART_ACK>(extraBufferCapacity = 4)
    val onClientDisconnect = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val onReady = Channel<GameMessage.READY>(Channel.BUFFERED)

    // 观察者集合：收广播、可发 READY，但不进 GameEngine.players（不计分/不显示/不占名额）。
    // 在服务器层注册+断线清理，跨局干净，避免残留导致后续游戏白等 READY 超时。
    fun addObserver(playerId: String) { synchronized(observers) { observers.add(playerId) } }
    fun removeObserver(playerId: String) { synchronized(observers) { observers.remove(playerId) } }
    val observerCount: Int get() = synchronized(observers) { observers.size }
    private val observers = mutableSetOf<String>()

    fun start(): Job {
        serverJob = scope.launch(Dispatchers.IO) {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(java.net.InetSocketAddress("0.0.0.0", port))
            serverSocket = server
            DebugLog.i("TCP 服务器启动 port=$port")
            try {
                while (true) {
                    val socket = server.accept()
                    handleClient(socket)
                }
            } catch (e: Exception) {
                DebugLog.e("服务器异常: ${e.message}")
            }
        }
        return serverJob!!
    }

    private fun handleClient(socket: Socket) {
        val playerId = "p${clientCounter.getAndIncrement()}"
        val session = ClientSession(playerId = playerId, socket = socket, json)
        clients.add(session)
        DebugLog.i("[TcpServer] 新连接: $playerId addr=${socket.remoteSocketAddress}")

        scope.launch(Dispatchers.IO) {
            try {
                val inputStream = socket.inputStream
                while (socket.isConnected) {
                    val data = TcpCodec.decode(inputStream) ?: break
                    DebugLog.d("[TcpServer] 收到 from $playerId: $data")
                    val msg = parseMessage(data)
                    when (msg) {
                        is GameMessage.JOIN -> {
                            if (msg.role == "observer") {
                                addObserver(playerId)
                                DebugLog.i("[TcpServer] JOIN 观察者: name=${msg.name} -> playerId=$playerId (不计分不显示)")
                            } else {
                                DebugLog.i("[TcpServer] JOIN: name=${msg.name} -> playerId=$playerId")
                            }
                            session.send(GameMessage.WELCOME(playerId = playerId))
                            DebugLog.d("[TcpServer] 回送 WELCOME: playerId=$playerId")
                            onClientJoin.emit(playerId to msg)
                        }
                        is GameMessage.ANSWER -> {
                            DebugLog.i("[TcpServer] ANSWER: playerId=${msg.playerId} choice=${msg.choice}")
                            onAnswer.emit(msg)
                        }
                        is GameMessage.READY -> {
                            DebugLog.i("[TcpServer] READY: playerId=${msg.playerId} round=${msg.round}")
                            onReady.trySend(msg)
                        }
                        is GameMessage.RESTART_ACK -> {
                            onRestartAck.emit(msg)
                        }
                        else -> DebugLog.w("[TcpServer] 未处理的消息类型: ${msg::class.simpleName}")
                    }
                }
            } catch (e: Exception) {
                DebugLog.w("[TcpServer] 连接异常 $playerId: ${e.message}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
                clients.remove(session)
                removeObserver(playerId)  // 观察者断线清理，避免残留卡后续游戏
                DebugLog.i("[TcpServer] 断开: $playerId")
                onClientDisconnect.tryEmit(playerId)
            }
        }
    }

    private fun parseMessage(jsonStr: String): GameMessage {
        val type = json.decodeFromString<TypeWrapper>(jsonStr).type
        return when (type) {
            "JOIN" -> json.decodeFromString<GameMessage.JOIN>(jsonStr)
            "ANSWER" -> json.decodeFromString<GameMessage.ANSWER>(jsonStr)
            "RESTART_ACK" -> json.decodeFromString<GameMessage.RESTART_ACK>(jsonStr)
            "READY" -> json.decodeFromString<GameMessage.READY>(jsonStr)
            else -> GameMessage.RESTART()
        }
    }

    suspend fun sendTo(playerId: String, msg: GameMessage) {
        val session = clients.firstOrNull { it.playerId == playerId } ?: return
        DebugLog.d("[TcpServer] sendTo $playerId: ${msg::class.simpleName}")
        session.send(msg)
    }

    suspend fun broadcast(msg: GameMessage) {
        DebugLog.d("[TcpServer] broadcast: ${msg::class.simpleName} to ${clients.map { it.playerId }}")
        val jsonStr = json.encodeToString(GameMessage.serializer(), msg)
        val data = TcpCodec.encode(jsonStr)
        val toRemove = mutableListOf<ClientSession>()
        for (session in clients) {
            try {
                session.sendRaw(data)
            } catch (e: Exception) {
                DebugLog.w("[TcpServer] broadcast 发送失败: ${session.playerId} -> ${e.message}")
                toRemove.add(session)
            }
        }
        clients.removeAll(toRemove)
        for (removed in toRemove) {
            DebugLog.i("[TcpServer] 断开: ${removed.playerId}")
        }
    }

    fun stop() {
        serverJob?.cancel()
        clients.forEach {
            try { it.socket.close() } catch (_: Exception) {}
        }
        clients.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        DebugLog.i("TCP 服务器已关闭，端口已释放")
    }

    inner class ClientSession(val playerId: String, val socket: Socket, private val j: Json) {
        fun send(msg: GameMessage) {
            val jsonStr = j.encodeToString(GameMessage.serializer(), msg)
            sendRaw(TcpCodec.encode(jsonStr))
        }
        fun sendRaw(data: ByteArray) {
            socket.outputStream.write(data)
            socket.outputStream.flush()
        }
    }

    private class ClientCounter {
        private var count = 0
        @Synchronized fun getAndIncrement(): Int = count++
    }

    fun asBridge(): GameNetworkBridge {
        val self = this
        return object : GameNetworkBridge {
            override suspend fun broadcast(msg: GameMessage) = self.broadcast(msg)
            override suspend fun sendTo(playerId: String, msg: GameMessage) = self.sendTo(playerId, msg)
            override val onAnswer = self.onAnswer
            override val onClientJoin = self.onClientJoin
            override val onReady: Channel<GameMessage.READY> = self.onReady
            override val observerCount: Int get() = self.observerCount
        }
    }

    companion object {
        const val DEFAULT_PORT = 5201
    }
}