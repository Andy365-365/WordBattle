package com.wordbattle.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpDiscovery(
    private val coroutineScope: CoroutineScope
) {

    private val json = Json { ignoreUnknownKeys = true }
    private var socket: DatagramSocket? = null
    private val broadcastPort = 5200

    val discoveredHosts = mutableMapOf<String, UdpBroadcast>()
    private val _onHostDiscovered = MutableSharedFlow<UdpBroadcast>(extraBufferCapacity = 4)
    val onHostDiscovered = _onHostDiscovered

    private var job: Job? = null

    fun startAdvertising(ip: String, tcpPort: Int, name: String, dir: String, total: Int) {
        stop()
        job = coroutineScope.launch(Dispatchers.IO) {
            socket = DatagramSocket().apply {
                broadcast = true
                reuseAddress = true
            }
            val broadcast = UdpBroadcast(ip = ip, port = tcpPort, name = name, dir = dir, total = total)
            val data = json.encodeToString(broadcast).toByteArray(Charsets.UTF_8)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            while (isActive) {
                try {
                    val packet = DatagramPacket(data, data.size, broadcastAddr, broadcastPort)
                    socket?.send(packet)
                } catch (_: Exception) {
                    // Android 15 后台 UDP 可能被阻止，静默忽略
                }
                delay(2000)
            }
        }
    }

    fun startListening() {
        stop()
        job = coroutineScope.launch(Dispatchers.IO) {
            socket = DatagramSocket(broadcastPort).apply {
                broadcast = true
                reuseAddress = true
                soTimeout = 5000
            }
            val buffer = ByteArray(1024)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket?.receive(packet)
                    val jsonStr = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val broadcast = json.decodeFromString<UdpBroadcast>(jsonStr)
                    discoveredHosts[broadcast.ip] = broadcast
                    _onHostDiscovered.tryEmit(broadcast)
                } catch (e: Exception) {
                    // 超时或解析错误，忽略
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    companion object {
        const val BROADCAST_PORT = 5200
    }
}