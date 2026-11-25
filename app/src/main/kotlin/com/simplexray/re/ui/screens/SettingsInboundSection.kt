package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.prefs.TunnelMode
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
internal fun SettingsInboundSection(
    mainViewModel: MainViewModel,
    onShowHelp: (String, String) -> Unit,
) {
    val settingsState by mainViewModel.settingsState.collectAsStateWithLifecycle()

        SmallTitle(text = stringResource(R.string.inbound_settings))
        Card(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = "仅用于路由 · routeOnly",
                summary = "嗅探域名仅用于匹配规则；关闭时也用作连接目标（默认关闭，重启服务生效）",
                checked = settingsState.switches.sniffingRouteOnly,
                onCheckedChange = mainViewModel::setSniffingRouteOnly,
            )
            EditableListItemWithMiuixBottomSheet(
                headline = stringResource(R.string.socks_address),
                currentValue = settingsState.socksAddress.value,
                onValueConfirmed = { newValue -> mainViewModel.updateSocksAddress(newValue) },
                label = stringResource(R.string.socks_address),
                supportingText = stringResource(
                    if (settingsState.switches.tunnelMode != TunnelMode.XrayTun) {
                        R.string.socks_address_summary_socks_tunnel
                    } else {
                        R.string.socks_address_summary
                    }
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            EditableListItemWithMiuixBottomSheet(
                headline = stringResource(R.string.socks_port),
                currentValue = settingsState.socksPort.value,
                onValueConfirmed = { newValue -> mainViewModel.updateSocksPort(newValue) },
                label = stringResource(R.string.socks_port),
                supportingText = stringResource(
                    if (settingsState.switches.tunnelMode != TunnelMode.XrayTun) {
                        R.string.socks_port_summary_socks_tunnel
                    } else {
                        R.string.socks_port_summary
                    }
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            EditableListItemWithMiuixBottomSheet(
                headline = stringResource(R.string.socks_user),
                currentValue = settingsState.socksUser.value,
                onValueConfirmed = { newValue -> mainViewModel.updateSocksUser(newValue) },
                label = stringResource(R.string.socks_user),
                supportingText = stringResource(R.string.socks_user_summary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )

            EditableListItemWithMiuixBottomSheet(
                headline = stringResource(R.string.socks_pass),
                currentValue = settingsState.socksPass.value,
                onValueConfirmed = { newValue -> mainViewModel.updateSocksPass(newValue) },
                label = stringResource(R.string.socks_pass),
                supportingText = stringResource(R.string.socks_pass_summary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            val httpProxyTitle = stringResource(R.string.http_proxy_title)
            val httpProxySummary = stringResource(R.string.http_proxy_summary)
            SwitchPreference(
                title = "",
                startAction = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = httpProxyTitle,
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { onShowHelp(httpProxyTitle, httpProxySummary) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Help,
                                contentDescription = httpProxyTitle,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                checked = settingsState.switches.httpProxyEnabled,
                onCheckedChange = { mainViewModel.setHttpProxyEnabled(it) },
            )
        }
    
}
