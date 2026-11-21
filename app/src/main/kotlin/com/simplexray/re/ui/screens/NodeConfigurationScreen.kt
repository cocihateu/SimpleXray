package com.simplexray.re.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.ProxyNode
import com.simplexray.re.ui.components.ConfirmOverlayDialog
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NodeConfigurationScreen(
    mainViewModel: MainViewModel,
    groupTag: String,
    paddingValues: PaddingValues,
    onGroupSettings: (String) -> Unit,
) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val measuredLatencies by mainViewModel.proxyLatency.collectAsStateWithLifecycle()
    val measuringTargets by mainViewModel.measuringProxyTargets.collectAsStateWithLifecycle()
    val refreshing by mainViewModel.refreshingGroups.collectAsStateWithLifecycle()
    val group = workspace.groupByTag(groupTag) ?: return
    var editingNode by remember(groupTag) { mutableStateOf<ProxyNode?>(null) }
    var addingNode by remember(groupTag) { mutableStateOf(false) }
    var deletingNode by remember(groupTag) { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) mainViewModel.importFromUriToGroup(groupTag, uri)
    }
    val members = (group.effectiveMembers() + group.nodes.map { GroupMemberRef(it.tag) }).distinct()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
            top = paddingValues.calculateTopPadding() + 12.dp,
            bottom = paddingValues.calculateBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.tag, style = MiuixTheme.textStyles.title3)
                Text("${if (group.sourceType == GroupSourceType.LOCAL) "基本" else "订阅"} · ${group.nodes.size} 个节点 · ${strategyShortLabel(group.strategy)}",
                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OverlayIconDropdownMenu(entry = DropdownEntry(listOf(
                        DropdownItem("从剪贴板导入", onClick = { mainViewModel.importFromClipboardToGroup(groupTag) }),
                        DropdownItem("从文件导入", onClick = { filePicker.launch(arrayOf("*/*")) }),
                        DropdownItem("手动添加节点", onClick = { addingNode = true }),
                    ))) {
                        Icon(Icons.Default.Add, contentDescription = "导入节点到 $groupTag")
                    }
                    TextButton(text = "测速", onClick = { mainViewModel.measureGroupLatency(groupTag) },
                        enabled = measuringTargets.isEmpty())
                    TextButton(text = "设置", onClick = { onGroupSettings(groupTag) })
                }
                group.frontProxy?.let { Text("链路：本机 → ${it.tag} → 组内节点 → 目标", style = MiuixTheme.textStyles.body2) }
                if (groupTag in refreshing) Text("正在更新订阅…", style = MiuixTheme.textStyles.body2)
            }
        }
        if (members.isEmpty()) item {
            Text("使用上方 + 导入或添加节点。", modifier = Modifier.padding(vertical = 24.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        items(members, key = { "${it.isGroup}:${it.tag}" }) { member ->
            val node = if (member.isGroup) null else workspace.nodeByTag(member.tag)
            val measured = measuredLatencies[member]
            val delayText = when {
                member in measuringTargets -> "测量中"
                measured != null -> if (measured.alive) "${measured.delayMs} ms" else "失败"
                else -> "待测"
            }
            Card(Modifier.fillMaxWidth(), onClick = {
                if (node != null) editingNode = node else if (member.isGroup) onGroupSettings(member.tag)
            }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(member.tag, style = MiuixTheme.textStyles.body1, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        if (group.strategy == GroupStrategy.FALLBACK) {
                            val position = group.effectiveMembers().indexOf(member)
                            if (position >= 0) Text(if (position == 0) "主节点" else "备用 $position",
                                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary)
                        }
                        val protocol = if (member.isGroup) "节点组" else node?.protocol?.uppercase() ?: when (member.tag) {
                            "direct" -> "直连"
                            "block" -> "拒绝"
                            else -> "节点"
                        }
                        Text("$protocol · $delayText", style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (node != null) {
                            if (node.udpOverTcpVersion > 0) Text("UDP over TCP v${node.udpOverTcpVersion}",
                                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(nodeDnsStrategyLabel(node.targetStrategy), style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (node.obfsMode.isNotBlank()) Text("simple-obfs · ${node.obfsMode.uppercase()}",
                                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    if (node != null || member.isGroup) IconButton(onClick = {
                        if (node != null) editingNode = node else onGroupSettings(member.tag)
                    }) { Icon(Icons.Default.Edit, contentDescription = "编辑") }
                    IconButton(onClick = {
                        if (node != null) deletingNode = node.tag else mainViewModel.removeMemberFromGroup(groupTag, member)
                    }) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = MiuixTheme.colorScheme.error) }
                }
            }
        }
    }
    if (editingNode != null || addingNode) {
        val node = editingNode
        NodeConfigSheet(node, onSave = { raw, uotVersion, obfsMode, obfsHost ->
            if (node != null) mainViewModel.updateNodeJson(node.tag, raw, uotVersion, obfsMode, obfsHost)
            else mainViewModel.addNodeToGroup(groupTag, raw)
        }, onDismiss = { editingNode = null; addingNode = false })
    }
    deletingNode?.let { tag ->
        ConfirmOverlayDialog(title = "删除节点", summary = "删除 $tag 及其组内引用", confirmText = "删除", cancelText = "取消",
            onConfirm = { mainViewModel.deleteWorkspaceNode(tag); deletingNode = null }, onDismiss = { deletingNode = null })
    }
}

private fun nodeDnsStrategyLabel(value: String): String = when (value) {
    "" -> "默认 · AsIs"
    "AsIs" -> "远端解析 · AsIs"
    "UseIP" -> "本地解析 IPv4 / IPv6 · UseIP"
    "UseIPv4" -> "本地解析 IPv4 · UseIPv4"
    "UseIPv6" -> "本地解析 IPv6 · UseIPv6"
    "ForceIP" -> "强制本地解析 · ForceIP"
    "ForceIPv4" -> "强制本地 IPv4 · ForceIPv4"
    "ForceIPv6" -> "强制本地 IPv6 · ForceIPv6"
    else -> value
}
