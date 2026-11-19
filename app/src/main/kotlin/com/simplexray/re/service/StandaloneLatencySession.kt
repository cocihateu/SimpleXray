package com.simplexray.re.service

import android.content.Context
import android.os.SystemClock
import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal object StandaloneLatencySession {
    suspend fun measure(
        context: Context,
        workspace: GroupWorkspace,
        members: List<GroupMemberRef>,
        url: String,
        onResult: (GroupMemberRef, Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        coroutineScope {
            val ports = mutableListOf<ServerSocket>()
            fun allocatePort(): Int = ServerSocket(0).also { ports.add(it) }.localPort
            val config = OutboundGroupConfig(workspace, allocatePort(), allocatePort(), ::allocatePort)
            val root = workspace.assembleRuntimeConfig(GlobalConfigStore(context).loadJson()).apply {
                put("inbounds", JSONArray())
                put("api", JSONObject().put("services", JSONArray().put("StatsService")))
                put("log", JSONObject().put("loglevel", "warning").put("access", "none"))
            }
            try {
                config.inject(root, context.filesDir, context.cacheDir, emptyList())
                root.remove("api")
                root.remove("stats")
            } finally {
                ports.forEach { it.close() }
            }
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", config.healthPort)))
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            val clients = config.users.mapValues { (_, user) ->
                client.newBuilder().proxyAuthenticator { _, response ->
                    response.request.newBuilder().header("Proxy-Authorization",
                        Credentials.basic(user, config.password)).build()
                }.build()
            }
            val controller = GroupRuntimeController(
                initialWorkspace = workspace,
                probeNode = { node, targetUrl -> clients[node]?.let { measureHttp(it, targetUrl) } },
                applyBalancerTarget = { _, _ -> },
                fallbackRelayOutbounds = config.fallbackRelayOutbounds,
                balancerTags = emptySet(),
                publishStatus = { _, _, _ -> },
                logEvent = {},
            )
            val relay = FallbackRelayServer(
                specs = config.fallbackRelaySpecs,
                nodeEntryPort = config.nodeEntryPort,
                nodeUsers = config.users,
                nodePassword = config.password,
                nodeUotVersions = workspace.allNodes().associate { it.tag to it.udpOverTcpVersion },
                resolvePlan = controller::buildFailoverPlan,
                onCandidateFailed = controller::onCandidateFailed,
                onCandidateConnected = controller::onCandidateConnected,
            )
            var process: Process? = null
            var readerJob: kotlinx.coroutines.Job? = null
            val obfs = SimpleObfsRelay(config.obfsPort, config.obfsSpecs)
            try {
                relay.start(this)
                obfs.start(this)
                val executable = "${TProxyService.getNativeLibraryDir(context)}/libxray.so"
                val child = ProcessBuilder(executable, "-config", "stdin:").apply {
                    directory(context.filesDir)
                    environment()["XRAY_LOCATION_ASSET"] = context.filesDir.path
                    redirectErrorStream(true)
                }.start()
                process = child
                val ready = CompletableDeferred<Unit>()
                readerJob = launch(Dispatchers.IO) {
                    child.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.contains("Xray") && line.contains(" started")) ready.complete(Unit)
                        }
                    }
                    if (ready.isCompleted == false) ready.completeExceptionally(IOException("测速核心启动失败"))
                }
                child.outputStream.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
                if (withTimeoutOrNull(10_000) { ready.await(); true } != true) {
                    throw IOException("测速核心启动超时")
                }
                fun leaves(member: GroupMemberRef): List<String> =
                    if (member.isGroup) workspace.groupByTag(member.tag)?.effectiveMembers()
                        ?.flatMap(::leaves).orEmpty() else listOf(member.tag)
                val prepared = mutableSetOf<GroupMemberRef>()
                suspend fun prepare(member: GroupMemberRef) {
                    if (prepared.add(member) == false) return
                    if (member.isGroup) {
                        val group = workspace.groupByTag(member.tag) ?: return
                        group.effectiveMembers().forEach { prepare(it) }
                        if (group.strategy == GroupStrategy.URL_TEST) {
                            leaves(member).distinct().forEach { leaf ->
                                controller.recordMeasurement(leaf, group.testUrl,
                                    clients[leaf]?.let { measureHttp(it, group.testUrl) })
                            }
                        }
                    } else {
                        workspace.groups.firstOrNull { owner -> owner.nodes.any { it.tag == member.tag } }
                            ?.frontProxy?.let { prepare(it) }
                    }
                }
                members.forEach { prepare(it) }
                val concurrency = Semaphore(6)
                members.map { member ->
                    async {
                        concurrency.withPermit {
                            val leaf = controller.activeLeaf(member) ?: member.tag
                            val delay = clients[leaf]?.let { measureHttp(it, url) }
                            controller.recordMeasurement(leaf, url, delay)
                            onResult(member, delay)
                        }
                    }
                }.awaitAll()
            } finally {
                relay.close()
                obfs.close()
                controller.stop()
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                process?.destroy()
                readerJob?.cancel()
                config.close()
            }
        }
    }

    private suspend fun measureHttp(client: OkHttpClient, url: String): Long? {
        val request = Request.Builder().url(url).build()
        val startedAt = SystemClock.elapsedRealtime()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        continuation.resume(if (it.code in 200..399)
                            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L) else null)
                    }
                }
            })
        }
    }
}
