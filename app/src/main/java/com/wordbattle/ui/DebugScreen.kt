package com.wordbattle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordbattle.debug.DebugLog
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun DebugScreen(
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf(DebugLog.getAll()) }
    var refreshKey by remember { mutableStateOf(0) }
    var exportMsg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            refreshKey++
        }
    }
    LaunchedEffect(refreshKey) {
        logs = DebugLog.getAll()
    }

    fun exportLogs() {
        try {
            val file = File("/storage/emulated/0/Download/wordbattle_log_${System.currentTimeMillis()}.txt")
            file.writeText(logs.joinToString("\n"))
            exportMsg = "已导出到 Download 目录"
            scope.launch {
                kotlinx.coroutines.delay(5000)
                exportMsg = ""
            }
        } catch (e: Exception) {
            exportMsg = "导出失败: ${e.message}"
            scope.launch {
                kotlinx.coroutines.delay(5000)
                exportMsg = ""
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column {
            Surface(color = Color(0xff1a1a2e)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("调试日志 (${logs.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xff00ff41))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { refreshKey++; logs = DebugLog.getAll() }) {
                            Text("刷新", color = Color(0xff00ff41))
                        }
                        TextButton(onClick = { DebugLog.clear(); logs = emptyList(); refreshKey++ }) {
                            Text("清空", color = Color(0xffff6b6b))
                        }
                        TextButton(onClick = { exportLogs() }) {
                            Text("导出", color = Color(0xffffcc00))
                        }
                        TextButton(onClick = onBack) {
                            Text("返回", color = Color(0xff00ff41))
                        }
                    }
                }
            }

            if (exportMsg.isNotEmpty()) {
                Surface(color = Color(0xff003300)) {
                    Text(
                        text = exportMsg,
                        fontSize = 11.sp,
                        color = Color(0xffffcc00),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                items(logs) { log ->
                    val level = log.substringAfter("[").substringBefore("]")
                    val color = when (level) {
                        "E" -> Color(0xffff6b6b)
                        "W" -> Color(0xffffcc00)
                        "I" -> Color(0xff66ccff)
                        else -> Color(0xff00ff41)
                    }
                    Text(
                        text = log,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}