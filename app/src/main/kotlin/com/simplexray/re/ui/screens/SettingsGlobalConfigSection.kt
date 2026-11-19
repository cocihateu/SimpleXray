package com.simplexray.re.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun SettingsGlobalConfigSection(
    mainViewModel: MainViewModel,
) {
    val summary by mainViewModel.globalConfigSummary.collectAsStateWithLifecycle()
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            mainViewModel.importGlobalConfigFromUri(uri)
        }
    }

    val routingText = summary.routingScript?.let { script ->
        stringResource(R.string.global_config_routing_lua, script)
    } ?: stringResource(R.string.global_config_routing_rules, summary.routingRuleCount)
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
}
