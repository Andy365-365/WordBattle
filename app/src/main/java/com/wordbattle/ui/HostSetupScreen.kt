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
fun HostSetupScreen(
    onStartWaiting: (direction: String, totalRounds: Int) -> Unit,
    onBack: () -> Unit
) {
    var direction by remember { mutableStateOf("EN_TO_ZH") }
    var totalRounds by remember { mutableIntStateOf(10) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("主机设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        Text("方向", fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(
                selected = direction == "EN_TO_ZH",
                onClick = { DebugLog.i("[UI] HostSetup: 选择方向 英→中"); direction = "EN_TO_ZH" }
            )
            Text("英→中", fontSize = 16.sp)
            RadioButton(
                selected = direction == "ZH_TO_EN",
                onClick = { DebugLog.i("[UI] HostSetup: 选择方向 中→英"); direction = "ZH_TO_EN" }
            )
            Text("中→英", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("题目数", fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 20, 30).forEach { n ->
                val selected = totalRounds == n
                Button(
                    onClick = { DebugLog.i("[UI] HostSetup: 选择题数 $n"); totalRounds = n },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text("$n")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                DebugLog.i("[UI] HostSetup: 点击'开始等待玩家' dir=$direction total=$totalRounds")
                onStartWaiting(direction, totalRounds)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text("开始等待玩家", fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = { DebugLog.i("[UI] HostSetup: 点击'返回'"); onBack() }) {
            Text("返回")
        }
    }
}