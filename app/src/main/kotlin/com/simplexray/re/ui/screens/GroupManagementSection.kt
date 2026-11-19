package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.ui.components.ConfirmOverlayDialog
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.groupManagementItems(mainViewModel: MainViewModel) {
    item(key = "group-management") { GroupManagementSection(mainViewModel) }
}

@Composable
private fun GroupManagementSection(mainViewModel: MainViewModel) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val refreshing by mainViewModel.refreshingGroups.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ProxyGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("分组", style = MiuixTheme.textStyles.title3, modifier = Modifier.weight(1f))
            IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, contentDescription = "新建分组") }
        }
        if (workspace.groups.isEmpty()) Text("点右上角 + 新建基本或订阅分组。")
        workspace.groups.forEach { group ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(group.tag, style = MiuixTheme.textStyles.title3)
                    Text("${if (group.sourceType == GroupSourceType.LOCAL) "基本" else "订阅"} · ${group.nodes.size} 个节点",
                        style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { editing = group }, modifier = Modifier.weight(1f)) { Text("编辑") }
                        if (group.sourceType == GroupSourceType.SUBSCRIPTION) {
                            Button(onClick = { mainViewModel.refreshSubscriptionGroup(group.tag) },
                                enabled = group.tag !in refreshing, modifier = Modifier.weight(1f)) {
                                Text(if (group.tag in refreshing) "更新中" else "更新")
                            }
                        }
                        Button(onClick = { deleting = group.tag }, modifier = Modifier.weight(1f)) { Text("删除") }
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        GroupSourceEditorSheet(editing, mainViewModel::saveWorkspaceGroup) { creating = false; editing = null }
    }
    deleting?.let { tag ->
        ConfirmOverlayDialog(title = "删除分组", summary = "删除 $tag 及其节点", confirmText = "删除", cancelText = "取消",
            onConfirm = { mainViewModel.deleteWorkspaceGroup(tag); deleting = null }, onDismiss = { deleting = null })
    }
}

@Composable
internal fun GroupSourceEditorSheet(
    original: ProxyGroup?,
    onSave: (String?, ProxyGroup) -> String?,
    onDismiss: () -> Unit,
) {
    var name by remember(original) { mutableStateOf(original?.tag.orEmpty()) }
    var source by remember(original) { mutableStateOf(original?.sourceType ?: GroupSourceType.LOCAL) }
    var url by remember(original) { mutableStateOf(original?.subscriptionUrl.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    GlassBottomSheet(show = true, backdrop = LocalLayerBackdrop.current,
        title = if (original == null) "新建分组" else "编辑分组", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = name, onValueChange = { name = it }, label = "名称", modifier = Modifier.fillMaxWidth())
            Card {
                OverlayDropdownPreference(title = "组类型", items = listOf("基本", "订阅"),
                    selectedIndex = if (source == GroupSourceType.LOCAL) 0 else 1,
                    onSelectedIndexChange = { source = if (it == 0) GroupSourceType.LOCAL else GroupSourceType.SUBSCRIPTION })
            }
            if (source == GroupSourceType.SUBSCRIPTION) {
                TextField(value = url, onValueChange = { url = it }, label = "订阅链接", modifier = Modifier.fillMaxWidth())
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            Button(onClick = {
                val group = (original ?: ProxyGroup(name, strategy = GroupStrategy.SELECT))
                    .copy(tag = name.trim(), sourceType = source, subscriptionUrl = url.trim())
                error = onSave(original?.tag, group)
                if (error == null) onDismiss()
            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存") }
        }
    }
}
