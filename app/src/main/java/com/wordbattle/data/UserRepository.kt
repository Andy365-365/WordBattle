package com.wordbattle.data

import android.content.Context
import android.content.SharedPreferences
import com.wordbattle.debug.DebugLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import kotlinx.serialization.Serializable

/**
 * 用户数据模型
 */
@Serializable
data class User(
    val username: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 用户管理仓库
 * 基于 SharedPreferences 存储，轻量且无需引入 Room
 */
class UserRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("users", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val KEY_USERS = "users_list"
    private val KEY_CURRENT = "current_username"

    companion object {
        const val MAX_USERS = 5
        const val DEFAULT_USERNAME = "玩家"
    }

    /**
     * 获取所有用户列表
     */
    fun getAll(): List<User> {
        val data = prefs.getString(KEY_USERS, null)
        return if (data != null) {
            json.decodeFromString<List<User>>(data)
        } else {
            // 首次使用，创建默认用户
            val default = listOf(User(DEFAULT_USERNAME))
            saveUsers(default)
            setCurrentUser(DEFAULT_USERNAME)
            DebugLog.i("[UserRepo] 首次初始化，创建默认用户: $DEFAULT_USERNAME")
            default
        }
    }

    /**
     * 获取当前用户
     * 先调 getAll() 确保首次初始化已执行（默认用户"玩家"被创建）
     */
    fun getCurrent(): User? {
        val all = getAll()
        val username = prefs.getString(KEY_CURRENT, null)
        if (username != null) return all.find { it.username == username }
        // KEY_CURRENT 缺失（历史数据/异常状态）：回退到第一个用户并补写
        if (all.isNotEmpty()) {
            setCurrentUser(all.first().username)
            DebugLog.i("[UserRepo] KEY_CURRENT 缺失，回退到: ${all.first().username}")
            return all.first()
        }
        return null
    }

    /**
     * 添加用户
     * @return 成功返回 true，已达上限或名字重复返回 false
     */
    fun addUser(username: String): Boolean {
        val users = getAll()
        if (users.size >= MAX_USERS) {
            DebugLog.w("[UserRepo] 已达用户上限 $MAX_USERS")
            return false
        }
        if (users.any { it.username == username }) {
            DebugLog.w("[UserRepo] 用户名已存在: $username")
            return false
        }
        val newUsers = users + User(username)
        saveUsers(newUsers)
        DebugLog.i("[UserRepo] 添加用户: $username")
        return true
    }

    /**
     * 编辑用户名
     * @return 成功返回 true
     */
    fun renameUser(oldName: String, newName: String): Boolean {
        val users = getAll()
        if (!users.any { it.username == oldName }) {
            DebugLog.w("[UserRepo] 用户不存在: $oldName")
            return false
        }
        if (users.any { it.username == newName }) {
            DebugLog.w("[UserRepo] 用户名已存在: $newName")
            return false
        }
        val newUsers = users.map { if (it.username == oldName) it.copy(username = newName) else it }
        saveUsers(newUsers)
        // 如果当前用户就是被改名的，更新当前用户
        val current = getCurrent()?.username
        if (current == oldName) {
            setCurrentUser(newName)
        }
        DebugLog.i("[UserRepo] 改名: $oldName -> $newName")
        return true
    }

    /**
     * 删除用户
     * @return 成功返回 true，仅剩1个用户时不可删除
     */
    fun deleteUser(username: String): Boolean {
        val users = getAll()
        if (users.size <= 1) {
            DebugLog.w("[UserRepo] 仅剩1个用户，不可删除")
            return false
        }
        val newUsers = users.filter { it.username != username }
        saveUsers(newUsers)
        // 如果删除的是当前用户，切换到第一个
        val current = getCurrent()?.username
        if (current == username) {
            setCurrentUser(newUsers.first().username)
        }
        DebugLog.i("[UserRepo] 删除用户: $username")
        return true
    }

    /**
     * 切换当前用户
     */
    fun switchUser(username: String): Boolean {
        val users = getAll()
        if (!users.any { it.username == username }) {
            return false
        }
        setCurrentUser(username)
        DebugLog.i("[UserRepo] 切换用户: $username")
        return true
    }

    private fun saveUsers(users: List<User>) {
        prefs.edit().putString(KEY_USERS, json.encodeToString(users)).apply()
    }

    private fun setCurrentUser(username: String) {
        prefs.edit().putString(KEY_CURRENT, username).apply()
    }
}