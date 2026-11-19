package com.yg.tweetsweeper.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yg.tweetsweeper.vm.MainViewModel
import com.yg.tweetsweeper.vm.UiState

@Composable
fun ToolsScreen(vm: MainViewModel, state: UiState) {
    val context = LocalContext.current
    var showHistory by remember { mutableStateOf(false) }
    var historyText by remember { mutableStateOf("") }

    val createCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val ok = writeTextTo(context, uri, vm.csv())
            vm.showToast(if (ok) "CSV 已保存" else "保存失败")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(2.dp))

        SectionCard("点赞清理", "DELETE /2/users/:id/likes/:tweet_id，逐条取消最近的点赞") {
            Text("当前统计：${state.likes} 条", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.loadLikesCount() }, Modifier.weight(1f)) { Text("统计") }
                Button(onClick = { vm.unlikeRecent(20) }, Modifier.weight(1f)) { Text("取消最近 20 条") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.unlikeRecent(100) }, Modifier.weight(1f)) { Text("取消 100 条") }
                OutlinedButton(onClick = { vm.unlikeRecent(500) }, Modifier.weight(1f)) { Text("取消 500 条") }
            }
        }

        SectionCard("转推清理", "删除自己账号发过的转推（DELETE /2/tweets/:id），原推不受影响") {
            Text("当前统计：${state.retweets} 条", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.loadRetweetsCount() }, Modifier.weight(1f)) { Text("统计") }
                Button(onClick = { vm.deleteRetweets(20) }, Modifier.weight(1f)) { Text("删 20 条") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.deleteRetweets(100) }, Modifier.weight(1f)) { Text("删 100 条") }
                OutlinedButton(onClick = { vm.deleteRetweets(500) }, Modifier.weight(1f)) { Text("删 500 条") }
            }
        }

        SectionCard("导出与备份", "把当前快照导成 CSV（含关系与封禁状态），可用 Excel 打开") {
            Button(
                onClick = {
                    val name = "tweetsweeper-${state.profile?.username ?: "export"}.csv"
                    createCsv.launch(name)
                },
                enabled = state.snapshot != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("导出 CSV 文件") }

            OutlinedButton(
                onClick = {
                    val csv = vm.csv()
                    if (csv.isBlank()) {
                        vm.showToast("没有可导出的数据")
                    } else {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_SUBJECT, "推特关系导出")
                            putExtra(Intent.EXTRA_TEXT, csv.take(200_000))
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, "分享 CSV"))
                        }
                    }
                },
                enabled = state.snapshot != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("分享 CSV 文本") }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        historyText = vm.history()
                        showHistory = true
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("操作历史") }
                OutlinedButton(
                    onClick = { vm.clearData() },
                    modifier = Modifier.weight(1f)
                ) { Text("清空数据") }
            }
        }

        SectionCard("接口配额说明", "官方限速是硬约束，App 会自动排队，不要同时开多个工具") {
            Text(
                "· 关注/粉丝列表：15 次 / 15 分钟（每页 1000 人）\n" +
                    "· 用户批量查询：用于封禁检测，每批 100 人\n" +
                    "· 取关、取消点赞、删推：写入类接口另有更严的窗口限制\n" +
                    "· 达到任何窗口上限时，界面会显示等待秒数并自动继续",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { vm.refreshProfile() }) { Text("重新读取账号资料") }
        }

        Spacer(Modifier.height(20.dp))
    }

    if (showHistory) {
        HistoryDialog(historyText) { showHistory = false }
    }
}
