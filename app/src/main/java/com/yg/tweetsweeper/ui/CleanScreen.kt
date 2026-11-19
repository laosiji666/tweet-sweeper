package com.yg.tweetsweeper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yg.tweetsweeper.vm.MainViewModel
import com.yg.tweetsweeper.vm.PersonRow
import com.yg.tweetsweeper.vm.RowFilter
import com.yg.tweetsweeper.vm.UiState

@Composable
fun CleanScreen(vm: MainViewModel, state: UiState) {
    var confirm by remember { mutableStateOf(false) }
    val selectedRows = state.rows.filter { it.id in state.selected }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(RowFilter.values().toList()) { f ->
                FilterChip(
                    selected = state.filter == f,
                    onClick = { vm.setFilter(f) },
                    label = { Text(f.label) }
                )
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = { vm.setQuery(it) },
            label = { Text("搜索用户名 / 昵称") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "已选 ${state.selected.size} / 显示 ${state.rows.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.selectAllVisible() }) { Text("全选本页") }
            TextButton(onClick = { vm.clearSelection() }) { Text("清空") }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (state.rows.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (state.snapshot == null) "还没有数据，先去「扫描」页拉一次关注与粉丝"
                    else "当前筛选下没有账号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(state.rows, key = { it.id }) { row ->
                    PersonItem(vm, row, row.id in state.selected)
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.quotaLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (state.dryRun) "试运行模式：只记录不执行" else "真实模式：会真的取关",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.dryRun) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        onClick = { confirm = true },
                        enabled = state.selected.isNotEmpty() && !state.busy && state.filter != RowFilter.FANS,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) { Text("批量取关") }
                }
                if (state.filter == RowFilter.FANS) {
                    Text(
                        "「仅粉丝」是你没关注的人，取关对他们无效；要取关请切到「不互关」或「全部」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("确认取关 ${state.selected.size} 个账号？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("将通过官方接口逐个调用 DELETE /2/users/:id/following/:target。取关后不能批量撤销。")
                    Text(
                        "白名单账号会自动跳过；当前每日剩余额度 ${(state.dailyCap - state.dailyUsed).coerceAtLeast(0)}。",
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (state.dryRun) {
                        Text(
                            "当前是试运行模式，不会真的取关。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    val sample = selectedRows.take(3).joinToString("、") { "@${it.username}" }
                    if (sample.isNotBlank()) {
                        Text("例如：$sample", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    vm.unfollowSelected()
                }) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PersonItem(vm: MainViewModel, row: PersonRow, selected: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.toggleSelect(row.id) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { vm.toggleSelect(row.id) })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (row.whitelisted) {
                    Spacer(Modifier.width(6.dp))
                    Pill("白名单", MaterialTheme.colorScheme.secondary, true)
                }
            }
            Text(
                "@${row.username} · ${row.followers} 粉丝 · ${row.following} 关注",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    row.suspended -> Pill("疑似封禁", MaterialTheme.colorScheme.error, true)
                    row.gone -> Pill("已注销", MaterialTheme.colorScheme.error, true)
                    row.nonMutual -> Pill("不互关", MaterialTheme.colorScheme.primary, true)
                    row.mutual -> Pill("互关", MaterialTheme.colorScheme.secondary, true)
                    row.fanOnly -> Pill("粉丝", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(
            onClick = { vm.toggleWhitelist(row.id) },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                if (row.whitelisted) "解除" else "保护",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
