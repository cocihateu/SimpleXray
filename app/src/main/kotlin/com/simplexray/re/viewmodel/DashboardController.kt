package com.simplexray.re.viewmodel

import android.content.Context
import android.util.Log
import com.simplexray.re.common.ConfigUtils
import com.simplexray.re.common.CoreStatsClient
import com.simplexray.re.common.TcpPing
import com.simplexray.re.common.config.GroupProfile
import com.simplexray.re.common.config.GroupProfileStore
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
import java.io.File

private const val TAG = "DashboardController"

internal class DashboardController(
    context: Context,
    private val prefs: Preferences,
    private val scope: CoroutineScope,
    private val selectedConfig: StateFlow<File?>,
    private val isVpnEnabled: () -> Boolean,
) {
    @Volatile
    private var coreStatsClient: CoreStatsClient? = null
    private val statsClientLock = Any()
    private var latencyTestJob: Job? = null
    private val groupProfileStore = GroupProfileStore(context)
    private val groupProfileLock = Any()
    private var groupProfileRefreshJob: Job? = null
    private var groupProfileObservationJob: Job? = null
    private var groupProfilePath: String? = null

    private val _coreStatsState = MutableStateFlow(CoreStatsState())
    val coreStatsState: StateFlow<CoreStatsState> = _coreStatsState.asStateFlow()

    private val _outboundNodes = MutableStateFlow<List<ConfigUtils.OutboundInfo>>(emptyList())
    val outboundNodes: StateFlow<List<ConfigUtils.OutboundInfo>> = _outboundNodes.asStateFlow()

    private val _outboundLatency = MutableStateFlow<Map<String, OutboundLatency>>(emptyMap())
    val outboundLatency: StateFlow<Map<String, OutboundLatency>> = _outboundLatency.asStateFlow()

    private val _groupProfile = MutableStateFlow(
        GroupProfile(groups = emptyList(), targetStrategies = emptyMap())
    )
    val groupProfile: StateFlow<GroupProfile> = _groupProfile.asStateFlow()

    val activeGroupNodes: StateFlow<Map<String, String>> = combine(
        selectedConfig,
        GroupRuntimeStatus.activePath,
        GroupRuntimeStatus.activeNodes,
    ) { file, activePath, nodes ->
        if (file != null && file.absolutePath == activePath) nodes else emptyMap()
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

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
        groupProfileRefreshJob?.cancel()
        groupProfileObservationJob?.cancel()
        groupProfileRefreshJob = null
        groupProfileObservationJob = null
        groupProfilePath = null
        _outboundNodes.value = emptyList()
        _groupProfile.value = GroupProfile(groups = emptyList(), targetStrategies = emptyMap())

        val file = selectedConfig.value ?: return@synchronized null
        val path = file.absolutePath
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val refreshJob = currentCoroutineContext()[Job]
            val content = runCatching { file.readText() }.getOrElse { e ->
                Log.e(TAG, "Error reading config file: ${file.name}", e)
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
        val file = selectedConfig.value ?: return
        val content = withContext(Dispatchers.IO) {
            runCatching { file.readText() }.getOrNull()
        } ?: return
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
