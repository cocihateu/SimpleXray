package com.simplexray.re.service

import android.content.Context
import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.prefs.Preferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

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
            val root = workspace.assembleRuntimeConfig(
                GlobalConfigStore(context).loadJson(), Preferences(context).dohBootstrapDns,
            ).apply {
                put("inbounds", JSONArray())
                put("api", JSONObject().put("services", JSONArray().put("StatsService")))
                put("log", JSONObject().put("loglevel", "warning").put("access", "none"))
            }
            try {
                config.inject(root, emptyList())
                root.remove("api")
                root.remove("stats")
            } finally {
                ports.forEach { it.close() }
            }
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", config.healthPort)))
                .callTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            val clients = config.users.mapValues { (_, user) ->
                client.newBuilder().dispatcher(Dispatcher()).proxyAuthenticator { _, response ->
                    response.request.newBuilder().header("Proxy-Authorization",
                        Credentials.basic(user, config.password)).build()
                }.build()
            }
            val controller = GroupRuntimeController(
                initialWorkspace = workspace,
                probeNode = { node, targetUrl, timeoutMs ->
                    clients[node]?.let { HttpLatencyMeasurement.measure(it, targetUrl, timeoutMs) }
                },
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
                onStalledUpload = controller::onStalledUpload,
                onDialResult = controller::onDialResult,
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
                controller.measureNestedGroups(members)
                val concurrency = Semaphore(6)
                members.map { member ->
                    async {
                        concurrency.withPermit {
                            onResult(member, controller.measureTarget(member, url, 5_000))
                        }
                    }
                }.awaitAll()
            } finally {
                relay.close()
                obfs.close()
                controller.stop()
                clients.values.forEach { it.dispatcher.cancelAll() }
                process?.destroy()
                readerJob?.cancel()
                config.close()
            }
        }
    }
}
