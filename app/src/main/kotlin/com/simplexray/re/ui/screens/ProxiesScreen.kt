package com.simplexray.re.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.service.FallbackRuntimeInfo
import com.simplexray.re.service.GroupRuntimeStatus
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProxiesScreen(
    mainViewModel: MainViewModel,
    paddingValues: PaddingValues,
) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val active by mainViewModel.activeGroupNodes.collectAsStateWithLifecycle()
    val fallbackStates by mainViewModel.fallbackRuntimeStates.collectAsStateWithLifecycle()
    val runtimeLatencies by mainViewModel.runtimeNodeLatencies.collectAsStateWithLifecycle()
    val runtimeMeasurements by GroupRuntimeStatus.nodeMeasurements.collectAsStateWithLifecycle()
    val measuredLatencies by mainViewModel.proxyLatency.collectAsStateWithLifecycle()
    val measuringTargets by mainViewModel.measuringProxyTargets.collectAsStateWithLifecycle()
    val running by mainViewModel.isServiceEnabled.collectAsStateWithLifecycle()
    var expandedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val searchFocus = remember { FocusRequester() }
    val query = searchText.trim()

    LaunchedEffect(searching) {
        if (searching) listState.scrollToItem(0)
    }

    LaunchedEffect(mainViewModel.proxyPageAction) {
        when (mainViewModel.proxyPageAction) {
            "search" -> {
                searching = !searching
                if (searching == false) searchText = ""
            }
            "expand" -> expandedGroups = workspace.groups.map { it.tag }
            "collapse" -> expandedGroups = emptyList()
            "measure" -> {
                if (measuringTargets.isEmpty()) {
                    val target = workspace.groupByTag(mainViewModel.selectedProxyGroupTag)
                        ?: workspace.groups.firstOrNull()
                    target?.let { mainViewModel.measureGroupLatency(it.tag) }
                } else {
                    mainViewModel.showSnackbar("正在测量，请等待本组完成")
                }
            }
        }
        mainViewModel.proxyPageAction = null
    }

    val visibleGroups = workspace.groups.filter { group ->
        query.isEmpty() || group.tag.contains(query, ignoreCase = true) ||
            group.effectiveMembers().any { member -> proxyMemberMatches(workspace, member, query) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
            top = paddingValues.calculateTopPadding() + 12.dp,
            bottom = paddingValues.calculateBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (searching) item {
            LaunchedEffect(Unit) { searchFocus.requestFocus() }
            TextField(value = searchText, onValueChange = {
                searchText = it
                if (it.isNotBlank()) expandedGroups = workspace.groups.map { group -> group.tag }
            }, label = "搜索分组或节点", singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                trailingIcon = {
                    IconButton(onClick = { searchText = ""; searching = false }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                    }
                })
        }
        if (workspace.groups.isEmpty()) item {
            Text("在配置页添加分组后，这里显示运行代理。", modifier = Modifier.padding(vertical = 24.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else if (visibleGroups.isEmpty()) item {
            Text("搜索结果为空", modifier = Modifier.padding(vertical = 24.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        items(visibleGroups, key = { it.tag }) { group ->
            val members = group.effectiveMembers()
            val current = proxyCurrentMember(workspace, group, running, active, fallbackStates[group.tag])
            val leaf = active[group.tag].takeIf { running }
            val summary = when {
                running == false -> current?.tag ?: "待选择"
                leaf == null -> "等待连接"
                current != null && current.tag != leaf -> "${current.tag} → $leaf"
                else -> leaf
            }
            val expanded = group.tag in expandedGroups
            val visibleMembers = if (query.isEmpty() || group.tag.contains(query, ignoreCase = true)) {
                members
            } else members.filter { proxyMemberMatches(workspace, it, query) }
            Card(Modifier.fillMaxWidth(), cornerRadius = 30.dp,
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                )) {
                Row(Modifier.fillMaxWidth().clickable {
                    mainViewModel.selectedProxyGroupTag = group.tag
                    expandedGroups = if (expanded) expandedGroups - group.tag else expandedGroups + group.tag
                }.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(group.tag, style = MiuixTheme.textStyles.title3,
                            fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(summary, style = MiuixTheme.textStyles.body2, fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Text(proxyStrategyLabel(group.strategy), style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Box(Modifier.size(32.dp).clip(CircleShape).background(MiuixTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center) {
                        Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "${if (expanded) "收起" else "展开"} ${group.tag}",
                            modifier = Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onBackground)
                    }
                }
                if (expanded) {
                    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${members.size} 个成员 · HTTP 延迟", modifier = Modifier.weight(1f),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            TextButton(text = if (members.any { it in measuringTargets }) "测量中…" else "测速",
                                enabled = measuringTargets.isEmpty(), onClick = {
                                    mainViewModel.selectedProxyGroupTag = group.tag
                                    mainViewModel.measureGroupLatency(group.tag)
                                })
                        }
                        if (members.isEmpty()) Text("组内成员为空", modifier = Modifier.padding(8.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        visibleMembers.forEach { member ->
                            val node = if (member.isGroup) null else workspace.nodeByTag(member.tag)
                            val measured = measuredLatencies[member]
                            val liveTag = if (member.isGroup) active[member.tag] else member.tag
                            val live = if (running) liveTag?.let { runtimeMeasurements[it] } else null
                            val runtimeDelay = if (running) liveTag?.let { runtimeLatencies[it] } else null
                            val useLive = live != null && (measured == null || live.completedAtMs >= measured.lastTryTime)
                            val delay = when {
                                useLive -> live?.delayMs
                                measured != null -> measured.delayMs.takeIf { measured.alive }
                                else -> runtimeDelay
                            }
                            val hasResult = live != null || measured != null || runtimeDelay != null
                            val measuring = member in measuringTargets
                            val delayText = when {
                                measuring -> "测量中…"
                                delay != null -> "$delay ms"
                                hasResult -> "失败"
                                else -> "待测"
                            }
                            val selected = current == member
                            val description = if (member.isGroup) {
                                if (running) "节点组 · ${active[member.tag] ?: "等待连接"}" else "节点组"
                            } else node?.protocol?.uppercase() ?: when (member.tag) {
                                "direct" -> "直连"
                                "block" -> "拒绝"
                                else -> "节点"
                            }
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .then(if (selected) Modifier.background(MiuixTheme.colorScheme.secondaryVariant) else Modifier)
                                .clickable {
                                    mainViewModel.selectedProxyGroupTag = group.tag
                                    mainViewModel.selectGroupMember(group.tag, member)
                                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(member.tag, style = MiuixTheme.textStyles.body1, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    val position = members.indexOf(member)
                                    val role = if (group.strategy == GroupStrategy.FALLBACK) {
                                        if (position == 0) " · 主节点" else " · 备用 $position"
                                    } else ""
                                    Text(description + role, style = MiuixTheme.textStyles.body2, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Text(delayText, style = MiuixTheme.textStyles.body2,
                                    color = when {
                                        measuring -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        delay != null -> MiuixTheme.colorScheme.primary
                                        hasResult -> MiuixTheme.colorScheme.error
                                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    })
                                if (selected) Icon(Icons.Default.Check, modifier = Modifier.size(20.dp),
                                    contentDescription = if (running) "当前成员" else "已保存的选择",
                                    tint = MiuixTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun proxyStrategyLabel(strategy: GroupStrategy): String = when (strategy) {
    GroupStrategy.SELECT -> "Selector"
    GroupStrategy.FALLBACK -> "Fallback"
    GroupStrategy.URL_TEST -> "URLTest"
}

private fun proxyMemberMatches(workspace: GroupWorkspace, member: GroupMemberRef, query: String): Boolean =
    member.tag.contains(query, ignoreCase = true) ||
        (member.isGroup == false && workspace.nodeByTag(member.tag)?.protocol?.contains(query, ignoreCase = true) == true)

private fun proxyCurrentMember(
    workspace: GroupWorkspace,
    group: ProxyGroup,
    running: Boolean,
    active: Map<String, String>,
    fallback: FallbackRuntimeInfo?,
): GroupMemberRef? {
    val members = group.effectiveMembers()
    if (running == false && group.strategy == GroupStrategy.FALLBACK) return members.firstOrNull()
    if (running == false || group.strategy == GroupStrategy.SELECT) {
        return group.selectedMember ?: members.firstOrNull()
    }
    val leaf = active[group.tag] ?: return null
    val candidates = if (group.strategy == GroupStrategy.FALLBACK) {
        if (fallback?.inFallback == true) members.drop(1) else members.take(1)
    } else members
    return candidates.firstOrNull { proxyMemberContainsLeaf(workspace, it, leaf) }
}

private fun proxyMemberContainsLeaf(workspace: GroupWorkspace, member: GroupMemberRef, leaf: String): Boolean =
    if (member.isGroup) workspace.groupByTag(member.tag)?.effectiveMembers()
        ?.any { proxyMemberContainsLeaf(workspace, it, leaf) } == true
    else member.tag == leaf

@Composable
internal fun GroupChainSheet(
    workspace: GroupWorkspace,
    group: ProxyGroup,
    onSave: (String?, ProxyGroup) -> String?,
    onDismiss: () -> Unit,
) {
    val choices = listOf<GroupMemberRef?>(null) +
        workspace.groups.filter { it.tag != group.tag }.map { GroupMemberRef(it.tag, true) } +
        workspace.allNodes().filter { candidate -> group.nodes.none { it.tag == candidate.tag } }.map { GroupMemberRef(it.tag) }
    var selected by remember(group) { mutableStateOf(group.frontProxy) }
    var error by remember { mutableStateOf<String?>(null) }
    GlassBottomSheet(show = true, backdrop = LocalLayerBackdrop.current, title = "链式代理", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("本机 → 前置代理 → 本组节点 → 目标", style = MiuixTheme.textStyles.body2)
            Card {
                OverlayDropdownPreference(title = "前置代理",
                    items = choices.map { ref -> ref?.let { "${it.tag} · ${if (it.isGroup) "组" else "节点"}" } ?: "直接连接" },
                    selectedIndex = choices.indexOf(selected).coerceAtLeast(0),
                    onSelectedIndexChange = { selected = choices[it] })
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            Button(onClick = {
                error = onSave(group.tag, group.copy(frontProxy = selected))
                if (error == null) onDismiss()
            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存") }
        }
    }
}
