package com.simplexray.re.ui.screens

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.simplexray.re.common.PERMISSION_ACCESS_LOCAL_NETWORK
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsLanSection(
    mainViewModel: MainViewModel,
    onShowHelp: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val localNetworkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        mainViewModel.prefs.localNetworkPrompted = true
    }

    val settingsState by mainViewModel.settingsState.collectAsStateWithLifecycle()
    val vpnDisabled = settingsState.switches.disableVpn
        SmallTitle(text = "局域网")
        Card(modifier = Modifier.fillMaxWidth()) {
            val bypassLanTitle = stringResource(R.string.bypass_lan_title)
            val bypassLanHelp = stringResource(R.string.lan_bypass_routing_help)
            SwitchPreference(
                title = "",
                startAction = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = bypassLanTitle,
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { onShowHelp(bypassLanTitle, bypassLanHelp) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Help,
                                contentDescription = bypassLanTitle,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                checked = settingsState.switches.bypassLanEnabled,
                onCheckedChange = { enabled ->
                    mainViewModel.setBypassLanEnabled(enabled)
                    if (enabled && Build.VERSION.SDK_INT >= 37) {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context,
                            PERMISSION_ACCESS_LOCAL_NETWORK
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasPermission) {
                            localNetworkLauncher.launch(PERMISSION_ACCESS_LOCAL_NETWORK)
                        }
                    }
                },
                enabled = !vpnDisabled
            )

        }
}

