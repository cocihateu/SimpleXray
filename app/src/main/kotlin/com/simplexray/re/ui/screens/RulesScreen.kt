package com.simplexray.re.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.common.config.TrafficRule
import com.simplexray.re.viewmodel.MainViewModel
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID

private val SUPPORTED_RULE_TYPES = listOf(
    TrafficRule.TYPE_DOMAIN_SUFFIX,
    TrafficRule.TYPE_DOMAIN_KEYWORD,
    TrafficRule.TYPE_DOMAIN,
    TrafficRule.TYPE_IP_CIDR,
    TrafficRule.TYPE_GEO_IP,
    TrafficRule.TYPE_GEO_SITE,
    TrafficRule.TYPE_DST_PORT,
    TrafficRule.TYPE_NETWORK,
    TrafficRule.TYPE_MATCH,
    TrafficRule.TYPE_XRAY
)

@Composable
fun RulesScreen(
    mainViewModel: MainViewModel,
    onBackClick: () -> Unit = {},
    paddingValues: PaddingValues = PaddingValues()
) {
    val rules by mainViewModel.trafficRules.collectAsStateWithLifecycle()
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()

    var editingRule by remember { mutableStateOf<TrafficRule?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    val availableTargets = remember(workspace.groups) {
        listOf(TrafficRule.TARGET_GLOBAL, TrafficRule.TARGET_DIRECT, TrafficRule.TARGET_BLOCK) +
            workspace.groups.map { it.tag } + workspace.allNodes().map { it.tag }
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Button(onClick = {
                    editingRule = TrafficRule(type = TrafficRule.TYPE_DOMAIN_SUFFIX, payload = "", target = TrafficRule.TARGET_GLOBAL)
                    isCreatingNew = true
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("添加规则") }
                val applyMessage by mainViewModel.ruleApplyMessage.collectAsStateWithLifecycle()
                if (applyMessage.isNotBlank()) Text(applyMessage, modifier = Modifier.padding(vertical = 8.dp))
            }

            if (rules.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.rules_empty),
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(rules, key = { _, item -> item.id }) { index, rule ->
                    RuleItemCard(
                        index = index + 1,
                        rule = rule,
                        canMoveUp = index > 0,
                        canMoveDown = index < rules.size - 1,
                        onToggleEnabled = {
                            mainViewModel.saveTrafficRule(rule.copy(enabled = it))
                        },
                        onMoveUp = {
                            mainViewModel.moveTrafficRule(rule.id, -1)
                        },
                        onMoveDown = {
                            mainViewModel.moveTrafficRule(rule.id, 1)
                        },
                        onEdit = {
                            editingRule = rule
                            isCreatingNew = false
                        },
                        onDelete = {
                            mainViewModel.deleteTrafficRule(rule.id)
                        }
                    )
                }
            }
        }
    if (editingRule != null) {
        RuleEditSheet(
            rule = editingRule!!,
            isNew = isCreatingNew,
            availableTargets = availableTargets,
            onDismiss = {
                editingRule = null
                isCreatingNew = false
            },
            onConfirm = { updatedRule ->
                mainViewModel.saveTrafficRule(updatedRule)
                editingRule = null
                isCreatingNew = false
            }
        )
    }
}

@Composable
private fun RuleItemCard(
    index: Int,
    rule: TrafficRule,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (rule.enabled) {
            CardDefaults.defaultColors()
        } else {
            CardDefaults.defaultColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$index",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.width(24.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = rule.type,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MiuixTheme.colorScheme.secondary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "→ ${rule.target}",
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = rule.payload.ifEmpty { "*" },
                    style = MiuixTheme.textStyles.body1,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (rule.enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggleEnabled
            )
        }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = canMoveUp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.rules_move_up),
                        tint = if (canMoveUp) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = canMoveDown,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.rules_move_down),
                        tint = if (canMoveDown) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.rules_edit)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.rules_delete),
                        tint = Color(0xFFD32F2F)
                    )
                }
            }
    }
}

@Composable
private fun RuleEditSheet(
    rule: TrafficRule,
    isNew: Boolean,
    availableTargets: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (TrafficRule) -> Unit
) {
    var selectedType by remember { mutableStateOf(rule.type) }
    var payloadText by remember { mutableStateOf(rule.payload) }
    var selectedTarget by remember { mutableStateOf(rule.target) }

    GlassBottomSheet(
        onDismissRequest = onDismiss,
        show = true,
        backdrop = LocalLayerBackdrop.current,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(if (isNew) R.string.rules_add else R.string.rules_edit),
                style = MiuixTheme.textStyles.title2
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.rules_type),
                    items = SUPPORTED_RULE_TYPES,
                    selectedIndex = SUPPORTED_RULE_TYPES.indexOf(selectedType).coerceAtLeast(0),
                    onSelectedIndexChange = { selectedType = SUPPORTED_RULE_TYPES[it] }
                )

                OverlayDropdownPreference(
                    title = stringResource(R.string.rules_target),
                    items = availableTargets,
                    selectedIndex = availableTargets.indexOf(selectedTarget).coerceAtLeast(0),
                    onSelectedIndexChange = { selectedTarget = availableTargets[it] }
                )
            }

            TextField(
                value = payloadText,
                onValueChange = { payloadText = it },
                label = stringResource(R.string.rules_payload),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors()
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        onConfirm(
                            rule.copy(
                                type = selectedType,
                                payload = payloadText.trim(),
                                target = selectedTarget
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        }
    }
}
