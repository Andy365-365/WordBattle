package com.wordbattle.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wordbattle.data.UserRepository
import com.wordbattle.debug.DebugLog

@Composable
fun HomeScreen(
    userRepository: UserRepository,
    onHostClicked: () -> Unit,
    onPlayerClicked: () -> Unit,
    onDebugClicked: () -> Unit,
    onUserManageClicked: () -> Unit
) {
    var clickCount by remember { mutableIntStateOf(0) }
    var lastClickTime by remember { mutableLongStateOf(0) }
    var showDebug by remember { mutableStateOf(false) }

    fun titleClick() {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 500) clickCount++ else clickCount = 1
        lastClickTime = now
        if (clickCount >= 5) { showDebug = true; clickCount = 0 }
    }

    LaunchedEffect(showDebug) { if (showDebug) { onDebugClicked(); showDebug = false } }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 顶部用户切换器
        Box(modifier = Modifier.fillMaxWidth()) {
            UserSwitcher(
                userRepository = userRepository,
                onManageClicked = onUserManageClicked
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "英语抢答对战",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { titleClick() }
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "v1.2",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(40.dp))
        Button(
            onClick = {
                DebugLog.i("[UI] HomeScreen: 点击'当主机'")
                onHostClicked()
            },
            modifier = Modifier.fillMaxWidth(0.8f).height(60.dp)
        ) {
            Text("主机+答题", fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                DebugLog.i("[UI] HomeScreen: 点击'答题'")
                onPlayerClicked()
            },
            modifier = Modifier.fillMaxWidth(0.8f).height(60.dp)
        ) {
            Text("答题", fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = onDebugClicked) {
            Text("调试日志")
        }
    }
}