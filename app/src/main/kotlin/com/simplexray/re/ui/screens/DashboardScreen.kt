package com.simplexray.re.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.simplexray.re.R
import com.simplexray.re.common.formatBytes
import com.simplexray.re.common.formatUptime
import com.simplexray.re.service.VpnRunningState
import com.simplexray.re.service.VpnStateHub
import com.simplexray.re.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DashboardScreen(
    mainViewModel: MainViewModel,
    onSwitchVpnService: () -> Unit = {},
    paddingValues: PaddingValues = PaddingValues(),
) {
    val stats by mainViewModel.coreStatsState.collectAsStateWithLifecycle()
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val active by mainViewModel.activeGroupNodes.collectAsStateWithLifecycle()
    val fallback by mainViewModel.fallbackRuntimeStates.collectAsStateWithLifecycle()
    val state by VpnStateHub.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                now = SystemClock.elapsedRealtime()
                mainViewModel.updateCoreStats()
                delay(1_000)
            }
        }
    }
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val enabled = state == VpnRunningState.Connected || state == VpnRunningState.Connecting
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = maxOf(paddingValues.calculateTopPadding(), safe.calculateTopPadding()) + 12.dp,
            bottom = maxOf(paddingValues.calculateBottomPadding(), safe.calculateBottomPadding()) + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Xray + Hev", style = MiuixTheme.textStyles.title2)
                    Text(
                        when (state) {
                            VpnRunningState.Connected -> "已连接"
                            VpnRunningState.Connecting -> "正在连接"
                            is VpnRunningState.Failed -> (state as VpnRunningState.Failed).message ?: "启动失败"
                            else -> "等待连接"
                        },
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Button(
                        onClick = onSwitchVpnService,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(if (enabled) "断开连接" else "连接") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverviewValue(stringResource(R.string.stats_uplink), formatBytes(stats.uplink))
                    OverviewValue(stringResource(R.string.stats_downlink), formatBytes(stats.downlink))
                    OverviewValue(stringResource(R.string.stats_uptime), formatUptime(stats.uptime))
                    OverviewValue(stringResource(R.string.stats_alloc), formatBytes(stats.alloc))
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.workspace_routing_mode_title),
                    items = listOf(
                        stringResource(R.string.routing_mode_rule),
                        stringResource(R.string.routing_mode_global),
                        stringResource(R.string.routing_mode_direct),
                    ),
                    selectedIndex = listOf("rule", "global", "direct").indexOf(workspace.routingMode).coerceAtLeast(0),
                    onSelectedIndexChange = { mainViewModel.setRoutingMode(listOf("rule", "global", "direct")[it]) },
                )
                val groups = workspace.groups
                if (groups.isNotEmpty()) {
                    OverlayDropdownPreference(
                        title = "默认分组",
                        items = groups.map { it.tag },
                        selectedIndex = groups.indexOfFirst { it.tag == workspace.effectiveDefaultGroupTag() }.coerceAtLeast(0),
                        onSelectedIndexChange = { mainViewModel.setDefaultWorkspaceGroup(groups[it].tag) },
                    )
                }
            }
        }
        item { SmallTitle(text = "分组状态") }
        if (workspace.groups.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text("前往代理页添加本地组或订阅组", modifier = Modifier.padding(16.dp))
                }
            }
        }
        items(workspace.groups, key = { it.tag }) { group ->
            val recovery = fallback[group.tag]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OverviewValue(group.tag, active[group.tag] ?: "等待连接")
                    Text(
                        when {
                            recovery?.inFallback == true && recovery.nextRecoveryAtElapsedMs > 0 ->
                                "备用运行 · ${((recovery.nextRecoveryAtElapsedMs - now).coerceAtLeast(0) + 999) / 1000} 秒后检查主节点"
                            recovery?.inFallback == true -> "正在选择可用备用"
                            else -> "${strategyShortLabel(group.strategy)} · ${group.effectiveMembers().size} 个成员"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item {
            Text(
                "节点选择、订阅与分组编辑位于代理页",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun OverviewValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
        Text(value, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
    }
}
