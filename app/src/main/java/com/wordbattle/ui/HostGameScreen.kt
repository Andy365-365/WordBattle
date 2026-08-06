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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordbattle.data.Question
import com.wordbattle.game.PlayerState
import com.wordbattle.game.RoundState

@Composable
fun HostGameScreen(
    roundState: RoundState,
    totalRounds: Int,
    players: List<PlayerState>,
    playerStatus: String,
    playerQuestion: String,
    playerOptions: List<String>,
    onAnswer: (Int) -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("第 ${roundState.round}/$totalRounds 题", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("退出") }
        }

        Spacer(Modifier.height(12.dp))
        Text("状态: ${playerStatus}", fontSize = 14.sp)

        Spacer(Modifier.height(16.dp))

        // 只有收到开始答题信号后才显示题目和选项
        if (playerStatus == "ANSWERING" || playerStatus == "SUBMITTED") {
            Text("题目: $playerQuestion", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("快速答题:", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            playerOptions.forEachIndexed { idx, opt ->
                Button(
                    onClick = { onAnswer(idx) },
                    enabled = playerStatus == "ANSWERING",
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(opt, fontSize = 18.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(16.dp))
        } else if (playerStatus == "READY" || playerStatus == "WAITING") {
            Text("准备中，请等待...", fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }

        // 揭晓答案
        if (playerStatus.startsWith("REVEAL")) {
            val parts = playerStatus.split(":")
            val correctText = parts.getOrNull(2)?.removePrefix("text=") ?: ""
            Card(elevation = CardDefaults.cardElevation(4.dp), modifier = Modifier.fillMaxWidth()) {
                PaddingValues(12.dp).let {
                    Column(Modifier.padding(it)) {
                        Text("正确答案: $correctText", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                        Spacer(Modifier.height(4.dp))
                        Text("等待下一题...", fontSize = 16.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        LazyColumn {
            item { Text("玩家比分:", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            items(players.sortedByDescending { it.score }) { p ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(p.name, modifier = Modifier.width(60.dp))
                    Text("${p.score} 分")
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) {
            Text("重新开始")
        }
    }
}