package com.simplexray.re.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.ProxyNode
import com.simplexray.re.ui.components.CardHeading
import com.simplexray.re.ui.components.ConfirmOverlayDialog
import com.simplexray.re.ui.components.DashboardCard
import com.simplexray.re.ui.components.StatusPill
import com.simplexray.re.viewmodel.MainViewModel
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun GroupNodeConfigurationContent(
    mainViewModel: MainViewModel,
    groupTag: String,
    paddingValues: PaddingValues,
    searchQuery: String = "",
) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val measuredLatencies by mainViewModel.proxyLatency.collectAsStateWithLifecycle()
    val measuringTargets by mainViewModel.measuringProxyTargets.collectAsStateWithLifecycle()
    val refreshing by mainViewModel.refreshingGroups.collectAsStateWithLifecycle()
    val group = workspace.groupByTag(groupTag) ?: return
    val context = LocalContext.current
    var editingNode by remember(groupTag) { mutableStateOf<ProxyNode?>(null) }
    var deletingNode by remember(groupTag) { mutableStateOf<String?>(null) }
    val query = searchQuery.trim()
    val nodes = group.nodes.filter { node ->
        node.tag.contains(query, ignoreCase = true) || node.protocol.contains(query, ignoreCase = true)
    }
    val subscriptionUpdate = remember(group.subscriptionUpdatedAtMs) {
        if (group.subscriptionUpdatedAtMs > 0) {
            "上次更新：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(group.subscriptionUpdatedAtMs))}"
        } else "尚未更新"
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
            top = paddingValues.calculateTopPadding() + 12.dp,
            bottom = paddingValues.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            DashboardCard {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CardHeading(
                            icon = if (group.sourceType == GroupSourceType.LOCAL) R.drawable.code else R.drawable.cloud_download,
                            title = if (group.sourceType == GroupSourceType.LOCAL) "本地分组" else "订阅分组",
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(text = if (group.nodes.any { GroupMemberRef(it.tag) in measuringTargets }) "测量中…" else "测速",
                            onClick = { mainViewModel.measureGroupLatency(groupTag) },
                            enabled = measuringTargets.isEmpty(), cornerRadius = 24.dp,
                            colors = ButtonDefaults.textButtonColorsPrimary())
                        if (group.sourceType == GroupSourceType.SUBSCRIPTION) {
                            IconButton(onClick = { mainViewModel.refreshSubscriptionGroup(groupTag) },
                                enabled = groupTag !in refreshing) {
                                Icon(Icons.Default.Refresh, contentDescription = "更新订阅", modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("${group.nodes.size} 个节点")
                    }
                    if (group.sourceType == GroupSourceType.SUBSCRIPTION) {
                        Text(if (groupTag in refreshing) "正在更新订阅…" else subscriptionUpdate,
                            modifier = Modifier.fillMaxWidth(), fontSize = 12.sp,
                            color = if (groupTag in refreshing) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        if (nodes.isEmpty()) item {
            DashboardCard {
                Text(if (group.nodes.isEmpty()) "点击上方 + 导入或添加节点" else "未找到匹配节点，请调整搜索词",
                    modifier = Modifier.padding(18.dp), fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        items(nodes, key = { it.tag }) { node ->
            val member = GroupMemberRef(node.tag)
            val measured = measuredLatencies[member]
            val delayText = when {
                member in measuringTargets -> "测量中"
                measured != null -> if (measured.alive) "${measured.delayMs} ms" else "失败"
                else -> "待测"
            }
            DashboardCard(onClick = { editingNode = node }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(node.tag, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1,
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { editingNode = node }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑 ${node.tag}", modifier = Modifier.size(18.dp))
                        }
                        OverlayIconDropdownMenu(entry = DropdownEntry(listOf(
                            DropdownItem("复制节点配置", onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(node.tag, node.outboundJson))
                                mainViewModel.showSnackbar("节点配置已复制，可切换分组后导入")
                            }),
                            DropdownItem("删除节点", onClick = { deletingNode = node.tag }),
                        )), modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.MoreVert, contentDescription = "节点操作 · ${node.tag}",
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(node.protocol.uppercase(), modifier = Modifier.weight(1f, fill = false),
                            color = MiuixTheme.colorScheme.primary)
                        StatusPill(delayText,
                            color = when {
                                member in measuringTargets -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                measured?.alive == true -> MiuixTheme.colorScheme.primary
                                measured?.alive == false -> MiuixTheme.colorScheme.error
                                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                            })
                    }
                    val details = buildList {
                        if (node.udpOverTcpVersion > 0) add("UDP over TCP v${node.udpOverTcpVersion}")
                        add(nodeDnsStrategyLabel(node.targetStrategy))
                        if (node.obfsMode.isNotBlank()) add("simple-obfs · ${node.obfsMode.uppercase()}")
                    }
                    if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MiuixTheme.textStyles.body2,
                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
    editingNode?.let { node ->
        NodeConfigSheet(node, onSave = { raw, uotVersion, obfsMode, obfsHost ->
            mainViewModel.updateNodeJson(node.tag, raw, uotVersion, obfsMode, obfsHost)
        }, onDismiss = { editingNode = null })
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
