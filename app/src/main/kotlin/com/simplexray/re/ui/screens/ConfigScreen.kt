package com.simplexray.re.ui.screens

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.common.config.ProxyNode
import com.simplexray.re.service.FallbackRuntimeInfo
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop
import com.simplexray.re.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

private val NODE_DNS_STRATEGY_CYCLE = listOf("", "UseIPv4", "ForceIP", "AsIs", "UseIP")

private data class MemberActionTarget(
    val groupTag: String,
    val index: Int,
    val member: GroupMemberRef,
    val totalMembers: Int,
)

@Composable
fun ConfigScreen(
    onReloadConfig: () -> Unit,
    onEditConfigClick: (File) -> Unit,
    onDeleteConfigClick: (File) -> Unit,
    onCreateNewConfigFileAndEdit: () -> Unit = {},
    onImportConfigFromClipboard: () -> Unit = {},
    mainViewModel: MainViewModel,
    listState: LazyListState,
    paddingValues: PaddingValues = PaddingValues(),
) {
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val activeGroupNodes by mainViewModel.activeGroupNodes.collectAsStateWithLifecycle()
    val fallbackStates by mainViewModel.fallbackRuntimeStates.collectAsStateWithLifecycle()
    val runtimeLatencies by mainViewModel.runtimeNodeLatencies.collectAsStateWithLifecycle()
    val manualLatencies by mainViewModel.outboundLatency.collectAsStateWithLifecycle()
    val refreshingGroups by mainViewModel.refreshingGroups.collectAsStateWithLifecycle()

    var showGroupEditor by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<ProxyGroup?>(null) }
    var addingNodesGroupTag by remember { mutableStateOf<String?>(null) }
    var pendingFileImportGroupTag by remember { mutableStateOf<String?>(null) }
    var editingNode by remember { mutableStateOf<ProxyNode?>(null) }
    var memberActionTarget by remember { mutableStateOf<MemberActionTarget?>(null) }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    var elapsedNowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(fallbackStates) {
        if (fallbackStates.values.any { it.inFallback }) {
            while (true) {
                elapsedNowMs = SystemClock.elapsedRealtime()
                delay(1000)
            }
        }
    }

    val fileImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetGroup = pendingFileImportGroupTag
        pendingFileImportGroupTag = null
        if (uri != null && targetGroup != null) {
            mainViewModel.importFromUriToGroup(targetGroup, uri)
        }
    }

    val isWideScreen = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width >= 600.dp.roundToPx()
    }
    val safePadding = WindowInsets.safeDrawing.asPaddingValues()
    val topPadding = paddingValues.calculateTopPadding()
        .coerceAtLeast(safePadding.calculateTopPadding()) + 12.dp
    val bottomPadding = paddingValues.calculateBottomPadding()
        .coerceAtLeast(safePadding.calculateBottomPadding() + 12.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (isWideScreen) Modifier.widthIn(max = 840.dp) else Modifier),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "workspace_routing_mode_bar") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.workspace_routing_mode_title),
                        items = listOf(
                            stringResource(R.string.routing_mode_rule),
                            stringResource(R.string.routing_mode_global),
                            stringResource(R.string.routing_mode_direct),
                        ),
                        selectedIndex = when (workspace.routingMode) {
                            "global" -> 1
                            "direct" -> 2
                            else -> 0
                        },
                        onSelectedIndexChange = { index ->
                            mainViewModel.setRoutingMode(
                                when (index) {
                                    1 -> "global"
                                    2 -> "direct"
                                    else -> "rule"
                                }
                            )
                        }
                    )
                }
            }

            item(key = "workspace_add_group_bar") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editingGroup = null
                            showGroupEditor = true
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.workspace_add_group),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.workspace_add_group),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (workspace.groups.isEmpty()) {
                item(key = "workspace_empty_hint") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.workspace_empty_hint),
                            modifier = Modifier.padding(16.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            items(workspace.groups, key = { "group:${it.tag}" }) { group ->
                val isExpanded = expandedGroups[group.tag] ?: (group.effectiveMembers().size <= 8)
                WorkspaceGroupCard(
                    group = group,
                    workspace = workspace,
                    isDefault = workspace.effectiveDefaultGroupTag() == group.tag,
                    isExpanded = isExpanded,
                    activeLeaf = activeGroupNodes[group.tag],
                    fallbackInfo = fallbackStates[group.tag],
                    elapsedNowMs = elapsedNowMs,
                    isRefreshing = group.tag in refreshingGroups,
                    latencyOf = { nodeTag ->
                        runtimeLatencies[nodeTag]
                            ?: manualLatencies[nodeTag]?.takeIf { it.alive }?.delayMs
                    },
                    onToggleExpand = { expandedGroups[group.tag] = !isExpanded },
                    onEditGroup = {
                        editingGroup = group
                        showGroupEditor = true
                    },
                    onAddNodes = { addingNodesGroupTag = group.tag },
                    onRefreshSubscription = { mainViewModel.refreshSubscriptionGroup(group.tag) },
                    onSetDefault = { mainViewModel.setDefaultWorkspaceGroup(group.tag) },
                    onSelectMember = { member -> mainViewModel.selectGroupMember(group.tag, member) },
                    onShowMemberActions = { member, idx ->
                        memberActionTarget = MemberActionTarget(group.tag, idx, member, group.effectiveMembers().size)
                    },
                )
            }
        }
    }

    if (showGroupEditor) {
        WorkspaceGroupEditorSheet(
            workspace = workspace,
            editingGroup = editingGroup,
            onSave = { originalTag, edited -> mainViewModel.saveWorkspaceGroup(originalTag, edited) },
            onDelete = { tag -> mainViewModel.deleteWorkspaceGroup(tag) },
            onDismiss = { showGroupEditor = false },
        )
    }

    addingNodesGroupTag?.let { groupTag ->
        AddNodesToGroupSheet(
            groupTag = groupTag,
            workspace = workspace,
            onImportClipboard = {
                addingNodesGroupTag = null
                mainViewModel.importFromClipboardToGroup(groupTag)
            },
            onImportFile = {
                addingNodesGroupTag = null
                pendingFileImportGroupTag = groupTag
                fileImportLauncher.launch(arrayOf("*/*"))
            },
            onAddRef = { ref ->
                val err = mainViewModel.addMemberRefToGroup(groupTag, ref)
                if (err != null) {
                    mainViewModel.showSnackbar(err)
                } else {
                    addingNodesGroupTag = null
                }
            },
            onDismiss = { addingNodesGroupTag = null },
        )
    }

    editingNode?.let { node ->
        NodeJsonEditorSheet(
            node = node,
            onSave = { rawJson -> mainViewModel.updateNodeJson(node.tag, rawJson) },
            onDismiss = { editingNode = null },
        )
    }

    memberActionTarget?.let { target ->
        MemberActionSheet(
            target = target,
            workspace = workspace,
            latencyMs = if (target.member.isGroup == false) {
                runtimeLatencies[target.member.tag]
                    ?: manualLatencies[target.member.tag]?.takeIf { it.alive }?.delayMs
            } else null,
            onDismiss = { memberActionTarget = null },
            onSelectMember = {
                mainViewModel.selectGroupMember(target.groupTag, target.member)
            },
            onCycleNodeStrategy = { node ->
                val idx = NODE_DNS_STRATEGY_CYCLE.indexOf(node.targetStrategy)
                val next = NODE_DNS_STRATEGY_CYCLE[(idx + 1) % NODE_DNS_STRATEGY_CYCLE.size]
                mainViewModel.updateNodeTargetStrategy(node.tag, next)
            },
            onEditNodeJson = { node ->
                editingNode = node
            },
            onMoveMember = { delta ->
                mainViewModel.moveMemberInGroup(target.groupTag, target.index, delta)
            },
            onRemoveMember = {
                mainViewModel.removeMemberFromGroup(target.groupTag, target.member)
            },
        )
    }
}

@Composable
private fun WorkspaceGroupCard(
    group: ProxyGroup,
    workspace: GroupWorkspace,
    isDefault: Boolean,
    isExpanded: Boolean,
    activeLeaf: String?,
    fallbackInfo: FallbackRuntimeInfo?,
    elapsedNowMs: Long,
    isRefreshing: Boolean,
    latencyOf: (String) -> Long?,
    onToggleExpand: () -> Unit,
    onEditGroup: () -> Unit,
    onAddNodes: () -> Unit,
    onRefreshSubscription: () -> Unit,
    onSetDefault: () -> Unit,
    onSelectMember: (GroupMemberRef) -> Unit,
    onShowMemberActions: (GroupMemberRef, Int) -> Unit,
) {
    val members = group.effectiveMembers()
    fun containsActiveLeaf(ref: GroupMemberRef, visited: Set<String> = emptySet()): Boolean {
        if (ref.isGroup == false) return ref.tag == activeLeaf
        if (ref.tag in visited) return false
        return workspace.groupByTag(ref.tag)?.effectiveMembers()
            ?.any { containsActiveLeaf(it, visited + ref.tag) } == true
    }
    val sourceLabel = if (group.sourceType == GroupSourceType.SUBSCRIPTION) {
        stringResource(R.string.workspace_source_subscription)
    } else {
        stringResource(R.string.workspace_source_local)
    }
    val metaLine = buildString {
        append(strategyShortLabel(group.strategy))
        append(" · ")
        append(sourceLabel)
        append(" · ")
        append("${members.size}")
        if (isDefault) {
            append(" · ")
            append(stringResource(R.string.workspace_default_badge))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (isDefault) MiuixTheme.colorScheme.surfaceContainer else MiuixTheme.colorScheme.surface
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.tag,
                        style = MiuixTheme.textStyles.title4,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = metaLine,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    activeLeaf?.let { leaf ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${stringResource(R.string.outbound_group_live_leaf)}: $leaf",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (fallbackInfo != null && fallbackInfo.inFallback) {
                        Spacer(modifier = Modifier.height(2.dp))
                        if (fallbackInfo.nextRecoveryAtElapsedMs == 0L) {
                            Text(
                                text = stringResource(R.string.workspace_status_waiting_backup),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                            )
                        } else {
                            val remainSec = ((fallbackInfo.nextRecoveryAtElapsedMs - elapsedNowMs) / 1000L)
                                .coerceAtLeast(0L)
                            Text(
                                text = stringResource(R.string.workspace_status_recovery_countdown, remainSec.toInt()),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (group.sourceType == GroupSourceType.SUBSCRIPTION) {
                    IconButton(onClick = onRefreshSubscription, enabled = isRefreshing == false) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.workspace_refresh_subscription),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                } else {
                    IconButton(onClick = onAddNodes) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.workspace_add_nodes),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
                IconButton(onClick = onEditGroup) {
                    Icon(
                        painter = painterResource(R.drawable.edit),
                        contentDescription = stringResource(R.string.outbound_group_edit, group.tag),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (members.isEmpty()) {
                    Text(
                        text = stringResource(R.string.workspace_members_empty),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    members.forEachIndexed { index, member ->
                        val node = if (member.isGroup == false) workspace.nodeByTag(member.tag) else null
                        val isActiveMember = when {
                            containsActiveLeaf(member) -> true
                            group.strategy == GroupStrategy.SELECT && group.selectedMember == member -> true
                            else -> false
                        }
                        val roleBadge = when {
                            group.strategy == GroupStrategy.FALLBACK && index == 0 ->
                                "[${stringResource(R.string.workspace_role_primary)}] "
                            group.strategy == GroupStrategy.FALLBACK ->
                                "[${stringResource(R.string.workspace_role_fallback, index)}] "
                            else -> ""
                        }
                        val titleText = roleBadge + if (member.isGroup) {
                            stringResource(R.string.outbound_group_target_group, member.tag)
                        } else {
                            member.tag
                        }
                        val latencyMs = if (member.isGroup == false) latencyOf(member.tag) else null
                        val subText = buildString {
                            if (member.isGroup) {
                                val child = workspace.groupByTag(member.tag)
                                append(child?.let { strategyShortLabel(it.strategy) } ?: "group")
                            } else if (node != null) {
                                append(node.protocol)
                                append(" · DNS: ")
                                append(node.targetStrategy.ifBlank { "default" })
                            } else {
                                append("builtin")
                            }
                            if (latencyMs != null) {
                                append(" · ${latencyMs}ms")
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectMember(member) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isActiveMember) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(MiuixTheme.colorScheme.primary, shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = titleText,
                                    style = MiuixTheme.textStyles.body1,
                                    color = if (isActiveMember) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = subText,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            if (latencyMs != null) {
                                val latencyColor = when {
                                    latencyMs <= 100 -> MiuixTheme.colorScheme.primary
                                    latencyMs <= 300 -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    else -> MiuixTheme.colorScheme.error
                                }
                                Text(
                                    text = "${latencyMs}ms",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = latencyColor,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp),
                                )
                            }

                            IconButton(
                                onClick = { onShowMemberActions(member, index) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.edit),
                                    contentDescription = stringResource(R.string.workspace_node_actions),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                if (isDefault == false) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_set_default_group),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onSetDefault)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddNodesToGroupSheet(
    groupTag: String,
    workspace: GroupWorkspace,
    onImportClipboard: () -> Unit,
    onImportFile: () -> Unit,
    onAddRef: (GroupMemberRef) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedRef by remember { mutableStateOf<GroupMemberRef?>(null) }
    val refs = remember(workspace, groupTag) {
        val groupRefs = workspace.groups
            .filter { it.tag != groupTag }
            .map { GroupMemberRef(it.tag, isGroup = true) }
        val nodeRefs = workspace.allNodes().map { GroupMemberRef(it.tag, isGroup = false) }
        val builtins = BUILTIN_GROUP_TARGET_TAGS.map { GroupMemberRef(it, isGroup = false) }
        (groupRefs + nodeRefs + builtins).distinct()
    }

    GlassBottomSheet(
        title = "${stringResource(R.string.workspace_add_nodes)} · $groupTag",
        show = true,
        backdrop = LocalLayerBackdrop.current,
        onDismissRequest = onDismiss,
        startAction = {
            IconButton(onClick = onDismiss) {
                Icon(imageVector = MiuixIcons.Close, contentDescription = stringResource(R.string.cancel))
            }
        },
        content = {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.workspace_import_clipboard_nodes),
                    summary = stringResource(R.string.workspace_import_clipboard_nodes_summary),
                    onClick = onImportClipboard,
                )
                ArrowPreference(
                    title = stringResource(R.string.workspace_import_file_nodes),
                    summary = stringResource(R.string.workspace_import_file_nodes_summary),
                    onClick = onImportFile,
                )
                if (refs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SmallTitle(text = stringResource(R.string.workspace_add_nested_ref))
                    val labels = listOf(stringResource(R.string.outbound_group_select_target)) +
                        refs.map { workspaceMemberLabel(it) }
                    val selectedIndex = refs.indexOfFirst { it == selectedRef } + 1
                    OverlayDropdownPreference(
                        title = stringResource(R.string.workspace_add_nested_ref),
                        items = labels,
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = { idx ->
                            selectedRef = if (idx <= 0) null else refs.getOrNull(idx - 1)
                        },
                    )
                    Button(
                        onClick = { selectedRef?.let(onAddRef) },
                        enabled = selectedRef != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.outbound_group_add_fallback))
                    }
                }
            }
        },
    )
}

@Composable
private fun MemberActionSheet(
    target: MemberActionTarget,
    workspace: GroupWorkspace,
    latencyMs: Long?,
    onDismiss: () -> Unit,
    onSelectMember: () -> Unit,
    onCycleNodeStrategy: (ProxyNode) -> Unit,
    onEditNodeJson: (ProxyNode) -> Unit,
    onMoveMember: (Int) -> Unit,
    onRemoveMember: () -> Unit,
) {
    val group = workspace.groupByTag(target.groupTag)
    val member = target.member
    val node = if (member.isGroup == false) workspace.nodeByTag(member.tag) else null
    val isSelected = group?.selectedMember == member

    GlassBottomSheet(
        title = "${stringResource(R.string.workspace_node_actions)} · ${member.tag}",
        show = true,
        backdrop = LocalLayerBackdrop.current,
        onDismissRequest = onDismiss,
        startAction = {
            IconButton(onClick = onDismiss) {
                Icon(imageVector = MiuixIcons.Close, contentDescription = stringResource(R.string.cancel))
            }
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.workspace_node_details),
                            style = MiuixTheme.textStyles.title4,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = buildString {
                                append("Tag: ")
                                append(member.tag)
                                if (member.isGroup) {
                                    append(" (Group)")
                                } else if (node != null) {
                                    append("\nProtocol: ")
                                    append(node.protocol)
                                    append("\nDNS Strategy: ")
                                    append(node.targetStrategy.ifBlank { "default" })
                                }
                                if (latencyMs != null) {
                                    append("\nLatency: ")
                                    append(latencyMs)
                                    append("ms")
                                }
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.workspace_node_select_hint),
                            summary = if (isSelected) "Active" else null,
                            onClick = {
                                onSelectMember()
                                onDismiss()
                            }
                        )

                        if (node != null) {
                            ArrowPreference(
                                title = stringResource(R.string.outbound_group_target_strategy),
                                summary = node.targetStrategy.ifBlank { "default" },
                                onClick = { onCycleNodeStrategy(node) }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.workspace_edit_node_json),
                                onClick = {
                                    onDismiss()
                                    onEditNodeJson(node)
                                }
                            )
                        }

                        if (target.totalMembers > 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.outbound_group_move_up, ""),
                                    style = MiuixTheme.textStyles.body1
                                )
                                Row {
                                    IconButton(
                                        onClick = { onMoveMember(-1) },
                                        enabled = target.index > 0
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowUp,
                                            contentDescription = stringResource(R.string.outbound_group_move_up, member.tag)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onMoveMember(1) },
                                        enabled = target.index + 1 < target.totalMembers
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.outbound_group_move_down, member.tag)
                                        )
                                    }
                                }
                            }
                        }

                        ArrowPreference(
                            title = stringResource(R.string.outbound_group_remove_fallback, member.tag),
                            onClick = {
                                onDismiss()
                                onRemoveMember()
                            }
                        )
                    }
                }
            }
        }
    )
}
