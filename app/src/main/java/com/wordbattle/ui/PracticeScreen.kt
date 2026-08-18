@file:OptIn(ExperimentalMaterial3Api::class)

package com.wordbattle.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordbattle.data.WordRepository
import com.wordbattle.data.WrongWord
import com.wordbattle.data.WrongWordRepository
import com.wordbattle.debug.DebugLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 错题练习：选题数 → 答题（10s 倒计时，答后 2s 反馈）→ 结束页
 * 题池由调用方传入（全部错题 / 本轮错题）
 */
@Composable
fun PracticeScreen(
    username: String,
    records: List<WrongWord>,
    wordRepo: WordRepository,
    wrongWordRepo: WrongWordRepository,
    onBack: () -> Unit,
    onHome: () -> Unit
) {
    var session by remember(records) { mutableStateOf<PracticeSession?>(null) }
    var phase by remember(records) { mutableIntStateOf(0) }   // 0=设置 1=答题 2=反馈 3=结束
    var countdown by remember { mutableIntStateOf(10) }
    var lastResult by remember { mutableStateOf<PracticeSession.SubmitResult?>(null) }
    var lastChoice by remember { mutableStateOf<Int?>(null) }
    var results by remember { mutableStateOf<List<PracticeSession.SubmitResult>>(emptyList()) }
    var emptyDialog by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val s = session

    // 答题阶段 10s 倒计时
    LaunchedEffect(phase, s?.currentIndex) {
        if (phase == 1 && s != null) {
            while (phase == 1) {
                delay(1000L)
                countdown = countdown - 1
                if (countdown <= 0) {
                    val r = s.submit(s.currentIndex, null, wrongWordRepo)
                    lastResult = r
                    lastChoice = null
                    results = results + r
                    phase = if (s.currentIndex >= s.questions.size) 3 else 2
                    break
                }
            }
        }
    }

    // 反馈阶段 2s 后自动下一题/结束
    LaunchedEffect(phase, lastResult) {
        if (phase == 2) {
            delay(2000L)
            val ss = session ?: return@LaunchedEffect
            if (ss.currentIndex >= ss.questions.size) {
                phase = 3
            } else {
                countdown = 10
                phase = 1
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("错题练习") },
                navigationIcon = {
                    TextButton(onClick = {
                        // 答题/反馈中按下 = 结束本轮直接看结果；设置/结束页 = 返回
                        if (phase == 1 || phase == 2) phase = 3 else onBack()
                    }) {
                        Text("← 返回")
                    }
                }
            )
        }
    ) { padding ->
        val sessionNow = session
        when (phase) {
            0 -> {  // ===== 设置页 =====
                if (records.isEmpty()) {
                    emptyDialog = true
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("共 ${records.size} 个词待练习",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("选练习题数：", fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(5, 10, 20, 30).forEach { n ->
                                OutlinedButton(
                                    onClick = {
                                        val ss = PracticeSession(username, records, wordRepo)
                                        ss.start(n)
                                        session = ss
                                        countdown = 10
                                        results = emptyList()
                                        phase = 1
                                    },
                                    modifier = Modifier.height(56.dp)
                                ) {
                                    Text("$n 题", fontSize = 18.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onBack) { Text("返回") }
                    }
                }
            }

            1, 2 -> {  // ===== 答题 / 反馈 =====
                val ss = sessionNow
                if (ss == null) return@Scaffold
                val idx = if (phase == 2) (ss.currentIndex - 1).coerceIn(0, ss.questions.size - 1)
                          else ss.currentIndex
                val q = ss.questions.getOrNull(idx)
                if (q == null) return@Scaffold
                val star = ss.starOf(idx)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("第 ${idx + 1} / ${ss.questions.size} 题", fontSize = 16.sp)
                        Text("★${star.coerceAtLeast(0)}", fontSize = 18.sp,
                            color = if (star > 0) Color(0xFFFFA000) else Color(0xFF4CAF50))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("$countdown 秒", fontSize = 16.sp,
                        color = if (countdown <= 3) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(q.questionText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))

                    val canAnswer = phase == 1
                    q.options.forEachIndexed { oi, opt ->
                        val label = "${('A' + oi).toChar()}"
                        val isChoice = lastChoice == oi
                        val isCorrect = oi == q.correctIdx
                        var bg = MaterialTheme.colorScheme.surface
                        var border = Color(0xFF999999)
                        if (phase == 2) {
                            if (isCorrect) { bg = Color(0xFFE8F5E9); border = Color(0xFF4CAF50) }
                            else if (isChoice) { bg = Color(0xFFFFEBEE); border = Color(0xFFE53935) }
                        }
                        ElevatedCard(
                            onClick = {
                                if (!canAnswer) return@ElevatedCard
                                val r = ss.submit(ss.currentIndex, oi, wrongWordRepo)
                                lastResult = r
                                lastChoice = oi
                                results = results + r
                                phase = if (ss.currentIndex >= ss.questions.size) 3 else 2
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(66.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = bg),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center) {
                                Text("$label. $opt", fontSize = 18.sp)
                            }
                        }
                        if (oi < 3) Spacer(modifier = Modifier.height(10.dp))
                    }
                    if (phase == 2) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val r = lastResult
                        Text(
                            r?.let {
                                if (it.isCorrect) "答对了 ✓"
                                else if (it.timedOut) "超时了 ✗"
                                else "答错了 ✗"
                            } ?: "",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold,
                            color = when {
                                r == null -> Color.Transparent
                                r.isCorrect -> Color(0xFF4CAF50)
                                else -> Color(0xFFE53935)
                            }
                        )
                    }
                }
            }

            3 -> {  // ===== 结束页 =====
                val ss = sessionNow
                if (ss == null) return@Scaffold
                val correct = results.count { it.isCorrect }
                val total = results.size
                val (up, down) = ss.starChangeSummary()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(40.dp))
                    Text("练习完成", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("总题数：$total", fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("答对：$correct", fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("正确率：${if (total == 0) 0 else correct * 100 / total}%",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(20.dp))
                    if (up > 0 || down > 0) {
                        Text("星级变化：${down} 个词降星 / ${up} 个词升星",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            val ss2 = PracticeSession(username, ss.recordAt, wordRepo)
                            ss2.start(ss.questions.size)
                            session = ss2
                            countdown = 10
                            results = emptyList()
                            phase = 1
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("再来一轮", fontSize = 18.sp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onHome,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("返回主页", fontSize = 18.sp)
                    }
                }
            }
        }
    }

    // 题池为空弹窗
    if (emptyDialog) {
        AlertDialog(
            onDismissRequest = { emptyDialog = false },
            title = { Text("提示") },
            text = { Text("没有可练习的词，快去对战积累吧") },
            confirmButton = {
                TextButton(onClick = {
                    emptyDialog = false
                    onBack()
                }) { Text("好的") }
            }
        )
    }
}
