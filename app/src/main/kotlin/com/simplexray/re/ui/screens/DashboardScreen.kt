package com.simplexray.re.ui.screens

import android.os.SystemClock
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.simplexray.re.R
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.formatBytes
import com.simplexray.re.common.formatUptime
import com.simplexray.re.service.RelayConnectionRegistry
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
    val backendMode = mainViewModel.prefs.tunnelMode.displayName
    val state by VpnStateHub.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var lastUplink by remember { mutableLongStateOf(0L) }
    var lastDownlink by remember { mutableLongStateOf(0L) }
    var uploadSpeedRate by remember { mutableLongStateOf(0L) }
    var downloadSpeedRate by remember { mutableLongStateOf(0L) }
    var lastSampleTimestamp by remember { mutableLongStateOf(0L) }
    var liveConnectionCount by remember { mutableLongStateOf(0L) }

    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                val currentElapsed = SystemClock.elapsedRealtime()
                now = currentElapsed
                mainViewModel.updateCoreStats()
                liveConnectionCount = RelayConnectionRegistry.all().size.toLong()

                val deltaMs = (currentElapsed - lastSampleTimestamp).coerceAtLeast(1L)
                if (lastSampleTimestamp > 0L && deltaMs in 500L..3000L) {
                    val deltaUp = (stats.uplink - lastUplink).coerceAtLeast(0L)
                    val deltaDown = (stats.downlink - lastDownlink).coerceAtLeast(0L)
                    uploadSpeedRate = (deltaUp * 1000L) / deltaMs
                    downloadSpeedRate = (deltaDown * 1000L) / deltaMs
                }
                lastUplink = stats.uplink
                lastDownlink = stats.downlink
                lastSampleTimestamp = currentElapsed
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
            HeroServiceCard(
                state = state,
                uploadSpeedRate = uploadSpeedRate,
                downloadSpeedRate = downloadSpeedRate,
                onSwitchVpnService = onSwitchVpnService,
                enabled = enabled
            )
        }

        item {
            TrafficStatsGrid(
                uplinkTotal = stats.uplink,
                downlinkTotal = stats.downlink,
                activeConnections = liveConnectionCount,
                memoryAlloc = stats.alloc
            )
        }
        item {
            Button(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://board.zash.run.place")))
            }, modifier = Modifier.fillMaxWidth()) { Text("zashboard · 实时连接与日志") }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val defaultTag = workspace.effectiveDefaultGroupTag() ?: "Default"
                    val activeLeaf = active[defaultTag] ?: "Direct"
                    val profileName = workspace.effectiveDefaultGroupTag() ?: "等待添加节点组"

                    OverviewValue(stringResource(R.string.overview_active_profile), profileName)
                    OverviewValue(stringResource(R.string.overview_current_proxy), "$defaultTag → $activeLeaf")
                    OverviewValue(stringResource(R.string.stats_uptime), formatUptime(stats.uptime))
                    OverviewValue("传输引擎", "Xray-core ($backendMode)")
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.workspace_routing_mode_title),
                        items = listOf(
                            stringResource(R.string.outbound_mode_rule),
                            stringResource(R.string.outbound_mode_global),
                            stringResource(R.string.outbound_mode_direct),
                        ),
                        selectedIndex = listOf("rule", "global", "direct").indexOf(workspace.routingMode).coerceAtLeast(0),
                        onSelectedIndexChange = { mainViewModel.setRoutingMode(listOf("rule", "global", "direct")[it]) },
                    )
                    val groups = workspace.groups
                    if (groups.isNotEmpty()) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.proxies_set_default),
                            items = groups.map { it.tag },
                            selectedIndex = groups.indexOfFirst { it.tag == workspace.effectiveDefaultGroupTag() }.coerceAtLeast(0),
                            onSelectedIndexChange = { mainViewModel.setDefaultWorkspaceGroup(groups[it].tag) },
                        )
                    }
                }
            }
        }

        item { SmallTitle(text = "出站分组运行概览") }
        if (workspace.groups.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text("进入代理页面添加或刷新分组", modifier = Modifier.padding(16.dp))
                }
            }
        }
        items(workspace.groups, key = { it.tag }) { group ->
            val recovery = fallback[group.tag]
            val activeNode = active[group.tag] ?: "等待连接"
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(group.tag, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                dashboardStrategyLabel(group.strategy),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        text = "生效节点: $activeNode",
                        style = MiuixTheme.textStyles.body1,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        when {
                            recovery?.inFallback == true && recovery.nextRecoveryAtElapsedMs > 0 ->
                                "备用运行中 · ${((recovery.nextRecoveryAtElapsedMs - now).coerceAtLeast(0) + 999) / 1000} 秒后检查主节点"
                            recovery?.inFallback == true -> "正在选择可用备用节点"
                            else -> "${group.effectiveMembers().size} 个候选节点"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroServiceCard(
    state: VpnRunningState,
    uploadSpeedRate: Long,
    downloadSpeedRate: Long,
    onSwitchVpnService: () -> Unit,
    enabled: Boolean
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.overview_service_title), style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
                    val statusText = when (state) {
                        VpnRunningState.Connected -> stringResource(R.string.vpn_status_connected)
                        VpnRunningState.Connecting -> "正在连接中"
                        is VpnRunningState.Failed -> (state as VpnRunningState.Failed).message ?: "服务异常"
                        else -> stringResource(R.string.vpn_status_stopped)
                    }
                    val statusColor = when (state) {
                        VpnRunningState.Connected -> Color(0xFF2E7D32)
                        VpnRunningState.Connecting -> Color(0xFFE65100)
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(statusText, style = MiuixTheme.textStyles.body2, color = statusColor)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SpeedRateItem(label = "↑", rate = "${formatBytes(uploadSpeedRate)}/s")
                    SpeedRateItem(label = "↓", rate = "${formatBytes(downloadSpeedRate)}/s")
                }
            }

            Button(
                onClick = onSwitchVpnService,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    text = if (enabled) stringResource(R.string.vpn_stop) else stringResource(R.string.vpn_start),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SpeedRateItem(label: String, rate: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(rate, style = MiuixTheme.textStyles.body1, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TrafficStatsGrid(
    uplinkTotal: Long,
    downlinkTotal: Long,
    activeConnections: Long,
    memoryAlloc: Long
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.traffic_total_up), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(formatBytes(uplinkTotal), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.traffic_total_down), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(formatBytes(downlinkTotal), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        Card(Modifier.weight(1f)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.connections_title), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("$activeConnections", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.stats_alloc), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(formatBytes(memoryAlloc), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun OverviewValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium)
    }
}

private fun dashboardStrategyLabel(strategy: GroupStrategy): String = when (strategy) {
    GroupStrategy.FALLBACK -> "Fallback 自动备用"
    GroupStrategy.URL_TEST -> "URLTest 最快响应"
    GroupStrategy.SELECT -> "Select 手动选择"
}
