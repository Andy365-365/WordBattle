package com.wordbattle.ui

import androidx.compose.foundation.clickable
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
import com.wordbattle.data.User
import com.wordbattle.data.UserRepository
import com.wordbattle.debug.DebugLog

/**
 * 首页顶部显示当前用户，点击弹出选择/管理菜单
 */
@Composable
fun UserSwitcher(
    userRepository: UserRepository,
    onManageClicked: () -> Unit
) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val currentUser by remember(refreshKey) {
        mutableStateOf(userRepository.getCurrent())
    }

    var showMenu by remember { mutableStateOf(false) }

    Box {
        TextButton(
            onClick = { showMenu = true },
            modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp)
        ) {
            Text(
                text = currentUser?.username ?: "玩家",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                " ▾",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            userRepository.getAll().forEach { user ->
                val isCurrent = currentUser?.username == user.username
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isCurrent) {
                                Text("●", color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(user.username)
                        }
                    },
                    onClick = {
                        if (!isCurrent) {
                            userRepository.switchUser(user.username)
                            refreshKey++
                        }
                        showMenu = false
                    }
                )
            }
            Divider()
            DropdownMenuItem(
                text = { Text("管理用户", color = MaterialTheme.colorScheme.secondary) },
                onClick = {
                    showMenu = false
                    onManageClicked()
                }
            )
        }
    }
}

/**
 * 用户管理页面：添加/编辑/删除/切换用户
 */
@Composable
fun UserManageScreen(
    userRepository: UserRepository,
    onBack: () -> Unit
) {
    var users by remember { mutableStateOf(userRepository.getAll()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<User?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        users = userRepository.getAll()
    }

    val currentUser = userRepository.getCurrent()

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← 返回", color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "用户管理 (${users.size}/${UserRepository.MAX_USERS})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(Modifier.size(48.dp)) // placeholder
            }
        }

        // 用户列表
        if (users.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无用户", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(users, key = { it.username }) { user ->
                    val isCurrent = currentUser?.username == user.username
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                            if (!isCurrent) {
                                userRepository.switchUser(user.username)
                                refreshKey++
                            }
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    if (isCurrent) "●" else "○",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 16.sp
                                )
                                Column {
                                    Text(
                                        user.username,
                                        fontSize = 16.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (isCurrent) {
                                        Text(
                                            "当前用户",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { showEditDialog = user }) {
                                    Text("编辑", fontSize = 14.sp)
                                }
                                if (users.size > 1) {
                                    TextButton(onClick = {
                                        userRepository.deleteUser(user.username)
                                        refreshKey++
                                    }) {
                                        Text("删除", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    if (users.size < UserRepository.MAX_USERS) {
                        Button(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("+ 添加用户", fontSize = 16.sp)
                        }
                    } else {
                        Text(
                            "已达用户上限 (${UserRepository.MAX_USERS})",
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }

    // 添加用户对话框
    if (showAddDialog) {
        AddUserDialog(
            userRepository = userRepository,
            onDismiss = {
                showAddDialog = false
                refreshKey++
            },
            onCancel = { showAddDialog = false }
        )
    }

    // 编辑用户对话框
    showEditDialog?.let { user ->
        EditUserDialog(
            userRepository = userRepository,
            user = user,
            onDismiss = {
                showEditDialog = null
                refreshKey++
            },
            onCancel = { showEditDialog = null }
        )
    }
}

@Composable
private fun AddUserDialog(
    userRepository: UserRepository,
    onDismiss: () -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("添加用户") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(10); errorMsg = null },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (name.length > 10) {
                    Text("最多10个字符", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                errorMsg?.let { msg ->
                    Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank() || name.length > 10) return@Button
                    if (!userRepository.addUser(name.trim())) {
                        errorMsg = "添加失败（已达上限或名称重复）"
                        return@Button
                    }
                    onDismiss()
                },
                enabled = name.isNotBlank()
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun EditUserDialog(
    userRepository: UserRepository,
    user: User,
    onDismiss: () -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(user.username) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("编辑用户") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(10); errorMsg = null },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (name.length > 10) {
                    Text("最多10个字符", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                errorMsg?.let { msg ->
                    Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank() || name.length > 10) return@Button
                    if (name.trim() == user.username) {
                        onDismiss()
                        return@Button
                    }
                    if (!userRepository.renameUser(user.username, name.trim())) {
                        errorMsg = "改名失败（名称重复）"
                        return@Button
                    }
                    onDismiss()
                },
                enabled = name.isNotBlank()
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
        }
    )
}