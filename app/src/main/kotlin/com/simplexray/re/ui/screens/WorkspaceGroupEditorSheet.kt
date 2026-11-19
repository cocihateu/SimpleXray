package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simplexray.re.R
import com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS
import com.simplexray.re.common.config.DEFAULT_URL_TEST
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.common.config.ProxyNode
import com.simplexray.re.ui.components.ConfirmOverlayDialog
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WorkspaceGroupEditorSheet(
    workspace: GroupWorkspace,
    editingGroup: ProxyGroup?,
    onSave: (originalTag: String?, edited: ProxyGroup) -> String?,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    showSource: Boolean = true,
) {
    var tag by remember(editingGroup) { mutableStateOf(editingGroup?.tag.orEmpty()) }
    var sourceType by remember(editingGroup) {
        mutableStateOf(editingGroup?.sourceType ?: GroupSourceType.LOCAL)
    }
    var strategy by remember(editingGroup) {
        mutableStateOf(editingGroup?.strategy ?: GroupStrategy.FALLBACK)
    }
    var subscriptionUrl by remember(editingGroup) {
        mutableStateOf(editingGroup?.subscriptionUrl.orEmpty())
    }
    var failoverTimeoutSeconds by remember(editingGroup) {
        mutableStateOf((editingGroup?.failoverTimeoutSeconds ?: 3).toString())
    }
    var recoveryIntervalSeconds by remember(editingGroup) {
        mutableStateOf((editingGroup?.recoveryIntervalSeconds ?: 300).toString())
    }
    var intervalSeconds by remember(editingGroup) {
        mutableStateOf((editingGroup?.intervalSeconds ?: 60).toString())
    }
    var trustSeconds by remember(editingGroup) {
        mutableStateOf((editingGroup?.trustSeconds ?: 120).toString())
    }
    var testUrl by remember(editingGroup) {
        mutableStateOf(editingGroup?.testUrl ?: DEFAULT_URL_TEST)
    }
    var members by remember(editingGroup) {
        mutableStateOf(editingGroup?.effectiveMembers().orEmpty())
    }
    var routingTargets by remember(editingGroup) {
        mutableStateOf(editingGroup?.routingTargets.orEmpty())
    }
    var newRoutingTarget by remember { mutableStateOf("") }
    var memberToAdd by remember(editingGroup) { mutableStateOf<GroupMemberRef?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val availableRefs = remember(workspace, editingGroup) {
        val groupRefs = workspace.groups
            .filter { it.tag != editingGroup?.tag }
            .map { GroupMemberRef(it.tag, isGroup = true) }
        val nodeRefs = workspace.allNodes().map { GroupMemberRef(it.tag, isGroup = false) }
        val builtinRefs = BUILTIN_GROUP_TARGET_TAGS.map { GroupMemberRef(it, isGroup = false) }
        (groupRefs + nodeRefs + builtinRefs).distinct()
    }

    val sheetHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.78f
    }

    val sourceLabels = listOf(
        stringResource(R.string.workspace_source_local),
        stringResource(R.string.workspace_source_subscription),
    )
    val strategyLabels = listOf(
        stringResource(R.string.workspace_strategy_fallback),
        stringResource(R.string.workspace_strategy_url_test),
        stringResource(R.string.workspace_strategy_select),
    )

    GlassBottomSheet(
        title = if (showSource == false) {
            "出站策略 · ${editingGroup?.tag.orEmpty()}"
        } else if (editingGroup == null) {
            stringResource(R.string.workspace_add_group)
        } else {
            stringResource(R.string.outbound_group_edit, editingGroup.tag)
        },
        show = true,
        backdrop = LocalLayerBackdrop.current,
        onDismissRequest = onDismiss,
        startAction = {
            IconButton(onClick = onDismiss) {
                Icon(imageVector = MiuixIcons.Close, contentDescription = stringResource(R.string.cancel))
            }
        },
        endAction = {
            IconButton(
                onClick = {
                    val failoverSec = failoverTimeoutSeconds.toIntOrNull()
                    val recoverySec = recoveryIntervalSeconds.toIntOrNull()
                    val intervalSec = intervalSeconds.toIntOrNull()
                    val trustSec = trustSeconds.toIntOrNull()
                    if (failoverSec == null || failoverSec < 1 ||
                        recoverySec == null || recoverySec < 5 ||
                        intervalSec == null || intervalSec < 5 ||
                        trustSec == null || trustSec < 5
                    ) {
                        errorMessage = "请输入合法的正整数时间参数"
                        return@IconButton
                    }
                    val edited = ProxyGroup(
                        tag = tag.trim(),
                        sourceType = sourceType,
                        strategy = strategy,
                        subscriptionUrl = subscriptionUrl.trim(),
                        subscriptionUpdatedAtMs = editingGroup?.subscriptionUpdatedAtMs ?: 0L,
                        nodes = editingGroup?.nodes.orEmpty(),
                        members = members,
                        selectedMember = editingGroup?.selectedMember?.takeIf { it in members }
                            ?: members.firstOrNull(),
                        failoverTimeoutSeconds = failoverSec,
                        recoveryIntervalSeconds = recoverySec,
                        intervalSeconds = intervalSec,
                        trustSeconds = trustSec,
                        testUrl = testUrl.trim().ifBlank { DEFAULT_URL_TEST },
                        routingTargets = routingTargets.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                        frontProxy = editingGroup?.frontProxy,
                    )
                    val err = onSave(editingGroup?.tag, edited)
                    if (err != null) {
                        errorMessage = err
                    } else {
                        onDismiss()
                    }
                }
            ) {
                Icon(imageVector = MiuixIcons.Ok, contentDescription = stringResource(R.string.save))
            }
        },
        content = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetHeight),
                contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                errorMessage?.let { msg ->
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = msg,
                                modifier = Modifier.padding(14.dp),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (showSource) item {
                    TextField(
                        value = tag,
                        onValueChange = { tag = it; errorMessage = null },
                        label = stringResource(R.string.outbound_group_name),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        if (showSource) OverlayDropdownPreference(
                            title = stringResource(R.string.workspace_source_type),
                            items = sourceLabels,
                            selectedIndex = if (sourceType == GroupSourceType.LOCAL) 0 else 1,
                            onSelectedIndexChange = { idx ->
                                sourceType = if (idx == 0) GroupSourceType.LOCAL else GroupSourceType.SUBSCRIPTION
                            },
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.workspace_strategy_type),
                            items = strategyLabels,
                            selectedIndex = when (strategy) {
                                GroupStrategy.FALLBACK -> 0
                                GroupStrategy.URL_TEST -> 1
                                GroupStrategy.SELECT -> 2
                            },
                            onSelectedIndexChange = { idx ->
                                strategy = when (idx) {
                                    0 -> GroupStrategy.FALLBACK
                                    1 -> GroupStrategy.URL_TEST
                                    else -> GroupStrategy.SELECT
                                }
                            },
                        )
                    }
                }

                if (showSource && sourceType == GroupSourceType.SUBSCRIPTION) {
                    item {
                        TextField(
                            value = subscriptionUrl,
                            onValueChange = { subscriptionUrl = it },
                            label = stringResource(R.string.workspace_subscription_url),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (strategy == GroupStrategy.FALLBACK) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextField(
                                value = failoverTimeoutSeconds,
                                onValueChange = { failoverTimeoutSeconds = it },
                                label = stringResource(R.string.workspace_failover_timeout_seconds),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextField(
                                value = recoveryIntervalSeconds,
                                onValueChange = { recoveryIntervalSeconds = it },
                                label = stringResource(R.string.outbound_group_recovery_interval_seconds),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = stringResource(R.string.workspace_fallback_hint),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                if (strategy == GroupStrategy.URL_TEST) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextField(
                                value = intervalSeconds,
                                onValueChange = { intervalSeconds = it },
                                label = stringResource(R.string.outbound_group_interval_seconds),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextField(
                                value = trustSeconds,
                                onValueChange = { trustSeconds = it },
                                label = stringResource(R.string.outbound_group_trust_seconds),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextField(
                                value = testUrl,
                                onValueChange = { testUrl = it },
                                label = stringResource(R.string.workspace_url_test_url),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                item {
                    SmallTitle(text = stringResource(R.string.workspace_routing_targets_title))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.workspace_routing_targets_summary),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            if (routingTargets.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                routingTargets.forEach { target ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = target,
                                            style = MiuixTheme.textStyles.body1,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            onClick = { routingTargets = routingTargets - target },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.delete),
                                                contentDescription = stringResource(R.string.delete_config),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextField(
                                    value = newRoutingTarget,
                                    onValueChange = { newRoutingTarget = it },
                                    label = stringResource(R.string.workspace_routing_target_hint),
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        val trimmed = newRoutingTarget.trim()
                                        if (trimmed.isNotEmpty() && trimmed !in routingTargets) {
                                            routingTargets = routingTargets + trimmed
                                            newRoutingTarget = ""
                                        }
                                    },
                                    enabled = newRoutingTarget.isNotBlank(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = stringResource(R.string.workspace_routing_target_add),
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    SmallTitle(text = stringResource(R.string.workspace_members_title))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            if (members.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.workspace_members_empty),
                                    modifier = Modifier.padding(16.dp),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            members.forEachIndexed { index, member ->
                                val rolePrefix = if (strategy == GroupStrategy.FALLBACK) {
                                    if (index == 0) "[${stringResource(R.string.workspace_role_primary)}] "
                                    else "[${stringResource(R.string.workspace_role_fallback, index)}] "
                                } else {
                                    "${index + 1}. "
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = rolePrefix + workspaceMemberLabel(member),
                                        style = MiuixTheme.textStyles.body1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                members = members.toMutableList().apply {
                                                    add(index - 1, removeAt(index))
                                                }
                                            }
                                        },
                                        enabled = index > 0,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowUp,
                                            contentDescription = stringResource(R.string.outbound_group_move_up, member.tag),
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (index + 1 < members.size) {
                                                members = members.toMutableList().apply {
                                                    add(index + 1, removeAt(index))
                                                }
                                            }
                                        },
                                        enabled = index + 1 < members.size,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.outbound_group_move_down, member.tag),
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            members = members.filterIndexed { i, _ -> i != index }
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.delete),
                                            contentDescription = stringResource(R.string.outbound_group_remove_fallback, member.tag),
                                        )
                                    }
                                }
                            }

                            if (availableRefs.isNotEmpty()) {
                                val labels = listOf(stringResource(R.string.outbound_group_select_target)) +
                                    availableRefs.map { workspaceMemberLabel(it) }
                                val selectedIndex = availableRefs.indexOfFirst { it == memberToAdd } + 1
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.workspace_add_nested_ref),
                                    items = labels,
                                    selectedIndex = selectedIndex,
                                    onSelectedIndexChange = { idx ->
                                        memberToAdd = if (idx <= 0) null else availableRefs.getOrNull(idx - 1)
                                    },
                                )
                                Button(
                                    onClick = {
                                        val picked = memberToAdd ?: return@Button
                                        if (picked !in members) {
                                            members = members + picked
                                        }
                                        memberToAdd = null
                                    },
                                    enabled = memberToAdd != null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                ) {
                                    Text(text = stringResource(R.string.outbound_group_add_fallback))
                                }
                            }
                        }
                    }
                }

                if (editingGroup != null) {
                    item {
                        Button(
                            onClick = { showDeleteConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.workspace_delete_group),
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
    )

    if (showDeleteConfirm && editingGroup != null) {
        ConfirmOverlayDialog(
            title = stringResource(R.string.workspace_delete_group),
            summary = stringResource(R.string.workspace_delete_group_confirm, editingGroup.tag),
            confirmText = stringResource(R.string.confirm),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                showDeleteConfirm = false
                onDelete(editingGroup.tag)
                onDismiss()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

@Composable
internal fun NodeJsonEditorSheet(
    node: ProxyNode,
    onSave: (String) -> String?,
    onDismiss: () -> Unit,
) {
    val pretty = remember(node.outboundJson) {
        runCatching { JSONObject(node.outboundJson).toString(2) }.getOrDefault(node.outboundJson)
    }
    var content by remember(node) { mutableStateOf(pretty) }
    var error by remember { mutableStateOf<String?>(null) }
    val sheetHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.72f
    }

    GlassBottomSheet(
        title = "${stringResource(R.string.workspace_edit_node_json)} · ${node.tag}",
        show = true,
        backdrop = LocalLayerBackdrop.current,
        onDismissRequest = onDismiss,
        startAction = {
            IconButton(onClick = onDismiss) {
                Icon(imageVector = MiuixIcons.Close, contentDescription = stringResource(R.string.cancel))
            }
        },
        endAction = {
            IconButton(
                onClick = {
                    val err = onSave(content)
                    if (err != null) error = err else onDismiss()
                }
            ) {
                Icon(imageVector = MiuixIcons.Ok, contentDescription = stringResource(R.string.save))
            }
        },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = sheetHeight)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                error?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                TextField(
                    value = content,
                    onValueChange = { content = it; error = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                )
            }
        },
    )
}

@Composable
internal fun workspaceMemberLabel(member: GroupMemberRef): String = when {
    member.isGroup -> stringResource(R.string.outbound_group_target_group, member.tag)
    member.tag in BUILTIN_GROUP_TARGET_TAGS -> stringResource(R.string.outbound_group_target_builtin, member.tag)
    else -> stringResource(R.string.outbound_group_target_node, member.tag)
}

@Composable
internal fun strategyShortLabel(strategy: GroupStrategy): String = when (strategy) {
    GroupStrategy.FALLBACK -> "fallback"
    GroupStrategy.URL_TEST -> "url-test"
    GroupStrategy.SELECT -> "select"
}
