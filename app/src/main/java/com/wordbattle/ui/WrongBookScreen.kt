@file:OptIn(ExperimentalMaterial3Api::class)

package com.wordbattle.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 错题本主页：两个入口按钮
 * - 查看错题 → WRONG_LIST（已实现，第3步）
 * - 错题练习 → 二期功能，暂占位（第4步实现后接线）
 */
@Composable
fun WrongBookScreen(
    wrongCount: Int,
    onViewClicked: () -> Unit,
    onPracticeClicked: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopAppBar(
            title = {
                Text("错题本", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("← 返回") }
            }
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("共 $wrongCount 个词待复习", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onViewClicked,
                modifier = Modifier.fillMaxWidth(0.8f).height(60.dp)
            ) {
                Text("查看错题", fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onPracticeClicked,
                modifier = Modifier.fillMaxWidth(0.8f).height(60.dp)
            ) {
                Text("错题练习", fontSize = 20.sp)
            }
        }
    }
}
