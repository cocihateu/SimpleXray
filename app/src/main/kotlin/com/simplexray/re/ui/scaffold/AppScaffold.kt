package com.simplexray.re.ui.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.simplexray.re.R
import com.simplexray.re.common.ROUTE_OVERVIEW
import com.simplexray.re.common.ROUTE_PROXIES
import com.simplexray.re.common.ROUTE_PROFILES
import com.simplexray.re.common.ROUTE_RULES
import com.simplexray.re.common.ROUTE_SETTINGS
import com.simplexray.re.viewmodel.LogViewModel
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalLayerBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
private val routes = listOf(ROUTE_OVERVIEW, ROUTE_PROXIES, ROUTE_PROFILES, ROUTE_RULES, ROUTE_SETTINGS)
private val titles = listOf("概览", "代理", "订阅", "规则", "设置")

@Composable
fun AppScaffold(
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    mainViewModel: MainViewModel,
    logViewModel: LogViewModel,
    onCreateNewConfigFileAndEdit: () -> Unit,
    onImportConfigFromClipboard: () -> Unit,
    onPerformExport: () -> Unit,
    onSwitchVpnService: () -> Unit,
    logListState: LazyListState,
    configListState: LazyListState,
    settingsScrollState: LazyListState,
    content: @Composable (PaddingValues) -> Unit,
) {
    val entry by navController.currentBackStackEntryAsState()
    val selected = routes.indexOf(entry?.destination?.route).coerceAtLeast(0)
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
    val icons = listOf(R.drawable.dashboard, R.drawable.code, R.drawable.upload_file, R.drawable.history, R.drawable.settings)
    val items = icons.mapIndexed { index, icon ->
        GlassNavigationItem(ImageVector.vectorResource(icon), titles[index], titles[index])
    }
    CompositionLocalProvider(LocalLayerBackdrop provides backdrop) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { GlassTopAppBar(title = titles[selected], expandable = false, backdrop = backdrop) },
            bottomBar = {
                GlassNavigationBar(
                    items = items,
                    selectedIndex = selected,
                    onSelect = { index ->
                        navController.navigate(routes[index]) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            },
            snackbarHost = { SnackbarHost(state = snackbarHostState) },
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                content(PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp))
            }
        }
    }
}
