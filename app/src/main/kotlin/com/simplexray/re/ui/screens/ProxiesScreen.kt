package com.simplexray.re.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.common.config.ProxyNode
import com.simplexray.re.ui.components.ConfirmOverlayDialog
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop
import com.simplexray.re.viewmodel.MainViewModel
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ProxiesScreen(
    mainViewModel: MainViewModel,
    paddingValues: PaddingValues = PaddingValues(),
    onManageGroups: () -> Unit = {},
    onGroupSettings: (String) -> Unit = {},
) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val active by mainViewModel.activeGroupNodes.collectAsStateWithLifecycle()
    val latencies by mainViewModel.runtimeNodeLatencies.collectAsStateWithLifecycle()
    val measuredLatencies by mainViewModel.proxyLatency.collectAsStateWithLifecycle()
    val measuringTargets by mainViewModel.measuringProxyTargets.collectAsStateWithLifecycle()
    val refreshing by mainViewModel.refreshingGroups.collectAsStateWithLifecycle()
    val selectedTag = mainViewModel.selectedProxyGroupTag
    val group = workspace.groupByTag(selectedTag) ?: workspace.groupByTag(workspace.effectiveDefaultGroupTag().orEmpty())
    var editingNode by remember { mutableStateOf<ProxyNode?>(null) }
    var addingNode by remember { mutableStateOf(false) }
    var deletingNode by remember { mutableStateOf<String?>(null) }
    var creatingGroup by remember { mutableStateOf(false) }
    var importTarget by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importTarget?.let { target -> if (uri != null) mainViewModel.importFromUriToGroup(target, uri) }
    }
    LaunchedEffect(mainViewModel.proxyPageAction) {
        when (mainViewModel.proxyPageAction) {
            "group" -> creatingGroup = true
            "measure" -> group?.let { mainViewModel.measureGroupLatency(it.tag) }
        }
        mainViewModel.proxyPageAction = null
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = paddingValues.calculateTopPadding() + 12.dp,
            bottom = paddingValues.calculateBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                workspace.groups.forEach { item ->
                    Button(onClick = { mainViewModel.selectedProxyGroupTag = item.tag },
                        colors = if (item.tag == group?.tag) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                        Text(item.tag)
                    }
                }
            }
        }
        if (group == null) {
            item {
                Text("点右上角 + 新建基本或订阅分组。订阅组保存后自动拉取节点。",
                    modifier = Modifier.padding(vertical = 24.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        } else {
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(group.tag, style = MiuixTheme.textStyles.title3)
                            Text("${if (group.sourceType == GroupSourceType.LOCAL) "基本" else "订阅"} · ${group.nodes.size} 个节点 · ${strategyShortLabel(group.strategy)}",
                                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        OverlayIconDropdownMenu(entry = DropdownEntry(listOf(
                            DropdownItem("从剪贴板导入", onClick = { mainViewModel.importFromClipboardToGroup(group.tag) }),
                            DropdownItem("从文件导入", onClick = { importTarget = group.tag; filePicker.launch(arrayOf("*/*")) }),
                            DropdownItem("手动添加节点", onClick = { addingNode = true }),
                        ))) {
                            Icon(Icons.Default.Add, contentDescription = "导入节点到 ${group.tag}")
                        }
                        TextButton(text = "测速", onClick = { mainViewModel.measureGroupLatency(group.tag) },
                            enabled = measuringTargets.isEmpty())
                        TextButton(text = "设置", onClick = { onGroupSettings(group.tag) })
                    }
                    group.frontProxy?.let { Text("链路：本机 → ${it.tag} → 组内节点 → 目标", style = MiuixTheme.textStyles.body2) }
                    if (group.tag in refreshing) Text("正在更新订阅…", style = MiuixTheme.textStyles.body2)
                }
            }
            val members = (group.effectiveMembers() + group.nodes.map { GroupMemberRef(it.tag) }).distinct()
            items(members, key = { "${it.isGroup}:${it.tag}" }) { member ->
                val node = if (member.isGroup) null else workspace.nodeByTag(member.tag)
                val measured = measuredLatencies[member]
                val delayText = when {
                    member in measuringTargets -> "测量中"
                    measured != null -> if (measured.alive) "${measured.delayMs} ms" else "失败"
                    latencies[member.tag] != null -> "${latencies[member.tag]} ms"
                    else -> "待测"
                }
                val selected = active[group.tag]?.let { it == if (member.isGroup) active[member.tag] else member.tag }
                    ?: ((group.selectedMember ?: group.effectiveMembers().firstOrNull()) == member)
                Card(Modifier.fillMaxWidth(), onClick = { mainViewModel.selectGroupMember(group.tag, member) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(member.tag, style = MiuixTheme.textStyles.body1)
                            if (group.strategy == GroupStrategy.FALLBACK) {
                                val position = group.effectiveMembers().indexOf(member)
                                if (position >= 0) Text(if (position == 0) "主节点" else "备用 $position",
                                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary)
                            }
                            Text("${if (member.isGroup) "节点组" else node?.protocol.orEmpty().uppercase()} · $delayText",
                                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (node != null && node.udpOverTcpVersion > 0) {
                                Text("UDP over TCP v${node.udpOverTcpVersion}", style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            if (node != null) {
                                Text(nodeDnsStrategyLabel(node.targetStrategy),
                                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                if (node.obfsMode.isNotBlank()) Text("simple-obfs · ${node.obfsMode.uppercase()}",
                                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            } else if (member.isGroup) {
                                Text("当前节点：${active[member.tag] ?: "等待连接"}",
                                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        if (selected) Icon(Icons.Default.Check, contentDescription = "已选择", tint = MiuixTheme.colorScheme.primary)
                        IconButton(onClick = {
                            if (node != null) editingNode = node else if (member.isGroup) mainViewModel.selectedProxyGroupTag = member.tag
                        }) { Icon(Icons.Default.Edit, contentDescription = "编辑") }
                        IconButton(onClick = {
                            if (node != null) deletingNode = node.tag else mainViewModel.removeMemberFromGroup(group.tag, member)
                        }) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = MiuixTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (creatingGroup) {
        GroupSourceEditorSheet(null, onSave = { originalTag, edited ->
            mainViewModel.saveWorkspaceGroup(originalTag, edited).also { error ->
                if (error == null) mainViewModel.selectedProxyGroupTag = edited.tag
            }
        }) {
            creatingGroup = false
        }
    }
    if (editingNode != null || addingNode) {
        val node = editingNode
        NodeConfigSheet(node, onSave = { raw, uotVersion, obfsMode, obfsHost ->
            if (node != null) mainViewModel.updateNodeJson(node.tag, raw, uotVersion, obfsMode, obfsHost)
            else group?.let { mainViewModel.addNodeToGroup(it.tag, raw) } ?: "请先选择分组"
        }, onDismiss = { editingNode = null; addingNode = false })
    }
    deletingNode?.let { tag ->
        ConfirmOverlayDialog(title = "删除节点", summary = "删除 $tag 及其组内引用", confirmText = "删除", cancelText = "取消",
            onConfirm = { mainViewModel.deleteWorkspaceNode(tag); deletingNode = null }, onDismiss = { deletingNode = null })
    }
}

@Composable
private fun NodeConfigSheet(node: ProxyNode?, onSave: (String, Int?, String?, String?) -> String?, onDismiss: () -> Unit) {
    var text by remember(node) { mutableStateOf(node?.outboundJson?.let { JSONObject(it).toString(2) }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var uotVersion by remember(node) { mutableStateOf(node?.udpOverTcpVersion ?: 0) }
    var obfsMode by remember(node) { mutableStateOf(node?.obfsMode.orEmpty()) }
    var obfsHost by remember(node) { mutableStateOf(node?.obfsHost.orEmpty()) }
    GlassBottomSheet(show = true, backdrop = LocalLayerBackdrop.current,
        title = node?.tag ?: "新增节点", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (node == null) "粘贴 Xray 节点 JSON 或分享链接。" else "编辑 Xray 节点配置；targetStrategy 控制该节点的 DNS 解析。",
                style = MiuixTheme.textStyles.body2)
            TextField(value = text, onValueChange = { text = it }, label = "节点配置",
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 360.dp))
            if (node != null) {
                val strategies = listOf("", "AsIs", "UseIP", "UseIPv4", "UseIPv6", "ForceIP", "ForceIPv4", "ForceIPv6")
                val strategy = runCatching { JSONObject(text).optString("targetStrategy") }.getOrDefault("")
                Card {
                    OverlayDropdownPreference(title = "目标 DNS 解析", items = strategies.map(::nodeDnsStrategyLabel),
                        selectedIndex = strategies.indexOf(strategy).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            runCatching {
                                text = JSONObject(text).apply {
                                    if (index == 0) remove("targetStrategy") else put("targetStrategy", strategies[index])
                                }.toString(2)
                            }.onFailure { error = "请先完成节点 JSON 编辑" }
                        })
                }
            }
            if (node?.protocol == "shadowsocks") {
                Card {
                    OverlayDropdownPreference(title = "UDP 传输",
                        items = listOf("原生 UDP", "UDP over TCP v1", "UDP over TCP v2"),
                        selectedIndex = uotVersion, onSelectedIndexChange = { uotVersion = it })
                    val modes = listOf("", "http", "tls")
                    OverlayDropdownPreference(title = "传输封装", items = listOf("原生 SS", "simple-obfs HTTP", "simple-obfs TLS"),
                        selectedIndex = modes.indexOf(obfsMode).coerceAtLeast(0),
                        onSelectedIndexChange = { obfsMode = modes[it] })
                }
                if (obfsMode.isNotBlank()) TextField(obfsHost, { obfsHost = it }, label = "simple-obfs Host",
                    modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            Button(onClick = {
                error = onSave(text, node?.let { uotVersion }, node?.let { obfsMode }, node?.let { obfsHost })
                if (error == null) onDismiss()
            },
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存") }
        }
    }
}

private fun nodeDnsStrategyLabel(value: String): String = when (value) {
    "" -> "默认解析策略"
    "AsIs" -> "远端解析 · AsIs"
    "UseIP" -> "本地解析 IPv4 / IPv6 · UseIP"
    "UseIPv4" -> "本地解析 IPv4 · UseIPv4"
    "UseIPv6" -> "本地解析 IPv6 · UseIPv6"
    "ForceIP" -> "强制本地解析 · ForceIP"
    "ForceIPv4" -> "强制本地 IPv4 · ForceIPv4"
    "ForceIPv6" -> "强制本地 IPv6 · ForceIPv6"
    else -> value
}

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
