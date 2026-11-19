package com.yg.tweetsweeper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yg.tweetsweeper.vm.MainViewModel
import com.yg.tweetsweeper.vm.UiState

@Composable
fun ScanScreen(vm: MainViewModel, state: UiState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(Modifier.height(2.dp)) }
        if (!state.authorized) {
            item { AuthCard(vm, state) }
            item { ScopeCard() }
        } else {
            item { AccountCard(vm, state) }
            item { ScanCard(vm, state) }
            item { StatsCard(state) }
        }
        item { SectionCard("运行日志", "限速、跳过、失败都会写在这里") { LogBlock(state, 10) } }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun AuthCard(vm: MainViewModel, state: UiState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var clientId by rememberSaveable { mutableStateOf(state.clientId) }

    SectionCard(
        "连接 X 账号",
        "在 X 开发者后台创建一个 OAuth 2.0 应用，类型选 Native App（公开客户端），把回调 URI 填成下面的地址"
    ) {
        Text(
            "回调 URI：tweetsweeper://callback",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            "权限：tweet.read users.read follows.read follows.write like.read like.write offline.access",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        OutlinedTextField(
            value = clientId,
            onValueChange = {
                clientId = it
                vm.saveClientId(it)
            },
            label = { Text("OAuth 2.0 Client ID") },
            placeholder = { Text("例如：Z1p4c2…（不是 API Key）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val uri = vm.authUriOrNull()
                if (uri != null) openInBrowser(context, uri)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) { Text("用 X 授权登录") }
        OutlinedButton(
            onClick = {
                val uri = vm.authUriOrNull()
                if (uri != null) {
                    clipboard.setText(AnnotatedString(uri.toString()))
                    vm.showToast("授权链接已复制，可粘贴进任意浏览器")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("复制授权链接") }
        Text(
            "登录页会在浏览器里打开，授权后自动回到本应用。刷新令牌已加密保存在本机。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScopeCard() {
    SectionCard("这个 App 会做什么", "全部通过 X 官方 API v2 执行，不使用任何私有接口或模拟登录") {
        Bullet("批量取关：按名单逐条调用取关接口，可设每日上限与随机间隔")
        Bullet("找不互关：对比关注列表与粉丝列表，算出单向关注")
        Bullet("查封禁/注销：批量核对关注对象，标记已停用或已消失的账号")
        Bullet("附加：点赞批量取消、转推批量删除、CSV 导出、白名单保护")
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("·", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(horizontal = 3.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AccountCard(vm: MainViewModel, state: UiState) {
    val p = state.profile
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        p?.name?.takeIf { it.isNotBlank() } ?: "已连接",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "@${p?.username ?: "…"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { vm.logout() }) { Text("退出") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("关注", "${p?.following ?: state.stats.following}", modifier = Modifier.weight(1f))
                StatTile("粉丝", "${p?.followers ?: state.stats.followers}", modifier = Modifier.weight(1f))
                StatTile("不互关", "${state.stats.nonMutual}", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScanCard(vm: MainViewModel, state: UiState) {
    SectionCard("数据源", state.snapshotLabel) {
        Button(
            onClick = { vm.scan() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
            } else {
                Text("开始扫描关注 + 粉丝")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.refreshProfile() },
                modifier = Modifier.weight(1f)
            ) { Text("刷新资料") }
            OutlinedButton(
                onClick = { vm.setFilter(com.yg.tweetsweeper.vm.RowFilter.NON_MUTUAL) },
                modifier = Modifier.weight(1f)
            ) { Text("看名单") }
        }
        Text(
            "扫描会调用官方接口：关注/粉丝各按每页 1000 人翻页，封禁检测按每批 100 人核对。关注量大的账号需要几分钟。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatsCard(state: UiState) {
    val s = state.stats
    SectionCard("关系概览", "基于本地快照，不联网") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("关注", "${s.following}", modifier = Modifier.weight(1f))
            StatTile("粉丝", "${s.followers}", modifier = Modifier.weight(1f))
            StatTile("互关", "${s.mutual}", MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("不互关", "${s.nonMutual}", MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            StatTile("仅粉丝", "${s.fanOnly}", modifier = Modifier.weight(1f))
            StatTile("封禁/注销", "${s.suspended}", MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        }
    }
}
