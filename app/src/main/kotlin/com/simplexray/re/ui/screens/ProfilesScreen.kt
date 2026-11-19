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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun ProfilesScreen(mainViewModel: MainViewModel, paddingValues: PaddingValues = PaddingValues()) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val refreshing by mainViewModel.refreshingGroups.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ProxyGroup?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var importTarget by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = importTarget
        if (uri != null && target != null) mainViewModel.importFromUriToGroup(target, uri)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = paddingValues.calculateTopPadding() + 12.dp,
            bottom = paddingValues.calculateBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("管理订阅来源，转换后的节点在代理页独立编辑。", style = MiuixTheme.textStyles.body2)
                Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text("添加订阅") }
            }
        }
        val subscriptions = workspace.groups.filter { it.sourceType == GroupSourceType.SUBSCRIPTION }
        if (subscriptions.isEmpty()) item { Text("添加链接后自动拉取并转换为 Xray 节点。") }
        items(subscriptions, key = { it.tag }) { group ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(group.tag, style = MiuixTheme.textStyles.title3)
                    val updated = if (group.subscriptionUpdatedAtMs > 0)
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(group.subscriptionUpdatedAtMs))
                    else "等待更新"
                    Text("${group.nodes.size} 个节点 · $updated", style = MiuixTheme.textStyles.body2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { mainViewModel.refreshSubscriptionGroup(group.tag) },
                            enabled = group.tag !in refreshing, modifier = Modifier.weight(1f)) {
                            Text(if (group.tag in refreshing) "更新中" else "更新")
                        }
                        Button(onClick = { editing = group }, modifier = Modifier.weight(1f)) { Text("编辑") }
                        Button(onClick = { deleting = group.tag }, modifier = Modifier.weight(1f)) { Text("删除") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { importTarget = group.tag; filePicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)) { Text("从文件导入") }
                        Button(onClick = { mainViewModel.importFromClipboardToGroup(group.tag) },
                            modifier = Modifier.weight(1f)) { Text("从剪贴板导入") }
                    }
                }
            }
        }
    }
    if (creating || editing != null) {
        val original = editing
        var name by remember(original) { mutableStateOf(original?.tag.orEmpty()) }
        var url by remember(original) { mutableStateOf(original?.subscriptionUrl.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        GlassBottomSheet(show = true, backdrop = LocalLayerBackdrop.current,
            title = if (creating) "添加订阅" else "编辑订阅",
            onDismissRequest = { creating = false; editing = null }) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(value = name, onValueChange = { name = it }, label = "名称", modifier = Modifier.fillMaxWidth())
                TextField(value = url, onValueChange = { url = it }, label = "订阅链接", modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
                Button(onClick = {
                    val group = (original ?: ProxyGroup(name, sourceType = GroupSourceType.SUBSCRIPTION, strategy = GroupStrategy.SELECT))
                        .copy(tag = name.trim(), subscriptionUrl = url.trim())
                    error = mainViewModel.saveWorkspaceGroup(original?.tag, group)
                    if (error == null) { creating = false; editing = null }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存") }
            }
        }
    }
    deleting?.let { tag ->
        ConfirmOverlayDialog(title = "删除订阅", summary = "删除 $tag 及其节点", confirmText = "删除", cancelText = "取消",
            onConfirm = { mainViewModel.deleteWorkspaceGroup(tag); deleting = null }, onDismiss = { deleting = null })
    }
}
