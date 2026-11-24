package com.yg.tweetsweeper.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yg.tweetsweeper.data.AppStore
import com.yg.tweetsweeper.data.ArchiveData
import com.yg.tweetsweeper.data.ArchiveImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream

enum class LocalFilter(val label: String) {
    NON_MUTUAL("不互关"),
    MUTUAL("互关"),
    FANS("仅粉丝"),
    ALL("全部")
}

data class LocalRow(
    val id: String,
    val relation: String,
    val done: Boolean = false,
    val doneAt: Long = 0L,
    val kept: Boolean = false
)

data class LocalState(
    val ready: Boolean = false,
    val loading: Boolean = false,
    val following: List<String> = emptyList(),
    val follower: List<String> = emptyList(),
    val filter: LocalFilter = LocalFilter.NON_MUTUAL,
    val rows: List<LocalRow> = emptyList(),
    val importedAt: Long = 0L,
    val files: String = "",
    val done: Map<String, Long> = emptyMap(),
    val whitelist: Set<String> = emptySet(),
    val hideDone: Boolean = true,
    val hideKept: Boolean = false,
    val donePresent: Int = 0,
    val keptPresent: Int = 0,
    val nonMutual: Int = 0,
    val mutual: Int = 0,
    val fanOnly: Int = 0,
    val lastOpenedId: String? = null,
    val showQuickKeep: Boolean = false,
    val toast: String? = null
) {
    val hasData: Boolean get() = following.isNotEmpty() || follower.isNotEmpty()
    val importedLabel: String
        get() = if (importedAt == 0L) "还没导入数据包"
        else "导入于 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(importedAt))
}

/**
 * 列表排序：未处理的保持数据包原顺序在前；已处理的按标记时间倒序排在后面
 * —— 最后取关的在最上面，最早取关的在最下面。
 */
internal fun orderRows(rows: List<LocalRow>): List<LocalRow> =
    rows.filter { it.done }.sortedByDescending { it.doneAt } + rows.filter { !it.done }

/**
 * 零成本模式：只用 X 官方数据导出（following.js / follower.js），不调用任何 API。
 */
class LocalViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppStore(app)
    private val _state = MutableStateFlow(LocalState())
    @Volatile private var awaitingReturn = false
    val state: StateFlow<LocalState> = _state.asStateFlow()

    fun init() {
        if (_state.value.ready) return
        viewModelScope.launch(Dispatchers.IO) {
            val archive = store.loadArchive()
            val done = store.loadDone()
            val whitelist = store.loadWhitelist()
            _state.update { st ->
                rebuild(
                    st.copy(
                        ready = true,
                        following = archive?.following ?: emptyList(),
                        follower = archive?.follower ?: emptyList(),
                        importedAt = archive?.savedAt ?: 0L,
                        done = done,
                        whitelist = whitelist
                    )
                )
            }
        }
    }

    /**
     * 点「打开」：标记已处理并记住是谁，等从 X 回来后弹快捷卡片。
     */
    fun openProfile(id: String) {
        markDone(id)
        _state.update { it.copy(lastOpenedId = id) }
        awaitingReturn = true
    }

    /** App 回到前台：如果中途去过 X，就弹快捷卡片 */
    fun onReturnedToApp() {
        if (!awaitingReturn) return
        awaitingReturn = false
        if (_state.value.lastOpenedId == null) return
        _state.update { it.copy(showQuickKeep = true) }
    }

    /** 快捷卡片：加入白名单（同时撤销「已处理」标记） */
    fun quickKeep() {
        val id = _state.value.lastOpenedId
        if (id == null) {
            dismissQuickKeep()
            return
        }
        if (id !in _state.value.whitelist) {
            val wl = _state.value.whitelist + id
            store.saveWhitelist(wl)
            _state.update { rebuild(it.copy(whitelist = wl)) }
        }
        undoDone(id)
        showToast("已加入白名单")
        dismissQuickKeep()
    }

    fun dismissQuickKeep() {
        _state.update { it.copy(showQuickKeep = false, lastOpenedId = null) }
    }

    /** 点了「打开」就标记为已处理（记录时间），默认从列表里隐藏 */
    fun markDone(id: String) {
        val done = _state.value.done + (id to System.currentTimeMillis())
        store.saveDone(done)
        _state.update { rebuild(it.copy(done = done)) }
    }

    fun undoDone(id: String) {
        val done = _state.value.done - id
        store.saveDone(done)
        _state.update { rebuild(it.copy(done = done)) }
    }

    fun resetDone() {
        store.saveDone(emptyMap())
        _state.update { rebuild(it.copy(done = emptyMap())) }
        showToast("已重置处理标记")
    }

    fun toggleHideDone() {
        _state.update { rebuild(it.copy(hideDone = !it.hideDone)) }
    }

    /** 保留（加入白名单）：离线模式下不会被列入清理，也用于对照 API 模式的白名单 */
    fun toggleKeep(id: String) {
        val wl = _state.value.whitelist.toMutableSet()
        val added = wl.add(id)
        if (!added) wl.remove(id)
        store.saveWhitelist(wl)
        _state.update { rebuild(it.copy(whitelist = wl)) }
        showToast(if (added) "已加入白名单" else "已移出白名单")
    }

    fun toggleHideKept() {
        _state.update { rebuild(it.copy(hideKept = !it.hideKept)) }
    }

    fun resetKept() {
        store.saveWhitelist(emptySet())
        _state.update { rebuild(it.copy(whitelist = emptySet())) }
        showToast("已清空白名单")
    }

    /**
     * [open] 是「打开输入流」的工厂，必须在 IO 协程内部调用：
     * 调用方（ActivityResult 回调）返回后流会被系统关闭，先开流再丢给协程必然 Stream Closed。
     */
    fun importZip(open: () -> InputStream?) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, toast = null) }
            val result = runCatching {
                val input = open() ?: error("打不开这个文件")
                input.use { ArchiveImporter.parse(it) }
            }
            result.onSuccess { parsed ->
                if (parsed.following.isEmpty() && parsed.follower.isEmpty()) {
                    _state.update {
                        it.copy(loading = false, toast = "没解析到账号：确认选的是完整数据包 zip（含 following.js / follower.js）")
                    }
                } else {
                    val now = System.currentTimeMillis()
                    store.saveArchive(ArchiveData(parsed.following, parsed.follower, now))
                    store.appendHistory(
                        "离线导入 关注${parsed.following.size} 粉丝${parsed.follower.size}"
                    )
                    _state.update { st ->
                        rebuild(
                            st.copy(
                                loading = false,
                                following = parsed.following,
                                follower = parsed.follower,
                                importedAt = now,
                                files = parsed.files.joinToString("、"),
                                toast = "导入完成：关注 ${parsed.following.size} / 粉丝 ${parsed.follower.size}"
                            )
                        )
                    }
                }
            }
            result.onFailure { e ->
                _state.update { it.copy(loading = false, toast = "解析失败：${e.message}") }
            }
        }
    }

    fun setFilter(filter: LocalFilter) {
        _state.update { rebuild(it.copy(filter = filter)) }
    }

    fun clear() {
        store.clearArchive()
        _state.update { st ->
            rebuild(st.copy(following = emptyList(), follower = emptyList(), importedAt = 0L, files = "", done = emptyMap()))
        }
        showToast("已清除离线数据")
    }

    fun csv(): String {
        val st = _state.value
        if (!st.hasData) return ""
        val followingSet = st.following.toHashSet()
        val followerSet = st.follower.toHashSet()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        val sb = StringBuilder("user_id,profile_url,relation,kept,done_at\n")
        st.following.forEach { id ->
            val rel = if (id in followerSet) "互关" else "不互关"
            sb.append(id).append(",https://x.com/i/user/").append(id).append(',').append(rel).append(',')
            sb.append(if (id in st.whitelist) "1" else "0").append(',')
            sb.append(st.done[id]?.let { fmt.format(java.util.Date(it)) } ?: "").append('\n')
        }
        st.follower.filter { it !in followingSet }.forEach { id ->
            sb.append(id).append(",https://x.com/i/user/").append(id).append(",仅粉丝,")
            sb.append(if (id in st.whitelist) "1" else "0").append(',')
            sb.append(st.done[id]?.let { fmt.format(java.util.Date(it)) } ?: "").append('\n')
        }
        return sb.toString()
    }

    fun nonMutualCsv(): String {
        val st = _state.value
        val followerSet = st.follower.toHashSet()
        val sb = StringBuilder("user_id,profile_url\n")
        st.following.filter { it !in followerSet && it !in st.whitelist }.forEach { id ->
            sb.append(id).append(",https://x.com/i/user/").append(id).append('\n')
        }
        return sb.toString()
    }

    fun consumeToast() {
        _state.update { it.copy(toast = null) }
    }

    fun showToast(msg: String) {
        _state.update { it.copy(toast = msg) }
    }

    private fun rebuild(st: LocalState): LocalState {
        val followingSet = st.following.toHashSet()
        val followerSet = st.follower.toHashSet()

        fun row(id: String, relation: String) = LocalRow(
            id = id,
            relation = relation,
            done = id in st.done,
            doneAt = st.done[id] ?: 0L,
            kept = id in st.whitelist
        )

        val all = when (st.filter) {
            LocalFilter.NON_MUTUAL ->
                st.following.filter { it !in followerSet }.map { row(it, "不互关") }
            LocalFilter.MUTUAL ->
                st.following.filter { it in followerSet }.map { row(it, "互关") }
            LocalFilter.FANS ->
                st.follower.filter { it !in followingSet }.map { row(it, "仅粉丝") }
            LocalFilter.ALL ->
                st.following.map { row(it, if (it in followerSet) "互关" else "不互关") } +
                    st.follower.filter { it !in followingSet }.map { row(it, "仅粉丝") }
        }

        var visible = all
        if (st.hideDone) visible = visible.filter { !it.done }
        if (st.hideKept) visible = visible.filter { !it.kept }

        return st.copy(
            rows = orderRows(visible),
            donePresent = st.done.keys.count { it in followingSet || it in followerSet },
            keptPresent = st.whitelist.count { it in followingSet || it in followerSet },
            nonMutual = st.following.count { it !in followerSet },
            mutual = st.following.count { it in followerSet },
            fanOnly = st.follower.count { it !in followingSet }
        )
    }
}
