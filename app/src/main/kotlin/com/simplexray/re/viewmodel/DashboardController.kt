package com.simplexray.re.viewmodel

import android.content.Context
import android.os.Process
import android.util.Log
import com.simplexray.re.common.CoreStatsClient
import com.simplexray.re.common.ProcessMemoryReader
import com.simplexray.re.common.config.GroupWorkspaceStore
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.service.StandaloneLatencySession
import com.simplexray.re.prefs.Preferences
import com.simplexray.re.service.GroupRuntimeStatus
import com.simplexray.re.service.VpnStateHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update

private const val TAG = "DashboardController"

internal class DashboardController(
    context: Context,
    private val prefs: Preferences,
    private val scope: CoroutineScope,
    private val isVpnEnabled: () -> Boolean,
) {
    private val appContext = context.applicationContext
    @Volatile
    private var coreStatsClient: CoreStatsClient? = null
    private val statsClientLock = Any()
    private val workspaceStore = GroupWorkspaceStore(context)

    private val _coreStatsState = MutableStateFlow(CoreStatsState())
    val coreStatsState: StateFlow<CoreStatsState> = _coreStatsState.asStateFlow()

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

    val activeGroupNodes: StateFlow<Map<String, String>> = GroupRuntimeStatus.activeNodes

    fun reset() {
        _coreStatsState.update { CoreStatsState(appMemoryBytes = it.appMemoryBytes) }
        close()
    }

    fun close() {
        synchronized(statsClientLock) {
            coreStatsClient?.close()
            coreStatsClient = null
        }
    }

    suspend fun updateCoreStats() = withContext(Dispatchers.IO) {
        val appPid = Process.myPid()
        val appMemoryBytes = ProcessMemoryReader.readRssBytes(appPid)
        val vpnEnabled = isVpnEnabled()
        val corePid = VpnStateHub.coreProcessId
        val coreMemoryBytes = when {
            vpnEnabled == false -> 0L
            corePid == null -> null
            corePid == appPid -> 0L
            else -> ProcessMemoryReader.readRssBytes(corePid)
        }
        _coreStatsState.update {
            it.copy(appMemoryBytes = appMemoryBytes, coreMemoryBytes = coreMemoryBytes)
        }
        if (vpnEnabled == false) return@withContext

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

        if (isVpnEnabled() == false) return@withContext

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
            uptime = stats?.uptime ?: 0,
            appMemoryBytes = appMemoryBytes,
            coreMemoryBytes = coreMemoryBytes
        )
        Log.d(TAG, "Core stats updated")
    }


}
