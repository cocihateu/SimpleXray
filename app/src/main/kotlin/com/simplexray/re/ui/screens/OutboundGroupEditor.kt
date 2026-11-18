package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import com.simplexray.re.common.config.GroupProfile
import com.simplexray.re.common.config.GroupTarget
import com.simplexray.re.common.config.OutboundGroup
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun OutboundGroupEditor(
    profile: GroupProfile,
    nodeTags: Set<String>,
    group: OutboundGroup?,
    onSave: (GroupProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var tag by remember(group) { mutableStateOf(group?.tag.orEmpty()) }
    var primary by remember(group) { mutableStateOf(group?.primary) }
    var fallbacks by remember(group) { mutableStateOf(group?.fallbacks.orEmpty()) }
    var intervalSeconds by remember(group) {
        mutableStateOf((group?.intervalSeconds ?: 60).toString())
    }
    var recoveryIntervalSeconds by remember(group) {
        mutableStateOf((group?.recoveryIntervalSeconds ?: 300).toString())
    }
    var trustSeconds by remember(group) {
        mutableStateOf((group?.trustSeconds ?: 120).toString())
    }
    var fallbackToAdd by remember(group) { mutableStateOf<GroupTarget?>(null) }
    val targets = remember(nodeTags, profile.groups) {
        (nodeTags + BUILTIN_GROUP_TARGET_TAGS).map { GroupTarget(tag = it) } +
            profile.groups.map { GroupTarget(tag = it.tag, isGroup = true) }
    }
    val candidateProfile = remember(
        profile, group, tag, primary, fallbacks, intervalSeconds, recoveryIntervalSeconds, trustSeconds
    ) {
        val selectedPrimary = primary
        val interval = intervalSeconds.toIntOrNull()
        val recoveryInterval = recoveryIntervalSeconds.toIntOrNull()
        val trust = trustSeconds.toIntOrNull()
        if (selectedPrimary != null && interval != null && recoveryInterval != null && trust != null) {
            val editedGroup = OutboundGroup(
                tag = tag,
                primary = selectedPrimary,
                fallbacks = fallbacks,
                intervalSeconds = interval,
                trustSeconds = trust,
                recoveryIntervalSeconds = recoveryInterval
            )
            GroupProfile(
                groups = if (group == null) {
                    profile.groups + editedGroup
                } else {
                    profile.groups.map { existing ->
                        if (existing.tag == group.tag) editedGroup else existing
                    }
                },
                targetStrategies = profile.targetStrategies
            )
        } else {
            null
        }
    }
    val validProfile = remember(candidateProfile, nodeTags) {
        candidateProfile?.takeIf { it.isValid(nodeTags) }
    }
    val editorHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.7f
    }

    OverlayBottomSheet(
        title = if (group == null) {
            stringResource(R.string.outbound_group_create)
        } else {
            stringResource(R.string.outbound_group_edit, group.tag)
        },
        show = true,
        onDismissRequest = onDismiss,
        startAction = {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.cancel)
                )
            }
        },
        endAction = {
            IconButton(
                onClick = { validProfile?.let(onSave) },
                enabled = validProfile != null
            ) {
                Icon(
                    imageVector = MiuixIcons.Ok,
                    contentDescription = stringResource(R.string.save)
                )
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = editorHeight),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (group == null) {
                item {
                    TextField(
                        value = tag,
                        onValueChange = { tag = it },
                        label = stringResource(R.string.outbound_group_name),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    GroupTargetPreference(
                        title = stringResource(R.string.outbound_group_primary),
                        targets = targets,
                        selectedTarget = primary,
                        onTargetSelected = { primary = it }
                    )
                }
            }
            item {
                SmallTitle(text = stringResource(R.string.outbound_group_fallbacks))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(
                            text = stringResource(R.string.outbound_group_fallback_order_hint),
                            modifier = Modifier.padding(16.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        fallbacks.forEachIndexed { index, target ->
                            val targetLabel = outboundGroupTargetLabel(target)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.outbound_group_ordered_target,
                                        index + 1,
                                        targetLabel
                                    ),
                                    modifier = Modifier.weight(1f),
                                    style = MiuixTheme.textStyles.body1
                                )
                                IconButton(
                                    onClick = {
                                        fallbacks = fallbacks.toMutableList().apply {
                                            add(index - 1, removeAt(index))
                                        }
                                    },
                                    enabled = index > 0
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = stringResource(
                                            R.string.outbound_group_move_up, targetLabel
                                        )
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        fallbacks = fallbacks.toMutableList().apply {
                                            add(index + 1, removeAt(index))
                                        }
                                    },
                                    enabled = index < fallbacks.lastIndex
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(
                                            R.string.outbound_group_move_down, targetLabel
                                        )
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        fallbacks = fallbacks.toMutableList().apply {
                                            removeAt(index)
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.delete),
                                        contentDescription = stringResource(
                                            R.string.outbound_group_remove_fallback, targetLabel
                                        )
                                    )
                                }
                            }
                        }
                        GroupTargetPreference(
                            title = stringResource(R.string.outbound_group_fallback_target),
                            targets = targets,
                            selectedTarget = fallbackToAdd,
                            onTargetSelected = { fallbackToAdd = it }
                        )
                        Button(
                            onClick = {
                                fallbackToAdd?.let { fallbacks = fallbacks + it }
                                fallbackToAdd = null
                            },
                            enabled = fallbackToAdd != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(text = stringResource(R.string.outbound_group_add_fallback))
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(
                        value = intervalSeconds,
                        onValueChange = { intervalSeconds = it },
                        label = stringResource(R.string.outbound_group_interval_seconds),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextField(
                        value = recoveryIntervalSeconds,
                        onValueChange = { recoveryIntervalSeconds = it },
                        label = stringResource(R.string.outbound_group_recovery_interval_seconds),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.outbound_group_recovery_hint),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    TextField(
                        value = trustSeconds,
                        onValueChange = { trustSeconds = it },
                        label = stringResource(R.string.outbound_group_trust_seconds),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                Text(
                    text = stringResource(R.string.outbound_group_validation_hint),
                    style = MiuixTheme.textStyles.body2,
                    color = if (candidateProfile != null && validProfile == null) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }
                )
            }
        }
    }
}

@Composable
private fun GroupTargetPreference(
    title: String,
    targets: List<GroupTarget>,
    selectedTarget: GroupTarget?,
    onTargetSelected: (GroupTarget?) -> Unit
) {
    val labels = listOf(stringResource(R.string.outbound_group_select_target)) +
        targets.map { outboundGroupTargetLabel(it) }
    val selectedIndex = targets.indexOfFirst {
        it.tag == selectedTarget?.tag && it.isGroup == selectedTarget?.isGroup
    } + 1

    OverlayDropdownPreference(
        title = title,
        items = labels,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            onTargetSelected(if (index == 0) null else targets[index - 1])
        }
    )
}

@Composable
internal fun outboundGroupTargetLabel(target: GroupTarget): String =
    stringResource(
        when {
            target.isGroup -> R.string.outbound_group_target_group
            target.tag in BUILTIN_GROUP_TARGET_TAGS -> R.string.outbound_group_target_builtin
            else -> R.string.outbound_group_target_node
        },
        target.tag
    )
