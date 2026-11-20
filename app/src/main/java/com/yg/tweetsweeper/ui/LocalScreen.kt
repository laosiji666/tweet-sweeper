package com.yg.tweetsweeper.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            SectionCard(
                "零成本模式",
                "只读官方数据包，不调用任何 API、不扣任何额度。取关由你在 X App 里点，App 只负责算名单和跳转。"
            ) {
                Text(
                    "1. X App → 设置 → 你的账号 → 下载你的数据存档\n" +
                        "2. 等邮件（通常几小时到 24 小时）→ 下载 zip\n" +
                        "3. 回到这里选那个 zip，自动解析 following.js / follower.js",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        pickArchive.launch(arrayOf("application/zip", "text/javascript", "application/octet-stream", "*/*"))
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
                            "· 包里只有账号 ID，没有用户名，所以列表显示数字 ID，点「打开」会跳到对方主页\n" +
                            "· 想要用户名、封禁状态（需要逐个读资料），还是得走「扫描」页的 API 模式\n" +
                            "· 支持直接选 following.js / follower.js 单文件",
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "待处理 ${state.rows.size} · 已处理 ${state.donePresent}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.toggleHideDone() }) {
                            Text(if (state.hideDone) "显示已处理" else "隐藏已处理")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            val text = state.rows.joinToString("\n") { "https://x.com/i/user/${it.id}" }
                            clipboard.setText(AnnotatedString(text))
                            vm.showToast("已复制 ${state.rows.size} 条主页链接")
                        }) { Text("复制本页链接") }
                        TextButton(onClick = { vm.resetDone() }) { Text("重置标记") }
                    }
                }
            }

            items(state.rows, key = { it.id }) { row ->
                LocalRowItem(row, onOpen = {
                    vm.markDone(row.id)
                    openXProfile(context, row.id)
                }, onUndo = { vm.undoDone(row.id) })
            }

            if (state.rows.isEmpty()) {
                item {
                    SectionCard("这一批处理完了", null) {
                        Text(
                            "点「重置标记」可以把已处理的重新列出来；或者重新导入最新的数据包。",
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
}

@Composable
private fun LocalRowItem(row: LocalRow, onOpen: () -> Unit, onUndo: () -> Unit) {
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
                color = if (row.done) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "x.com/i/user/${row.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(6.dp))
        Pill(
            row.relation,
            when (row.relation) {
                "不互关" -> MaterialTheme.colorScheme.primary
                "互关" -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            filled = true
        )
        if (row.done) {
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onUndo, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("撤销", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onOpen,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("打开", style = MaterialTheme.typography.labelMedium) }
        }
    }
}
