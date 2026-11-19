package com.simplexray.re.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsGlobalConfigSection(
    mainViewModel: MainViewModel,
) {
    val summary by mainViewModel.globalConfigSummary.collectAsStateWithLifecycle()
    val rules by mainViewModel.trafficRules.collectAsStateWithLifecycle()
    val workspace by mainViewModel.groupWorkspace.collectAsStateWithLifecycle()
    val bindings = remember(workspace) { workspace.effectiveRoutingBindings() }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            mainViewModel.importGlobalConfigFromUri(uri)
        }
    }

    val routingText = stringResource(R.string.global_config_routing_rules, rules.count { it.enabled })
    val summaryText = stringResource(
        R.string.global_config_summary,
        summary.dnsServerCount,
        summary.inboundCount,
        routingText,
    )

    SmallTitle(text = stringResource(R.string.global_config_category_title))
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.global_config_edit_title),
            summary = summaryText,
            onClick = { mainViewModel.openGlobalConfigEditor() },
        )
        ArrowPreference(
            title = stringResource(R.string.global_config_import_title),
            onClick = { filePicker.launch(arrayOf("*/*")) },
        )
    }

    if (bindings.isNotEmpty()) {
        SmallTitle(text = stringResource(R.string.workspace_routing_bindings_title))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.workspace_routing_bindings_summary),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                bindings.forEach { (target, groupTag) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = target,
                            style = MiuixTheme.textStyles.body1,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "→ $groupTag",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
