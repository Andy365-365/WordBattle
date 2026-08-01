package com.wordbattle.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordbattle.debug.DebugLog

@Composable
fun HostWaitingScreen(
    playerName: String,
    playerCount: Int,
    onStart: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("等待玩家加入...", fontSize = 20.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Text("已加入: $playerCount 人", fontSize = 18.sp)
        Spacer(modifier = Modifier.height(40.dp))
        Button(
            onClick = { DebugLog.i("[UI] HostWaiting: 点击'开始游戏'"); onStart() },
            modifier = Modifier.height(60.dp)
        ) {
            Text("开始游戏", fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { DebugLog.i("[UI] HostWaiting: 点击'退出'"); onBack() }) {
            Text("退出")
        }
    }
}