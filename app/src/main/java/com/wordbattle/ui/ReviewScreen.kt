package com.wordbattle.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordbattle.data.RoundRecord

/**
 * 赛后复盘页：本轮答错/超时的题目列表
 * [records] 只传答错/超时的题；[correctCount] 用于顶部统计
 */
@Composable
fun ReviewScreen(
    records: List<RoundRecord>,
    correctCount: Int,
    onPractice: () -> Unit,
    onBack: () -> Unit
) {
    val wrongCount = records.count { !it.timedOut }
    val timeoutCount = records.count { it.timedOut }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("本轮错题", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "$correctCount 题答对 / $wrongCount 题答错 / $timeoutCount 题超时",
                fontSize = 16.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(records) { rec ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${rec.round}. ${rec.word}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "题目：${rec.question}",
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("正确答案：${rec.correctAnswer}", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        Text(
                            if (rec.timedOut) "你的答案：超时未答"
                            else "你的答案：${rec.userAnswer}",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onPractice,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text("练习这些词")
            }
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) {
                Text("返回主页")
            }
        }
    }
}
