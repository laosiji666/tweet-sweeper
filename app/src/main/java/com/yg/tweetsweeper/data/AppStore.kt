package com.yg.tweetsweeper.data

import android.content.Context
import com.yg.tweetsweeper.net.XUser
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

@Serializable
data class ArchiveData(
    val following: List<String> = emptyList(),
    val follower: List<String> = emptyList(),
    val savedAt: Long = 0L
)

@Serializable
data class Profile(
    val id: String = "",
    val username: String = "",
    val name: String? = null,
    val avatar: String? = null,
    val followers: Int = 0,
    val following: Int = 0,
    val verified: Boolean = false
)

/** 一次扫描的完整快照，落盘可离线查看、可增量更新 */
@Serializable
data class Snapshot(
    val createdAt: Long = 0L,
    val me: Profile = Profile(),
    val followingIds: List<String> = emptyList(),
    val followerIds: List<String> = emptyList(),
    val following: Map<String, XUser> = emptyMap(),
    val followers: Map<String, XUser> = emptyMap(),
    val suspendedIds: List<String> = emptyList(),
    val goneIds: List<String> = emptyList()
) {
    fun nonMutualIds(): List<String> {
        val fs = followerIds.toHashSet()
        return followingIds.filter { it !in fs }
    }

    fun mutualIds(): List<String> {
        val fs = followerIds.toHashSet()
        return followingIds.filter { it in fs }
    }

    fun fanOnlyIds(): List<String> {
        val fg = followingIds.toHashSet()
        return followerIds.filter { it !in fg }
    }

    fun displayName(id: String): String {
        val u = following[id] ?: followers[id]
        return u?.username?.let { "@$it" } ?: id
    }
}

class AppStore(context: Context) {

    private val ctx = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val snapshotFile = File(ctx.filesDir, "snapshot.json")
    private val whitelistFile = File(ctx.filesDir, "whitelist.json")
    private val historyFile = File(ctx.filesDir, "history.log")
    private val archiveFile = File(ctx.filesDir, "archive.json")
    private val doneFile = File(ctx.filesDir, "archive_done.json")
    private val prefs = ctx.getSharedPreferences("ts_settings", Context.MODE_PRIVATE)

    fun loadSnapshot(): Snapshot? = runCatching {
        if (snapshotFile.exists()) json.decodeFromString<Snapshot>(snapshotFile.readText()) else null
    }.getOrNull()

    fun saveSnapshot(snapshot: Snapshot) {
        runCatching { snapshotFile.writeText(json.encodeToString(snapshot)) }
    }

    fun saveArchive(data: ArchiveData) {
        runCatching { archiveFile.writeText(json.encodeToString(data)) }
    }

    fun loadArchive(): ArchiveData? = runCatching {
        if (archiveFile.exists()) json.decodeFromString<ArchiveData>(archiveFile.readText()) else null
    }.getOrNull()

    fun clearArchive() {
        runCatching { archiveFile.delete() }
        runCatching { doneFile.delete() }
    }

    /** id -> 标记时间（毫秒）。旧版本存的是纯数组，读取时自动迁移并补上递增时间。 */
    fun saveDone(marks: Map<String, Long>) {
        runCatching { doneFile.writeText(json.encodeToString(marks)) }
    }

    fun loadDone(): Map<String, Long> = runCatching {
        if (!doneFile.exists()) return@runCatching emptyMap<String, Long>()
        val text = doneFile.readText().trim()
        when {
            text.startsWith("{") -> json.decodeFromString<Map<String, Long>>(text)
            text.startsWith("[") -> json.decodeFromString<List<String>>(text)
                .mapIndexed { index, id -> id to index.toLong() }
                .toMap()
            else -> emptyMap()
        }
    }.getOrDefault(emptyMap())

    fun loadWhitelist(): Set<String> = runCatching {
        if (!whitelistFile.exists()) emptySet()
        else json.decodeFromString<List<String>>(whitelistFile.readText()).toSet()
    }.getOrDefault(emptySet())

    fun saveWhitelist(ids: Set<String>) {
        runCatching { whitelistFile.writeText(json.encodeToString(ids.toList())) }
    }

    fun appendHistory(line: String) {
        runCatching {
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            historyFile.appendText("[$stamp] $line\n")
        }
    }

    fun historyText(): String = runCatching {
        if (!historyFile.exists()) "" else historyFile.readText()
    }.getOrDefault("")

    fun clearHistory() {
        runCatching { historyFile.delete() }
    }

    fun clearAll() {
        runCatching { snapshotFile.delete() }
        runCatching { historyFile.delete() }
    }

    // ---------- 设置 ----------

    var dailyCap: Int
        get() = prefs.getInt("daily_cap", 50)
        set(v) = prefs.edit().putInt("daily_cap", v.coerceIn(1, 500)).apply()

    var dryRun: Boolean
        get() = prefs.getBoolean("dry_run", false)
        set(v) = prefs.edit().putBoolean("dry_run", v).apply()

    var delayMinMs: Int
        get() = prefs.getInt("delay_min", 1500)
        set(v) = prefs.edit().putInt("delay_min", v.coerceIn(300, 15000)).apply()

    var delayMaxMs: Int
        get() = prefs.getInt("delay_max", 3500)
        set(v) = prefs.edit().putInt("delay_max", v.coerceIn(300, 30000)).apply()

    var includeSuspendedInUnfollow: Boolean
        get() = prefs.getBoolean("unfollow_suspended", true)
        set(v) = prefs.edit().putBoolean("unfollow_suspended", v).apply()

    private var quotaDate: String
        get() = prefs.getString("quota_date", "") ?: ""
        set(v) = prefs.edit().putString("quota_date", v).apply()

    var quotaUsed: Int
        get() {
            val today = LocalDate.now().toString()
            if (quotaDate != today) {
                quotaDate = today
                prefs.edit().putInt("quota_used", 0).apply()
                return 0
            }
            return prefs.getInt("quota_used", 0)
        }
        set(v) {
            quotaDate = LocalDate.now().toString()
            prefs.edit().putInt("quota_used", v).apply()
        }

    fun remainingQuota(): Int = (dailyCap - quotaUsed).coerceAtLeast(0)

    fun consumeQuota(): Boolean {
        val used = quotaUsed
        if (used >= dailyCap) return false
        quotaUsed = used + 1
        return true
    }
}
