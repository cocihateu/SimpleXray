package com.simplexray.re.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.common.config.DEFAULT_URL_TEST
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.ui.components.ConfirmOverlayDialog
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
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
    val refreshing by mainViewModel.refreshingGroups.collectAsStateWithLifecycle()
    var editingGroup by remember { mutableStateOf<ProxyGroup?>(null) }
    var deletingGroup by remember { mutableStateOf<ProxyGroup?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(16.dp, paddingValues.calculateTopPadding() + 12.dp,
            16.dp, paddingValues.calculateBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("${workspace.groups.size} 个分组 · ${workspace.groups.sumOf { it.nodes.size }} 个节点",
                modifier = Modifier.padding(vertical = 4.dp), style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (workspace.groups.isEmpty()) item {
            Text("本地分组添加或导入节点，订阅分组从链接获取节点",
                modifier = Modifier.padding(vertical = 16.dp), style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        itemsIndexed(workspace.groups, key = { _, group -> group.tag }) { index, group ->
            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp,
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.background),
                onClick = { onOpenGroup(group.tag) }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(group.tag, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${if (group.sourceType == GroupSourceType.LOCAL) "本地" else "订阅"} · ${group.nodes.size} 个节点",
                                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (group.tag in refreshing) Text("正在更新订阅…", style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary)
                        }
                        if (group.sourceType == GroupSourceType.SUBSCRIPTION) {
                            IconButton(onClick = { mainViewModel.refreshSubscriptionGroup(group.tag) },
                                enabled = group.tag !in refreshing) {
                                Icon(Icons.Default.Refresh, contentDescription = "更新订阅", modifier = Modifier.size(20.dp))
                            }
                        }
                        IconButton(onClick = { editingGroup = group }) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑配置分组", modifier = Modifier.size(20.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { mainViewModel.moveConfigurationGroup(group.tag, -1) }, enabled = index > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移分组", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { mainViewModel.moveConfigurationGroup(group.tag, 1) },
                            enabled = index < workspace.groups.lastIndex) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移分组", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { deletingGroup = group }, enabled = workspace.groups.size > 1) {
                            Icon(Icons.Default.Delete, contentDescription = "删除配置分组", modifier = Modifier.size(20.dp),
                                tint = if (workspace.groups.size > 1) MiuixTheme.colorScheme.error
                                    else MiuixTheme.colorScheme.disabledOnSecondaryVariant)
                        }
                    }
                }
            }
        }
    }
    if (mainViewModel.createGroupRequested || editingGroup != null) {
        GroupSourceEditorSheet(editingGroup, onSave = { originalTag, group ->
            mainViewModel.saveWorkspaceGroup(originalTag, group).also {
                if (it == null) mainViewModel.selectConfigurationGroup(group.tag)
            }
        }) { mainViewModel.createGroupRequested = false; editingGroup = null }
    }
    deletingGroup?.let { group ->
        ConfirmOverlayDialog(title = "删除配置分组", summary = "删除 ${group.tag} 及其 ${group.nodes.size} 个节点",
            confirmText = "删除", cancelText = "取消",
            onConfirm = { mainViewModel.deleteWorkspaceGroup(group.tag); deletingGroup = null },
            onDismiss = { deletingGroup = null })
    }
}

@Composable
fun GroupSettingsScreen(
    mainViewModel: MainViewModel,
    initialTag: String,
    paddingValues: PaddingValues,
) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val measuring by mainViewModel.measuringProxyTargets.collectAsStateWithLifecycle()
    val measurements by mainViewModel.proxyLatency.collectAsStateWithLifecycle()
    var tag by rememberSaveable(initialTag) { mutableStateOf(initialTag) }
    val group = workspace.groupByTag(tag) ?: return
    var name by remember(tag) { mutableStateOf(group.tag) }
    var strategy by remember(tag) { mutableStateOf(group.strategy) }
    var members by remember(tag, group.subscriptionUpdatedAtMs) { mutableStateOf(group.effectiveMembers()) }
    var frontProxy by remember(tag) { mutableStateOf(group.frontProxy) }
    var timeout by remember(tag) { mutableStateOf(group.failoverTimeoutSeconds.toString()) }
    var recovery by remember(tag) { mutableStateOf(group.recoveryIntervalSeconds.toString()) }
    var interval by remember(tag) { mutableStateOf(group.intervalSeconds.toString()) }
    var trust by remember(tag) { mutableStateOf(group.trustSeconds.toString()) }
    var testUrl by remember(tag) { mutableStateOf(group.testUrl) }
    var message by remember { mutableStateOf("") }
    val choices = workspace.groups.filter { it.tag != tag }.map { GroupMemberRef(it.tag, true) } +
        workspace.allNodes().map { GroupMemberRef(it.tag) } + listOf(GroupMemberRef("direct"), GroupMemberRef("block"))
    val frontChoices = listOf<GroupMemberRef?>(null) + choices.filter { ref ->
        ref.isGroup || group.nodes.none { it.tag == ref.tag }
    }
    val strategyChoices = listOf(GroupStrategy.SELECT, GroupStrategy.FALLBACK, GroupStrategy.URL_TEST)
    fun save(): Boolean {
        val edited = group.copy(
            tag = name.trim(), strategy = strategy, members = members,
            selectedMember = group.selectedMember?.takeIf { it in members } ?: members.firstOrNull(),
            frontProxy = frontProxy,
            failoverTimeoutSeconds = timeout.toIntOrNull() ?: 0,
            recoveryIntervalSeconds = recovery.toIntOrNull() ?: 0,
            intervalSeconds = interval.toIntOrNull() ?: 0,
            trustSeconds = trust.toIntOrNull() ?: 0,
            testUrl = testUrl.trim().ifBlank { DEFAULT_URL_TEST },
        )
        val error = mainViewModel.saveWorkspaceGroup(group.tag, edited)
        message = error ?: "已保存"
        if (error == null) {
            tag = edited.tag
            members = edited.effectiveMembers()
            mainViewModel.selectedProxyGroupTag = edited.tag
        }
        return error == null
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(16.dp, paddingValues.calculateTopPadding() + 12.dp,
            16.dp, paddingValues.calculateBottomPadding() + 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("代理组 · ${group.tag}", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
                Text("${strategyShortLabel(strategy)} · ${members.size} 个成员",
                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(name, { name = it }, label = "代理组名称", modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item { SmallTitle("代理策略") }
        item {
            Card {
                OverlayDropdownPreference(title = "选择策略", items = listOf("手动选择", "主备回落", "自动测速"),
                    selectedIndex = strategyChoices.indexOf(strategy), onSelectedIndexChange = { strategy = strategyChoices[it] })
                if (strategy == GroupStrategy.FALLBACK) {
                    OverlayDropdownPreference(title = "主节点", summary = "可选择节点或代理组",
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
            SmallTitle(if (strategy == GroupStrategy.FALLBACK) "备用成员 · 按优先级发起连接" else "组内成员")
            if (members.isEmpty()) {
                Text("留空则在保存后使用本组全部 ${group.nodes.size} 个节点",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
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
                        Text("响应等待触发主备测活，确认降权后关闭停滞连接。健康慢请求继续等待响应，切换备用后定时检查主节点")
                        GroupSecondsField("连接建立超时 · 秒", timeout) { timeout = it }
                        GroupSecondsField("主节点恢复检查间隔 · 秒", recovery) { recovery = it }
                    } else if (strategy == GroupStrategy.URL_TEST) {
                        Text("定时测速，优先选择有效测活结果中延迟最低的节点")
                        GroupSecondsField("测活间隔 · 秒", interval) { interval = it }
                        GroupSecondsField("测活结果信任时长 · 秒", trust) { trust = it }
                    } else {
                        Text("在代理页手动选择成员和测速")
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
                if (workspace.effectiveDefaultGroupTag() == tag) {
                    BasicComponent(title = "当前默认组",
                        summary = "全局模式和规则兜底使用此组，不改动其他规则目标")
                } else {
                    ArrowPreference(title = "保存并设为默认组",
                        summary = "全局模式和规则兜底使用此组，不改动其他规则目标",
                        onClick = { if (save()) mainViewModel.setDefaultWorkspaceGroup(tag) })
                }
            }
        }
        item {
            if (message.isNotEmpty()) Text(message, modifier = Modifier.padding(bottom = 8.dp))
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存代理组设置") }
        }
    }
}

private fun groupChoiceLabel(ref: GroupMemberRef): String =
    "${if (ref.isGroup) "组" else "节点"} · ${when (ref.tag) { "direct" -> "直连"; "block" -> "拒绝"; else -> ref.tag }}"

@Composable
private fun GroupSecondsField(label: String, value: String, onChange: (String) -> Unit) {
    TextField(value, onChange, label = label, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth())
}
