package com.wordbattle.debug

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object DebugLog {
    private val logs = ConcurrentLinkedQueue<String>()
    private const val MAX = 500
    private val sdf by lazy { SimpleDateFormat("HH:mm:ss", Locale.US) }
    private lateinit var logFile: File
    const val VERSION = "v1.5-host-answer"

    fun init(context: Context) {
        logFile = File("/storage/emulated/0/Download/ts/wordbattle_debug.log")
        appendToFile("=== $VERSION started ===\n")
    }

    fun i(msg: String) = add("I", msg)
    fun d(msg: String) = add("D", msg)
    fun w(msg: String) = add("W", msg)
    fun e(msg: String) = add("E", msg)
    fun e(tag: String, msg: String) = add("E", "[$tag] $msg")

    private fun add(level: String, msg: String) {
        val entry = "[$VERSION] ${sdf.format(System.currentTimeMillis())} [$level] $msg"
        synchronized(logs) {
            if (logs.size >= MAX) logs.poll()
            logs.add(entry)
        }
        appendToFile(entry + "\n")
    }

    private fun appendToFile(text: String) {
        if (::logFile.isInitialized) {
            try {
                logFile.appendText(text)
            } catch (_: Exception) { }
        }
    }

    fun getAll(): List<String> = synchronized(logs) { logs.toList() }
    fun clear() {
        synchronized(logs) { logs.clear() }
        appendToFile("=== logs cleared ===\n")
    }

    val filePath: String
        get() = if (::logFile.isInitialized) logFile.absolutePath else "not initialized"
}

object CrashHandler {
    private var handler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        handler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            DebugLog.e("CRASH", "${t.name}: ${e.javaClass.simpleName}: ${e.message}")
            e.stackTrace.take(10).forEach { DebugLog.e("  at $it") }
            handler?.uncaughtException(t, e)
        }
    }
}