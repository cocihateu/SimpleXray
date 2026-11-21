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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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
    val exitInfo by mainViewModel.exitInfo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultGroup = workspace.effectiveDefaultGroupTag()
    val exitNode = defaultGroup?.let(active::get)
    LaunchedEffect(state, defaultGroup, exitNode, workspace.routingMode) {
        if (state != VpnRunningState.Connecting) mainViewModel.refreshExitInfo()
    }

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
            RoutingModeCard(workspace.routingMode, mainViewModel::setRoutingMode)
        }
        item {
            HeroServiceCard(
                state = state,
                backendMode = backendMode,
                uptime = stats.uptime,
                activeConnections = liveConnectionCount,
                onSwitchVpnService = onSwitchVpnService,
                enabled = enabled
            )
        }

        item {
            TrafficStatsCard(
                uploadSpeedRate = uploadSpeedRate,
                downloadSpeedRate = downloadSpeedRate,
                uplinkTotal = stats.uplink,
                downlinkTotal = stats.downlink
            )
        }
        item {
            MemoryStatsCard(stats.appMemoryBytes, stats.coreMemoryBytes)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("出口信息", style = MiuixTheme.textStyles.title3)
                            Text(if (state == VpnRunningState.Connected && workspace.routingMode != "direct")
                                "${defaultGroup.orEmpty()} → ${exitNode ?: "等待节点"}" else "直连出口",
                                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        TextButton(text = "刷新", enabled = exitInfo.loading == false && state != VpnRunningState.Connecting,
                            onClick = mainViewModel::refreshExitInfo)
                    }
                    if (exitInfo.loading) {
                        Text("正在获取出口 IP 与网络信息…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    } else if (exitInfo.error.isNotBlank()) {
                        Text(exitInfo.error, color = MiuixTheme.colorScheme.error)
                    } else {
                        Text(exitInfo.ip.ifBlank { "等待查询" }, style = MiuixTheme.textStyles.title2,
                            fontFamily = FontFamily.Monospace)
                        val flag = exitInfo.countryCode.uppercase().takeIf { it.length == 2 }?.let { code ->
                            code.map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("")
                        }.orEmpty()
                        OverviewValue("国家 / 地区", "$flag ${exitInfo.country}".trim())
                        OverviewValue("城市", listOf(exitInfo.region, exitInfo.city).filter { it.isNotBlank() }.distinct().joinToString(" · "))
                        OverviewValue("AS", exitInfo.asn.takeIf { it.isNotBlank() }?.let { "AS$it" } ?: "—")
                        OverviewValue("网络机构", exitInfo.organization.ifBlank { "—" })
                        OverviewValue("运营商", exitInfo.isp.ifBlank { "—" })
                    }
                }
            }
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
                }
            }
        }

        if (workspace.groups.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val groups = workspace.groups
                    OverlayDropdownPreference(
                        title = stringResource(R.string.proxies_set_default),
                        items = groups.map { it.tag },
                        selectedIndex = groups.indexOfFirst { it.tag == workspace.effectiveDefaultGroupTag() }.coerceAtLeast(0),
                        onSelectedIndexChange = { mainViewModel.setDefaultWorkspaceGroup(groups[it].tag) },
                    )
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
private fun RoutingModeCard(mode: String, onModeChange: (String) -> Unit) {
    val modes = listOf("rule", "global", "direct")
    val labels = listOf(
        stringResource(R.string.routing_mode_rule),
        stringResource(R.string.routing_mode_global),
        stringResource(R.string.routing_mode_direct),
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.workspace_routing_mode_title), style = MiuixTheme.textStyles.title3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEachIndexed { index, value ->
                    Button(
                        onClick = { onModeChange(value) },
                        modifier = Modifier.weight(1f),
                        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
                        colors = if (mode == value) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    ) { Text(labels[index], maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun HeroServiceCard(
    state: VpnRunningState,
    backendMode: String,
    uptime: Int,
    activeConnections: Long,
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
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.dashboard_status), style = MiuixTheme.textStyles.title3)
                        when (state) {
                            VpnRunningState.Connected -> Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = stringResource(R.string.dashboard_loaded),
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(28.dp),
                            )
                            VpnRunningState.Connecting -> CircularProgressIndicator(size = 22.dp)
                            is VpnRunningState.Failed -> Text(
                                stringResource(R.string.dashboard_failed),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                            )
                            else -> Text(
                                stringResource(R.string.vpn_status_stopped),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    Text(backendMode, style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Button(
                    onClick = onSwitchVpnService,
                    modifier = Modifier.size(64.dp),
                    cornerRadius = 32.dp,
                    minWidth = 64.dp,
                    minHeight = 64.dp,
                    insideMargin = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Icon(
                        painterResource(if (enabled) R.drawable.stop else R.drawable.play),
                        contentDescription = stringResource(if (enabled) R.string.vpn_stop else R.string.vpn_start),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            if (state is VpnRunningState.Failed) {
                state.message?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.stats_uptime), style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(if (state == VpnRunningState.Connected) formatUptime(uptime) else "—",
                        style = MiuixTheme.textStyles.body1, fontFamily = FontFamily.Monospace)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.connections_title), style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text("$activeConnections", style = MiuixTheme.textStyles.body1, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun SpeedMetric(label: String, rate: Long, total: Long, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text("${formatBytes(rate)}/s", style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.dashboard_traffic_total, formatBytes(total)), style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    }
}

@Composable
private fun TrafficStatsCard(
    uploadSpeedRate: Long,
    downloadSpeedRate: Long,
    uplinkTotal: Long,
    downlinkTotal: Long
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.dashboard_network_speed), style = MiuixTheme.textStyles.title3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpeedMetric(stringResource(R.string.dashboard_upload), uploadSpeedRate, uplinkTotal, Modifier.weight(1f))
                SpeedMetric(stringResource(R.string.dashboard_download), downloadSpeedRate, downlinkTotal, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MemoryStatsCard(appBytes: Long?, coreBytes: Long?) {
    val total = if (appBytes != null && coreBytes != null) appBytes + coreBytes else null
    val appColor = MiuixTheme.colorScheme.primary
    val coreColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.4f)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.dashboard_memory), style = MiuixTheme.textStyles.title3)
            Text(total?.let(::formatBytes) ?: "—", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.dashboard_memory_resident), style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
                if (total != null && total > 0L) {
                    if (appBytes != null && appBytes > 0L) {
                        Box(Modifier.weight((appBytes.toDouble() / total).toFloat()).height(10.dp).background(appColor))
                    }
                    if (coreBytes != null && coreBytes > 0L) {
                        Box(Modifier.weight((coreBytes.toDouble() / total).toFloat()).height(10.dp).background(coreColor))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MemoryLegend(stringResource(R.string.dashboard_memory_app), appBytes, appColor, Modifier.weight(1f))
                MemoryLegend(stringResource(R.string.dashboard_memory_core), coreBytes, coreColor, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MemoryLegend(label: String, bytes: Long?, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(bytes?.let(::formatBytes) ?: "—", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
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
