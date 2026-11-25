package com.simplexray.re.ui.screens

import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.R
import com.simplexray.re.viewmodel.MainViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import com.simplexray.re.ui.components.ConfirmOverlayDialog
import com.simplexray.re.ui.components.InfoOverlayDialog
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop

@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    geoipFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    geositeFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    scrollState: LazyListState,
    paddingValues: PaddingValues = PaddingValues(),
    onOpenRuleResources: () -> Unit = {},
    ruleResourcesOnly: Boolean = false,
) {
    val geoipProgress by mainViewModel.geoipDownloadProgress.collectAsStateWithLifecycle()
    val geositeProgress by mainViewModel.geositeDownloadProgress.collectAsStateWithLifecycle()
    val customDatProgress by mainViewModel.customDatDownloadProgress.collectAsStateWithLifecycle()
    val newVersionTag by mainViewModel.newVersionAvailable.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        mainViewModel.updateSettingsState()
        mainViewModel.refreshCustomDatFiles()
    }

    var showGeoipDeleteDialog by remember { mutableStateOf(false) }
    var showGeositeDeleteDialog by remember { mutableStateOf(false) }

    var editingRuleFile by remember { mutableStateOf<String?>(null) }
    var ruleFileUrl by remember { mutableStateOf("") }

    var showDatUrlImportSheet by remember { mutableStateOf(false) }
    var datImportUrl by remember { mutableStateOf("") }

    val geoipUrlDefault = stringResource(R.string.geoip_url)
    val geositeUrlDefault = stringResource(R.string.geosite_url)
    val backdrop = LocalLayerBackdrop.current
    val category = mainViewModel.settingsCategory
    val categories = listOf("通用", "TUN", "入站", "规则", "DNS", "面板", "关于")

    if (editingRuleFile != null) {
        GlassBottomSheet(
            title = editingRuleFile ?: "Rule File URL",
            show = editingRuleFile != null,
            backdrop = backdrop,
            onDismissRequest = { editingRuleFile = null },
            startAction = {
                IconButton(onClick = { editingRuleFile = null }) {
                    Icon(imageVector = MiuixIcons.Close, contentDescription = stringResource(R.string.cancel))
                }
            },
            endAction = {
                IconButton(onClick = {
                    val fileName = editingRuleFile
                    if (fileName != null) {
                        if (fileName != "geoip.dat" && fileName != "geosite.dat") {
                            mainViewModel.updateCustomDatUrl(fileName, ruleFileUrl)
                        }
                        mainViewModel.downloadRuleFile(ruleFileUrl, fileName)
                    }
                    editingRuleFile = null
                }) {
                    Icon(imageVector = MiuixIcons.Ok, contentDescription = stringResource(R.string.update))
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                TextField(
                    value = ruleFileUrl,
                    onValueChange = { ruleFileUrl = it },
                    label = "URL",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // "Restore default URL" only applies to the built-in GEO files;
                // third-party dat files have no default address.
                if (editingRuleFile == "geoip.dat" || editingRuleFile == "geosite.dat") {
                    TextButton(
                        text = stringResource(id = R.string.restore_default_url),
                        onClick = {
                            ruleFileUrl =
                                if (editingRuleFile == "geoip.dat") geoipUrlDefault
                                else geositeUrlDefault
                        }
                    )
                }
            }
        }
    }

    if (showDatUrlImportSheet) {
        GlassBottomSheet(
            title = stringResource(R.string.download_from_url_import),
            show = showDatUrlImportSheet,
            backdrop = backdrop,
            onDismissRequest = {
                showDatUrlImportSheet = false
                datImportUrl = ""
            },
            startAction = {
                IconButton(onClick = {
                    showDatUrlImportSheet = false
                    datImportUrl = ""
                }) {
                    Icon(imageVector = MiuixIcons.Close, contentDescription = stringResource(R.string.cancel))
                }
            },
            endAction = {
                IconButton(onClick = {
                    mainViewModel.downloadDatFromUrl(datImportUrl.trim())
                    showDatUrlImportSheet = false
                    datImportUrl = ""
                }) {
                    Icon(imageVector = MiuixIcons.Ok, contentDescription = stringResource(R.string.confirm))
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                TextField(
                    value = datImportUrl,
                    onValueChange = { datImportUrl = it },
                    label = "URL",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showGeoipDeleteDialog) {
        ConfirmOverlayDialog(
            title = stringResource(R.string.delete_rule_file_title),
            summary = stringResource(R.string.delete_rule_file_message),
            confirmText = stringResource(R.string.confirm),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                mainViewModel.restoreDefaultGeoip { }
                showGeoipDeleteDialog = false
            },
            onDismiss = { showGeoipDeleteDialog = false }
        )
    }

    if (showGeositeDeleteDialog) {
        ConfirmOverlayDialog(
            title = stringResource(R.string.delete_rule_file_title),
            summary = stringResource(R.string.delete_rule_file_message),
            confirmText = stringResource(R.string.confirm),
            cancelText = stringResource(R.string.cancel),
            onConfirm = {
                mainViewModel.restoreDefaultGeosite { }
                showGeositeDeleteDialog = false
            },
            onDismiss = { showGeositeDeleteDialog = false }
        )
    }

    var activeHelpDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    if (activeHelpDialog != null) {
        InfoOverlayDialog(
            title = activeHelpDialog!!.first,
            summary = activeHelpDialog!!.second,
            onDismiss = { activeHelpDialog = null }
        )
    }

    if (newVersionTag != null) {
        ConfirmOverlayDialog(
            title = stringResource(R.string.new_version_available_title),
            summary = stringResource(R.string.new_version_available_message, newVersionTag!!),
            confirmText = stringResource(R.string.download),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { mainViewModel.downloadNewVersion(newVersionTag!!) },
            onDismiss = { mainViewModel.clearNewVersionAvailable() }
        )
    }

    val isWideScreen = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width >= 600.dp.roundToPx()
    }
    val topPadding = paddingValues.calculateTopPadding() + 12.dp
    val bottomPadding = paddingValues.calculateBottomPadding().coerceAtLeast(12.dp)

    if (category == 3 && ruleResourcesOnly == false) {
        RulesScreen(
            mainViewModel = mainViewModel,
            paddingValues = paddingValues,
            onOpenRuleResources = onOpenRuleResources,
            categoryHeader = {
                SettingsCategorySelector(mainViewModel, categories, category)
            },
        )
        return
    }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isWideScreen) Modifier.widthIn(max = 840.dp) else Modifier
                ),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topPadding, bottom = bottomPadding)
        ) {
        if (ruleResourcesOnly == false) item {
            SettingsCategorySelector(mainViewModel, categories, category)
        }
        if (category == 0 && ruleResourcesOnly == false) item {
            SettingsGeneralSection(
                mainViewModel = mainViewModel,
                onShowHelp = { title, summary -> activeHelpDialog = title to summary },
            )
        }

        if (category == 1 && ruleResourcesOnly == false) item {
            SettingsTunnelSection(mainViewModel = mainViewModel)
            SettingsLanSection(mainViewModel = mainViewModel,
                onShowHelp = { title, summary -> activeHelpDialog = title to summary })
        }

        if (category == 2 && ruleResourcesOnly == false) item {
            SettingsInboundSection(
                mainViewModel = mainViewModel,
                onShowHelp = { title, summary -> activeHelpDialog = title to summary },
            )
        }

        if (ruleResourcesOnly) item {
            SettingsRuleFilesSection(
                mainViewModel = mainViewModel,
                geoipProgress = geoipProgress,
                geositeProgress = geositeProgress,
                customDatProgress = customDatProgress,
                geoipFilePickerLauncher = geoipFilePickerLauncher,
                geositeFilePickerLauncher = geositeFilePickerLauncher,
                onEditRuleFile = { fileName, url ->
                    ruleFileUrl = url
                    editingRuleFile = fileName
                },
                onDeleteGeoip = { showGeoipDeleteDialog = true },
                onDeleteGeosite = { showGeositeDeleteDialog = true },
                onShowDatUrlImport = { showDatUrlImportSheet = true },
            )
        }

        if (category == 4 && ruleResourcesOnly == false) item {
            SettingsGlobalConfigSection()
        }

        if (category == 5 && ruleResourcesOnly == false) item {
            SettingsClashApiSection(mainViewModel = mainViewModel)
        }

        if (category == 6 && ruleResourcesOnly == false) item {
            SettingsAboutSection(mainViewModel)
        }
    }
}
}

@Composable
private fun SettingsCategorySelector(
    mainViewModel: MainViewModel,
    categories: List<String>,
    selectedIndex: Int,
) {
    Card {
        OverlayDropdownPreference(
            title = "设置分类",
            items = categories,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { mainViewModel.settingsCategory = it },
        )
    }
}

