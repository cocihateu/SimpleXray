package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.common.config.DEFAULT_URL_TEST
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.ui.components.ConfirmOverlayDialog
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
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
    var name by remember(tag) { mutableStateOf(group.tag) }
    var url by remember(tag) { mutableStateOf(group.subscriptionUrl) }
    var strategy by remember(tag) { mutableStateOf(group.strategy) }
    var members by remember(tag, group.subscriptionUpdatedAtMs) { mutableStateOf(group.effectiveMembers()) }
    var frontProxy by remember(tag) { mutableStateOf(group.frontProxy) }
    var timeout by remember(tag) { mutableStateOf(group.failoverTimeoutSeconds.toString()) }
    var recovery by remember(tag) { mutableStateOf(group.recoveryIntervalSeconds.toString()) }
    var interval by remember(tag) { mutableStateOf(group.intervalSeconds.toString()) }
    var trust by remember(tag) { mutableStateOf(group.trustSeconds.toString()) }
    var testUrl by remember(tag) { mutableStateOf(group.testUrl) }
    var aliases by remember(tag) { mutableStateOf(group.routingTargets.joinToString("\n")) }
    var advanced by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    val choices = workspace.groups.filter { it.tag != tag }.map { GroupMemberRef(it.tag, true) } +
        workspace.allNodes().map { GroupMemberRef(it.tag) } + listOf(GroupMemberRef("direct"), GroupMemberRef("block"))
    val frontChoices = listOf<GroupMemberRef?>(null) + choices.filter { ref ->
        ref.isGroup || group.nodes.none { it.tag == ref.tag }
    }
    val strategyChoices = listOf(GroupStrategy.SELECT, GroupStrategy.FALLBACK, GroupStrategy.URL_TEST)
    fun save(): Boolean {
        val edited = group.copy(
            tag = name.trim(), subscriptionUrl = url.trim(), strategy = strategy, members = members,
            selectedMember = group.selectedMember?.takeIf { it in members } ?: members.firstOrNull(),
            frontProxy = frontProxy,
            failoverTimeoutSeconds = timeout.toIntOrNull() ?: 0,
            recoveryIntervalSeconds = recovery.toIntOrNull() ?: 0,
            intervalSeconds = interval.toIntOrNull() ?: 0,
            trustSeconds = trust.toIntOrNull() ?: 0,
            testUrl = testUrl.trim().ifBlank { DEFAULT_URL_TEST },
            routingTargets = aliases.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        val error = mainViewModel.saveWorkspaceGroup(group.tag, edited)
        message = error ?: "已保存"
        if (error == null) {
            tag = edited.tag
            mainViewModel.selectedProxyGroupTag = edited.tag
        }
        return error == null
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, paddingValues.calculateTopPadding() + 12.dp,
            16.dp, paddingValues.calculateBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(group.tag, style = MiuixTheme.textStyles.title2)
            Text("${if (group.sourceType == GroupSourceType.LOCAL) "基本组" else "订阅组"} · ${group.nodes.size} 个节点",
                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(name, { name = it }, label = "分组名称", modifier = Modifier.fillMaxWidth())
                    if (group.sourceType == GroupSourceType.SUBSCRIPTION) {
                        TextField(url, { url = it }, label = "订阅链接", modifier = Modifier.fillMaxWidth())
                        Button(onClick = { if (save()) mainViewModel.refreshSubscriptionGroup(tag) },
                            enabled = tag !in refreshing) { Text(if (tag in refreshing) "更新中…" else "保存并更新订阅") }
                    }
                }
            }
        }
        item { SmallTitle("节点选择") }
        item {
            Card {
                OverlayDropdownPreference(title = "运行方式", items = listOf("手动选择", "主备回落", "自动测速"),
                    selectedIndex = strategyChoices.indexOf(strategy), onSelectedIndexChange = { strategy = strategyChoices[it] })
                if (strategy == GroupStrategy.FALLBACK) {
                    OverlayDropdownPreference(title = "主节点", summary = "可选择单个节点或节点组",
                        items = listOf("选择主节点") + choices.map(::groupChoiceLabel),
                        selectedIndex = choices.indexOf(members.firstOrNull()) + 1,
                        onSelectedIndexChange = { index ->
                            choices.getOrNull(index - 1)?.let { picked ->
                                members = listOf(picked) + members.filter { it != picked }
                            }
                        })
                }
            }
        }
        item {
            SmallTitle(if (strategy == GroupStrategy.FALLBACK) "备用成员 · 从上到下依次回落" else "组内成员")
        }
        val visibleMembers = if (strategy == GroupStrategy.FALLBACK) members.drop(1) else members
        itemsIndexed(visibleMembers, key = { _, member -> "${member.isGroup}:${member.tag}" }) { index, member ->
            val actualIndex = if (strategy == GroupStrategy.FALLBACK) index + 1 else index
            Card {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${groupChoiceLabel(member)}", modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        members = members.toMutableList().apply { add(actualIndex - 1, removeAt(actualIndex)) }
                    }, enabled = index > 0) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移") }
                    IconButton(onClick = {
                        members = members.toMutableList().apply { add(actualIndex + 1, removeAt(actualIndex)) }
                    }, enabled = index < visibleMembers.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移") }
                    IconButton(onClick = { members = members.filter { it != member } }) {
                        Icon(Icons.Default.Delete, contentDescription = "移除成员")
                    }
                }
            }
        }
        item {
            val available = choices.filter { it !in members }
            Card {
                OverlayDropdownPreference(
                    title = if (strategy == GroupStrategy.FALLBACK) "添加备用节点或组" else "添加节点或组",
                    items = listOf("选择后加入") + available.map(::groupChoiceLabel), selectedIndex = 0,
                    onSelectedIndexChange = { index -> available.getOrNull(index - 1)?.let { members = members + it } })
            }
        }
        item { SmallTitle(if (strategy == GroupStrategy.FALLBACK) "故障切换与恢复" else "延迟与测活") }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (strategy == GroupStrategy.FALLBACK) {
                        Text("主节点由实际业务判断状态。失败后回落；备用返回数据后开始计时，到期检查主节点，恢复后切回。")
                        GroupSecondsField("响应等待上限 · 秒", timeout) { timeout = it }
                        GroupSecondsField("主节点恢复检查间隔 · 秒", recovery) { recovery = it }
                    } else if (strategy == GroupStrategy.URL_TEST) {
                        Text("定时测量成员，按有效测活结果选择节点，可作为主备组的备用。")
                        GroupSecondsField("测活间隔 · 秒", interval) { interval = it }
                        GroupSecondsField("测活结果信任时长 · 秒", trust) { trust = it }
                    } else {
                        Text("在代理页选择当前节点。测速由手动操作触发。")
                    }
                    TextField(testUrl, { testUrl = it }, label = "测活地址", modifier = Modifier.fillMaxWidth())
                    val completed = members.mapNotNull { measurements[it] }
                    Button(onClick = { if (save()) mainViewModel.measureGroupLatency(tag) },
                        enabled = measuring.isEmpty()) { Text(if (members.any { it in measuring }) "测速中…" else "保存并测量延迟") }
                    if (completed.isNotEmpty()) Text("${completed.count { it.alive }} 个连通 · ${completed.count { it.alive == false }} 个失败")
                }
            }
        }
        item {
            SmallTitle("链式代理")
            Card {
                OverlayDropdownPreference(title = "前置代理", summary = "本机 → 前置代理 → 本组节点 → 目标",
                    items = frontChoices.map { it?.let(::groupChoiceLabel) ?: "直接连接本组节点" },
                    selectedIndex = frontChoices.indexOf(frontProxy).coerceAtLeast(0),
                    onSelectedIndexChange = { frontProxy = frontChoices[it] })
            }
        }
        item {
            Card {
                ArrowPreference(title = if (workspace.effectiveDefaultGroupTag() == tag) "当前默认组" else "设为默认组",
                    summary = "全局模式与兜底规则使用此组；专门分流规则保留各自出口",
                    onClick = { if (save()) mainViewModel.setDefaultWorkspaceGroup(tag) })
                ArrowPreference(title = "高级设置", summary = "路由别名", onClick = { advanced = !advanced })
                if (advanced) TextField(aliases, { aliases = it }, label = "路由别名 · 每行一个",
                    modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
        }
        item {
            if (message.isNotEmpty()) Text(message, modifier = Modifier.padding(bottom = 8.dp))
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存分组") }
        }
        item {
            Card { ArrowPreference(title = "删除分组", summary = "删除此组及其节点", onClick = { deleting = true }) }
        }
    }
    if (deleting) {
        ConfirmOverlayDialog(title = "删除分组", summary = "删除 ${group.tag} 及其节点", confirmText = "删除", cancelText = "取消",
            onConfirm = { mainViewModel.deleteWorkspaceGroup(group.tag); deleting = false; onDeleted() },
            onDismiss = { deleting = false })
    }
}

private fun groupChoiceLabel(ref: GroupMemberRef): String =
    "${if (ref.isGroup) "组" else "节点"} · ${when (ref.tag) { "direct" -> "直连"; "block" -> "拒绝"; else -> ref.tag }}"

@Composable
private fun GroupSecondsField(label: String, value: String, onChange: (String) -> Unit) {
    TextField(value, onChange, label = label, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth())
}
