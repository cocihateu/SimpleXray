package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.common.ColorMode
import com.simplexray.re.common.ThemeMode
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop

@Composable
internal fun SettingsGeneralSection(
    mainViewModel: MainViewModel,
    onShowHelp: (String, String) -> Unit,
) {
    val settingsState by mainViewModel.settingsState.collectAsStateWithLifecycle()
    val backdrop = LocalLayerBackdrop.current
    val themeOptions = listOf(
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
        stringResource(R.string.auto)
    )
    val themeModes = listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.Auto)
    val currentThemeIndex = themeModes.indexOf(settingsState.switches.themeMode).coerceAtLeast(0)
    val colorOptions = listOf(
        stringResource(R.string.color_monochrome),
        stringResource(R.string.color_dynamic)
    )
    val colorModes = listOf(ColorMode.Monochrome, ColorMode.Dynamic)
    val currentColorIndex = colorModes.indexOf(settingsState.switches.colorMode).coerceAtLeast(0)
    val iconOptions = listOf(
        stringResource(R.string.icon_flat),
        stringResource(R.string.icon_lineal),
        stringResource(R.string.icon_lineal_color)
    )
    val iconKeys = listOf("flat", "lineal", "lineal_color")

        SmallTitle(text = stringResource(R.string.general))
        Card(modifier = Modifier.fillMaxWidth()) {
            GlassDropdownPreference(
                title = stringResource(R.string.theme_title),
                items = themeOptions,
                selectedIndex = currentThemeIndex,
                onSelectedIndexChange = { index ->
                    mainViewModel.setTheme(themeModes[index])
                },
                backdrop = backdrop
            )

            GlassDropdownPreference(
                title = stringResource(R.string.color_title),
                items = colorOptions,
                selectedIndex = currentColorIndex,
                onSelectedIndexChange = { index ->
                    mainViewModel.setColorMode(colorModes[index])
                },
                backdrop = backdrop
            )

            val currentIcon by mainViewModel.appIcon.collectAsStateWithLifecycle()
            val currentIconIndex = iconKeys.indexOf(currentIcon).coerceAtLeast(0)
            GlassDropdownPreference(
                title = stringResource(R.string.app_icon),
                items = iconOptions,
                selectedIndex = currentIconIndex,
                onSelectedIndexChange = { index ->
                    mainViewModel.setAppIcon(iconKeys[index])
                },
                backdrop = backdrop
            )

            SwitchPreference(
                title = stringResource(R.string.hide_from_recents_title),
                checked = settingsState.switches.hideFromRecents,
                onCheckedChange = { mainViewModel.setHideFromRecentsEnabled(it) }
            )

            val keepAwakeTitle = stringResource(R.string.keep_awake_title)
            val keepAwakeSummary = stringResource(R.string.keep_awake_summary)
            SwitchPreference(
                title = "",
                startAction = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = keepAwakeTitle,
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = { onShowHelp(keepAwakeTitle, keepAwakeSummary) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Help,
                                contentDescription = keepAwakeTitle,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                checked = settingsState.switches.keepAwake,
                onCheckedChange = { mainViewModel.setKeepAwakeEnabled(it) }
            )
        }
    
}
