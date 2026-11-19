package com.simplexray.re.service

import android.os.SystemClock
import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.GroupWorkspaceStore
import com.xray.app.router.command.OverrideBalancerTargetRequest
import com.xray.app.router.command.RoutingServiceGrpc
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
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

internal object GroupRuntimeStatus {
    private val nodes = MutableStateFlow<Map<String, String>>(emptyMap())
    private val fallbacks = MutableStateFlow<Map<String, FallbackRuntimeInfo>>(emptyMap())
    private val latencies = MutableStateFlow<Map<String, Long>>(emptyMap())
    val activeNodes = nodes.asStateFlow()
    val fallbackStates = fallbacks.asStateFlow()
    val nodeLatencies = latencies.asStateFlow()
    @Volatile
    private var owner: GroupCoreSession? = null

    @Synchronized fun start(session: GroupCoreSession) {
        owner = session
        nodes.value = emptyMap()
        fallbacks.value = emptyMap()
        latencies.value = emptyMap()
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
        }
    }

    fun switchGroupMemberLive(groupTag: String, member: GroupMemberRef) {
        owner?.switchGroupMemberLive(groupTag, member)
    }
}

internal class GroupCoreSession(
    private val store: GroupWorkspaceStore,
    private val config: OutboundGroupConfig,
    private val apiPort: Int,
) {
    private var job: Job? = null
    @Volatile private var closed = false
    @Volatile private var relayServer: FallbackRelayServer? = null
    @Volatile private var controller: GroupRuntimeController? = null
    private val initialGlobalVersion: Long = GlobalConfigStore.version.value

    fun switchGroupMemberLive(groupTag: String, member: GroupMemberRef) {
        controller?.forceSwitchMember(groupTag, member)
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
            resolvePlan = { groupTag ->
                controller?.buildFailoverPlan(groupTag) ?: initialFallbackPlan(groupTag)
            },
            onPrimaryFailed = { groupTag, failedLeaf ->
                controller?.onPrimaryFailed(groupTag, failedLeaf)
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

            val runtimeController = GroupRuntimeController(
                initialWorkspace = config.workspace,
                probeNode = { node, testUrl ->
                    val client = clients[node] ?: return@GroupRuntimeController null
                    val req = Request.Builder().url(testUrl.ifBlank { "https://www.gstatic.com/generate_204" }).build()
                    val startedAt = SystemClock.elapsedRealtime()
                    suspendCancellableCoroutine { continuation ->
                        val call = client.newCall(req)
                        continuation.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                continuation.resume(null)
                            }

                            override fun onResponse(call: Call, response: Response) {
                                response.use {
                                    if (it.code in 200..399) {
                                        continuation.resume((SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L))
                                    } else {
                                        continuation.resume(null)
                                    }
                                }
                            }
                        })
                    }
                },
                applyBalancerTarget = { group, target ->
                    val balancerTag = config.balancers[group] ?: return@GroupRuntimeController
                    stub.withDeadlineAfter(2, TimeUnit.SECONDS).overrideBalancerTarget(
                        OverrideBalancerTargetRequest.newBuilder()
                            .setBalancerTag(balancerTag)
                            .setTarget(target)
                            .build()
                    )
                },
                fallbackRelayOutbounds = config.fallbackRelayOutbounds,
                balancerTags = config.balancers.keys,
                publishStatus = { active, fallbackState, latencies ->
                    GroupRuntimeStatus.publish(this@GroupCoreSession, active, fallbackState, latencies)
                },
            )
            controller = runtimeController

            try {
                runtimeController.start(this)
                launch {
                    GlobalConfigStore.version.drop(1).collect { version ->
                        if (version != initialGlobalVersion) {
                            reload()
                            throw CancellationException("Applying updated global configuration")
                        }
                    }
                }
                val initialNodeSignature = nodeStructuralSignature(config.workspace)
                val initialRelayGroups = config.fallbackRelayOutbounds.keys
                val initialBalancerGroups = config.balancers.keys
                store.observe().collect { updated ->
                    val newRelayGroups = updated.groups
                        .filter { it.strategy == GroupStrategy.FALLBACK && it.effectiveMembers().size >= 2 }
                        .map { it.tag }
                        .toSet()
                    val newBalancerGroups = updated.groups.map { it.tag }.toSet() - newRelayGroups
                    if (nodeStructuralSignature(updated) != initialNodeSignature ||
                        newRelayGroups != initialRelayGroups ||
                        newBalancerGroups != initialBalancerGroups
                    ) {
                        reload()
                        throw CancellationException("Applying updated group topology")
                    }
                    runtimeController.updateWorkspace(updated)
                }
            } finally {
                runtimeController.stop()
                if (controller === runtimeController) controller = null
                channel.shutdownNow()
                base.connectionPool.evictAll()
                GroupRuntimeStatus.stop(this@GroupCoreSession)
            }
        }
    }

    private fun initialFallbackPlan(groupTag: String): FailoverPlan {
        val group = config.workspace.groupByTag(groupTag)
            ?: return FailoverPlan(null, emptyList(), 3_000)
        val leaves = group.effectiveMembers().mapNotNull { member ->
            if (member.isGroup) config.workspace.groupByTag(member.tag)?.effectiveMembers()?.firstOrNull()?.tag
            else member.tag
        }.distinct()
        return FailoverPlan(
            primaryLeaf = leaves.firstOrNull(),
            candidates = leaves,
            failoverTimeoutMs = group.failoverTimeoutSeconds.coerceAtLeast(1) * 1_000,
        )
    }

    private fun nodeStructuralSignature(workspace: GroupWorkspace): String =
        workspace.allNodes().joinToString("|") { "${it.tag}:${it.targetStrategy}:${it.outboundJson.length}" }

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
