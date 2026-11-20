package com.simplexray.re.viewmodel

import android.content.Context
import android.util.Log
import com.simplexray.re.common.ConfigUtils
import com.simplexray.re.common.CoreStatsClient
import com.simplexray.re.common.TcpPing
import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.common.config.GroupProfile
import com.simplexray.re.common.config.GroupProfileStore
import com.simplexray.re.common.config.GroupWorkspaceStore
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.service.StandaloneLatencySession
import com.simplexray.re.prefs.Preferences
import com.simplexray.re.service.GroupRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import java.io.File

private const val TAG = "DashboardController"

internal class DashboardController(
    context: Context,
    private val prefs: Preferences,
    private val scope: CoroutineScope,
    private val selectedConfig: StateFlow<File?>,
    private val isVpnEnabled: () -> Boolean,
) {
    private val appContext = context.applicationContext
    @Volatile
    private var coreStatsClient: CoreStatsClient? = null
    private val statsClientLock = Any()
    private var latencyTestJob: Job? = null
    private val groupProfileStore = GroupProfileStore(context)
    private val globalConfigStore = GlobalConfigStore(context)
    private val workspaceStore = GroupWorkspaceStore(context)
    private val groupProfileLock = Any()
    private var groupProfileRefreshJob: Job? = null
    private var groupProfileObservationJob: Job? = null
    private var groupProfilePath: String? = null

    private val _coreStatsState = MutableStateFlow(CoreStatsState())
    val coreStatsState: StateFlow<CoreStatsState> = _coreStatsState.asStateFlow()

    private val _outboundNodes = MutableStateFlow<List<ConfigUtils.OutboundInfo>>(emptyList())
    val outboundNodes: StateFlow<List<ConfigUtils.OutboundInfo>> = _outboundNodes.asStateFlow()

    private val _outboundLoadError = MutableStateFlow<String?>(null)
    val outboundLoadError: StateFlow<String?> = _outboundLoadError.asStateFlow()

    private val _outboundLatency = MutableStateFlow<Map<String, OutboundLatency>>(emptyMap())
    val outboundLatency: StateFlow<Map<String, OutboundLatency>> = _outboundLatency.asStateFlow()
    private val _proxyLatency = MutableStateFlow<Map<GroupMemberRef, OutboundLatency>>(emptyMap())
    val proxyLatency = _proxyLatency.asStateFlow()
    private val _measuringProxyTargets = MutableStateFlow<Set<GroupMemberRef>>(emptySet())
    val measuringProxyTargets = _measuringProxyTargets.asStateFlow()
    private var proxyLatencyJob: Job? = null

    fun measureGroupLatency(groupTag: String) {
        if (proxyLatencyJob?.isActive == true) return
        proxyLatencyJob = scope.launch(Dispatchers.IO) {
            val workspace = workspaceStore.load()
            val group = workspace.groupByTag(groupTag) ?: return@launch
            val members = (group.effectiveMembers() + group.nodes.map { GroupMemberRef(it.tag) }).distinct()
            _measuringProxyTargets.value = members.toSet()
            fun record(member: GroupMemberRef, delay: Long?) {
                _proxyLatency.update { it + (member to OutboundLatency(delay != null, delay ?: -1L, System.currentTimeMillis())) }
                _measuringProxyTargets.update { it - member }
            }
            try {
                if (isVpnEnabled()) {
                    members.forEach { member ->
                        record(member, GroupRuntimeStatus.measureTarget(member, group.testUrl, 5_000))
                    }
                } else {
                    StandaloneLatencySession.measure(appContext, workspace, members, group.testUrl, ::record)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Proxy latency measurement failed: ${e.message}")
                _measuringProxyTargets.value.forEach { record(it, null) }
            } finally {
                _measuringProxyTargets.value = emptySet()
            }
        }
    }

    private val _groupProfile = MutableStateFlow(
        GroupProfile(groups = emptyList(), targetStrategies = emptyMap())
    )
    val groupProfile: StateFlow<GroupProfile> = _groupProfile.asStateFlow()

    val activeGroupNodes: StateFlow<Map<String, String>> = GroupRuntimeStatus.activeNodes

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            workspaceStore.observe().collect { workspace ->
                _outboundNodes.value = workspace.allNodes().map {
                    ConfigUtils.OutboundInfo(tag = it.tag, protocol = it.protocol)
                }
                _outboundLoadError.value = null
            }
        }
    }

    fun reset() {
        _coreStatsState.value = CoreStatsState()
        close()
    }

    fun close() {
        synchronized(statsClientLock) {
            coreStatsClient?.close()
            coreStatsClient = null
        }
    }

    suspend fun updateCoreStats() = withContext(Dispatchers.IO) {
        if (!isVpnEnabled()) return@withContext

        val client = synchronized(statsClientLock) {
            coreStatsClient ?: CoreStatsClient.create(prefs.apiAddress, prefs.apiPort).also {
                Log.d(TAG, "=== [DEBUG gRPC] Connecting CoreStatsClient to ${prefs.apiAddress}:${prefs.apiPort} ===")
                coreStatsClient = it
            }
        }

        // reset()/close() may close the client concurrently; tolerate the
        // resulting errors instead of crashing or leaking the channel.
        val stats = runCatching { client.getSystemStats() }.getOrNull()
        val traffic = runCatching { client.getTraffic() }.getOrNull()
        Log.d(TAG, "=== [DEBUG gRPC RESULT] uplink=${traffic?.uplink}, downlink=${traffic?.downlink}, sys=${stats?.sys} ===")

        if (stats == null && traffic == null) {
            Log.w(TAG, "=== [DEBUG gRPC FAILED] Both stats & traffic returned null, resetting client ===")
            synchronized(statsClientLock) {
                if (coreStatsClient === client) {
                    runCatching { client.close() }
                    coreStatsClient = null
                }
            }
            return@withContext
        }

        _coreStatsState.value = CoreStatsState(
            uplink = traffic?.uplink ?: 0,
            downlink = traffic?.downlink ?: 0,
            numGoroutine = stats?.numGoroutine ?: 0,
            numGC = stats?.numGC ?: 0,
            alloc = stats?.alloc ?: 0,
            totalAlloc = stats?.totalAlloc ?: 0,
            sys = stats?.sys ?: 0,
            mallocs = stats?.mallocs ?: 0,
            frees = stats?.frees ?: 0,
            liveObjects = stats?.liveObjects ?: 0,
            pauseTotalNs = stats?.pauseTotalNs ?: 0,
            uptime = stats?.uptime ?: 0
        )
        Log.d(TAG, "Core stats updated")
    }

    suspend fun refreshOutboundNodes() {
        refreshConfig()?.join()
    }

    fun refreshConfig(): Job? = synchronized(groupProfileLock) {
        val file = selectedConfig.value
        val changed = groupProfilePath != file?.absolutePath
        groupProfileRefreshJob?.cancel()
        groupProfileObservationJob?.cancel()
        groupProfileRefreshJob = null
        groupProfileObservationJob = null
        _outboundLoadError.value = null
        if (changed) {
            groupProfilePath = null
            _outboundNodes.value = emptyList()
            _groupProfile.value = GroupProfile(groups = emptyList(), targetStrategies = emptyMap())
        }

        if (file == null) return@synchronized null
        val path = file.absolutePath
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val refreshJob = currentCoroutineContext()[Job]
            val content = runCatching { file.readText() }.getOrElse {
                setOutboundLoadError(path, refreshJob, "Config file read failed")
                return@launch
            }
            if (!ConfigUtils.isValidConfigContent(content)) {
                setOutboundLoadError(path, refreshJob, "Config content parsing failed")
                return@launch
            }
            val nodes = ConfigUtils.extractOutbounds(content)
            val nodeTags = nodes.map { it.tag }
            synchronized(groupProfileLock) {
                if (groupProfileRefreshJob === refreshJob) {
                    val profile = groupProfileStore.load(path, nodeTags)
                    if (selectedConfig.value?.absolutePath == path) {
                        _outboundNodes.value = nodes
                        _groupProfile.value = profile
                        groupProfilePath = path
                        groupProfileObservationJob = scope.launch(Dispatchers.IO) {
                            groupProfileStore.observe(path, nodeTags).collect {
                                synchronized(groupProfileLock) {
                                    if (groupProfileRefreshJob === refreshJob) {
                                        // Resolve queued notifications from current storage under the save lock.
                                        val updated = groupProfileStore.load(path, nodeTags)
                                        if (selectedConfig.value?.absolutePath == path) {
                                            _groupProfile.value = updated
                                        }
                                    }
                                }
                            }
                        }
                        Log.d(TAG, "Refreshed ${nodes.size} outbound nodes from ${file.name}")
                    }
                }
            }
        }
        groupProfileRefreshJob = job
        job.start()
        job
    }

    private fun setOutboundLoadError(path: String, refreshJob: Job?, message: String) {
        synchronized(groupProfileLock) {
            if (groupProfileRefreshJob === refreshJob &&
                selectedConfig.value?.absolutePath == path
            ) {
                groupProfilePath = null
                _outboundNodes.value = emptyList()
                _groupProfile.value = GroupProfile(groups = emptyList(), targetStrategies = emptyMap())
                _outboundLoadError.value = message
                Log.w(TAG, message)
            }
        }
    }

    fun saveGroupProfile(profile: GroupProfile) {
        synchronized(groupProfileLock) {
            val path = groupProfilePath
            val nodeTags = _outboundNodes.value.map { it.tag }
            if (path != null && selectedConfig.value?.absolutePath == path &&
                profile.isValid(nodeTags.toSet())
            ) {
                groupProfileStore.save(path, profile)
                val updated = groupProfileStore.load(path, nodeTags)
                if (selectedConfig.value?.absolutePath == path) {
                    _groupProfile.value = updated
                }
            }
        }
    }

    suspend fun testOutboundLatency() {
        val content = withContext(Dispatchers.IO) {
            val workspace = workspaceStore.load()
            workspace.assembleRuntimeConfig(globalConfigStore.loadJson(), prefs.dohBootstrapDns).toString()
        }
        val endpoints = ConfigUtils.extractOutboundEndpoints(content)
        if (endpoints.isEmpty()) {
            _outboundLatency.value = emptyMap()
            return
        }
        val now = System.currentTimeMillis() / 1000
        val io = Dispatchers.IO.limitedParallelism(8)
        val results = coroutineScope {
            endpoints.map { ep ->
                async(io) {
                    val delay = withTimeoutOrNull(4000L) {
                        TcpPing.pingBlocking(ep.host, ep.port)
                    } ?: -1L
                    ep.tag to delay
                }
            }.awaitAll()
        }
        _outboundLatency.value = results.associate { (tag, delay) ->
            Log.d(TAG, "[tcping] tag=$tag delay=${if (delay >= 0) "${delay}ms" else "failed"}")
            tag to OutboundLatency(
                alive = delay >= 0,
                delayMs = delay.coerceAtLeast(0),
                lastTryTime = now
            )
        }
        Log.d(TAG, "Outbound latency (TCPing) updated: ${results.size} entries")
    }

    fun refreshLatency() {
        if (latencyTestJob?.isActive == true) return
        latencyTestJob = scope.launch { testOutboundLatency() }
    }
}
