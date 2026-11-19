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

data class LocalRow(val id: String, val relation: String, val done: Boolean = false)

data class LocalState(
    val ready: Boolean = false,
    val loading: Boolean = false,
    val following: List<String> = emptyList(),
    val follower: List<String> = emptyList(),
    val filter: LocalFilter = LocalFilter.NON_MUTUAL,
    val rows: List<LocalRow> = emptyList(),
    val importedAt: Long = 0L,
    val files: String = "",
    val done: Set<String> = emptySet(),
    val hideDone: Boolean = true,
    val donePresent: Int = 0,
    val nonMutual: Int = 0,
    val mutual: Int = 0,
    val fanOnly: Int = 0,
    val toast: String? = null
) {
    val hasData: Boolean get() = following.isNotEmpty() || follower.isNotEmpty()
    val importedLabel: String
        get() = if (importedAt == 0L) "还没导入数据包"
        else "导入于 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(importedAt))
}

/**
 * 零成本模式：只用 X 官方数据导出（following.js / follower.js），不调用任何 API。
 * 结果按用户 ID 展示，点「打开主页」交给 X App 处理取关。
 */
class LocalViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppStore(app)
    private val _state = MutableStateFlow(LocalState())
    val state: StateFlow<LocalState> = _state.asStateFlow()

    fun init() {
        if (_state.value.ready) return
        viewModelScope.launch(Dispatchers.IO) {
            val archive = store.loadArchive()
            val done = store.loadDone()
            _state.update { st ->
                val base = st.copy(
                    ready = true,
                    following = archive?.following ?: emptyList(),
                    follower = archive?.follower ?: emptyList(),
                    importedAt = archive?.savedAt ?: 0L,
                    done = done
                )
                rebuild(base)
            }
        }
    }

    /** 点了「打开」就标记为已处理，默认从列表里隐藏 */
    fun markDone(id: String) {
        val done = _state.value.done + id
        store.saveDone(done)
        _state.update { rebuild(it.copy(done = done)) }
    }

    fun undoDone(id: String) {
        val done = _state.value.done - id
        store.saveDone(done)
        _state.update { rebuild(it.copy(done = done)) }
    }

    fun resetDone() {
        store.saveDone(emptySet())
        _state.update { rebuild(it.copy(done = emptySet())) }
        showToast("已重置处理标记")
    }

    fun toggleHideDone() {
        _state.update { rebuild(it.copy(hideDone = !it.hideDone)) }
    }

    fun importZip(input: InputStream) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, toast = null) }
            val result = runCatching { ArchiveImporter.parse(input) }
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
            rebuild(st.copy(following = emptyList(), follower = emptyList(), importedAt = 0L, files = "", done = emptySet()))
        }
        showToast("已清除离线数据")
    }

    fun csv(): String {
        val st = _state.value
        if (!st.hasData) return ""
        val followingSet = st.following.toHashSet()
        val followerSet = st.follower.toHashSet()
        val sb = StringBuilder("user_id,profile_url,relation\n")
        st.following.forEach { id ->
            val rel = if (id in followerSet) "互关" else "不互关"
            sb.append(id).append(",https://x.com/i/user/").append(id).append(',').append(rel).append('\n')
        }
        st.follower.filter { it !in followingSet }.forEach { id ->
            sb.append(id).append(",https://x.com/i/user/").append(id).append(",仅粉丝\n")
        }
        return sb.toString()
    }

    fun nonMutualCsv(): String {
        val st = _state.value
        val followerSet = st.follower.toHashSet()
        val sb = StringBuilder("user_id,profile_url\n")
        st.following.filter { it !in followerSet }.forEach { id ->
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
        val all = when (st.filter) {
            LocalFilter.NON_MUTUAL ->
                st.following.filter { it !in followerSet }.map { LocalRow(it, "不互关", it in st.done) }
            LocalFilter.MUTUAL ->
                st.following.filter { it in followerSet }.map { LocalRow(it, "互关", it in st.done) }
            LocalFilter.FANS ->
                st.follower.filter { it !in followingSet }.map { LocalRow(it, "仅粉丝", it in st.done) }
            LocalFilter.ALL ->
                st.following.map { LocalRow(it, if (it in followerSet) "互关" else "不互关", it in st.done) } +
                    st.follower.filter { it !in followingSet }.map { LocalRow(it, "仅粉丝", it in st.done) }
        }
        val rows = if (st.hideDone) all.filter { !it.done } else all
        return st.copy(
            rows = rows,
            donePresent = st.done.count { it in followingSet || it in followerSet },
            nonMutual = st.following.count { it !in followerSet },
            mutual = st.following.count { it in followerSet },
            fanOnly = st.follower.count { it !in followingSet }
        )
    }
}
