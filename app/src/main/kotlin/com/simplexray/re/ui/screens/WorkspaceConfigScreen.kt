package com.simplexray.re.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun WorkspaceConfigScreen(
    mainViewModel: MainViewModel,
    paddingValues: PaddingValues,
    onOpenNodes: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenRules: () -> Unit,
) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val rules by mainViewModel.trafficRules.collectAsStateWithLifecycle()
    val refreshing by mainViewModel.refreshingGroups.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(16.dp, paddingValues.calculateTopPadding() + 12.dp,
            16.dp, paddingValues.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.background)) {
                ArrowPreference(title = "规则管理", summary = "${rules.count { it.enabled }} 条启用 · 编辑顺序、条件与出站目标",
                    onClick = onOpenRules)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("节点与分组", style = MiuixTheme.textStyles.title3)
                    Text("${workspace.groups.size} 个分组 · ${workspace.allNodes().size} 个节点",
                        style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                TextButton(text = "新建", onClick = { mainViewModel.createGroupRequested = true })
            }
        }
        if (workspace.groups.isEmpty()) item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Text("创建基本分组以添加节点，或创建订阅分组从链接获取节点。",
                    Modifier.padding(18.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        items(workspace.groups, key = { it.tag }) { group ->
            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.background)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(group.tag, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${if (group.sourceType == GroupSourceType.SUBSCRIPTION) "订阅" else "本地"} · ${group.nodes.size} 个节点 · ${strategyShortLabel(group.strategy)}",
                                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        if (workspace.effectiveDefaultGroupTag() == group.tag) {
                            Text("默认", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenNodes(group.tag) }, modifier = Modifier.weight(1f)) { Text("编辑节点") }
                        Button(onClick = { onOpenGroup(group.tag) }, modifier = Modifier.weight(1f)) { Text("分组设置") }
                    }
                    if (group.sourceType == GroupSourceType.SUBSCRIPTION) {
                        TextButton(text = if (group.tag in refreshing) "更新中…" else "更新订阅",
                            onClick = { mainViewModel.refreshSubscriptionGroup(group.tag) }, enabled = group.tag !in refreshing)
                    }
                }
            }
        }
    }
    if (mainViewModel.createGroupRequested) {
        GroupSourceEditorSheet(null, onSave = { originalTag, group ->
            mainViewModel.saveWorkspaceGroup(originalTag, group).also { message ->
                if (message == null) mainViewModel.selectedProxyGroupTag = group.tag
            }
        }, onDismiss = { mainViewModel.createGroupRequested = false })
    }
}
