package com.simplexray.re.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.simplexray.re.R
import com.simplexray.re.common.ColorMode
import com.simplexray.re.common.ConfigUtils
import com.simplexray.re.common.SocksAuthenticatorInstaller
import com.simplexray.re.common.ROUTE_APP_LIST
import com.simplexray.re.common.ThemeMode
import com.simplexray.re.common.config.GroupProfile
import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.common.config.GlobalConfigSummary
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.data.source.FileManager
import com.simplexray.re.prefs.LogLevel
import com.simplexray.re.prefs.Preferences
import com.simplexray.re.service.FallbackRuntimeInfo
import com.simplexray.re.service.VpnRunningState
import com.simplexray.re.service.VpnStateHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "MainViewModel"

sealed class MainViewUiEvent {
    data class ShowSnackbar(val message: String) : MainViewUiEvent()
    data class ShareLauncher(val intent: Intent) : MainViewUiEvent()
    data class StartService(val intent: Intent) : MainViewUiEvent()
    data object RefreshConfigList : MainViewUiEvent()
    data class Navigate(val route: String) : MainViewUiEvent()
}

class MainViewModel(application: Application) :
    AndroidViewModel(application) {
    val prefs: Preferences = Preferences(application)
    private val fileManager: FileManager = FileManager(application, prefs)

    private val configFileController: ConfigFileController = ConfigFileController(
        application,
        prefs,
        fileManager,
        viewModelScope,
        { isVpnEnabled() },
        { _uiEvent.trySend(it) },
        { file -> settingsController.syncSocksCredentialsFromConfig(file?.absolutePath) },
        { dashboardController.refreshConfig() },
    )

    private val settingsController by lazy {
        SettingsController(
            application,
            prefs,
            fileManager,
            viewModelScope,
            { isVpnEnabled() },
            { message -> showSnackbar(message) },
        )
    }

    private val mihomoSubscriptionController by lazy {
        MihomoSubscriptionController(
            application,
            prefs,
            fileManager,
            viewModelScope,
            configFileController.selectedConfigFile,
            { isVpnEnabled() },
            { file ->
                configFileController.updateSelectedConfigFile(file)
                configFileController.refreshConfigFileList()
            },
        )
    }

    private val appIconController by lazy {
        AppIconController(application, prefs)
    }

    private val ruleFileController by lazy {
        RuleFileController(
            application,
            prefs,
            fileManager,
            viewModelScope,
            { isVpnEnabled() },
            { settingsController.refreshRuleFileState(it) },
            { settingsController.updateSettingsState() },
            { _uiEvent.trySend(it) },
        )
    }

    private val updateController by lazy {
        UpdateController(
            application,
            prefs,
            viewModelScope,
            { isVpnEnabled() },
            { _uiEvent.trySend(it) },
        )
    }

    private val dashboardController: DashboardController = DashboardController(
        application,
        prefs,
        viewModelScope,
        configFileController.selectedConfigFile,
        { isVpnEnabled() },
    )

    private val workspaceController: GroupWorkspaceController = GroupWorkspaceController(
        application = application,
        scope = viewModelScope,
        onGlobalConfigUpdated = { path -> settingsController.syncSocksCredentialsFromConfig(path) },
        onEditFile = { path -> configFileController.editConfig(path) },
        showMessage = { message -> showSnackbar(message) },
    )

    private val vpnServiceController by lazy {
        VpnServiceController(
            application,
            prefs,
            viewModelScope,
            configFileController.selectedConfigFile,
            { _uiEvent.trySend(it) },
        )
    }

    private fun isVpnEnabled(): Boolean = vpnServiceController.isServiceEnabled.value

    var editingFilePath: String?
        get() = configFileController.editingFilePath
        set(value) {
            configFileController.editingFilePath = value
        }

    val settingsState: StateFlow<SettingsState>
        get() = settingsController.settingsState

    val coreStatsState: StateFlow<CoreStatsState>
        get() = dashboardController.coreStatsState

    val outboundNodes: StateFlow<List<ConfigUtils.OutboundInfo>>
        get() = dashboardController.outboundNodes

    val outboundLoadError: StateFlow<String?>
        get() = dashboardController.outboundLoadError

    val outboundLatency: StateFlow<Map<String, OutboundLatency>>
        get() = dashboardController.outboundLatency

    val groupProfile: StateFlow<GroupProfile>
        get() = dashboardController.groupProfile

    val activeGroupNodes: StateFlow<Map<String, String>>
        get() = dashboardController.activeGroupNodes

    val groupWorkspace: StateFlow<GroupWorkspace>
        get() = workspaceController.workspace

    var settingsCategory by mutableStateOf(0)
    var selectedProxyGroupTag by mutableStateOf("")
    var proxyPageAction by mutableStateOf<String?>(null)
    var createGroupRequested by mutableStateOf(false)
    val proxyLatency get() = dashboardController.proxyLatency
    val measuringProxyTargets get() = dashboardController.measuringProxyTargets
    fun measureGroupLatency(groupTag: String) = dashboardController.measureGroupLatency(groupTag)

    private val trafficRulesController = TrafficRulesController(application, viewModelScope)
    val trafficRules get() = trafficRulesController.rules
    val ruleApplyMessage get() = com.simplexray.re.service.RuleRuntimeStatus.message
    fun saveTrafficRule(rule: com.simplexray.re.common.config.TrafficRule) { trafficRulesController.save(rule) }
    fun deleteTrafficRule(id: String) { trafficRulesController.delete(id) }
    fun moveTrafficRule(id: String, delta: Int) { trafficRulesController.move(id, delta) }
    val ruleTransferMessage get() = trafficRulesController.message
    fun clearRuleTransferMessage() { trafficRulesController.clearMessage() }
    fun importTrafficRules(uri: Uri) { trafficRulesController.importRules(uri) }
    fun exportTrafficRules(uri: Uri) { trafficRulesController.exportRules(uri) }
    fun reorderTrafficRules(ids: List<String>) { trafficRulesController.reorderRules(ids) }

    val globalConfigSummary: StateFlow<GlobalConfigSummary>
        get() = workspaceController.globalSummary

    val fallbackRuntimeStates: StateFlow<Map<String, FallbackRuntimeInfo>>
        get() = workspaceController.fallbackStates

    val runtimeNodeLatencies: StateFlow<Map<String, Long>>
        get() = workspaceController.runtimeNodeLatencies

    val refreshingGroups: StateFlow<Set<String>>
        get() = workspaceController.refreshingGroups

    val isServiceEnabled: StateFlow<Boolean>
        get() = vpnServiceController.isServiceEnabled

    val appIcon: StateFlow<String>
        get() = appIconController.appIcon

    private val _uiEvent = Channel<MainViewUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    fun showSnackbar(message: String) {
        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(message))
    }

    val configFiles: StateFlow<List<File>>
        get() = configFileController.configFiles

    val selectedConfigFile: StateFlow<File?>
        get() = configFileController.selectedConfigFile

    val mihomoSubscriptionState: StateFlow<MihomoSubscriptionState>
        get() = mihomoSubscriptionController.state

    val geoipDownloadProgress: StateFlow<String?>
        get() = ruleFileController.geoipDownloadProgress

    val geositeDownloadProgress: StateFlow<String?>
        get() = ruleFileController.geositeDownloadProgress

    val customDatDownloadProgress: StateFlow<Map<String, String?>>
        get() = ruleFileController.customDatDownloadProgress

    val customDatVersion: StateFlow<Long>
        get() = ruleFileController.customDatVersion

    val isCheckingForUpdates: StateFlow<Boolean>
        get() = updateController.isCheckingForUpdates

    val newVersionAvailable: StateFlow<String?>
        get() = updateController.newVersionAvailable

    init {
        Log.d(TAG, "MainViewModel initialized.")

        SocksAuthenticatorInstaller.install(application)

        viewModelScope.launch {
            VpnStateHub.state.collect { state ->
                when (state) {
                    is VpnRunningState.Connected -> {
                        Log.d(TAG, "VPN state: Connected")
                        setServiceEnabled(true)
                    }
                    is VpnRunningState.Connecting -> {
                        Log.d(TAG, "VPN state: Connecting")
                    }
                    is VpnRunningState.Disconnected -> {
                        Log.d(TAG, "VPN state: Disconnected")
                        setServiceEnabled(false)
                        dashboardController.reset()
                    }
                    is VpnRunningState.Failed -> {
                        Log.d(TAG, "VPN state: Failed (${state.message})")
                        setServiceEnabled(false)
                        dashboardController.reset()
                        val msg = state.message ?: application.getString(R.string.core_start_failed)
                        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(msg))
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            // Independent initializations run concurrently to reduce first-launch
            // latency; coroutineScope waits for all of them before init finishes.
            // updateSettingsState and loadKernelVersion both read-modify-write
            // _settingsState, so they must run serially (concurrent RMW would
            // drop fields); the rest are independent.
            coroutineScope {
                launch {
                    vpnServiceController.setServiceEnabled(VpnStateHub.state.value is VpnRunningState.Connected)
                }
                launch { ensureAppIconSelected() }
                launch {
                    if (prefs.geoUpdateIntervalHours > 0) {
                        com.simplexray.re.service.GeoUpdateWorker.schedule(application, prefs.geoUpdateIntervalHours)
                    }
                }
                launch {
                    updateSettingsState()
                    refreshConfigFileList()
                    loadKernelVersion()
                }
            }
        }
    }

    fun updateSettingsState() = settingsController.updateSettingsState()

    private fun loadKernelVersion() = settingsController.loadKernelVersion()

    fun setServiceEnabled(enabled: Boolean) = vpnServiceController.setServiceEnabled(enabled)

    /**
     * Initializes the app icon preference on first launch: defaults to the
     * manifest-enabled alias (flat) so the first launcher icon and the settings
     * dropdown agree. Component states are never touched here — disabling the
     * currently running launcher alias mid-run makes the system rebuild the
     * task (feels like a crash + auto-restart). The choice is only applied when
     * the user manually switches via [setAppIcon].
     */
    fun ensureAppIconSelected() = appIconController.ensureAppIconSelected()

    fun setAppIcon(key: String) = appIconController.setAppIcon(key)





    suspend fun createConfigFile(): String? = configFileController.createConfigFile()

    suspend fun updateCoreStats() = dashboardController.updateCoreStats()

    /**
     * Refreshes the outbound node list from the currently selected config file.
     * Works whether or not the service is running.
     */
    suspend fun refreshOutboundNodes() = dashboardController.refreshOutboundNodes()

    fun saveGroupProfile(profile: GroupProfile) = dashboardController.saveGroupProfile(profile)

    fun saveWorkspaceGroup(originalTag: String?, group: ProxyGroup): String? =
        workspaceController.saveGroup(originalTag, group)

    fun deleteWorkspaceGroup(tag: String) = workspaceController.deleteGroup(tag)

    fun setDefaultWorkspaceGroup(tag: String) = workspaceController.setDefaultGroup(tag)

    fun setRoutingMode(mode: String) = workspaceController.setRoutingMode(mode)

    fun importFromClipboardToGroup(groupTag: String) = workspaceController.importFromClipboardToGroup(groupTag)

    fun importFromUriToGroup(groupTag: String, uri: Uri) = workspaceController.importFromUriToGroup(groupTag, uri)

    fun refreshSubscriptionGroup(groupTag: String) = workspaceController.refreshSubscriptionGroup(groupTag)

    fun importGlobalConfigFromUri(uri: Uri) = workspaceController.importGlobalConfigFromUri(uri)

    fun openGlobalConfigEditor() = workspaceController.openGlobalConfigEditor()

    fun openLuaScriptEditor(fileName: String) = workspaceController.openLuaScriptEditor(fileName)

    fun selectGroupMember(groupTag: String, member: GroupMemberRef) =
        workspaceController.selectGroupMember(groupTag, member)

    fun removeMemberFromGroup(groupTag: String, member: GroupMemberRef) =
        workspaceController.removeMemberFromGroup(groupTag, member)

    fun moveMemberInGroup(groupTag: String, index: Int, delta: Int) =
        workspaceController.moveMemberInGroup(groupTag, index, delta)

    fun addMemberRefToGroup(groupTag: String, member: GroupMemberRef): String? =
        workspaceController.addMemberRefToGroup(groupTag, member)

    fun updateNodeTargetStrategy(nodeTag: String, strategy: String) =
        workspaceController.updateNodeTargetStrategy(nodeTag, strategy)

    fun updateNodeJson(nodeTag: String, rawJson: String, udpOverTcpVersion: Int? = null): String? =
        workspaceController.updateNodeJson(nodeTag, rawJson, udpOverTcpVersion)

    fun addNodeToGroup(groupTag: String, rawJson: String): String? =
        workspaceController.addNodeToGroup(groupTag, rawJson)

    fun deleteWorkspaceNode(nodeTag: String) = workspaceController.deleteNode(nodeTag)

    /**
     * Latency-tests every TCP-capable outbound endpoint (1-RTT TCP connect,
     * independent of the core). Called when the dashboard is shown and on
     * manual refresh. UDP-only protocols (wireguard/hysteria2) and QUIC
     * transports are skipped and keep showing no data.
     */
    suspend fun testOutboundLatency() = dashboardController.testOutboundLatency()

    /** Non-suspend wrapper for UI callbacks (e.g. the dashboard refresh button). */
    fun refreshLatency() = dashboardController.refreshLatency()

    suspend fun importConfigFromClipboard(): String? = configFileController.importConfigFromClipboard()

    fun importMihomoSubscriptionUrl(url: String) = mihomoSubscriptionController.importUrl(url)

    fun importMihomoSubscriptionFile(uri: Uri) = mihomoSubscriptionController.importFile(uri)

    fun deleteConfigFile(file: File) = configFileController.deleteConfigFile(file)

    fun extractAssetsIfNeeded() = ruleFileController.extractAssetsIfNeeded()

    fun updateSocksAddress(addressString: String): Boolean = settingsController.updateSocksAddress(addressString)

    fun updateSocksPort(portString: String): Boolean = settingsController.updateSocksPort(portString)

    fun updateSocksUser(userString: String): Boolean = settingsController.updateSocksUser(userString)

    fun updateSocksPass(passString: String): Boolean = settingsController.updateSocksPass(passString)

    fun updateDnsIpv4(ipv4Addr: String): Boolean = settingsController.updateDnsIpv4(ipv4Addr)

    fun updateDnsIpv6(ipv6Addr: String): Boolean = settingsController.updateDnsIpv6(ipv6Addr)

    fun setIpv6Enabled(enabled: Boolean) = settingsController.setIpv6Enabled(enabled)

    fun setHideFromRecentsEnabled(enabled: Boolean) = settingsController.setHideFromRecentsEnabled(enabled)

    fun updateGeoUpdateInterval(hoursString: String): Boolean = settingsController.updateGeoUpdateInterval(hoursString)

    fun updateTunnelMtu(mtuString: String): Boolean = settingsController.updateTunnelMtu(mtuString)

    fun setHttpProxyEnabled(enabled: Boolean) = settingsController.setHttpProxyEnabled(enabled)

    fun setSniffingRouteOnly(enabled: Boolean) = settingsController.setSniffingRouteOnly(enabled)

    fun setBypassLanEnabled(enabled: Boolean) = settingsController.setBypassLanEnabled(enabled)

    fun setKeepAwakeEnabled(enabled: Boolean) = settingsController.setKeepAwakeEnabled(enabled)

    fun setLogLevel(logLevel: LogLevel) = settingsController.setLogLevel(logLevel)

    fun setAccessLog(enabled: Boolean) = settingsController.setAccessLog(enabled)

    fun setDnsLog(enabled: Boolean) = settingsController.setDnsLog(enabled)

    fun setDisableVpnEnabled(enabled: Boolean) = settingsController.setDisableVpnEnabled(enabled)

    fun setTunnelMode(mode: com.simplexray.re.prefs.TunnelMode) = settingsController.setTunnelMode(mode)

    fun setTheme(mode: ThemeMode) = settingsController.setTheme(mode)

    fun setColorMode(mode: ColorMode) = settingsController.setColorMode(mode)

    fun importRuleFile(uri: Uri, fileName: String) = ruleFileController.importRuleFile(uri, fileName)

    fun showExportFailedSnackbar() {
        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.export_failed)))
    }

    fun startTProxyService(action: String) = vpnServiceController.startTProxyService(action)

    fun editConfig(filePath: String) = configFileController.editConfig(filePath)

    fun shareIntent(chooserIntent: Intent, packageManager: PackageManager) = configFileController.shareIntent(chooserIntent, packageManager)

    fun stopTProxyService() = vpnServiceController.stopTProxyService()

    fun prepareAndStartVpn(vpnPrepareLauncher: ActivityResultLauncher<Intent>) = vpnServiceController.prepareAndStartVpn(vpnPrepareLauncher)

    fun navigateToAppList() {
        viewModelScope.launch {
            _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_APP_LIST))
        }
    }

    fun moveConfigFile(fromIndex: Int, toIndex: Int) = configFileController.moveConfigFile(fromIndex, toIndex)

    fun persistConfigFilesOrder() = configFileController.persistConfigFilesOrder()



    fun importConfigFromFile(uri: android.net.Uri) = configFileController.importConfigFromFile(uri)

    fun replaceConfigFile(uri: android.net.Uri, targetFile: java.io.File) = configFileController.replaceConfigFile(uri, targetFile)

    fun refreshConfigFileList() = configFileController.refreshConfigFileList()

    fun onConfigEditFinished(savedSelectedConfig: Boolean) {
        refreshConfigFileList()
        refreshLuaScriptFiles()
        settingsController.syncSocksCredentialsFromConfig(GlobalConfigStore(application).file().absolutePath)
        if (savedSelectedConfig && isVpnEnabled()) {
            startTProxyService(com.simplexray.re.service.TProxyService.ACTION_RELOAD_CONFIG)
            showSnackbar(application.getString(R.string.config_edited_service_running))
        }
    }

    fun updateSelectedConfigFile(file: File?) = configFileController.updateSelectedConfigFile(file)

    fun restoreDefaultGeoip(callback: () -> Unit) = ruleFileController.restoreDefaultGeoip(callback)

    fun restoreDefaultGeosite(callback: () -> Unit) = ruleFileController.restoreDefaultGeosite(callback)

    fun cancelDownload(fileName: String) = ruleFileController.cancelDownload(fileName)

    fun downloadRuleFile(url: String, fileName: String) = ruleFileController.downloadRuleFile(url, fileName)


    fun refreshCustomDatFiles() = ruleFileController.refreshCustomDatFiles()

    fun importCustomDatFile(uri: android.net.Uri) = ruleFileController.importCustomDatFile(uri)

    /**
     * Download and import a new third-party .dat file from a direct link.
     * The file name is inferred from the URL path; standard GEO file names
     * (case-insensitive) are rejected.
     */
    fun downloadDatFromUrl(url: String) = ruleFileController.downloadDatFromUrl(url)

    fun getCustomDatSummary(fileName: String): String = ruleFileController.getCustomDatSummary(fileName)



    fun deleteCustomDatFile(fileName: String) = ruleFileController.deleteCustomDatFile(fileName)

    fun updateCustomDatUrl(fileName: String, url: String) = ruleFileController.updateCustomDatUrl(fileName, url)

    val luaScriptVersion: StateFlow<Long>
        get() = ruleFileController.luaScriptVersion

    fun refreshLuaScriptFiles() = ruleFileController.refreshLuaScriptFiles()

    fun importLuaFile(uri: android.net.Uri, targetFileName: String? = null) =
        ruleFileController.importLuaFile(uri, targetFileName)

    fun getLuaFileSummary(fileName: String): String = ruleFileController.getLuaFileSummary(fileName)

    fun deleteLuaFile(fileName: String) = ruleFileController.deleteLuaFile(fileName)

    fun checkForUpdates() = updateController.checkForUpdates()

    fun downloadNewVersion(versionTag: String) = updateController.downloadNewVersion(versionTag)

    fun clearNewVersionAvailable() = updateController.clearNewVersionAvailable()


    override fun onCleared() {
        dashboardController.close()
        super.onCleared()
    }


}

