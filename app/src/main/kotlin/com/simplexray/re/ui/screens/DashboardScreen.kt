package com.simplexray.re.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.simplexray.re.R
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.common.config.ProxyNode
import com.simplexray.re.common.formatBytes
import com.simplexray.re.common.formatNumber
import com.simplexray.re.common.formatUptime
import com.simplexray.re.service.FallbackRuntimeInfo
import com.simplexray.re.viewmodel.MainViewModel
import com.simplexray.re.viewmodel.OutboundLatency
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val LatencyStaleSeconds = 60L

@Composable
fun DashboardScreen(
    mainViewModel: MainViewModel,
    onSwitchVpnService: () -> Unit = {},
    paddingValues: PaddingValues = PaddingValues()
) {
    val coreStats by mainViewModel.coreStatsState.collectAsStateWithLifecycle()
    val outboundLoadError by mainViewModel.outboundLoadError.collectAsStateWithLifecycle()
    val outboundLatency by mainViewModel.outboundLatency.collectAsStateWithLifecycle()
    val runtimeLatencies by mainViewModel.runtimeNodeLatencies.collectAsStateWithLifecycle()
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val activeGroupNodes by mainViewModel.activeGroupNodes.collectAsStateWithLifecycle()
    val fallbackStates by mainViewModel.fallbackRuntimeStates.collectAsStateWithLifecycle()
    val isServiceEnabled by mainViewModel.isServiceEnabled.collectAsStateWithLifecycle()
    val allNodes = remember(workspace) { workspace.allNodes() }
    var showGroupEditor by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<ProxyGroup?>(null) }
    var elapsedNowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                while (isActive) {
                    elapsedNowMs = SystemClock.elapsedRealtime()
                    mainViewModel.updateCoreStats()
                    delay(1000)
                }
            }
        }
    }

    val isWideScreen = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width >= 600.dp.roundToPx()
    }
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    val topPadding = paddingValues.calculateTopPadding()
        .coerceAtLeast(safePadding.calculateTopPadding()) + 12.dp
    val bottomPadding = paddingValues.calculateBottomPadding()
        .coerceAtLeast(safePadding.calculateBottomPadding() + 12.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isWideScreen) Modifier.widthIn(max = 840.dp) else Modifier
                ),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topPadding, bottom = bottomPadding)
        ) {
            outboundLoadError?.let { message ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(16.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
            item {
                SmallTitle(text = stringResource(id = R.string.traffic_info))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        StatRow(
                            label = stringResource(id = R.string.stats_uplink),
                            value = formatBytes(coreStats.uplink)
                        )
                        StatRow(
                            label = stringResource(id = R.string.stats_downlink),
                            value = formatBytes(coreStats.downlink)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                SmallTitle(text = stringResource(id = R.string.core_runtime_status))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        StatRow(
                            label = stringResource(id = R.string.stats_num_goroutine),
                            value = formatNumber(coreStats.numGoroutine.toLong())
                        )
                        StatRow(
                            label = stringResource(id = R.string.stats_num_gc),
                            value = formatNumber(coreStats.numGC.toLong())
                        )
                        StatRow(
                            label = stringResource(id = R.string.stats_alloc),
                            value = formatBytes(coreStats.alloc)
                        )
                        StatRow(
                            label = stringResource(id = R.string.stats_uptime),
                            value = formatUptime(coreStats.uptime)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                SmallTitle(text = stringResource(id = R.string.core_control))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(color = Color.Transparent),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Button(
                        onClick = onSwitchVpnService,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = if (isServiceEnabled) ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.error,
                            contentColor = MiuixTheme.colorScheme.onError
                        ) else ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isServiceEnabled) R.drawable.pause else R.drawable.play
                            ),
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = if (isServiceEnabled) stringResource(R.string.cancel) else stringResource(R.string.app_name),
                            fontSize = MiuixTheme.textStyles.body1.fontSize
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmallTitle(
                        text = stringResource(R.string.outbound_groups),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            editingGroup = null
                            showGroupEditor = true
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.outbound_group_create),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (workspace.groups.isEmpty() && outboundLoadError == null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.outbound_group_empty),
                            modifier = Modifier.padding(16.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            items(workspace.groups, key = { "outbound_group:${it.tag}" }) { group ->
                DashboardGroupCard(
                    group = group,
                    activeLeaf = activeGroupNodes[group.tag],
                    fallbackInfo = fallbackStates[group.tag],
                    elapsedNowMs = elapsedNowMs,
                    onEdit = {
                        editingGroup = group
                        showGroupEditor = true
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            item {
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (allNodes.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallTitle(
                            text = stringResource(id = R.string.outbound_nodes),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { mainViewModel.refreshLatency() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh_latency),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(allNodes, key = { "outbound_node:${it.tag}" }) { node ->
                    DashboardNodeCard(
                        node = node,
                        runtimeLatencyMs = runtimeLatencies[node.tag],
                        latency = outboundLatency[node.tag],
                        onTargetStrategyChange = { strategy ->
                            mainViewModel.updateNodeTargetStrategy(node.tag, strategy)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }

    if (showGroupEditor) {
        WorkspaceGroupEditorSheet(
            workspace = workspace,
            editingGroup = editingGroup,
            onSave = { originalTag, edited -> mainViewModel.saveWorkspaceGroup(originalTag, edited) },
            onDelete = { tag -> mainViewModel.deleteWorkspaceGroup(tag) },
            onDismiss = { showGroupEditor = false }
        )
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            color = MiuixTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DashboardGroupCard(
    group: ProxyGroup,
    activeLeaf: String?,
    fallbackInfo: FallbackRuntimeInfo?,
    elapsedNowMs: Long,
    onEdit: () -> Unit
) {
    val members = group.effectiveMembers()
    val primaryLabel = members.firstOrNull()?.let { workspaceMemberLabel(it) }
        ?: stringResource(R.string.outbound_group_live_idle)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${group.tag} (${strategyShortLabel(group.strategy)})",
                    style = MiuixTheme.textStyles.title4,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit) {
                    Icon(
                        painter = painterResource(R.drawable.edit),
                        contentDescription = stringResource(R.string.outbound_group_edit, group.tag),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            OutboundGroupDetail(
                label = stringResource(R.string.outbound_group_live_leaf),
                value = activeLeaf ?: stringResource(R.string.outbound_group_live_idle)
            )
            if (fallbackInfo != null && fallbackInfo.inFallback) {
                val remainSec = ((fallbackInfo.nextRecoveryAtElapsedMs - elapsedNowMs) / 1000L)
                    .coerceAtLeast(0L)
                Text(
                    text = stringResource(R.string.workspace_status_in_fallback, remainSec.toInt()),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            if (group.strategy == GroupStrategy.FALLBACK) {
                OutboundGroupDetail(
                    label = stringResource(R.string.outbound_group_primary),
                    value = primaryLabel
                )
                StatRow(
                    label = stringResource(R.string.workspace_failover_timeout_seconds),
                    value = "${group.failoverTimeoutSeconds}s"
                )
                StatRow(
                    label = stringResource(R.string.outbound_group_recovery_interval_seconds),
                    value = "${group.recoveryIntervalSeconds}s"
                )
            } else if (group.strategy == GroupStrategy.URL_TEST) {
                StatRow(
                    label = stringResource(R.string.outbound_group_interval_seconds),
                    value = "${group.intervalSeconds}s"
                )
                StatRow(
                    label = stringResource(R.string.outbound_group_trust_seconds),
                    value = "${group.trustSeconds}s"
                )
            }
        }
    }
}

@Composable
private fun OutboundGroupDetail(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DashboardNodeCard(
    node: ProxyNode,
    runtimeLatencyMs: Long?,
    latency: OutboundLatency?,
    onTargetStrategyChange: (String) -> Unit
) {
    val nowSeconds = System.currentTimeMillis() / 1000
    val stale = latency != null && nowSeconds - latency.lastTryTime > LatencyStaleSeconds
    val goodColor = MiuixTheme.colorScheme.primary
    val fairColor = lerp(goodColor, MiuixTheme.colorScheme.error, 0.5f)

    val effectiveMs = runtimeLatencyMs
        ?: latency?.takeIf { !stale && it.alive }?.delayMs

    val (text, color) = when {
        effectiveMs == null ->
            "-ms" to MiuixTheme.colorScheme.onSurfaceVariantSummary
        effectiveMs <= 100 ->
            "${effectiveMs}ms" to goodColor
        effectiveMs <= 300 ->
            "${effectiveMs}ms" to fairColor
        else ->
            "${effectiveMs}ms" to MiuixTheme.colorScheme.error
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.tag,
                        fontSize = MiuixTheme.textStyles.body1.fontSize,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = node.protocol,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1
                    )
                }
                Text(
                    text = text,
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    color = color,
                    fontFamily = FontFamily.Monospace
                )
            }
            OverlayDropdownPreference(
                title = stringResource(R.string.outbound_group_target_strategy),
                items = listOf(
                    stringResource(R.string.outbound_group_strategy_original),
                    stringResource(R.string.outbound_group_strategy_use_ipv4),
                    stringResource(R.string.outbound_group_strategy_force_ip),
                    stringResource(R.string.outbound_group_strategy_as_is),
                    stringResource(R.string.outbound_group_strategy_use_ip)
                ),
                selectedIndex = when (node.targetStrategy) {
                    "UseIPv4" -> 1
                    "ForceIP" -> 2
                    "AsIs" -> 3
                    "UseIP" -> 4
                    else -> 0
                },
                onSelectedIndexChange = { index ->
                    onTargetStrategyChange(
                        when (index) {
                            1 -> "UseIPv4"
                            2 -> "ForceIP"
                            3 -> "AsIs"
                            4 -> "UseIP"
                            else -> ""
                        }
                    )
                }
            )
        }
    }
}
