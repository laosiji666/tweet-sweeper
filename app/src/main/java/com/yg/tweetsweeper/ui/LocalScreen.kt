package com.yg.tweetsweeper.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yg.tweetsweeper.vm.LocalFilter
import com.yg.tweetsweeper.vm.LocalRow
import com.yg.tweetsweeper.vm.LocalState
import com.yg.tweetsweeper.vm.LocalViewModel
import kotlinx.coroutines.launch

/** 用 X App（或浏览器）打开某个用户的主页，取关由你在官方界面完成 */
fun openXProfile(context: Context, userId: String) {
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/i/user/$userId"))
    if (runCatching { context.startActivity(web) }.isSuccess) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("twitter://user?id=$userId"))) }
}

@Composable
fun LocalScreen(vm: LocalViewModel, state: LocalState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showHelp by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 不能在这里开流再交给协程，回调结束流就被关了
            vm.importZip { runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() }
        }
    }

    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val text = vm.csv()
            val ok = text.isNotBlank() && writeTextTo(context, uri, text)
            vm.showToast(if (ok) "CSV 已保存" else "保存失败")
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 22.dp, top = 8.dp, bottom = 12.dp)
        ) {
            item {
                SectionCard(
                    "零成本模式",
                    "只读官方数据包，不调用任何 API、不扣任何额度。取关由你在 X App 里点，App 只负责算名单和跳转。"
                ) {
                    Text(
                        "1. X App → 设置 → 你的账号 → 下载你的数据存档\n" +
                            "2. 等邮件（几小时到 24 小时）→ 下载 zip\n" +
                            "3. 回到这里选那个 zip，自动解析 following.js / follower.js",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            pickArchive.launch(
                                arrayOf("application/zip", "text/javascript", "application/octet-stream", "*/*")
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !state.loading
                    ) { Text(if (state.loading) "解析中…" else "选择数据包 zip") }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.importedLabel + if (state.files.isNotBlank()) " · ${state.files}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "收起" else "说明") }
                    }
                    if (showHelp) {
                        Text(
                            "· 数据包是快照：取关后要重新申请导出，名单才会更新\n" +
                                "· 包里只有账号 ID，点「打开」会跳到对方主页\n" +
                                "· 点「保留」把人加进白名单，批量清理时不会碰他\n" +
                                "· 已处理按时间倒序：最后取关的在最上面\n" +
                                "· 右侧有滚动条，可以拖动快速翻页",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (state.hasData) {
                item {
                    SectionCard("关系概览", "本地计算，不联网") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("关注", "${state.following.size}", modifier = Modifier.weight(1f))
                            StatTile("粉丝", "${state.follower.size}", modifier = Modifier.weight(1f))
                            StatTile("互关", "${state.mutual}", MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("不互关", "${state.nonMutual}", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                            StatTile("仅粉丝", "${state.fanOnly}", modifier = Modifier.weight(1f))
                        }
                    }
                }

                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(LocalFilter.values().toList()) { f ->
                            FilterChip(
                                selected = state.filter == f,
                                onClick = { vm.setFilter(f) },
                                label = { Text(f.label) }
                            )
                        }
                    }
                }

                item {
                    Column {
                        Text(
                            "待处理 ${state.rows.count { !it.done }} · 已处理 ${state.donePresent} · 已保留 ${state.keptPresent}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.toggleHideDone() }) {
                                Text(if (state.hideDone) "显示已处理" else "隐藏已处理")
                            }
                            TextButton(onClick = { vm.toggleHideKept() }) {
                                Text(if (state.hideKept) "显示已保留" else "隐藏已保留")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                val text = state.rows.joinToString("\n") { "https://x.com/i/user/${it.id}" }
                                clipboard.setText(AnnotatedString(text))
                                vm.showToast("已复制 ${state.rows.size} 条主页链接")
                            }) { Text("复制本页链接") }
                            TextButton(onClick = { vm.resetDone() }) { Text("重置标记") }
                            TextButton(onClick = { vm.resetKept() }) { Text("清空白名单") }
                        }
                    }
                }

                items(state.rows, key = { it.id }) { row ->
                    LocalRowItem(
                        row = row,
                        onOpen = {
                            vm.markDone(row.id)
                            openXProfile(context, row.id)
                        },
                        onUndo = { vm.undoDone(row.id) },
                        onKeep = { vm.toggleKeep(row.id) }
                    )
                }

                if (state.rows.isEmpty()) {
                    item {
                        SectionCard("这一批处理完了", null) {
                            Text(
                                "点「重置标记」可以重新列出已处理的；「显示已处理」可以回看并按取关时间排序。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { createCsv.launch("x-relations-${System.currentTimeMillis()}.csv") },
                            modifier = Modifier.weight(1f)
                        ) { Text("导出全部") }
                        OutlinedButton(
                            onClick = { vm.clear() },
                            modifier = Modifier.weight(1f)
                        ) { Text("清除离线数据") }
                    }
                }
            } else if (!state.loading) {
                item {
                    SectionCard("还没有离线数据", null) {
                        Text(
                            "点上面的按钮选官方数据包 zip。这个模式完全免费，也不会消耗开发者额度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (state.rows.size > 20) {
            FastScrollBar(
                listState = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 12.dp)
            )
        }
    }
}

/**
 * 自绘快速滚动条：拖动右侧滑块按比例跳转，列表滚动时滑块跟随。
 */
@Composable
private fun FastScrollBar(listState: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf(0f) }

    val layout = listState.layoutInfo
    val total = layout.totalItemsCount
    if (total <= 1) return

    val visible = layout.visibleItemsInfo.size.coerceAtLeast(1)
    val progress = if (dragging) dragProgress else
        (listState.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)

    BoxWithConstraints(modifier.width(26.dp)) {
        val track = maxHeight
        val fraction = (visible.toFloat() / total).coerceIn(0.04f, 1f)
        val thumb = (track * fraction).coerceAtLeast(44.dp).coerceAtMost(track)
        val maxOffset = (track - thumb).coerceAtLeast(0.dp)

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = maxOffset * progress)
                .width(5.dp)
                .height(thumb)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 0.95f else 0.40f),
                    RoundedCornerShape(3.dp)
                )
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(total) {
                    val thumbPx = thumb.toPx()
                    fun seek(y: Float) {
                        val span = (size.height - thumbPx).coerceAtLeast(1f)
                        val ratio = ((y - thumbPx / 2f) / span).coerceIn(0f, 1f)
                        dragProgress = ratio
                        val index = (ratio * (total - 1)).toInt().coerceIn(0, total - 1)
                        scope.launch { listState.scrollToItem(index) }
                    }
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            seek(offset.y)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ -> seek(change.position.y) }
                    )
                }
        )
    }
}

@Composable
private fun LocalRowItem(
    row: LocalRow,
    onOpen: () -> Unit,
    onUndo: () -> Unit,
    onKeep: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.id,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    row.done -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    row.kept -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                when {
                    row.done -> "已取关 · " + formatTime(row.doneAt)
                    row.kept -> "已保留 · x.com/i/user/${row.id}"
                    else -> "x.com/i/user/${row.id}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(4.dp))
        Pill(
            if (row.kept) "保留" else row.relation,
            when {
                row.kept -> MaterialTheme.colorScheme.secondary
                row.relation == "不互关" -> MaterialTheme.colorScheme.primary
                row.relation == "互关" -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            filled = true
        )
        if (row.done) {
            TextButton(onClick = onUndo, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("撤销", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            TextButton(onClick = onKeep, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(
                    if (row.kept) "已保留" else "保留",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.kept) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onOpen,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) { Text("打开", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

private fun formatTime(ts: Long): String =
    if (ts <= 0) "" else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        .format(java.util.Date(ts))
