package com.wordbattle.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

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

    // Advertising state (mutable)
    private var advIp = ""
    private var advPort = 0
    private var advName = ""
    private var advDir = ""
    private var advTotal = 0
    @Volatile private var advStatus = "IDLE"

    private fun safeStop() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        job?.cancel()
        job = null
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val addrs = mutableListOf<InetAddress>()
        try {
            addrs.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}
        try {
            NetworkInterface.getNetworkInterfaces().asSequence().forEach { iface ->
                if (iface.isUp && !iface.isLoopback) {
                    iface.interfaceAddresses.forEach { ia ->
                        val addr = ia.address
                        if (addr is Inet4Address && addr.hostAddress?.startsWith("127.") != true) {
                            val netmaskBytes = ia.networkPrefixLength.toInt().let { prefix ->
                                val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                                byteArrayOf(
                                    (mask shr 24).toByte(),
                                    (mask shr 16).toByte(),
                                    (mask shr 8).toByte(),
                                    mask.toByte()
                                )
                            }
                            val ipBytes = addr.address
                            val broadcastBytes = ipBytes.mapIndexed { i, b ->
                                val ipByte = b.toInt() and 0xFF
                                val notMask = (netmaskBytes[i].toInt() and 0xFF) xor 0xFF
                                (ipByte or notMask).toByte()
                            }.toByteArray()
                            try {
                                addrs.add(InetAddress.getByAddress(broadcastBytes))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return addrs
    }

    fun startAdvertising(ip: String, tcpPort: Int, name: String, dir: String, total: Int) {
        safeStop()
        advIp = ip
        advPort = tcpPort
        advName = name
        advDir = dir
        advTotal = total
        advStatus = "IDLE"
        val broadcastAddrs = getBroadcastAddresses()
        job = coroutineScope.launch(Dispatchers.IO) {
            socket = DatagramSocket().apply {
                broadcast = true
                reuseAddress = true
            }
            while (isActive) {
                val broadcast = UdpBroadcast(ip = advIp, port = advPort, name = advName, dir = advDir, total = advTotal, status = advStatus)
                val data = json.encodeToString(broadcast).toByteArray(Charsets.UTF_8)
                for (addr in broadcastAddrs) {
                    try {
                        val packet = DatagramPacket(data, data.size, addr, broadcastPort)
                        socket?.send(packet)
                    } catch (_: Exception) {}
                }
                delay(2000)
            }
        }
    }

    fun updateStatus(status: String) {
        advStatus = status
    }

    fun startListening() {
        safeStop()
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
        safeStop()
    }

    companion object {
        const val BROADCAST_PORT = 5200
    }
}