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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSetupScreen(
    units: List<String>,
    onStartWaiting: (direction: String, totalRounds: Int, answerTimeout: Int, unit: String) -> Unit,
    onBack: () -> Unit
) {
    var direction by remember { mutableStateOf("EN_TO_ZH") }
    var totalRounds by remember { mutableIntStateOf(10) }
    var answerTimeout by remember { mutableIntStateOf(5) }
    var selectedUnit by remember { mutableStateOf("") }

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

        var expanded by remember { mutableStateOf(false) }
        val unitLabels = listOf("全部") + units

        Text("范围", fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = if (selectedUnit.isEmpty()) "全部" else selectedUnit,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
                modifier = Modifier.fillMaxWidth().menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                unitLabels.forEach { label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            DebugLog.i("[UI] HostSetup: 选择范围 $label")
                            selectedUnit = if (label == "全部") "" else label
                            expanded = false
                        }
                    )
                }
            }
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

        Spacer(modifier = Modifier.height(24.dp))

        Text("答题等待时间(秒)", fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 20, 30).forEach { n ->
                val selected = answerTimeout == n
                Button(
                    onClick = { DebugLog.i("[UI] HostSetup: 选择答题等待 $n 秒"); answerTimeout = n },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.width(80.dp)
                ) {
                    Text("$n")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                DebugLog.i("[UI] HostSetup: 点击'开始等待玩家' dir=$direction total=$totalRounds timeout=${answerTimeout}s unit=$selectedUnit")
                onStartWaiting(direction, totalRounds, answerTimeout, selectedUnit)
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