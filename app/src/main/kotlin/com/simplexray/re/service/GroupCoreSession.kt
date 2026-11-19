package com.simplexray.re.service

import android.os.SystemClock
import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspaceStore
import com.simplexray.re.common.config.RuleWorkspaceStore
import com.simplexray.re.common.config.GroupWorkspace
import com.xray.app.router.command.OverrideBalancerTargetRequest
import com.xray.app.router.command.RoutingServiceGrpc
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal data class GroupNodeMeasurement(val delayMs: Long?, val completedAtMs: Long)

internal object GroupRuntimeStatus {
    private val nodes = MutableStateFlow<Map<String, String>>(emptyMap())
    private val fallbacks = MutableStateFlow<Map<String, FallbackRuntimeInfo>>(emptyMap())
    private val latencies = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val measurements = MutableStateFlow<Map<String, GroupNodeMeasurement>>(emptyMap())
    val activeNodes = nodes.asStateFlow()
    val fallbackStates = fallbacks.asStateFlow()
    val nodeLatencies = latencies.asStateFlow()
    val nodeMeasurements = measurements.asStateFlow()
    @Volatile
    private var owner: GroupCoreSession? = null

    @Synchronized fun start(session: GroupCoreSession) {
        owner = session
        nodes.value = emptyMap()
        fallbacks.value = emptyMap()
        latencies.value = emptyMap()
        measurements.value = emptyMap()
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
        }
    }

    fun switchGroupMemberLive(groupTag: String, member: GroupMemberRef) {
        owner?.switchGroupMemberLive(groupTag, member)
    }

    fun clearGroupSelection(groupTag: String) { owner?.clearGroupSelection(groupTag) }

    suspend fun measureTarget(member: GroupMemberRef, url: String, timeoutMs: Int): Long? =
        owner?.measureTarget(member, url, timeoutMs)

    @Synchronized fun recordMeasurement(session: GroupCoreSession, node: String, delay: Long?) {
        if (owner === session) {
            measurements.value = measurements.value + (node to GroupNodeMeasurement(delay, System.currentTimeMillis()))
        }
    }
}

internal class GroupCoreSession(
    private val store: GroupWorkspaceStore,
    private val config: OutboundGroupConfig,
    private val apiPort: Int,
    private val ruleStore: RuleWorkspaceStore,
    private val initialRules: List<com.simplexray.re.common.config.TrafficRule>,
    private val routingEditor: XrayRoutingEditor,
    private val logEvent: (String) -> Unit = VpnStateHub::emitLog,
) {
    private var job: Job? = null
    @Volatile private var closed = false
    @Volatile private var relayServer: FallbackRelayServer? = null
    @Volatile private var measureNode: (suspend (String, String, Int) -> Long?)? = null
    @Volatile private var applyTarget: (suspend (String, String) -> Unit)? = null
    @Volatile private var controller: GroupRuntimeController? = GroupRuntimeController(
        initialWorkspace = config.workspace,
        probeNode = { node, url -> measureNode?.invoke(node, url, 5_000) },
        applyBalancerTarget = { group, target -> applyTarget?.invoke(group, target) },
        fallbackRelayOutbounds = config.fallbackRelayOutbounds,
        balancerTags = config.balancers.keys,
        publishStatus = { active, fallback, latency ->
            GroupRuntimeStatus.publish(this, active, fallback, latency)
        },
        logEvent = logEvent,
    )
    private val initialGlobalVersion: Long = GlobalConfigStore.version.value

    fun formatLogLine(line: String): String = config.formatLogLine(line)

    fun switchGroupMemberLive(groupTag: String, member: GroupMemberRef) {
        controller?.forceSwitchMember(groupTag, member)
    }

    fun clearGroupSelection(groupTag: String) { controller?.clearSelection(groupTag) }

    suspend fun measureTarget(member: GroupMemberRef, url: String, timeoutMs: Int): Long? {
        val leaf = controller?.activeLeaf(member) ?: if (member.isGroup) return null else member.tag
        val result = measureNode?.invoke(leaf, url, timeoutMs)
        controller?.recordMeasurement(leaf, url, result)
        return result
    }

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
            resolvePlan = { groupTag ->
                controller?.buildFailoverPlan(groupTag) ?: FailoverPlan(null, emptyList(), 3_000)
            },
            onCandidateFailed = { groupTag, failedLeaf ->
                controller?.onCandidateFailed(groupTag, failedLeaf)
            },
            onCandidateConnected = { groupTag, leaf ->
                controller?.onCandidateConnected(groupTag, leaf)
            },
        )
        relayServer = relay
        relay.start(scope)
    }

    @Synchronized fun start(scope: CoroutineScope, reload: () -> Unit) {
        if (closed) return
        GroupRuntimeStatus.start(this)
        startRelayBeforeCore(scope)
        job = scope.launch(Dispatchers.IO) {
            val channel = ManagedChannelBuilder.forAddress("127.0.0.1", apiPort).usePlaintext().build()
            val stub = RoutingServiceGrpc.newBlockingStub(channel)
            val base = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", config.healthPort)))
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            val clients = config.users.mapValues { (_, user) ->
                base.newBuilder().proxyAuthenticator { _, response ->
                    response.request.newBuilder().header(
                        "Proxy-Authorization",
                        Credentials.basic(user, config.password),
                    ).build()
                }.build()
            }

            val measurement: suspend (String, String, Int) -> Long? = measurement@{ node, testUrl, timeoutMs ->
                    val client = clients[node] ?: return@measurement null
                    val req = Request.Builder().url(testUrl.ifBlank { "https://www.gstatic.com/generate_204" }).build()
                    val startedAt = SystemClock.elapsedRealtime()
                    suspendCancellableCoroutine { continuation ->
                        val call = client.newCall(req)
                        call.timeout().timeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                        continuation.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                GroupRuntimeStatus.recordMeasurement(this@GroupCoreSession, node, null)
                                continuation.resume(null)
                            }

                            override fun onResponse(call: Call, response: Response) {
                                response.use {
                                    if (it.code in 200..399) {
                                        val latency = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                                        GroupRuntimeStatus.recordMeasurement(this@GroupCoreSession, node, latency)
                                        continuation.resume(latency)
                                    } else {
                                        GroupRuntimeStatus.recordMeasurement(this@GroupCoreSession, node, null)
                                        continuation.resume(null)
                                    }
                                }
                            }
                        })
                    }
                }
            measureNode = measurement
            applyTarget = apply@{ group, target ->
                    val balancerTag = config.balancers[group] ?: return@apply
                    stub.withDeadlineAfter(2, TimeUnit.SECONDS).overrideBalancerTarget(
                        OverrideBalancerTargetRequest.newBuilder()
                            .setBalancerTag(balancerTag)
                            .setTarget(target)
                            .build()
                    )
                }
            val runtimeController = controller ?: return@launch

            try {
                runtimeController.start(this)
                config.routingBindings.forEach { (target, group) ->
                    logEvent("[Info] Routing target '$target' uses group '$group'.")
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
                val initialBalancerGroups = config.balancers.keys
                var previousWorkspace = config.workspace
                var previousRules = initialRules
                combine(store.observe(), ruleStore.observe()) { workspace, rules -> workspace to rules }.collect { (updated, rules) ->
                    val newRelayGroups = (updated.groups.map { it.tag } +
                        (updated.allNodes().map { it.tag } + com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS)
                            .map(OutboundGroupConfig::physicalTarget)).toSet()
                    val newBalancerGroups = emptySet<String>()
                    if (updated.allNodes() != initialNodes ||
                        updated.groups.map { it.tag to it.frontProxy } != config.workspace.groups.map { it.tag to it.frontProxy } ||
                        newRelayGroups != initialRelayGroups ||
                        newBalancerGroups != initialBalancerGroups
                    ) {
                        reload()
                        throw CancellationException("Applying updated group topology")
                    }
                    runtimeController.updateWorkspace(updated)
                    if (rules != previousRules ||
                        updated.effectiveRoutingBindings() != previousWorkspace.effectiveRoutingBindings() ||
                        updated.effectiveDefaultGroupTag() != previousWorkspace.effectiveDefaultGroupTag() ||
                        updated.routingMode != previousWorkspace.routingMode
                    ) {
                        runCatching { routingEditor.apply(updated, rules) }
                            .onSuccess {
                                previousRules = rules
                                previousWorkspace = updated
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
                runtimeController.stop()
                if (controller === runtimeController) controller = null
                measureNode = null
                applyTarget = null
                channel.shutdownNow()
                base.connectionPool.evictAll()
                GroupRuntimeStatus.stop(this@GroupCoreSession)
            }
        }
    }

    @Synchronized fun close() {
        closed = true
        controller?.stop()
        relayServer?.close()
        relayServer = null
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
