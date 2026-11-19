package com.yg.tweetsweeper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yg.tweetsweeper.vm.LocalViewModel
import com.yg.tweetsweeper.vm.MainViewModel

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("扫描", Icons.Filled.Search),
    Tab("清理", Icons.Filled.Delete),
    Tab("工具", Icons.Filled.Star),
    Tab("离线", Icons.Filled.List),
    Tab("设置", Icons.Filled.Settings)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val localVm: LocalViewModel = viewModel()
    val localState by localVm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.init()
        localVm.init()
    }

    LaunchedEffect(localState.toast) {
        val msg = localState.toast
        if (!msg.isNullOrBlank()) {
            snackbar.showSnackbar(msg)
            localVm.consumeToast()
        }
    }

    LaunchedEffect(state.toast) {
        val msg = state.toast
        if (!msg.isNullOrBlank()) {
            snackbar.showSnackbar(msg)
            vm.consumeToast()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("推特清理助手", fontWeight = FontWeight.Bold)
                        Text(
                            state.snapshotLabel + " · " + state.quotaLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tab) {
                0 -> ScanScreen(vm, state)
                1 -> CleanScreen(vm, state)
                2 -> ToolsScreen(vm, state)
                3 -> LocalScreen(localVm, localState)
                else -> SettingsScreen(vm, state)
            }
        }
        BusyDialog(state) { vm.cancel() }
    }
}
