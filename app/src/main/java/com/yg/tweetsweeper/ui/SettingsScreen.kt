package com.yg.tweetsweeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yg.tweetsweeper.vm.MainViewModel
import com.yg.tweetsweeper.vm.UiState

@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState) {
    var clientId by rememberSaveable { mutableStateOf(state.clientId) }
    var cap by remember(state.dailyCap) { mutableFloatStateOf(state.dailyCap.toFloat()) }
    var dmin by remember(state.delayMinSec) { mutableFloatStateOf(state.delayMinSec) }
    var dmax by remember(state.delayMaxSec) { mutableFloatStateOf(state.delayMaxSec) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(2.dp))

        SectionCard("授权设置", "换成自己的开发者 App 时改这里，改完需要重新授权") {
            OutlinedTextField(
                value = clientId,
                onValueChange = {
                    clientId = it
                    vm.saveClientId(it)
                },
                label = { Text("OAuth 2.0 Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "回调 URI：tweetsweeper://callback",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.weight(1f)) {
                    Text("退出登录")
                }
                OutlinedButton(onClick = { vm.clearData() }, modifier = Modifier.weight(1f)) {
                    Text("清空快照")
                }
            }
        }

        SectionCard("取关节奏", "越慢越安全。官方写入接口本身有限额，太快会直接 429") {
            Text("每日取关上限：${cap.toInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = cap,
                onValueChange = { cap = it },
                onValueChangeFinished = { vm.setDailyCap(cap.toInt()) },
                valueRange = 1f..200f,
                steps = 0
            )
            Text("每次取关前随机等待：${dmin}–${dmax} 秒", style = MaterialTheme.typography.bodyMedium)
            Text("最小间隔", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = dmin,
                onValueChange = { dmin = it },
                onValueChangeFinished = { vm.setDelay((dmin * 1000).toInt(), (dmax * 1000).toInt()) },
                valueRange = 0.5f..10f,
                steps = 18
            )
            Text("最大间隔", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = dmax,
                onValueChange = { dmax = it },
                onValueChangeFinished = { vm.setDelay((dmin * 1000).toInt(), (dmax * 1000).toInt()) },
                valueRange = 1f..30f,
                steps = 28
            )
        }

        SectionCard("安全开关", "试运行只写日志，不改动账号") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("试运行（Dry run）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "打开后「批量取关」只记录不执行，适合先核对名单",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.dryRun, onCheckedChange = { vm.setDryRun(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("白名单", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "已保护 ${state.whitelist.size} 个账号，批量取关永不触碰",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.whitelist.isNotEmpty()) {
                    OutlinedButton(onClick = { vm.clearWhitelist() }) { Text("清空") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("今日额度", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        state.quotaLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { vm.resetQuota() }) { Text("重置") }
            }
        }

        SectionCard("关于", "v1.0.0 · 纯客户端实现，不经过任何中间服务器") {
            Text(
                "所有请求都从这台手机直连 api.x.com；令牌保存在 Android 加密存储（EncryptedSharedPreferences）。\n\n" +
                    "用到的官方接口：\n" +
                    "GET /2/users/me\n" +
                    "GET /2/users/:id/following\n" +
                    "GET /2/users/:id/followers\n" +
                    "GET /2/users?ids=…（封禁检测）\n" +
                    "DELETE /2/users/:id/following/:target\n" +
                    "DELETE /2/users/:id/likes/:tweet\n" +
                    "DELETE /2/tweets/:id",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { vm.refreshProfile() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.authorized
            ) { Text("重新读取账号资料") }
        }

        Spacer(Modifier.height(20.dp))
    }
}
