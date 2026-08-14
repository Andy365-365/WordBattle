package com.wordbattle.debug

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object DebugLog {
    const val VERSION = "v2.0-20260814-0000"

    private val queue = ConcurrentLinkedQueue<String>()
    private var logFile: File? = null
    private const val CHANNEL_SIZE = 500

    private var logServerIp: String? = null
    private var logServerPort = 8765
    private var pushJob: Job? = null
    var deviceTag = "unknown"
    var deviceId = "unknown"
    private val networkQueue = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun init(context: Context) {
        deviceTag = if (context.packageName.contains("client")) "client" else "host"
        deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).take(8)
        val logDir = File("/storage/emulated/0/Download/ts")
        if (!logDir.exists()) logDir.mkdirs()
        logFile = File("/storage/emulated/0/Download/ts/wordbattle_debug_${deviceTag}.log")
        appendToFile("=== $VERSION [$deviceTag][$deviceId] started ===\n")
        setLogServer("192.168.50.201", 8765)
        if (pushJob?.isActive != true) {
            pushJob = scope.launch {
                for ((level, msg) in networkQueue) {
                    try {
                        pushToServer(level, msg)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun setLogServer(ip: String, port: Int = 8765) {
        logServerIp = ip
        logServerPort = port
    }

    private suspend fun pushToServer(level: String, msg: String) {
        val ip = logServerIp ?: return
        val body = "{\"device\":\"$deviceTag\",\"devId\":\"$deviceId\",\"version\":\"$VERSION\",\"level\":\"$level\",\"msg\":\"${msg.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"
        try {
            val conn = URL("http://$ip:$logServerPort/log").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(body.toByteArray())
            val rc = conn.responseCode
            conn.disconnect()
            if (rc != 200) DebugLog.e("[NetLog] push failed rc=$rc")
        } catch (e: Exception) {
            DebugLog.e("[NetLog] push error: ${e.message}")
        }
    }

    fun i(msg: String) = log("I", msg)
    fun d(msg: String) = log("D", msg)
    fun w(msg: String) = log("W", msg)
    fun e(tag: String, msg: String) = log("E", "[$tag] $msg")
    fun e(msg: String) = log("E", msg)

    private fun log(level: String, msg: String) {
        val ts = fmt.format(System.currentTimeMillis())
        val line = "[$VERSION][$deviceTag] $ts [$level] $msg"
        queue.add(line)
        if (queue.size > CHANNEL_SIZE) queue.poll()
        try { appendToFile(line + "\n") } catch (_: Exception) {}
        networkQueue.trySend(level to msg)
    }

    private fun appendToFile(text: String) {
        try { logFile?.appendText(text) } catch (_: Exception) {}
    }

    fun getLogs(): List<String> = queue.toList().reversed()
    fun getAll(): List<String> = getLogs()
    fun clear() { queue.clear() }
}