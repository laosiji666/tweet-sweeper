package com.yg.tweetsweeper.vm

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yg.tweetsweeper.auth.AuthRepository
import com.yg.tweetsweeper.data.AppStore
import com.yg.tweetsweeper.data.Profile
import com.yg.tweetsweeper.data.Snapshot
import com.yg.tweetsweeper.net.XApi
import com.yg.tweetsweeper.net.XUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class RowFilter(val label: String) {
    NON_MUTUAL("不互关"),
    SUSPENDED("疑似封禁"),
    MUTUAL("互关"),
    FANS("仅粉丝"),
    ALL("全部")
}

data class PersonRow(
    val id: String,
    val username: String,
    val name: String,
    val followers: Int = 0,
    val following: Int = 0,
    val mutual: Boolean = false,
    val nonMutual: Boolean = false,
    val fanOnly: Boolean = false,
    val suspended: Boolean = false,
    val gone: Boolean = false,
    val whitelisted: Boolean = false
)

data class Stats(
    val following: Int = 0,
    val followers: Int = 0,
    val nonMutual: Int = 0,
    val mutual: Int = 0,
    val fanOnly: Int = 0,
    val suspended: Int = 0
)

data class UiState(
    val ready: Boolean = false,
    val authorized: Boolean = false,
    val clientId: String = "",
    val profile: Profile? = null,
    val snapshot: Snapshot? = null,
    val rows: List<PersonRow> = emptyList(),
    val filter: RowFilter = RowFilter.NON_MUTUAL,
    val query: String = "",
    val selected: Set<String> = emptySet(),
    val whitelist: Set<String> = emptySet(),
    val busy: Boolean = false,
    val busyLabel: String = "",
    val progress: Float? = null,
    val logs: List<String> = emptyList(),
    val toast: String? = null,
    val stats: Stats = Stats(),
    val dailyCap: Int = 50,
    val dailyUsed: Int = 0,
    val dryRun: Boolean = false,
    val likes: Int = 0,
    val retweets: Int = 0,
    val delayMinSec: Float = 1.5f,
    val delayMaxSec: Float = 3.5f,
    val lastScanAt: Long = 0L
) {
    val quotaLabel: String get() = "今日已取关 $dailyUsed / $dailyCap"
    val snapshotLabel: String get() = if (lastScanAt == 0L) "尚未扫描" else "上次扫描 " + formatTime(lastScanAt)
}

private fun formatTime(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ts))

private class WorkCancelled : Exception("已取消")

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppStore(app)
    private val auth = AuthRepository(app)
    private val api = XApi(auth).apply { log = { line -> append(line) } }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile private var cancelRequested = false
    @Volatile private var loading = false

    fun init() {
        if (_state.value.ready || loading) return
        loading = true
        viewModelScope.launch(Dispatchers.IO) {
        val snapshot = store.loadSnapshot()
        val whitelist = store.loadWhitelist()
        _state.update {
            it.copy(
                ready = true,
                authorized = auth.isAuthorized,
                clientId = auth.clientId,
                whitelist = whitelist,
                snapshot = snapshot,
                rows = snapshot?.let { s -> buildRows(s, it.filter, it.query, whitelist) } ?: emptyList(),
                stats = snapshot?.let { s -> statsOf(s) } ?: Stats(),
                lastScanAt = snapshot?.createdAt ?: 0L,
                profile = snapshot?.me,
                dailyCap = store.dailyCap,
                dailyUsed = store.quotaUsed,
                dryRun = store.dryRun,
                delayMinSec = store.delayMinMs / 1000f,
                delayMaxSec = store.delayMaxMs / 1000f
            )
        }
        if (auth.isAuthorized) silentRefreshProfile()
        }
    }

    // ---------------- 授权 ----------------

    fun saveClientId(id: String) {
        auth.clientId = id
        _state.update { it.copy(clientId = id.trim()) }
    }

    fun authUriOrNull(): Uri? {
        if (auth.clientId.isBlank()) {
            toast("先填 Client ID")
            return null
        }
        return runCatching { auth.startAuth() }
            .onFailure { append("生成授权链接失败：${it.message}") }
            .getOrNull()
    }

    fun handleCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "tweetsweeper") return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = "正在完成授权…") }
            runCatching { auth.completeAuth(data) }
                .onSuccess {
                    append("授权成功，scope：${auth.scope.ifBlank { "unknown" }}")
                    _state.update { it.copy(busy = false, authorized = true) }
                    refreshProfile()
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false) }
                    append("授权失败：${e.message}")
                }
        }
    }

    fun logout() {
        auth.signOut()
        _state.update { it.copy(authorized = false, profile = null) }
        append("已退出登录（本地快照保留）")
    }

    private fun silentRefreshProfile() {
        viewModelScope.launch {
            runCatching { api.me() }.onSuccess { me ->
                val p = toProfile(me)
                _state.update { it.copy(profile = p) }
            }
        }
    }

    fun refreshProfile() {
        launchWork("读取账号信息") {
            val me = api.me()
            _state.update { it.copy(profile = toProfile(me)) }
        }
    }

    private fun toProfile(me: XUser) = Profile(
        id = me.id,
        username = me.username ?: "",
        name = me.name,
        avatar = me.avatar,
        followers = me.metrics?.followers ?: 0,
        following = me.metrics?.following ?: 0,
        verified = me.verified == true
    )

    // ---------------- 扫描 ----------------

    fun scan() {
        val st = _state.value
        if (st.busy) return
        if (!st.authorized) {
            toast("先授权登录")
            return
        }
        launchWork("开始扫描…") {
            cancelRequested = false
            _state.update { it.copy(progress = 0f) }

            val me = api.me()
            val profile = toProfile(me)
            _state.update { it.copy(profile = profile) }

            // 1) 关注列表
            val following = ArrayList<XUser>()
            var cursor: String? = null
            var page = 0
            while (true) {
                if (cancelRequested) throw WorkCancelled()
                page++
                _state.update {
                    it.copy(
                        busyLabel = "抓取关注列表 · 第 $page 页 · ${following.size} 人",
                        progress = (page / 40f).coerceAtMost(0.35f)
                    )
                }
                val env = api.followingPage(profile.id, cursor)
                following += env.data.orEmpty()
                cursor = env.meta?.nextToken
                if (cursor.isNullOrBlank() || page >= XApi.MAX_PAGES) break
            }
            append("关注列表 ${following.size} 人")

            // 2) 粉丝列表
            val followerList = ArrayList<XUser>()
            cursor = null
            page = 0
            while (true) {
                if (cancelRequested) throw WorkCancelled()
                page++
                _state.update {
                    it.copy(
                        busyLabel = "抓取粉丝列表 · 第 $page 页 · ${followerList.size} 人",
                        progress = (0.35f + page / 60f).coerceAtMost(0.7f)
                    )
                }
                val env = api.followersPage(profile.id, cursor)
                followerList += env.data.orEmpty()
                cursor = env.meta?.nextToken
                if (cursor.isNullOrBlank() || page >= XApi.MAX_PAGES) break
            }
            append("粉丝列表 ${followerList.size} 人")

            // 3) 封禁 / 注销检测
            val known = HashMap<String, XUser>()
            following.forEach { known[it.id] = it }
            val suspended = LinkedHashSet<String>()
            val gone = LinkedHashSet<String>()
            val chunks = following.map { it.id }.chunked(100)
            chunks.forEachIndexed { index, chunk ->
                if (cancelRequested) throw WorkCancelled()
                _state.update {
                    it.copy(
                        busyLabel = "封禁检测 · 第 ${index + 1}/${chunks.size} 批",
                        progress = (0.7f + 0.3f * (index + 1) / chunks.size.coerceAtLeast(1)).coerceAtMost(0.99f)
                    )
                }
                val env = api.usersByIds(chunk)
                val returned = env.data.orEmpty()
                returned.forEach { known[it.id] = it }
                val returnedIds = returned.map { it.id }.toHashSet()
                env.errors.orEmpty().forEach { err ->
                    val id = err.value ?: err.resourceId ?: return@forEach
                    if (err.suspended) suspended += id else gone += id
                }
                chunk.filter { it !in returnedIds && it !in suspended && it !in gone }
                    .forEach { gone += it }
            }

            val snapshot = Snapshot(
                createdAt = System.currentTimeMillis(),
                me = profile,
                followingIds = following.map { it.id }.distinct(),
                followerIds = followerList.map { it.id }.distinct(),
                following = known,
                followers = followerList.associateBy { it.id },
                suspendedIds = suspended.toList(),
                goneIds = gone.toList()
            )
            store.saveSnapshot(snapshot)
            store.appendHistory(
                "扫描 关注${snapshot.followingIds.size} 粉丝${snapshot.followerIds.size} " +
                    "不互关${snapshot.nonMutualIds().size} 封禁${suspended.size}"
            )
            applySnapshot(snapshot)
            append(
                "扫描完成：不互关 ${snapshot.nonMutualIds().size}｜互关 ${snapshot.mutualIds().size}｜" +
                    "仅粉丝 ${snapshot.fanOnlyIds().size}｜疑似封禁/注销 ${suspended.size + gone.size}"
            )
        }
    }

    // ---------------- 筛选 / 选择 ----------------

    fun setFilter(filter: RowFilter) {
        _state.update { st ->
            st.copy(filter = filter, rows = st.snapshot?.let { buildRows(it, filter, st.query, st.whitelist) } ?: emptyList())
        }
    }

    fun setQuery(query: String) {
        _state.update { st ->
            st.copy(query = query, rows = st.snapshot?.let { buildRows(it, st.filter, query, st.whitelist) } ?: emptyList())
        }
    }

    fun toggleSelect(id: String) {
        _state.update { st ->
            val sel = if (id in st.selected) st.selected - id else st.selected + id
            st.copy(selected = sel)
        }
    }

    fun selectAllVisible() {
        _state.update { st ->
            val ids = st.rows.map { it.id }.toSet()
            st.copy(selected = if (st.selected.containsAll(ids) && ids.isNotEmpty()) st.selected - ids else st.selected + ids)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    fun toggleWhitelist(id: String) {
        val wl = _state.value.whitelist.toMutableSet()
        if (!wl.add(id)) wl.remove(id)
        store.saveWhitelist(wl)
        _state.update { st ->
            st.copy(
                whitelist = wl,
                rows = st.snapshot?.let { buildRows(it, st.filter, st.query, wl) } ?: emptyList(),
                selected = st.selected - id
            )
        }
    }

    // ---------------- 批量取关 ----------------

    fun unfollowSelected() {
        val st = _state.value
        if (st.busy) return
        val me = st.profile ?: st.snapshot?.me
        if (me == null) {
            toast("先扫描一次")
            return
        }
        val targets = st.selected.toList()
        if (targets.isEmpty()) {
            toast("先选中要取关的账号")
            return
        }
        launchWork("批量取关") {
            cancelRequested = false
            val whitelist = st.whitelist
            val dry = store.dryRun
            val done = LinkedHashSet<String>()
            var ok = 0
            var fail = 0
            var skipped = 0

            for (index in targets.indices) {
                val id = targets[index]
                if (cancelRequested) break
                if (id in whitelist) {
                    skipped++
                    continue
                }
                if (!dry && store.remainingQuota() <= 0) {
                    append("已达每日上限 ${store.dailyCap}，停止本次任务")
                    break
                }
                val name = st.snapshot?.displayName(id) ?: id
                jitter()
                try {
                    if (dry) {
                        ok++
                        append("[试运行] 将取关 $name")
                    } else {
                        store.consumeQuota()
                        api.unfollow(me.id, id)
                        ok++
                        done += id
                        append("已取关 $name")
                    }
                } catch (e: Exception) {
                    fail++
                    append("取关失败 $name：${e.message}")
                }
                _state.update {
                    it.copy(
                        progress = (index + 1).toFloat() / targets.size,
                        dailyUsed = store.quotaUsed
                    )
                }
            }

            if (done.isNotEmpty()) {
                val snap = _state.value.snapshot
                if (snap != null) {
                    val updated = snap.copy(
                        followingIds = snap.followingIds.filter { it !in done },
                        following = snap.following.filterKeys { it !in done },
                        me = snap.me.copy(
                            following = (snap.me.following - done.size).coerceAtLeast(0),
                            followers = snap.me.followers
                        )
                    )
                    store.saveSnapshot(updated)
                    applySnapshot(updated)
                }
            }
            _state.update { it.copy(selected = it.selected - done, dailyUsed = store.quotaUsed) }
            append(
                "取关结束：成功 $ok｜失败 $fail｜白名单跳过 $skipped｜" +
                    "今日剩余 ${store.remainingQuota()}"
            )
            store.appendHistory("取关 成功$ok 失败$fail 跳过$skipped dryRun=$dry")
        }
    }

    // ---------------- 点赞 / 转推清理 ----------------

    fun loadLikesCount() {
        val me = _state.value.profile ?: return toast("先授权并读取账号")
        launchWork("读取点赞") {
            var cursor: String? = null
            var total = 0
            var page = 0
            while (page < 20) {
                if (cancelRequested) throw WorkCancelled()
                page++
                val env = api.likesPage(me.id, cursor)
                total += env.data.orEmpty().size
                _state.update { it.copy(busyLabel = "读取点赞 · $total 条", likes = total) }
                cursor = env.meta?.nextToken ?: break
            }
            append("点赞列表共 $total 条（最多读 20 页）")
        }
    }

    fun unlikeRecent(limit: Int) {
        val me = _state.value.profile ?: return toast("先授权并读取账号")
        launchWork("取消点赞") {
            cancelRequested = false
            var removed = 0
            var cursor: String? = null
            outer@ while (removed < limit) {
                if (cancelRequested) break
                val env = api.likesPage(me.id, cursor)
                val batch = env.data.orEmpty()
                if (batch.isEmpty()) break
                for (t in batch) {
                    if (removed >= limit || cancelRequested) break@outer
                    jitter()
                    runCatching { api.unlike(me.id, t.id) }
                        .onSuccess {
                            removed++
                            append("取消点赞 ${t.id}")
                        }
                        .onFailure { append("取消点赞失败 ${t.id}：${it.message}") }
                    _state.update { it.copy(progress = removed.toFloat() / limit) }
                }
                cursor = env.meta?.nextToken ?: break
            }
            _state.update { it.copy(likes = (it.likes - removed).coerceAtLeast(0)) }
            append("取消点赞完成：$removed 条")
            store.appendHistory("取消点赞 $removed 条")
        }
    }

    fun loadRetweetsCount() {
        val me = _state.value.profile ?: return toast("先授权并读取账号")
        launchWork("读取转推") {
            var cursor: String? = null
            var total = 0
            var page = 0
            while (page < 20) {
                if (cancelRequested) throw WorkCancelled()
                page++
                val env = api.tweetsPage(me.id, cursor)
                total += env.data.orEmpty().count { it.isRetweet }
                _state.update { it.copy(busyLabel = "读取转推 · $total 条", retweets = total) }
                cursor = env.meta?.nextToken ?: break
            }
            append("转推数量约 $total 条（最多读 20 页）")
        }
    }

    fun deleteRetweets(limit: Int) {
        val me = _state.value.profile ?: return toast("先授权并读取账号")
        launchWork("删除转推") {
            cancelRequested = false
            var deleted = 0
            var cursor: String? = null
            outer@ while (deleted < limit) {
                if (cancelRequested) break
                val env = api.tweetsPage(me.id, cursor)
                val batch = env.data.orEmpty().filter { it.isRetweet }
                if (batch.isEmpty()) {
                    cursor = env.meta?.nextToken ?: break
                    continue@outer
                }
                for (t in batch) {
                    if (deleted >= limit || cancelRequested) break@outer
                    jitter()
                    runCatching { api.deleteTweet(t.id) }
                        .onSuccess {
                            deleted++
                            append("已删除转推 ${t.id}")
                        }
                        .onFailure { append("删除失败 ${t.id}：${it.message}") }
                    _state.update { it.copy(progress = deleted.toFloat() / limit) }
                }
                cursor = env.meta?.nextToken ?: break
            }
            _state.update { it.copy(retweets = (it.retweets - deleted).coerceAtLeast(0)) }
            append("删除转推完成：$deleted 条")
            store.appendHistory("删除转推 $deleted 条")
        }
    }

    // ---------------- 设置 ----------------

    fun setDailyCap(v: Int) {
        store.dailyCap = v
        _state.update { it.copy(dailyCap = store.dailyCap) }
    }

    fun setDryRun(v: Boolean) {
        store.dryRun = v
        _state.update { it.copy(dryRun = v) }
    }

    fun setDelay(min: Int, max: Int) {
        store.delayMinMs = min
        store.delayMaxMs = max.coerceAtLeast(min)
        _state.update { it.copy(delayMinSec = store.delayMinMs / 1000f, delayMaxSec = store.delayMaxMs / 1000f) }
    }

    fun clearWhitelist() {
        store.saveWhitelist(emptySet())
        _state.update { st ->
            st.copy(
                whitelist = emptySet(),
                rows = st.snapshot?.let { buildRows(it, st.filter, st.query, emptySet()) } ?: emptyList()
            )
        }
        append("白名单已清空")
    }

    fun resetQuota() {
        store.quotaUsed = 0
        _state.update { it.copy(dailyUsed = store.quotaUsed) }
        append("今日取关额度已重置为 ${store.dailyCap}")
    }

    fun clearData() {
        store.clearAll()
        store.clearHistory()
        _state.update {
            it.copy(snapshot = null, rows = emptyList(), selected = emptySet(), stats = Stats(), lastScanAt = 0L, likes = 0, retweets = 0)
        }
        append("已清空快照与历史")
    }

    fun csv(): String {
        val snap = _state.value.snapshot ?: return ""
        val followerSet = snap.followerIds.toHashSet()
        val suspended = snap.suspendedIds.toHashSet()
        val gone = snap.goneIds.toHashSet()
        val sb = StringBuilder("id,username,name,followers,following,relation,status\n")
        val ids = (snap.followingIds + snap.followerIds.filter { it !in snap.followingIds.toHashSet() }).distinct()
        ids.forEach { id ->
            val u = snap.following[id] ?: snap.followers[id]
            val relation = when {
                id in snap.followingIds.toHashSet() && id in followerSet -> "互关"
                id in snap.followingIds.toHashSet() -> "不互关"
                else -> "仅粉丝"
            }
            val status = when {
                id in suspended -> "疑似封禁"
                id in gone -> "注销/不可见"
                else -> "正常"
            }
            sb.append(csvCell(id)).append(',')
                .append(csvCell(u?.username ?: "")).append(',')
                .append(csvCell(u?.name ?: "")).append(',')
                .append(u?.metrics?.followers ?: 0).append(',')
                .append(u?.metrics?.following ?: 0).append(',')
                .append(csvCell(relation)).append(',')
                .append(csvCell(status)).append('\n')
        }
        return sb.toString()
    }

    fun history(): String = store.historyText()

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun showToast(msg: String) = toast(msg)

    fun cancel() {
        cancelRequested = true
        append("正在停止…")
    }

    // ---------------- 内部 ----------------

    private fun launchWork(label: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = label, progress = null) }
            try {
                block()
            } catch (c: WorkCancelled) {
                append("任务已取消")
            } catch (e: Exception) {
                append("出错：${e.message}")
            } finally {
                _state.update { it.copy(busy = false, busyLabel = "", progress = null) }
            }
        }
    }

    private suspend fun jitter() {
        val min = store.delayMinMs.toLong()
        val max = store.delayMaxMs.toLong().coerceAtLeast(min)
        delay(Random.nextLong(min, max + 1))
    }

    private fun applySnapshot(snapshot: Snapshot) {
        store.saveSnapshot(snapshot)
        _state.update { st ->
            val rows = buildRows(snapshot, st.filter, st.query, st.whitelist)
            st.copy(
                snapshot = snapshot,
                lastScanAt = snapshot.createdAt,
                profile = snapshot.me,
                rows = rows,
                stats = statsOf(snapshot),
                selected = st.selected.filter { id -> rows.any { it.id == id } }.toSet()
            )
        }
    }

    private fun statsOf(s: Snapshot) = Stats(
        following = s.followingIds.size,
        followers = s.followerIds.size,
        nonMutual = s.nonMutualIds().size,
        mutual = s.mutualIds().size,
        fanOnly = s.fanOnlyIds().size,
        suspended = s.suspendedIds.size + s.goneIds.size
    )

    private fun buildRows(snapshot: Snapshot, filter: RowFilter, query: String, whitelist: Set<String>): List<PersonRow> {
        val followingSet = snapshot.followingIds.toHashSet()
        val followerSet = snapshot.followerIds.toHashSet()
        val suspended = snapshot.suspendedIds.toHashSet()
        val gone = snapshot.goneIds.toHashSet()
        val q = query.trim().removePrefix("@").lowercase()
        val out = ArrayList<PersonRow>()

        fun make(id: String) {
            val u = snapshot.following[id] ?: snapshot.followers[id]
            val username = u?.username ?: id
            if (q.isNotEmpty()) {
                val hit = username.lowercase().contains(q) ||
                    (u?.name?.lowercase()?.contains(q) == true)
                if (!hit) return
            }
            out.add(
                PersonRow(
                    id = id,
                    username = username,
                    name = u?.name ?: "（已不可见）",
                    followers = u?.metrics?.followers ?: 0,
                    following = u?.metrics?.following ?: 0,
                    mutual = id in followingSet && id in followerSet,
                    nonMutual = id in followingSet && id !in followerSet,
                    fanOnly = id in followerSet && id !in followingSet,
                    suspended = id in suspended,
                    gone = id in gone,
                    whitelisted = id in whitelist
                )
            )
        }

        when (filter) {
            RowFilter.NON_MUTUAL -> snapshot.followingIds.forEach { if (it !in followerSet) make(it) }
            RowFilter.SUSPENDED -> snapshot.followingIds.forEach { if (it in suspended || it in gone) make(it) }
            RowFilter.MUTUAL -> snapshot.followingIds.forEach { if (it in followerSet) make(it) }
            RowFilter.FANS -> snapshot.followerIds.forEach { if (it !in followingSet) make(it) }
            RowFilter.ALL -> {
                snapshot.followingIds.forEach { make(it) }
                snapshot.followerIds.forEach { if (it !in followingSet) make(it) }
            }
        }
        return out.sortedBy { it.username.lowercase() }
    }

    private fun append(line: String) {
        _state.update { st ->
            val logs = (st.logs + line).takeLast(300)
            st.copy(logs = logs)
        }
    }

    private fun toast(msg: String) {
        _state.update { it.copy(toast = msg) }
    }

    private fun csvCell(v: String): String {
        val s = if (v.length > 200) v.take(200) else v
        return if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    override fun onCleared() {
        super.onCleared()
        cancelRequested = true
    }
}
