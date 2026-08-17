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
    const val VERSION = "v2.2-20260817-1925"

    private val queue = ConcurrentLinkedQueue<String>()
    private var logFile: File? = null
    private const val CHANNEL_SIZE = 500
    private const val MAX_LOG_FILE_SIZE = 10L * 1024 * 1024  // 10MB 轮转，保留 current + .old

    private var logServerIp: String? = null
    private var logServerPort = 8765
    private var pushJob: Job? = null
    var deviceTag = "unknown"
    var deviceId = "unknown"
    // 推送开关（Debug 页可切换）。默认开：开发期 log_receiver 依赖它。
    @Volatile var pushEnabled = true
        private set
    @Volatile private var pushFailures = 0
    @Volatile private var pushPausedUntil = 0L
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
        startPushJob()
    }

    fun setLogServer(ip: String, port: Int = 8765) {
        logServerIp = ip
        logServerPort = port
        pushFailures = 0
    }

    /** Debug 页切换日志推送。 */
    fun setPushEnabled(v: Boolean) {
        if (pushEnabled == v) return
        pushEnabled = v
        if (v) pushFailures = 0
        localNote("[NetLog] 日志推送: ${if (v) "开启" else "关闭"}")
    }

    /** 推送状态（Debug 页展示）。 */
    fun pushStatus(): String {
        val ip = logServerIp ?: return "推送: 未配置"
        return when {
            !pushEnabled -> "推送: 已关闭 ($ip:$logServerPort)"
            System.currentTimeMillis() < pushPausedUntil ->
                "推送: 暂停 ${((pushPausedUntil - System.currentTimeMillis()) / 1000 + 1)}s（$ip:$logServerPort 不可达）"
            else -> "推送: 开启 ($ip:$logServerPort)"
        }
    }

    private fun startPushJob() {
        if (pushJob?.isActive == true) return
        pushJob = scope.launch {
            for ((level, msg) in networkQueue) {
                handlePushResult(pushToServer(level, msg))
            }
        }
    }

    private suspend fun pushToServer(level: String, msg: String): Boolean {
        val ip = logServerIp ?: return false
        if (!pushEnabled) return false
        if (System.currentTimeMillis() < pushPausedUntil) return false
        val body = "{\"device\":\"$deviceTag\",\"devId\":\"$deviceId\",\"version\":\"$VERSION\",\"level\":\"$level\",\"msg\":\"${msg.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"
        return try {
            val conn = URL("http://$ip:$logServerPort/log").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(body.toByteArray())
            val rc = conn.responseCode
            conn.disconnect()
            rc == 200
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 熔断：连续 10 次失败暂停 30s。
     * 关键：失败提示只写本地文件（localNote），绝不走 log()——
     * 否则错误日志会再次入队推送、再次失败再生成错误日志，无限滚雪球。
     */
    private fun handlePushResult(ok: Boolean) {
        if (ok) { pushFailures = 0; return }
        pushFailures++
        if (pushFailures == 10) {
            pushPausedUntil = System.currentTimeMillis() + 30_000
            pushFailures = 0
            localNote("[NetLog] 日志服务器连续 10 次失败，暂停推送 30s（日志仍写入本地文件）")
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
        appendToFile(line + "\n")
        networkQueue.trySend(level to msg)
    }

    /** 仅本地记录（文件 + 内存队列），不入推送队列——用于推送失败/开关等系统提示。 */
    private fun localNote(msg: String) {
        val ts = fmt.format(System.currentTimeMillis())
        val line = "[$VERSION][$deviceTag] $ts [E] $msg"
        queue.add(line)
        if (queue.size > CHANNEL_SIZE) queue.poll()
        appendToFile(line + "\n")
    }

    private fun appendToFile(text: String) {
        try {
            val f = logFile ?: return
            if (f.length() > MAX_LOG_FILE_SIZE) {
                val old = File(f.parentFile, f.name + ".old")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(text)
        } catch (_: Exception) {}
    }

    fun getLogs(): List<String> = queue.toList().reversed()
    fun getAll(): List<String> = getLogs()
    fun clear() { queue.clear() }
}
