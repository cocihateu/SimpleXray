package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.prefs.TunnelMode
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop

@Composable
internal fun SettingsTunnelSection(
    mainViewModel: MainViewModel,
) {
    val settingsState by mainViewModel.settingsState.collectAsStateWithLifecycle()
    val vpnDisabled = settingsState.switches.disableVpn
    val backdrop = LocalLayerBackdrop.current

    val xrayTunTitle = stringResource(R.string.tunnel_mode_xray_tun)
    val xrayTunSummary = stringResource(R.string.tunnel_mode_xray_tun_summary)
    val hevTitle = stringResource(R.string.tunnel_mode_hev_socks5)
    val hevSummary = stringResource(R.string.tunnel_mode_hev_socks5_summary)
    val simpleTunTitle = stringResource(R.string.tunnel_mode_simpletun)
    val simpleTunSummary = stringResource(R.string.tunnel_mode_simpletun_summary)

    val tunnelModes = remember {
        listOf(
            TunnelMode.XrayTun,
            TunnelMode.HevSocks5Tunnel,
            TunnelMode.SimpleTun,
        )
    }
    val tunnelModeTitles = remember(xrayTunTitle, hevTitle, simpleTunTitle) {
        listOf(xrayTunTitle, hevTitle, simpleTunTitle)
    }
    val currentTunnelModeIndex = remember(settingsState.switches.tunnelMode) {
        tunnelModes.indexOf(settingsState.switches.tunnelMode).coerceAtLeast(0)
    }
    val currentTunnelModeSummary = when (settingsState.switches.tunnelMode) {
        TunnelMode.XrayTun -> xrayTunSummary
        TunnelMode.HevSocks5Tunnel -> hevSummary
        TunnelMode.SimpleTun -> simpleTunSummary
    }

        SmallTitle(text = stringResource(R.string.vpn_interface))
        Card(modifier = Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = stringResource(R.string.apps_title),
                onClick = { mainViewModel.navigateToAppList() }
            )

            SwitchPreference(
                title = stringResource(R.string.disable_vpn_title),
                summary = stringResource(R.string.disable_vpn_summary),
                checked = settingsState.switches.disableVpn,
                onCheckedChange = { mainViewModel.setDisableVpnEnabled(it) }
            )

            GlassDropdownPreference(
                title = stringResource(R.string.tunnel_mode_title),
                summary = currentTunnelModeSummary,
                items = tunnelModeTitles,
                selectedIndex = currentTunnelModeIndex,
                onSelectedIndexChange = { index ->
                    mainViewModel.setTunnelMode(tunnelModes[index])
                },
                backdrop = backdrop,
                enabled = !vpnDisabled
            )

            val isSimpleTun = settingsState.switches.tunnelMode == TunnelMode.SimpleTun

            EditableListItemWithMiuixBottomSheet(
                headline = stringResource(R.string.tunnel_mtu_title),
                currentValue = if (isSimpleTun) "1500" else settingsState.tunnelMtu.value,
                onValueConfirmed = { newValue -> mainViewModel.updateTunnelMtu(newValue) },
                label = stringResource(R.string.tunnel_mtu_title),
                supportingText = stringResource(R.string.tunnel_mtu_summary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !vpnDisabled && !isSimpleTun
            )

            SwitchPreference(
                title = stringResource(R.string.ipv6),
                summary = stringResource(R.string.ipv6_summary),
                checked = settingsState.switches.ipv6Enabled,
                onCheckedChange = { mainViewModel.setIpv6Enabled(it) },
                enabled = !vpnDisabled && !isSimpleTun
            )

        }
    
}
