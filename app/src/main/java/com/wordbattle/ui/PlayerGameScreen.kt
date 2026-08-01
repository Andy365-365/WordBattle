package com.wordbattle.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 抢答者游戏页：显示题目和四个选项按钮
 */
@Composable
fun PlayerGameScreen(
    questionText: String,
    options: List<String>,
    onAnswer: (choice: Int) -> Unit,
    onBack: () -> Unit,
    status: String = "WAITING"
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        // 状态提示
        when {
            status == "WAITING" -> {
                Text("等待题目...", fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            status == "READY" -> {
                Text("准备!", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
            status == "ANSWERING" -> {
                Text("抢答!", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            status == "SUBMITTED" -> {
                Text("已提交，等待揭晓", fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            status.startsWith("REVEAL") -> {
                val parts = status.removePrefix("REVEAL:idx=").split(":")
                val text = parts.getOrNull(1)?.removePrefix("text=") ?: ""
                Text("正确答案: $text",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 题目
        if (status != "WAITING") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(questionText, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 四个选项按钮
            val canAnswer = status == "ANSWERING"
            options.forEachIndexed { idx, opt ->
                val label = "${('A'+idx).toChar()}"
                Button(
                    onClick = { if (canAnswer) onAnswer(idx) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = canAnswer,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text("$label. $opt", fontSize = 20.sp)
                }
                if (idx < 3) Spacer(modifier = Modifier.height(12.dp))
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onBack) {
            Text("退出")
        }
    }
}