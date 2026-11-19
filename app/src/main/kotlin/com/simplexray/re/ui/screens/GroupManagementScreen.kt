package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.ui.components.ConfirmOverlayDialog
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun GroupManagementScreen(
    mainViewModel: MainViewModel,
    paddingValues: PaddingValues,
    onOpenGroup: (String) -> Unit,
) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, paddingValues.calculateTopPadding() + 12.dp,
            16.dp, paddingValues.calculateBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (workspace.groups.isEmpty()) item {
            Text("点右上角 + 新建基本或订阅分组。")
        }
        items(workspace.groups, key = { it.tag }) { group ->
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = group.tag,
                    summary = "${if (group.sourceType == GroupSourceType.LOCAL) "基本" else "订阅"} · ${group.nodes.size} 个节点 · ${strategyShortLabel(group.strategy)}",
                    onClick = { onOpenGroup(group.tag) },
                )
            }
        }
    }
    if (mainViewModel.createGroupRequested) {
        GroupSourceEditorSheet(null, onSave = { originalTag, group ->
            mainViewModel.saveWorkspaceGroup(originalTag, group).also {
                if (it == null) mainViewModel.selectedProxyGroupTag = group.tag
            }
        }) { mainViewModel.createGroupRequested = false }
    }
}

@Composable
fun GroupSettingsScreen(
    mainViewModel: MainViewModel,
    initialTag: String,
    paddingValues: PaddingValues,
    onDeleted: () -> Unit,
) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val refreshing by mainViewModel.refreshingGroups.collectAsStateWithLifecycle()
    val measuring by mainViewModel.measuringProxyTargets.collectAsStateWithLifecycle()
    val measurements by mainViewModel.proxyLatency.collectAsStateWithLifecycle()
    var tag by rememberSaveable(initialTag) { mutableStateOf(initialTag) }
    val group = workspace.groupByTag(tag) ?: return
    val members = (group.effectiveMembers() + group.nodes.map { GroupMemberRef(it.tag) }).distinct()
    val completed = members.mapNotNull { measurements[it] }
    var editor by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val healthSummary = when (group.strategy) {
        GroupStrategy.FALLBACK -> "响应期限 ${group.failoverTimeoutSeconds} 秒 · 回落后每 ${group.recoveryIntervalSeconds} 秒检查主节点"
        GroupStrategy.URL_TEST -> "测活间隔 ${group.intervalSeconds} 秒 · 结果信任 ${group.trustSeconds} 秒"
        GroupStrategy.SELECT -> "手动选择节点 · 按需测量延迟"
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, paddingValues.calculateTopPadding() + 12.dp,
            16.dp, paddingValues.calculateBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(group.tag, style = MiuixTheme.textStyles.title2, modifier = Modifier.padding(vertical = 8.dp)) }
        item {
            Card {
                ArrowPreference(title = "来源与订阅",
                    summary = if (group.sourceType == GroupSourceType.LOCAL) "基本 · 本地节点" else "订阅 · ${group.nodes.size} 个节点",
                    onClick = { editor = "source" })
                if (group.sourceType == GroupSourceType.SUBSCRIPTION) {
                    ArrowPreference(title = if (group.tag in refreshing) "正在更新订阅…" else "更新订阅",
                        enabled = group.tag !in refreshing,
                        onClick = { mainViewModel.refreshSubscriptionGroup(group.tag) })
                }
            }
        }
        item {
            Card {
                ArrowPreference(title = "出站策略与测活", summary = healthSummary, onClick = { editor = "policy" })
                ArrowPreference(title = "链式代理",
                    summary = group.frontProxy?.let { "本机 → ${it.tag} → 本组节点 → 目标" } ?: "直接连接本组节点",
                    onClick = { editor = "chain" })
                ArrowPreference(title = if (members.any { it in measuring }) "正在测量延迟…" else "测量组内节点延迟",
                    summary = if (completed.isEmpty()) "通过 Xray 发送 HTTP 请求"
                        else "${completed.count { it.alive }} 个连通 · ${completed.count { it.alive == false }} 个失败",
                    enabled = measuring.isEmpty(),
                    onClick = { mainViewModel.measureGroupLatency(group.tag) })
                ArrowPreference(title = "默认出站组",
                    summary = if (workspace.effectiveDefaultGroupTag() == group.tag) "当前默认组" else "设为默认组",
                    onClick = { mainViewModel.setDefaultWorkspaceGroup(group.tag) })
            }
        }
        item {
            Card {
                ArrowPreference(title = "删除分组", summary = "删除此组及其节点", onClick = { deleting = true })
            }
        }
    }
    when (editor) {
        "source" -> GroupSourceEditorSheet(group, onSave = { originalTag, edited ->
            mainViewModel.saveWorkspaceGroup(originalTag, edited).also { error ->
                if (error == null) {
                    tag = edited.tag
                    mainViewModel.selectedProxyGroupTag = edited.tag
                }
            }
        }) { editor = null }
        "policy" -> WorkspaceGroupEditorSheet(workspace, group, mainViewModel::saveWorkspaceGroup,
            mainViewModel::deleteWorkspaceGroup, onDismiss = { editor = null }, showSource = false)
        "chain" -> GroupChainSheet(workspace, group, mainViewModel::saveWorkspaceGroup) { editor = null }
    }
    if (deleting) {
        ConfirmOverlayDialog(title = "删除分组", summary = "删除 ${group.tag} 及其节点", confirmText = "删除", cancelText = "取消",
            onConfirm = { mainViewModel.deleteWorkspaceGroup(group.tag); deleting = false; onDeleted() },
            onDismiss = { deleting = false })
    }
}
