package com.wordbattle.network

import com.wordbattle.debug.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Socket

class TcpClient(
    private val scope: CoroutineScope
) {

    private val json = Json { ignoreUnknownKeys = true }
    private var socket: Socket? = null
    private var readJob: Job? = null

    private val _onMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val onMessage = _onMessage

    val isConnected: Boolean
        get() = socket?.isConnected == true && !socket?.isClosed!!

    suspend fun connect(hostIp: String, port: Int = 5201): Boolean {
        DebugLog.i("[TcpClient] 尝试连接 $hostIp:$port")
        try {
            socket = Socket(hostIp, port)
            DebugLog.i("[TcpClient] 连接成功 $hostIp:$port")
            readJob = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val jsonStr = TcpCodec.decode(socket!!.getInputStream())
                        DebugLog.d("[TcpClient] 收到: $jsonStr")
                        _onMessage.emit(jsonStr)
                    }
                } catch (e: Exception) {
                    DebugLog.e("[TcpClient] 读消息异常", "${e.javaClass.simpleName}: ${e.message}")
                }
            }
            return true
        } catch (e: Exception) {
            DebugLog.e("[TcpClient] 连接失败", "${e.javaClass.simpleName}: ${e.message}")
            socket?.close()
            socket = null
            return false
        }
    }

    suspend fun send(msg: GameMessage) {
        val jsonStr = json.encodeToString(GameMessage.serializer(), msg)
        DebugLog.d("[TcpClient] 发送: $jsonStr")
        try {
            val data = TcpCodec.encode(jsonStr)
            socket?.getOutputStream()?.write(data)
            socket?.getOutputStream()?.flush()
        } catch (e: Exception) {
            DebugLog.e("[TcpClient] 发送失败", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun disconnect() {
        DebugLog.i("[TcpClient] 主动断开")
        readJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}