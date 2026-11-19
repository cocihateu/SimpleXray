package com.simplexray.re.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.common.config.ProxyNode
import com.simplexray.re.ui.components.ConfirmOverlayDialog
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop
import com.simplexray.re.service.GroupRuntimeStatus
import com.simplexray.re.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import org.json.JSONObject

private val NODE_DNS_STRATEGIES = listOf("默认", "UseIPv4", "ForceIP", "AsIs", "UseIP")

@Composable
fun ProxiesScreen(
    mainViewModel: MainViewModel,
    paddingValues: PaddingValues = PaddingValues()
) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val activeNodes by mainViewModel.activeGroupNodes.collectAsStateWithLifecycle()
    val latencies by mainViewModel.runtimeNodeLatencies.collectAsStateWithLifecycle()
    val refreshingGroups by mainViewModel.refreshingGroups.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val groups = workspace.groups
    val allNodesMap = remember(workspace) { workspace.allNodes().associateBy { it.tag } }
    var selectedGroupTag by remember(groups) {
        mutableStateOf(workspace.effectiveDefaultGroupTag() ?: groups.firstOrNull()?.tag.orEmpty())
    }

    val currentGroup = groups.firstOrNull { it.tag == selectedGroupTag } ?: groups.firstOrNull()

    var editingGroup by remember { mutableStateOf<ProxyGroup?>(null) }
    var showCreateGroupSheet by remember { mutableStateOf(false) }
    var groupToDelete by remember { mutableStateOf<String?>(null) }
    var inspectingNode by remember { mutableStateOf<ProxyNode?>(null) }
    var showNodes by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            currentGroup?.let { grp -> mainViewModel.importFromUriToGroup(grp.tag, it) }
        }
    }

    val safe = WindowInsets.safeDrawing.asPaddingValues()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = maxOf(paddingValues.calculateTopPadding(), safe.calculateTopPadding()) + 8.dp,
            bottom = maxOf(paddingValues.calculateBottomPadding(), safe.calculateBottomPadding()) + 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showNodes = false }, modifier = Modifier.weight(1f),
                    colors = if (showNodes == false) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text("节点组") }
                Button(onClick = { showNodes = true }, modifier = Modifier.weight(1f),
                    colors = if (showNodes) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text("全部节点") }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                groups.forEach { group ->
                    val isSelected = group.tag == (currentGroup?.tag ?: "")
                    Button(
                        onClick = { selectedGroupTag = group.tag },
                        colors = if (isSelected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    ) { Text(group.tag) }
                }

                IconButton(
                    onClick = { showCreateGroupSheet = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.proxies_add_group)
                    )
                }
            }
        }

        if (showNodes) {
            items(workspace.allNodes(), key = { it.tag }) { node ->
                NodeCard(node.tag, node.protocol, latencies[node.tag], false,
                    onSelect = { inspectingNode = node }, onInspect = { inspectingNode = node })
            }
        } else if (currentGroup != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = currentGroup.tag,
                                    style = MiuixTheme.textStyles.title2,
                                    fontWeight = FontWeight.Bold
                                )
                                if (currentGroup.tag == workspace.effectiveDefaultGroupTag()) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "默认",
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            val memberCount = currentGroup.effectiveMembers().size
                            Text(
                                text = "${strategyLabel(currentGroup.strategy)} · $memberCount 个候选节点",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        currentGroup.effectiveMembers().forEach {
                                            GroupRuntimeStatus.measureTarget(it, currentGroup.testUrl, 5_000)
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.proxies_test_all)
                                )
                            }

                            IconButton(
                                onClick = { editingGroup = currentGroup },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.proxies_edit_group)
                                )
                            }

                            IconButton(
                                onClick = { groupToDelete = currentGroup.tag },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.proxies_delete_group),
                                    tint = MiuixTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            val effectiveMembers = currentGroup.effectiveMembers()
            val activeNodeTag = activeNodes[currentGroup.tag]

            items(effectiveMembers, key = { "${it.isGroup}:${it.tag}" }) { memberRef ->
                val node = allNodesMap[memberRef.tag]
                val latencyMs = latencies[memberRef.tag]
                val isSelected = (activeNodeTag == memberRef.tag) || (currentGroup.selectedMember?.tag == memberRef.tag)

                NodeCard(
                    tag = memberRef.tag,
                    protocol = node?.protocol ?: if (memberRef.isGroup) "GROUP" else "PROXY",
                    latencyMs = latencyMs,
                    isSelected = isSelected,
                    onSelect = {
                        mainViewModel.selectGroupMember(currentGroup.tag, memberRef)
                    },
                    onInspect = {
                        if (node != null) inspectingNode = node
                    }
                )
            }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "暂无出站代理分组",
                            style = MiuixTheme.textStyles.title3
                        )
                        Button(
                            onClick = { showCreateGroupSheet = true },
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text(stringResource(R.string.proxies_add_group))
                        }
                    }
                }
            }
        }
    }

    if (inspectingNode != null) {
        NodeDetailsSheet(
            node = inspectingNode!!,
            onDismiss = { inspectingNode = null },
            onSaveJson = { newJson ->
                inspectingNode?.let {
                    mainViewModel.updateNodeJson(it.tag, newJson)
                }
            }
        )
    }

    if (editingGroup != null) {
        WorkspaceGroupEditorSheet(
            workspace = workspace,
            editingGroup = editingGroup,
            onDismiss = { editingGroup = null },
            onSave = mainViewModel::saveWorkspaceGroup,
            onDelete = mainViewModel::deleteWorkspaceGroup,
        )
    }

    if (showCreateGroupSheet) {
        WorkspaceGroupEditorSheet(
            workspace = workspace,
            editingGroup = null,
            onDismiss = { showCreateGroupSheet = false },
            onSave = mainViewModel::saveWorkspaceGroup,
            onDelete = mainViewModel::deleteWorkspaceGroup,
        )
    }

    if (groupToDelete != null) {
        ConfirmOverlayDialog(
            title = stringResource(R.string.proxies_delete_group),
            summary = "确认删除代理分组 ${groupToDelete.orEmpty()}？",
            confirmText = stringResource(R.string.delete),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                groupToDelete?.let { mainViewModel.deleteWorkspaceGroup(it) }
                groupToDelete = null
            },
            onDismiss = { groupToDelete = null }
        )
    }
}

@Composable
private fun NodeCard(
    tag: String,
    protocol: String,
    latencyMs: Long?,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onInspect: () -> Unit
) {
    val borderColor = if (isSelected) MiuixTheme.colorScheme.primary else Color.Transparent
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        colors = if (isSelected) {
            CardDefaults.defaultColors()
        } else {
            CardDefaults.defaultColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tag,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = protocol.uppercase(),
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                }
            }

            LatencyPill(latencyMs = latencyMs)

            IconButton(
                onClick = onInspect,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.node_detail_title),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun LatencyPill(latencyMs: Long?) {
    val (text, color) = when {
        latencyMs == null -> "—" to MiuixTheme.colorScheme.onSurfaceVariantSummary
        latencyMs in 1..250 -> "${latencyMs}ms" to Color(0xFF2E7D32)
        latencyMs in 251..600 -> "${latencyMs}ms" to Color(0xFFE65100)
        latencyMs > 600 -> "${latencyMs}ms" to Color(0xFFC62828)
        else -> "超时" to Color(0xFFC62828)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun NodeDetailsSheet(
    node: ProxyNode,
    onDismiss: () -> Unit,
    onSaveJson: (String) -> String?
) {
    var showRawJson by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf(node.outboundJson) }
    var selectedStrategy by remember { mutableStateOf(node.targetStrategy.ifEmpty { "默认" }) }
    var error by remember { mutableStateOf<String?>(null) }

    GlassBottomSheet(onDismissRequest = onDismiss, show = true, backdrop = LocalLayerBackdrop.current) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.node_detail_title),
                style = MiuixTheme.textStyles.title2,
                fontWeight = FontWeight.Bold
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailRow(stringResource(R.string.node_name), node.tag)
                    DetailRow(stringResource(R.string.node_protocol), node.protocol.uppercase())
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    title = "DNS 解析策略",
                    items = NODE_DNS_STRATEGIES,
                    selectedIndex = NODE_DNS_STRATEGIES.indexOf(selectedStrategy).coerceAtLeast(0),
                    onSelectedIndexChange = {
                        val picked = NODE_DNS_STRATEGIES[it]
                        selectedStrategy = picked
                        jsonText = JSONObject(jsonText).apply {
                            if (picked == "默认") remove("targetStrategy") else put("targetStrategy", picked)
                        }.toString(2)
                    }
                )
            }

            Button(
                onClick = { showRawJson = !showRawJson },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors()
            ) {
                Text(if (showRawJson) "收起原始配置" else "展开底层配置")
            }

            if (showRawJson) {
                TextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    label = stringResource(R.string.node_json_title),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )

            }
                error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
                Button(
                    onClick = {
                        error = onSaveJson(jsonText)
                        if (error == null) onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(stringResource(R.string.save))
                }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
    }
}

private fun strategyLabel(strategy: GroupStrategy): String = when (strategy) {
    GroupStrategy.FALLBACK -> "自动备用 (Fallback)"
    GroupStrategy.URL_TEST -> "最快响应 (URLTest)"
    GroupStrategy.SELECT -> "手动选择 (Select)"
}
