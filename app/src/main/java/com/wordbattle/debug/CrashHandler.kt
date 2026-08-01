package com.wordbattle.debug

import java.io.PrintWriter
import java.io.StringWriter

object CrashHandler {
    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            DebugLog.e("[CRASH] ${t.name}: ${e.message}")
            DebugLog.e(sw.toString())
        }
    }
}