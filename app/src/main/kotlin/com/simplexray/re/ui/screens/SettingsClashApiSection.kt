package com.simplexray.re.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simplexray.re.R
import com.simplexray.re.service.ClashApiPreferences
import com.simplexray.re.service.TProxyService
import com.simplexray.re.service.VpnRunningState
import com.simplexray.re.service.VpnStateHub
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsClashApiSection(
    mainViewModel: MainViewModel,
) {
    val context = LocalContext.current
    val prefs = remember { ClashApiPreferences(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var port by remember { mutableIntStateOf(prefs.port) }
    var secret by remember { mutableStateOf(prefs.secret) }

    SmallTitle(text = stringResource(R.string.clash_api_title))
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = "打开内置 zashboard",
            summary = "http://127.0.0.1:$port · 离线可用，需启动服务",
            onClick = {
                when {
                    !prefs.enabled -> mainViewModel.showSnackbar("请先开启控制器")
                    VpnStateHub.state.value != VpnRunningState.Connected ->
                        mainViewModel.showSnackbar("请先启动服务，等待连接成功")
                    else -> context.startService(
                        Intent(context, TProxyService::class.java).setAction(TProxyService.ACTION_OPEN_ZASHBOARD)
                    )
                }
            },
        )
        SwitchPreference(
            title = stringResource(R.string.clash_api_enable),
            summary = stringResource(R.string.clash_api_enable_summary),
            checked = enabled,
            onCheckedChange = { newValue ->
                enabled = newValue
                prefs.enabled = newValue
                TProxyService.applyClashApiSettings(context)
                mainViewModel.showSnackbar(context.getString(R.string.clash_api_restart_notice))
            }
        )

        EditableListItemWithMiuixBottomSheet(
            headline = stringResource(R.string.clash_api_port),
            currentValue = port.toString(),
            onValueConfirmed = { newValue ->
                val parsed = newValue.toIntOrNull()
                if (parsed != null && parsed in 1024..65535) {
                    port = parsed
                    prefs.port = parsed
                    TProxyService.applyClashApiSettings(context)
                    mainViewModel.showSnackbar(context.getString(R.string.clash_api_restart_notice))
                }
            },
            label = stringResource(R.string.clash_api_port),
            supportingText = "1024–65535",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = enabled,
        )

        BasicComponent(
            title = stringResource(R.string.panel_secret),
            endActions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            secret = prefs.resetSecret()
                            TProxyService.applyClashApiSettings(context)
                            mainViewModel.showSnackbar(context.getString(R.string.panel_secret_generated))
                        },
                        enabled = enabled,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = stringResource(R.string.clash_api_generate_secret),
                        )
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Clash Secret", secret))
                            mainViewModel.showSnackbar(context.getString(R.string.clash_api_secret_copied))
                        },
                        enabled = enabled && secret.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Copy,
                            contentDescription = stringResource(R.string.clash_api_copy_secret),
                        )
                    }
                }
            },
            enabled = enabled,
        )

        Text(
            text = stringResource(R.string.clash_api_restart_notice),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
