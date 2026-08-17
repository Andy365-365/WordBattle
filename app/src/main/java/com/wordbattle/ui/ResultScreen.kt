package com.wordbattle.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordbattle.data.RoundRecord
import com.wordbattle.network.RankEntry

@Composable
fun ResultScreen(
    ranking: List<RankEntry>,
    /** 本轮答错/超时的题；null = 未记录（不显示复盘入口） */
    wrongRecords: List<RoundRecord>?,
    onRestart: () -> Unit,
    onBack: () -> Unit,
    onReview: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Text("比赛结束!", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(30.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(modifier = Modifier.padding(16.dp)) {
                items(ranking) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val medal = when (ranking.indexOf(entry)) {
                                0 -> "\uD83E\uDD47"
                                1 -> "\uD83E\uDD48"
                                2 -> "\uD83E\uDD49"
                                else -> ""
                            }
                            Text("$medal ${ranking.indexOf(entry) + 1}. ${entry.name}",
                                fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("${entry.score} 分", fontSize = 18.sp)
                    }
                }
            }
        }

        // 复盘入口：有错题显示按钮，全对显示文案，无记录不显示
        wrongRecords?.let { records ->
            Spacer(modifier = Modifier.height(16.dp))
            if (records.isEmpty()) {
                Text("全部答对，太厉害了！", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            } else {
                TextButton(onClick = onReview) {
                    Text("查看错题（${records.size} 题）", fontSize = 18.sp)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onRestart,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Text("再来一局", fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onBack) {
            Text("返回首页")
        }
    }
}
