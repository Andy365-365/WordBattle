package com.wordbattle.data

import android.content.Context
import android.content.SharedPreferences
import com.wordbattle.debug.DebugLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 错题记录（持久化）
 * key = (username, word, direction)：同一用户对同一单词、同一方向一条记录
 *
 * 星级规则（见 docs/requirements/错题本需求文档_v2.md）：
 * - 答错/超时：无记录则新建 starLevel=1；已有则 +1（上限 6）
 * - 答对：已有记录则 -1；归零（0 星=已纠正）保留在列表，由用户手动删除
 * - 连续 3 次答对直接归零的逻辑在练习模式内存里实现，
 *   本仓库的 recordAnswer 只做单步 +/-1
 */
@Serializable
data class WrongWord(
    val username: String,
    val word: String,          // 英文单词
    val meaning: String,       // 中文释义（记入时快照）
    val direction: String,     // EN_TO_ZH / ZH_TO_EN
    var starLevel: Int = 1,    // 1-6，0=已纠正
    var wrongCount: Int = 1,   // 累计答错次数（含超时）
    var lastWrongTime: Long = System.currentTimeMillis()
)

/**
 * 错题本仓库（SharedPreferences 存储，与 UserRepository 同模式）
 * 数据量小（每词一条，用户级），无需 SQLite
 */
class WrongWordRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wrong_words", Context.MODE_PRIVATE)

    private val KEY_LIST = "wrong_word_list"
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** 所有错题记录 */
    fun getAll(): List<WrongWord> {
        val data = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            json.decodeFromString(data)
        } catch (e: Exception) {
            DebugLog.e("加载错题列表失败", e.message ?: e.javaClass.simpleName)
            emptyList()
        }
    }

    /** 某用户的错题记录 */
    fun getByUser(username: String): List<WrongWord> =
        getAll().filter { it.username == username }

    /** 精确查一条记录 */
    fun find(username: String, word: String, direction: String): WrongWord? =
        getAll().firstOrNull { it.username == username && it.word == word && it.direction == direction }

    /**
     * 记录一次答题结果（对战/练习统一入口）
     * - 答错/超时：无记录→新建（star=1）；有记录→star+1（≤6），wrongCount+1，更新 lastWrongTime
     * - 答对：无记录→不动；有记录→star-1
     * @return 变化后的记录（新建/修改/未变化都是同一条，未命中返回 null）
     */
    fun recordAnswer(username: String, word: String, meaning: String,
                     direction: String, isCorrect: Boolean): WrongWord? {
        if (word.isEmpty()) {
            DebugLog.w("[WrongWord] 空单词，跳过记录")
            return null
        }
        val all = getAll().toMutableList()
        val existing = all.firstOrNull {
            it.username == username && it.word == word && it.direction == direction
        }

        val result: WrongWord = if (isCorrect) {
            if (existing == null) {
                // 无记录且答对：不产生记录
                return null
            } else {
                val updated = existing.copy(starLevel = (existing.starLevel - 1).coerceAtLeast(0))
                val idx = all.indexOf(existing)
                all[idx] = updated
                updated
            }
        } else {
            if (existing == null) {
                val created = WrongWord(username, word, meaning, direction,
                    starLevel = 1, wrongCount = 1, lastWrongTime = System.currentTimeMillis())
                all.add(created)
                created
            } else {
                val updated = existing.copy(
                    starLevel = (existing.starLevel + 1).coerceAtMost(6),
                    wrongCount = existing.wrongCount + 1,
                    lastWrongTime = System.currentTimeMillis()
                )
                val idx = all.indexOf(existing)
                all[idx] = updated
                updated
            }
        }

        saveAll(all)
        DebugLog.i("[WrongWord] ${if (isCorrect) "答对降星" else "答错/超时升星"}: " +
            "$username $word $direction -> star=${result.starLevel} wrong=${result.wrongCount}")
        return result
    }

    /** 设置某条记录星级（练习模式"连续 3 次答对直接归零"用，recordAnswer 只做 ±1） */
    fun setStarLevel(username: String, word: String, direction: String, star: Int) {
        val all = getAll().toMutableList()
        val idx = all.indexOfFirst {
            it.username == username && it.word == word && it.direction == direction
        }
        if (idx < 0) return
        all[idx] = all[idx].copy(starLevel = star.coerceIn(0, 6))
        saveAll(all)
        DebugLog.i("[WrongWord] 设星级: $username $word $direction -> star=$star")
    }

    /** 删除一条记录（用户认为已掌握）。word/direction 定位，username 限定 */
    fun delete(username: String, word: String, direction: String) {
        val all = getAll()
        val target = all.firstOrNull {
            it.username == username && it.word == word && it.direction == direction
        } ?: return
        saveAll(all - target)
        DebugLog.i("[WrongWord] 删除: $username $word $direction (star=${target.starLevel})")
    }

    private fun saveAll(list: List<WrongWord>) {
        prefs.edit().putString(KEY_LIST, json.encodeToString(list)).apply()
    }
}