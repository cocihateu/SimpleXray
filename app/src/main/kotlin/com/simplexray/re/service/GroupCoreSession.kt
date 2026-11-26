package com.simplexray.re.service

import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.GroupWorkspaceStore
import com.simplexray.re.common.config.RuleWorkspaceStore
import com.simplexray.re.common.config.TrafficRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal data class GroupNodeMeasurement(val delayMs: Long?, val completedAtMs: Long)
internal data class ExitInfoProxy(val port: Int, val username: String, val password: String)
internal data class AppliedGroupRouting(val mode: String, val defaultGroupTag: String?, val rules: List<TrafficRule>)

internal object GroupRuntimeStatus {
    private val nodes = MutableStateFlow<Map<String, String>>(emptyMap())
    private val fallbacks = MutableStateFlow<Map<String, FallbackRuntimeInfo>>(emptyMap())
    private val latencies = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val measurements = MutableStateFlow<Map<String, GroupNodeMeasurement>>(emptyMap())
    private val routing = MutableStateFlow<AppliedGroupRouting?>(null)
    val activeNodes = nodes.asStateFlow()
    val fallbackStates = fallbacks.asStateFlow()
    val nodeLatencies = latencies.asStateFlow()
    val nodeMeasurements = measurements.asStateFlow()
    val appliedRouting = routing.asStateFlow()
    @Volatile
    private var owner: GroupCoreSession? = null

    @Synchronized fun start(session: GroupCoreSession, workspace: GroupWorkspace, rules: List<TrafficRule>) {
        owner = session
        nodes.value = emptyMap()
        fallbacks.value = emptyMap()
        latencies.value = emptyMap()
        measurements.value = emptyMap()
        applyRouting(session, workspace, rules)
    }

    @Synchronized fun applyRouting(session: GroupCoreSession, workspace: GroupWorkspace, rules: List<TrafficRule>) {
        if (owner === session) {
            routing.value = AppliedGroupRouting(workspace.routingMode, workspace.effectiveDefaultGroupTag(), rules)
        }
    }

    @Synchronized fun publish(
        session: GroupCoreSession,
        active: Map<String, String>,
        fallbackState: Map<String, FallbackRuntimeInfo>,
        nodeLatency: Map<String, Long>,
    ) {
        if (owner === session) {
            nodes.value = active
            fallbacks.value = fallbackState
            latencies.value = nodeLatency
        }
    }

    @Synchronized fun stop(session: GroupCoreSession) {
        if (owner === session) {
            owner = null
            nodes.value = emptyMap()
            fallbacks.value = emptyMap()
            latencies.value = emptyMap()
            measurements.value = emptyMap()
            routing.value = null
        }
    }

    fun switchGroupMemberLive(groupTag: String, member: GroupMemberRef) {
        owner?.switchGroupMemberLive(groupTag, member)
    }

    fun clearGroupSelection(groupTag: String) { owner?.clearGroupSelection(groupTag) }

    suspend fun measureTarget(member: GroupMemberRef, url: String, timeoutMs: Int): Long? =
        owner?.measureTarget(member, url, timeoutMs)

    fun exitInfoProxy(): ExitInfoProxy? = owner?.exitInfoProxy()

    @Synchronized fun recordMeasurement(session: GroupCoreSession, node: String, delay: Long?) {
        if (owner === session) {
            measurements.value = measurements.value + (node to GroupNodeMeasurement(delay, System.currentTimeMillis()))
        }
    }
}

internal class GroupCoreSession(
    private val store: GroupWorkspaceStore,
    private val config: OutboundGroupConfig,
    @Suppress("UNUSED_PARAMETER") apiPort: Int,
    private val ruleStore: RuleWorkspaceStore,
    private val initialRules: List<TrafficRule>,
    private val routingEditor: XrayRoutingEditor,
    private val logEvent: (String) -> Unit = VpnStateHub::emitLog,
) {
    private var job: Job? = null
    @Volatile private var closed = false
    @Volatile private var relayServer: FallbackRelayServer? = null
    private var obfsRelay: SimpleObfsRelay? = null
    private val standbyReady = CompletableDeferred<Unit>()
    @Volatile private var measureNode: (suspend (String, String, Int) -> Long?)? = null
    @Volatile private var controller: GroupRuntimeController? = GroupRuntimeController(
        initialWorkspace = config.workspace,
        probeNode = { node, url, timeoutMs -> measureNode?.invoke(node, url, timeoutMs) },
        publishStatus = { active, fallback, latency ->
            GroupRuntimeStatus.publish(this, active, fallback, latency)
        },
        logEvent = logEvent,
        onDemoted = { group, member -> relayServer?.closeStalledConnections(group, member) },
    )
    private val initialGlobalVersion: Long = GlobalConfigStore.version.value

    fun formatLogLine(line: String): String = config.formatLogLine(line)
    fun exitInfoProxy() = ExitInfoProxy(config.healthPort, config.exitInfoUser, config.password)

    fun switchGroupMemberLive(groupTag: String, member: GroupMemberRef) {
        controller?.forceSwitchMember(groupTag, member)
    }

    fun clearGroupSelection(groupTag: String) { controller?.clearSelection(groupTag) }

    suspend fun measureTarget(member: GroupMemberRef, url: String, timeoutMs: Int): Long? =
        controller?.measureTarget(member, url, timeoutMs)

    /**
     * Binds local FALLBACK relay listeners before Xray spawns so the earliest packet already finds
     * listening loopback ports.
     */
    @Synchronized fun startRelayBeforeCore(scope: CoroutineScope) {
        if (closed || config.fallbackRelaySpecs.isEmpty() || relayServer != null) return
        val relay = FallbackRelayServer(
            specs = config.fallbackRelaySpecs,
            nodeEntryPort = config.nodeEntryPort,
            nodeUsers = config.users,
            nodePassword = config.password,
            nodeUotVersions = config.workspace.allNodes().associate { it.tag to it.udpOverTcpVersion },
            resolvePlan = { groupTag, host ->
                controller?.buildFailoverPlan(groupTag, host) ?: FailoverPlan(null, emptyList(), 3_000)
            },
            onCandidateFailed = { groupTag, failedLeaf, owner, host, strong, destinationFailure ->
                controller?.onCandidateFailed(groupTag, failedLeaf, owner, host, strong, destinationFailure)
            },
            onCandidateConnected = { groupTag, leaf, owner, host ->
                controller?.onCandidateConnected(groupTag, leaf, owner, host)
            },
            onStalledUpload = { groupTag, leaf, owner, host, strong, responsePending, applyIfWaiting ->
                controller?.onStalledUpload(groupTag, leaf, owner, host, strong, responsePending, applyIfWaiting) ?: false
            },
            awaitReady = { standbyReady.await() },
        )
        relayServer = relay
        relay.start(scope)
        obfsRelay = SimpleObfsRelay(config.obfsPort, config.obfsSpecs).also { it.start(scope) }
    }

    @Synchronized fun start(scope: CoroutineScope, onReady: () -> Unit, reload: () -> Unit) {
        if (closed) return
        GroupRuntimeStatus.start(this, config.workspace, initialRules)
        startRelayBeforeCore(scope)
        job = scope.launch(Dispatchers.IO) {
            val sessionScope = this
            val base = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", config.healthPort)))
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            val clients = config.users.mapValues { (_, user) ->
                base.newBuilder().dispatcher(Dispatcher()).proxyAuthenticator { _, response ->
                    response.request.newBuilder().header(
                        "Proxy-Authorization",
                        Credentials.basic(user, config.password),
                    ).build()
                }.build()
            }

            val measurement: suspend (String, String, Int) -> Long? = measurement@{ node, testUrl, timeoutMs ->
                    val client = clients[node] ?: return@measurement null
                    val latency = HttpLatencyMeasurement.measure(
                        client, testUrl.ifBlank { "https://www.gstatic.com/generate_204" }, timeoutMs,
                    )
                    GroupRuntimeStatus.recordMeasurement(this@GroupCoreSession, node, latency)
                    latency
                }
            measureNode = measurement
            val runtimeController = controller ?: return@launch

            try {
                onReady()
                launch {
                    runtimeController.start(sessionScope)
                    standbyReady.complete(Unit)
                }
                launch {
                    GlobalConfigStore.version.collect { version ->
                        if (version != initialGlobalVersion) {
                            reload()
                            throw CancellationException("Applying updated global configuration")
                        }
                    }
                }
                val initialNodes = config.workspace.allNodes()
                val initialRelayGroups = config.fallbackRelayOutbounds.keys
                var previousWorkspace = config.workspace
                var previousRules = initialRules
                combine(store.observe(), ruleStore.observe()) { workspace, rules -> workspace to rules }.collect { (updated, rules) ->
                    val newRelayGroups = (updated.groups.map { it.tag } +
                        (updated.allNodes().map { it.tag } + com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS)
                            .map(OutboundGroupConfig::physicalTarget)).toSet()
                    if (updated.allNodes() != initialNodes ||
                        updated.groups.map { it.tag to it.frontProxy } != config.workspace.groups.map { it.tag to it.frontProxy } ||
                        newRelayGroups != initialRelayGroups
                    ) {
                        reload()
                        throw CancellationException("Applying updated group topology")
                    }
                    runtimeController.updateWorkspace(updated, startHealthChecks = standbyReady.isCompleted)
                    if (rules != previousRules ||
                        updated.effectiveDefaultGroupTag() != previousWorkspace.effectiveDefaultGroupTag() ||
                        updated.routingMode != previousWorkspace.routingMode
                    ) {
                        runCatching { routingEditor.apply(updated, rules) }
                            .onSuccess {
                                previousRules = rules
                                previousWorkspace = updated
                                GroupRuntimeStatus.applyRouting(this@GroupCoreSession, updated, rules)
                                RuleRuntimeStatus.applied()
                                logEvent("[Info] Applied ${rules.count { it.enabled }} traffic rules.")
                            }
                            .onFailure {
                                RuleRuntimeStatus.failed(it.message.orEmpty())
                                logEvent("[Error] Applying traffic rules failed: ${it.message}")
                            }
                    }
                }
            } finally {
                clients.values.forEach { it.dispatcher.cancelAll() }
                runtimeController.stop()
                if (controller === runtimeController) controller = null
                measureNode = null
                GroupRuntimeStatus.stop(this@GroupCoreSession)
            }
        }
    }

    @Synchronized fun close() {
        closed = true
        standbyReady.cancel()
        controller?.stop()
        relayServer?.close()
        relayServer = null
        obfsRelay?.close()
        obfsRelay = null
        job?.cancel()
        GroupRuntimeStatus.stop(this)
        config.close()
    }
}

internal object RuleRuntimeStatus {
    private val result = MutableStateFlow("")
    val message = result.asStateFlow()
    fun applied() { result.value = "规则已应用" }
    fun failed(reason: String) { result.value = "规则应用失败：$reason" }
}
