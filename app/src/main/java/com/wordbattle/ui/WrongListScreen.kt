@file:OptIn(ExperimentalMaterial3Api::class)

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
import com.wordbattle.data.WrongWord
import com.wordbattle.debug.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 最后错误时间显示为 "8.16" 这种 月.日 格式 */
private fun formatMonthDay(millis: Long): String {
    val df = SimpleDateFormat("M.d", Locale.getDefault())
    return df.format(Date(millis))
}

/**
 * 查看错题列表页
 * - 顶部方向单选：中→英 / 英→中（同一词两个方向各是独立记录）
 * - 排序：星级从高到低，同星级按最后错误时间从近到远
 * - 空状态：一个词都没有时列表空着，不显示鼓励语
 * - 删除：星级>0 弹确认防误触；0 星（已纠正）直接删
 */
@Composable
fun WrongListScreen(
    records: List<WrongWord>,
    onDelete: (word: String, direction: String) -> Unit,
    onBack: () -> Unit
) {
    var directionFilter by remember { mutableStateOf("EN_TO_ZH") }

    // 按方向过滤 + 星级降序（同星级按最后错误时间降序）
    val shown = records
        .filter { it.direction == directionFilter }
        .sortedWith(compareByDescending<WrongWord> { it.starLevel }
            .thenByDescending { it.lastWrongTime })

    var pendingDelete by remember { mutableStateOf<WrongWord?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("查看错题", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        // 方向单选
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                SegmentedButton(
                    selected = directionFilter == "ZH_TO_EN",
                    onClick = { directionFilter = "ZH_TO_EN" },
                    shape = SegmentedButtonDefaults.itemShape(0, 1)
                ) { Text("中→英") }
                SegmentedButton(
                    selected = directionFilter == "EN_TO_ZH",
                    onClick = { directionFilter = "EN_TO_ZH" },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text("英→中") }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (shown.isEmpty()) {
            // 空状态：什么都不显示
            Box(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shown) { rec ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(rec.word, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    rec.meaning,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    if (rec.starLevel == 0) "已纠正" else "★".repeat(rec.starLevel),
                                    fontSize = 13.sp,
                                    color = if (rec.starLevel == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                           else MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    "错 ${rec.wrongCount} 次 · ${formatMonthDay(rec.lastWrongTime)}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (rec.starLevel > 0) pendingDelete = rec
                                    else {
                                        DebugLog.i("[WrongList] 直接删除(0星): ${rec.word} ${rec.direction}")
                                        onDelete(rec.word, rec.direction)
                                    }
                                }
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("返回")
        }
    }

    // 删除确认对话框（仅星级>0 的词）
    pendingDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("确认删除") },
            text = {
                Text("\"${rec.word}\" 还有 ${rec.starLevel} 星，删除后记录将清空。确定已掌握？")
            },
            confirmButton = {
                TextButton(onClick = {
                    DebugLog.i("[WrongList] 确认删除: ${rec.word} ${rec.direction} star=${rec.starLevel}")
                    onDelete(rec.word, rec.direction)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}
