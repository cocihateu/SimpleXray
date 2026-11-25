package com.simplexray.re.ui.navigation

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.common.ROUTE_OVERVIEW
import com.simplexray.re.common.ROUTE_PROXIES
import com.simplexray.re.common.ROUTE_GROUPS
import com.simplexray.re.common.ROUTE_GROUP_SETTINGS
import com.simplexray.re.common.ROUTE_CONFIG
import com.simplexray.re.common.ROUTE_LOG
import com.simplexray.re.common.ROUTE_RULE_RESOURCES
import com.simplexray.re.common.ROUTE_SETTINGS
import com.simplexray.re.ui.screens.DashboardScreen
import com.simplexray.re.ui.screens.LogScreen
import com.simplexray.re.ui.screens.ProxiesScreen
import com.simplexray.re.ui.screens.SettingsScreen
import com.simplexray.re.ui.screens.GroupManagementScreen
import com.simplexray.re.ui.screens.GroupSettingsScreen
import com.simplexray.re.ui.screens.WorkspaceConfigScreen
import com.simplexray.re.viewmodel.LogViewModel
import com.simplexray.re.viewmodel.MainViewModel

@Composable
fun BottomNavHost(
    navController: NavHostController,
    paddingValues: PaddingValues,
    mainViewModel: MainViewModel,
    logViewModel: LogViewModel,
    geoipFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    geositeFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    logListState: LazyListState,
    settingsScrollState: LazyListState,
    onSwitchVpnService: () -> Unit = {},
) {
    NavHost(navController, startDestination = ROUTE_OVERVIEW, modifier = Modifier.fillMaxSize()) {
        composable(ROUTE_OVERVIEW) { DashboardScreen(mainViewModel, onSwitchVpnService, paddingValues) }
        composable(ROUTE_PROXIES) {
            ProxiesScreen(mainViewModel, paddingValues,
                onOpenGroupSettings = { navController.navigate("proxy_group_settings/${Uri.encode(it)}") })
        }
        composable(ROUTE_CONFIG) {
            WorkspaceConfigScreen(mainViewModel, paddingValues)
        }
        composable(ROUTE_GROUPS) {
            GroupManagementScreen(mainViewModel, paddingValues,
                onOpenGroup = {
                    mainViewModel.selectConfigurationGroup(it)
                    navController.popBackStack()
                })
        }
        composable(ROUTE_GROUP_SETTINGS) { entry ->
            GroupSettingsScreen(mainViewModel, entry.arguments?.getString("tag").orEmpty(), paddingValues)
        }
        composable(ROUTE_RULE_RESOURCES) {
            SettingsScreen(
                mainViewModel = mainViewModel,
                geoipFilePickerLauncher = geoipFilePickerLauncher,
                geositeFilePickerLauncher = geositeFilePickerLauncher,
                scrollState = rememberLazyListState(),
                paddingValues = paddingValues,
                ruleResourcesOnly = true,
            )
        }
        composable(ROUTE_LOG) {
            val settings by mainViewModel.settingsState.collectAsStateWithLifecycle()
            LogScreen(
                logViewModel = logViewModel,
                listState = logListState,
                paddingValues = paddingValues,
                logLevel = settings.switches.logLevel,
                onLogLevelChange = mainViewModel::setLogLevel,
                accessLog = settings.switches.accessLog,
                onAccessLogChange = mainViewModel::setAccessLog,
                dnsLog = settings.switches.dnsLog,
                onDnsLogChange = mainViewModel::setDnsLog
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                mainViewModel = mainViewModel,
                geoipFilePickerLauncher = geoipFilePickerLauncher,
                geositeFilePickerLauncher = geositeFilePickerLauncher,
                scrollState = settingsScrollState,
                paddingValues = paddingValues,
                onOpenRuleResources = { navController.navigate(ROUTE_RULE_RESOURCES) },
            )
        }
    }
}
