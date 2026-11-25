package com.simplexray.re.ui.screens

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.simplexray.re.R
import com.simplexray.re.common.formatUptime
import com.simplexray.re.service.GroupRuntimeStatus
import com.simplexray.re.service.VpnRunningState
import com.simplexray.re.service.VpnStateHub
import com.simplexray.re.ui.components.CardHeading
import com.simplexray.re.ui.components.DashboardCard
import com.simplexray.re.ui.components.StatusPill
import com.simplexray.re.viewmodel.EXIT_INFO_HOST
import com.simplexray.re.viewmodel.ExitInfoState
import com.simplexray.re.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
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
    val state by VpnStateHub.state.collectAsStateWithLifecycle()
    val exitInfo by mainViewModel.exitInfo.collectAsStateWithLifecycle()
    val appliedRouting by GroupRuntimeStatus.appliedRouting.collectAsStateWithLifecycle()
    val exitNode = appliedRouting?.defaultGroupTag?.let(active::get)
    LaunchedEffect(state, appliedRouting, exitNode) {
        if (state != VpnRunningState.Connecting) mainViewModel.refreshExitInfo()
    }

    var lastUplink by remember { mutableLongStateOf(0L) }
    var lastDownlink by remember { mutableLongStateOf(0L) }
    var uploadSpeedRate by remember { mutableLongStateOf(0L) }
    var downloadSpeedRate by remember { mutableLongStateOf(0L) }
    var lastSampleTimestamp by remember { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                val currentElapsed = SystemClock.elapsedRealtime()
                mainViewModel.updateCoreStats()
                val deltaMs = (currentElapsed - lastSampleTimestamp).coerceAtLeast(1L)
                if (lastSampleTimestamp > 0L && deltaMs in 500L..3000L) {
                    uploadSpeedRate = (stats.uplink - lastUplink).coerceAtLeast(0L) * 1000L / deltaMs
                    downloadSpeedRate = (stats.downlink - lastDownlink).coerceAtLeast(0L) * 1000L / deltaMs
                }
                lastUplink = stats.uplink
                lastDownlink = stats.downlink
                lastSampleTimestamp = currentElapsed
                delay(1_000)
            }
        }
    }
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = maxOf(paddingValues.calculateTopPadding(), safe.calculateTopPadding()) + 12.dp,
            bottom = maxOf(paddingValues.calculateBottomPadding(), safe.calculateBottomPadding()) + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { RoutingModeCard(workspace.routingMode, mainViewModel::setRoutingMode) }
        item {
            StatusCard(
                state = state,
                exitInfo = exitInfo,
                uptime = stats.uptime,
                onSwitchVpnService = onSwitchVpnService,
                onRefreshExit = mainViewModel::refreshExitInfo,
            )
        }
        item { TrafficStatsCard(uploadSpeedRate, downloadSpeedRate, stats.uplink, stats.downlink) }
        item { MemoryStatsCard(stats.appMemoryBytes, stats.coreMemoryBytes) }
    }
}

@Composable
private fun RoutingModeCard(mode: String, onModeChange: (String) -> Unit) {
    val modes = listOf("rule", "global", "direct")
    val labels = listOf(R.string.routing_mode_rule, R.string.routing_mode_global, R.string.routing_mode_direct)
    val icons = listOf(R.drawable.dashboard_rule, R.drawable.dashboard_global, R.drawable.dashboard_direct)
    DashboardCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardHeading(R.drawable.dashboard_rule, stringResource(R.string.dashboard_routing_mode))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modes.forEachIndexed { index, value ->
                    Button(
                        onClick = { onModeChange(value) },
                        modifier = Modifier.weight(1f),
                        cornerRadius = 24.dp,
                        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        colors = if (mode == value) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(painterResource(icons[index]), contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(stringResource(labels[index]), maxLines = 1, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: VpnRunningState,
    exitInfo: ExitInfoState,
    uptime: Int,
    onSwitchVpnService: () -> Unit,
    onRefreshExit: () -> Unit,
) {
    val enabled = state == VpnRunningState.Connected || state == VpnRunningState.Connecting
    val accent = MiuixTheme.colorScheme.primary
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val ornament = MiuixTheme.colorScheme.surface
    val exitText = when {
        exitInfo.loading -> stringResource(R.string.dashboard_exit_loading)
        exitInfo.error.isNotBlank() -> stringResource(R.string.dashboard_exit_retry)
        else -> exitInfo.ip.ifBlank { stringResource(R.string.dashboard_exit_waiting) }
    }
    val location = listOf(exitInfo.country, exitInfo.city).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    DashboardCard {
        Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                drawPath(Path().apply {
                    moveTo(w * 0.78f, 0f)
                    cubicTo(w * 0.89f, h * 0.22f, w * 0.65f, h * 0.24f, w * 0.76f, h * 0.46f)
                    cubicTo(w * 0.94f, h * 0.68f, w * 0.74f, h * 0.94f, w, h * 0.88f)
                    lineTo(w, 0f)
                    close()
                }, ornament)
            }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CardHeading(R.drawable.dashboard_status, stringResource(R.string.dashboard_status))
                    Spacer(Modifier.width(10.dp))
                    when (state) {
                        VpnRunningState.Connecting -> CircularProgressIndicator(size = 18.dp)
                        is VpnRunningState.Failed -> StatusPill(stringResource(R.string.dashboard_failed))
                        else -> Unit
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onRefreshExit, enabled = exitInfo.loading == false && state != VpnRunningState.Connecting) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dashboard_exit_refresh),
                            tint = muted, modifier = Modifier.size(20.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CountryBadge(exitInfo.countryCode)
                            Text(exitText, modifier = Modifier.weight(1f),
                                autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 20.sp, stepSize = 1.sp),
                                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (exitInfo.ip.isNotBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusPill(if (exitInfo.routedLookup) EXIT_INFO_HOST else stringResource(R.string.dashboard_direct_exit))
                                if (location.isNotBlank()) StatusPill(location, Modifier.weight(1f, fill = false))
                            }
                        }
                    }
                    Button(
                        onClick = onSwitchVpnService,
                        modifier = Modifier.size(78.dp).shadow(10.dp, CircleShape, ambientColor = accent.copy(alpha = 0.2f),
                            spotColor = accent.copy(alpha = 0.25f)),
                        cornerRadius = 39.dp,
                        minWidth = 78.dp,
                        minHeight = 78.dp,
                        insideMargin = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Icon(painterResource(if (enabled) R.drawable.stop else R.drawable.dashboard_play),
                            contentDescription = stringResource(if (enabled) R.string.vpn_stop else R.string.vpn_start),
                            modifier = Modifier.size(32.dp))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.width(82.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.dashboard_uptime), fontSize = 12.sp, color = muted)
                        Text(if (state == VpnRunningState.Connected) formatUptime(uptime) else "—", fontSize = 15.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.dashboard_organization), fontSize = 12.sp, color = muted)
                        Text(exitInfo.organization.ifBlank { exitInfo.isp }.ifBlank { "—" }, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state is VpnRunningState.Failed) {
                    state.message?.let { Text(it, color = MiuixTheme.colorScheme.error, fontSize = 12.sp) }
                }
                if (exitInfo.error.isNotBlank()) {
                    Text(exitInfo.error, color = MiuixTheme.colorScheme.error, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun TrafficStatsCard(uploadRate: Long, downloadRate: Long, uplinkTotal: Long, downlinkTotal: Long) {
    DashboardCard {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardHeading(R.drawable.dashboard_speed, stringResource(R.string.dashboard_network_speed))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpeedMetric(R.drawable.dashboard_upload, stringResource(R.string.dashboard_upload),
                    uploadRate, uplinkTotal, MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.weight(1f))
                SpeedMetric(R.drawable.dashboard_download, stringResource(R.string.dashboard_download),
                    downloadRate, downlinkTotal, MiuixTheme.colorScheme.primary, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SpeedMetric(@DrawableRes icon: Int, label: String, rate: Long, total: Long, tint: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(MiuixTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
            Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        MetricNumber(rate)
        val formatted = dashboardBytes(total)
        Text(stringResource(R.string.dashboard_traffic_total, "${formatted.first} ${formatted.second}"),
            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    }
}

@Composable
private fun MemoryStatsCard(appBytes: Long?, coreBytes: Long?) {
    val total = if (appBytes != null && coreBytes != null) appBytes + coreBytes else null
    val appColor = MiuixTheme.colorScheme.primary
    val coreColor = appColor.copy(alpha = 0.4f)
    val formattedTotal = dashboardBytes(total)
    DashboardCard {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CardHeading(R.drawable.dashboard_memory, stringResource(R.string.dashboard_memory), Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formattedTotal.first, modifier = Modifier.alignByBaseline(), fontSize = 26.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1)
                    if (formattedTotal.second.isNotEmpty()) {
                        Text(formattedTotal.second, modifier = Modifier.alignByBaseline(), fontSize = 15.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(9.dp).clip(CircleShape).background(MiuixTheme.colorScheme.surface)) {
                if (total != null && total > 0L) {
                    if (appBytes != null && appBytes > 0L) Box(Modifier.weight((appBytes.toDouble() / total).toFloat())
                        .height(9.dp).background(appColor))
                    if (coreBytes != null && coreBytes > 0L) Box(Modifier.weight((coreBytes.toDouble() / total).toFloat())
                        .height(9.dp).background(coreColor))
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
private fun MemoryLegend(label: String, bytes: Long?, color: Color, modifier: Modifier) {
    val formatted = dashboardBytes(bytes)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text("$label ${formatted.first} ${formatted.second}".trim(), fontSize = 14.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun MetricNumber(bytes: Long?) {
    val formatted = dashboardBytes(bytes)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(formatted.first, modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
            autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 26.sp, stepSize = 1.sp),
            fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatted.second + "/s", modifier = Modifier.alignByBaseline(), fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1)
    }
}

@Composable
private fun CountryBadge(countryCode: String) {
    val background = MiuixTheme.colorScheme.surfaceContainerHigh
    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.width * 0.3f
            for (x in listOf(0.3f, 0.7f)) {
                for (y in listOf(0.3f, 0.7f)) {
                    drawCircle(background, radius, Offset(size.width * x, size.height * y))
                }
            }
        }
        Text(countryCode.uppercase().ifBlank { "—" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

private fun dashboardBytes(bytes: Long?): Pair<String, String> {
    if (bytes == null) return "—" to ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var amount = bytes.toDouble()
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) {
        amount /= 1024
        unit++
    }
    return (if (unit == 0) bytes.toString() else "%.1f".format(amount)) to units[unit]
}
