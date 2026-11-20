package com.simplexray.re.ui.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.simplexray.re.R
import com.simplexray.re.common.ROUTE_OVERVIEW
import com.simplexray.re.common.ROUTE_PROXIES
import com.simplexray.re.common.ROUTE_GROUPS
import com.simplexray.re.common.ROUTE_GROUP_SETTINGS
import com.simplexray.re.common.ROUTE_LOG
import com.simplexray.re.common.ROUTE_RULES
import com.simplexray.re.common.ROUTE_SETTINGS
import com.simplexray.re.viewmodel.LogViewModel
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
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
private val routes = listOf(ROUTE_OVERVIEW, ROUTE_PROXIES, ROUTE_RULES, ROUTE_LOG, ROUTE_SETTINGS)
private val titles = listOf("概览", "代理", "规则", "日志", "设置")

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
    val managingGroups = entry?.destination?.route == ROUTE_GROUPS
    val groupSettings = entry?.destination?.route == ROUTE_GROUP_SETTINGS
    val selected = if (managingGroups || groupSettings) routes.indexOf(ROUTE_PROXIES)
        else routes.indexOf(entry?.destination?.route).coerceAtLeast(0)
    var isLogSearching by remember { mutableStateOf(false) }
    val searchingLogs = routes[selected] == ROUTE_LOG && isLogSearching
    val logSearchQuery by logViewModel.searchQuery.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(searchingLogs) {
        if (searchingLogs) {
            focusRequester.requestFocus()
        }
    }

    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
    val icons = listOf(R.drawable.dashboard, R.drawable.code, R.drawable.history, R.drawable.history, R.drawable.settings)
    val items = icons.mapIndexed { index, icon ->
        GlassNavigationItem(ImageVector.vectorResource(icon), titles[index], titles[index])
    }
    CompositionLocalProvider(LocalLayerBackdrop provides backdrop) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                GlassTopAppBar(title = when { searchingLogs -> ""; managingGroups -> "分组管理"; groupSettings -> "分组设置"; else -> titles[selected] },
                    expandable = false, backdrop = backdrop, navigationIcon = {
                        if (searchingLogs) {
                            IconButton(onClick = {
                                isLogSearching = false
                                logViewModel.onSearchQueryChange("")
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close_search))
                            }
                        } else if (managingGroups || groupSettings) IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }, actions = {
                    if (searchingLogs) {
                        InputField(
                            query = logSearchQuery,
                            onQueryChange = logViewModel::onSearchQueryChange,
                            onSearch = {},
                            expanded = isLogSearching,
                            onExpandedChange = { isLogSearching = it },
                            label = stringResource(R.string.search),
                            modifier = Modifier.fillMaxWidth(0.7f).focusRequester(focusRequester)
                        )
                        if (logSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { logViewModel.onSearchQueryChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear_search))
                            }
                        }
                    } else if (managingGroups) {
                        IconButton(onClick = { mainViewModel.createGroupRequested = true }) {
                            Icon(Icons.Default.Add, contentDescription = "新建分组")
                        }
                    } else if (routes[selected] == ROUTE_PROXIES && groupSettings == false) {
                        IconButton(onClick = { mainViewModel.proxyPageAction = "group" }) {
                            Icon(Icons.Default.Add, contentDescription = "新建分组")
                        }
                        OverlayIconDropdownMenu(entry = DropdownEntry(listOf(
                            DropdownItem("分组管理", onClick = { navController.navigate(ROUTE_GROUPS) { launchSingleTop = true } }),
                            DropdownItem("测量当前组延迟", onClick = { mainViewModel.proxyPageAction = "measure" }),
                            DropdownItem("更新全部订阅", onClick = {
                                mainViewModel.groupWorkspace.value.groups
                                    .filter { it.sourceType == com.simplexray.re.common.config.GroupSourceType.SUBSCRIPTION }
                                    .forEach { mainViewModel.refreshSubscriptionGroup(it.tag) }
                            }),
                        ))) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                    } else if (routes[selected] == ROUTE_LOG) {
                        LogActions(
                            onPerformExport = onPerformExport,
                            logViewModel = logViewModel,
                            onLogSearchingChange = { isLogSearching = it },
                            mainViewModel = mainViewModel
                        )
                    }
                })
            },
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

@Composable
private fun LogActions(
    onPerformExport: () -> Unit,
    logViewModel: LogViewModel,
    onLogSearchingChange: (Boolean) -> Unit,
    mainViewModel: MainViewModel
) {
    val hasLogsToExport by logViewModel.hasLogsToExport.collectAsStateWithLifecycle()
    val logEntries by logViewModel.logEntries.collectAsStateWithLifecycle()
    val logsCleared = stringResource(R.string.logs_cleared)

    IconButton(onClick = { onLogSearchingChange(true) }) {
        Icon(
            painter = painterResource(R.drawable.search),
            contentDescription = stringResource(R.string.search)
        )
    }
    IconButton(onClick = onPerformExport, enabled = hasLogsToExport) {
        Icon(
            painter = painterResource(R.drawable.ic_export),
            contentDescription = stringResource(R.string.export)
        )
    }
    IconButton(
        onClick = {
            logViewModel.clearLogs()
            mainViewModel.showSnackbar(logsCleared)
        },
        enabled = logEntries.isNotEmpty()
    ) {
        Icon(
            painter = painterResource(R.drawable.delete),
            contentDescription = stringResource(R.string.clear_logs)
        )
    }
}
