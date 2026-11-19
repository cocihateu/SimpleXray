package com.simplexray.re.ui.navigation

import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.simplexray.re.common.ROUTE_OVERVIEW
import com.simplexray.re.common.ROUTE_PROXIES
import com.simplexray.re.common.ROUTE_PROFILES
import com.simplexray.re.common.ROUTE_RULES
import com.simplexray.re.common.ROUTE_SETTINGS
import com.simplexray.re.ui.screens.DashboardScreen
import com.simplexray.re.ui.screens.ProxiesScreen
import com.simplexray.re.ui.screens.ProfilesScreen
import com.simplexray.re.ui.screens.RulesScreen
import com.simplexray.re.ui.screens.SettingsScreen
import com.simplexray.re.viewmodel.LogViewModel
import com.simplexray.re.viewmodel.MainViewModel
import java.io.File

@Composable
fun BottomNavHost(
    navController: NavHostController,
    paddingValues: PaddingValues,
    mainViewModel: MainViewModel,
    onDeleteConfigClick: (File) -> Unit,
    onCreateNewConfigFileAndEdit: () -> Unit = {},
    onImportConfigFromClipboard: () -> Unit = {},
    logViewModel: LogViewModel,
    geoipFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    geositeFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    logListState: LazyListState,
    configListState: LazyListState,
    settingsScrollState: LazyListState,
    onSwitchVpnService: () -> Unit = {},
) {
    NavHost(navController, startDestination = ROUTE_OVERVIEW, modifier = Modifier.fillMaxSize()) {
        composable(ROUTE_OVERVIEW) { DashboardScreen(mainViewModel, onSwitchVpnService, paddingValues) }
        composable(ROUTE_PROXIES) { ProxiesScreen(mainViewModel, paddingValues) }
        composable(ROUTE_PROFILES) { ProfilesScreen(mainViewModel, paddingValues) }
        composable(ROUTE_RULES) { RulesScreen(mainViewModel, paddingValues = paddingValues) }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(mainViewModel, geoipFilePickerLauncher, geositeFilePickerLauncher, settingsScrollState, paddingValues)
        }
    }
}
