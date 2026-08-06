package com.wordbattle.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordbattle.network.UdpBroadcast

@Composable
fun PlayerJoinScreen(
    hosts: List<UdpBroadcast>,
    onJoin: (host: UdpBroadcast) -> Unit,
    onBack: () -> Unit
) {
    var manualIp by remember { mutableStateOf("127.0.0.1") }
    var showManual by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("发现的主机", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))

        // 手动输入
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("手动加入", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                if (!showManual) {
                    Button(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("输入 IP 加入（本机测试用）")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = { Text("IP 地址") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            onJoin(UdpBroadcast(ip = manualIp.trim(), port = 5201, name = "手动", dir = "", total = 0))
                            showManual = false
                        }) {
                            Text("加入")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (hosts.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("未发现主机，请确保在同一局域网",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn {
                items(hosts) { host ->
                    val isAnswering = host.status == "ANSWERING"
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isAnswering)
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(host.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("IP: ${host.ip} | 方向: ${if (host.dir == "EN_TO_ZH") "英→中" else "中→英"} | 题数: ${host.total}",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isAnswering) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("答题中，无法加入", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { onJoin(host) },
                                enabled = !isAnswering
                            ) {
                                Text(if (isAnswering) "答题中" else "加入")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onBack) {
            Text("返回")
        }
    }
}

@Composable
fun PlayerWaitingScreen(
    hostName: String,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("已加入 ${hostName}", fontSize = 20.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text("等待主机开始...", fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(40.dp))
        TextButton(onClick = onBack) {
            Text("退出")
        }
    }
}