package com.simplexray.re.service

import com.simplexray.re.common.config.GroupProfileStore
import com.xray.app.router.command.OverrideBalancerTargetRequest
import com.xray.app.router.command.RoutingServiceGrpc
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val path = MutableStateFlow<String?>(null)
    private val nodes = MutableStateFlow<Map<String, String>>(emptyMap())
    val activePath = path.asStateFlow()
    val activeNodes = nodes.asStateFlow()
    private var owner: GroupCoreSession? = null

    @Synchronized fun start(session: GroupCoreSession, configPath: String) {
        owner = session
        nodes.value = emptyMap()
        path.value = configPath
    }

    @Synchronized fun publish(session: GroupCoreSession, active: Map<String, String>) {
        if (owner === session) nodes.value = active
    }

    @Synchronized fun stop(session: GroupCoreSession) {
        if (owner === session) {
            owner = null
            path.value = null
            nodes.value = emptyMap()
        }
    }
}

internal class GroupCoreSession(
    private val store: GroupProfileStore,
    private val path: String,
    private val config: OutboundGroupConfig,
    private val apiPort: Int,
) {
    private var job: Job? = null
    @Volatile private var closed = false
    private val traffic = Channel<GroupTraffic>(Channel.UNLIMITED)
    private val detector = GroupFailureDetector(
        config.balancers.entries.associate { (group, marker) -> marker to group },
        config.nodes.toSet(),
    ) { traffic.trySend(it) }

    fun acceptLog(line: String) {
        if (closed == false) detector.accept(line)
    }

    @Synchronized fun start(scope: CoroutineScope, reload: () -> Unit) {
        if (closed) return
        GroupRuntimeStatus.start(this, path)
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
                    response.request.newBuilder().header("Proxy-Authorization",
                        Credentials.basic(user, config.password)).build()
                }.build()
            }
            val request = Request.Builder().url("https://www.gstatic.com/generate_204").build()
            try {
                val profiles = store.observe(path, config.nodes).onEach { updated ->
                    if (updated.targetStrategies != config.profile.targetStrategies ||
                        updated.groups.any { it.tag !in config.balancers }
                    ) {
                        reload()
                        throw CancellationException("Applying outbound configuration")
                    }
                }
                OutboundGroupMonitor().run(
                    profiles,
                    traffic = traffic.receiveAsFlow(),
                    check = { node ->
                        suspendCancellableCoroutine { continuation ->
                            val call = clients.getValue(node).newCall(request)
                            continuation.invokeOnCancellation { call.cancel() }
                            call.enqueue(object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
                                    continuation.resume(false)
                                }

                                override fun onResponse(call: Call, response: Response) {
                                    response.use { continuation.resume(it.code == 204) }
                                }
                            })
                        }
                    },
                    applyTarget = { group, leaf ->
                        stub.withDeadlineAfter(2, TimeUnit.SECONDS).overrideBalancerTarget(
                            OverrideBalancerTargetRequest.newBuilder()
                                .setBalancerTag(config.balancers.getValue(group))
                                .setTarget(leaf ?: config.reject)
                                .build()
                        )
                    },
                    publish = { GroupRuntimeStatus.publish(this@GroupCoreSession, it) },
                )
            } finally {
                channel.shutdownNow()
                base.connectionPool.evictAll()
                GroupRuntimeStatus.stop(this@GroupCoreSession)
            }
        }
    }

    @Synchronized fun close() {
        closed = true
        job?.cancel()
        traffic.close()
        GroupRuntimeStatus.stop(this)
        config.close()
    }
}
