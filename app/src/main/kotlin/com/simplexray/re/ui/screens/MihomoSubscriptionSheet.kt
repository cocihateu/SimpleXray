package com.simplexray.re.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MihomoSubscriptionSheet(
    mainViewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val state by mainViewModel.mihomoSubscriptionState.collectAsStateWithLifecycle()
    val selectedFile by mainViewModel.selectedConfigFile.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(mainViewModel::importMihomoSubscriptionFile)
    }
    val sheetHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.7f
    }

    OverlayBottomSheet(
        title = stringResource(R.string.subscription_title),
        show = true,
        onDismissRequest = onDismiss,
        startAction = {
            IconButton(onClick = onDismiss) {
                Icon(imageVector = MiuixIcons.Close, contentDescription = stringResource(R.string.cancel))
            }
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = sheetHeight),
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = selectedFile?.let {
                        stringResource(R.string.subscription_merge_target, it.name)
                    } ?: stringResource(R.string.subscription_create_target),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            item {
                Text(
                    text = stringResource(R.string.subscription_policy_hint),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            item {
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    label = stringResource(R.string.subscription_url),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { mainViewModel.importMihomoSubscriptionUrl(url) },
                        enabled = state.isImporting == false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.subscription_import_url))
                    }
                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = state.isImporting == false,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.subscription_import_file))
                    }
                }
            }
            state.progress?.let { message ->
                item {
                    Text(text = message, style = MiuixTheme.textStyles.body1, color = MiuixTheme.colorScheme.primary)
                }
            }
            state.error?.let { message ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = message,
                            modifier = Modifier.padding(16.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error
                        )
                    }
                }
            }
            state.summary?.let { summary ->
                item {
                    SmallTitle(text = stringResource(R.string.subscription_import_complete))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.subscription_import_summary,
                                    summary.fileName,
                                    summary.nodeCount,
                                    summary.groupCount,
                                ),
                                style = MiuixTheme.textStyles.body1
                            )
                            summary.notes.forEach { note ->
                                Text(
                                    text = note,
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
