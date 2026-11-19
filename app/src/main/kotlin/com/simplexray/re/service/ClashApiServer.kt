package com.simplexray.re.service

import android.content.Context
import com.simplexray.re.common.CoreStatsClient
import com.simplexray.re.common.config.DEFAULT_URL_TEST
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.GroupWorkspaceStore
import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.common.subscription.NodeImportConverter
import com.simplexray.re.prefs.Preferences
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Instant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Build dependency required:
 * implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
 */
internal class ClashApiServer(context: Context) :
    NanoWSD("127.0.0.1", ClashApiPreferences(context).port),
    Closeable {

    private val appContext: Context = context.applicationContext
    private val apiPrefs: ClashApiPreferences = ClashApiPreferences(appContext)
    private val workspaceStore: GroupWorkspaceStore = GroupWorkspaceStore(appContext)
    private val corePrefs: Preferences = Preferences(appContext)
    private val serverScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSockets = CopyOnWriteArrayList<ClashWebSocket>()
    private val httpClient: OkHttpClient = OkHttpClient.Builder().build()

    @Volatile
    private var coreStatsClient: CoreStatsClient? = null
    private var statsPort = 0

    fun startServer() {
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun close() {
        for (socket in activeSockets) {
            runCatching { socket.close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "Shutdown", false) }
        }
        activeSockets.clear()
        serverScope.cancel()
        coreStatsClient?.close()
        coreStatsClient = null
        stop()
    }

    private fun obtainStatsClient(): CoreStatsClient? {
        val port = corePrefs.apiPort
        if (port <= 0) return null
        val current = coreStatsClient
        if (current != null && statsPort == port) return current
        return synchronized(this) {
            if (statsPort != port) {
                coreStatsClient?.close()
                coreStatsClient = null
                statsPort = port
            }
            coreStatsClient ?: runCatching {
                CoreStatsClient.create(corePrefs.apiAddress, port)
            }.getOrNull()?.also { coreStatsClient = it }
        }
    }

    private fun checkAuthorized(session: IHTTPSession): Boolean {
        val secret = apiPrefs.secret.trim()
        if (secret.isEmpty()) return true
        val authHeader = session.headers["authorization"] ?: session.headers["Authorization"]
        if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
            if (authHeader.substring(7).trim() == secret) return true
        }
        val tokenParam = session.parms["token"]
        if (tokenParam != null && tokenParam.trim() == secret) {
            return true
        }
        return false
    }

    private fun buildCorsResponse(status: Response.IStatus, mimeType: String, txt: String): Response {
        val res = newFixedLengthResponse(status, mimeType, txt)
        res.addHeader("Access-Control-Allow-Origin", "*")
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS")
        res.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        res.addHeader("Access-Control-Allow-Private-Network", "true")
        return res
    }

    private fun jsonResponse(status: Response.IStatus, json: String): Response {
        return buildCorsResponse(status, "application/json; charset=utf-8", json)
    }

    private fun emptyResponse(status: Response.IStatus): Response {
        return buildCorsResponse(status, MIME_PLAINTEXT, "")
    }

    private fun readRequestBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        runCatching { session.parseBody(files) }
        val postData = files["postData"]
        if (!postData.isNullOrBlank()) return postData
        val contentPath = files["content"]
        if (!contentPath.isNullOrBlank()) {
            return runCatching { File(contentPath).readText() }.getOrDefault("")
        }
        return ""
    }

    override fun serveHttp(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            return buildCorsResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        }

        if (!checkAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, """{"message":"Unauthorized"}""")
        }

        val rawUri = session.uri.trimEnd('/').ifEmpty { "/" }
        val uri = rawUri

        return when (session.method) {
            Method.GET -> handleGet(uri, session)
            Method.PUT -> handlePut(uri, session)
            Method.PATCH -> handlePatch(uri, session)
            Method.DELETE -> handleDelete(uri)
            else -> jsonResponse(Response.Status.METHOD_NOT_ALLOWED, """{"message":"Method Not Allowed"}""")
        }
    }

    private fun handleGet(uri: String, session: IHTTPSession): Response {
        return when {
            uri == "/version" -> {
                val versionJson = JSONObject()
                    .put("version", "SimpleXray-Clash-1.0.0")
                    .put("premium", false)
                    .put("meta", false)
                    .put("core", "Xray")
                    .put("connectionScope", "group-relays")
                jsonResponse(Response.Status.OK, versionJson.toString())
            }

            uri == "/configs" -> {
                val ws = workspaceStore.load()
                val cfg = JSONObject()
                    .put("port", 0)
                    .put("socks-port", corePrefs.socksPort)
                    .put("redir-port", 0)
                    .put("tproxy-port", 0)
                    .put("mixed-port", 0)
                    .put("mode", ws.routingMode)
                    .put("log-level", "info")
                    .put("allow-lan", false)
                    .put("bind-address", "127.0.0.1")
                jsonResponse(Response.Status.OK, cfg.toString())
            }

            uri == "/proxies" -> {
                val ws = workspaceStore.load()
                val mapper = ClashTagMapper(ws)
                val proxies = assembleAllProxies(ws, mapper)
                val root = JSONObject().put("proxies", proxies)
                jsonResponse(Response.Status.OK, root.toString())
            }

            uri.startsWith("/proxies/") && uri.endsWith("/delay") -> {
                val encodedTarget = uri.removePrefix("/proxies/").removeSuffix("/delay")
                val targetName = encodedTarget
                val ws = workspaceStore.load()
                val mapper = ClashTagMapper(ws)
                val targetRef = mapper.resolve(targetName)
                    ?: return jsonResponse(Response.Status.NOT_FOUND, """{"message":"Proxy target found empty"}""")

                val testUrl = session.parms["url"]?.takeIf { it.isNotBlank() } ?: DEFAULT_URL_TEST
                val timeoutMs = session.parms["timeout"]?.toIntOrNull() ?: 5000

                val measured = runBlocking {
                    GroupRuntimeStatus.measureTarget(targetRef, testUrl, timeoutMs)
                }

                if (measured != null && measured >= 0) {
                    jsonResponse(Response.Status.OK, JSONObject().put("delay", measured).toString())
                } else {
                    jsonResponse(Response.Status.GATEWAY_TIMEOUT, """{"message":"Timeout or measure failure"}""")
                }
            }

            uri.startsWith("/proxies/") -> {
                val targetName = uri.removePrefix("/proxies/")
                val ws = workspaceStore.load()
                val mapper = ClashTagMapper(ws)
                val proxies = assembleAllProxies(ws, mapper)
                val target = proxies.optJSONObject(targetName)
                if (target != null) {
                    jsonResponse(Response.Status.OK, target.toString())
                } else {
                    jsonResponse(Response.Status.NOT_FOUND, """{"message":"Proxy not found"}""")
                }
            }

            uri == "/group" -> {
                val ws = workspaceStore.load()
                val mapper = ClashTagMapper(ws)
                val proxies = assembleAllProxies(ws, mapper)
                val groupsArray = JSONArray()
                for (group in ws.groups) {
                    groupsArray.put(proxies.getJSONObject(mapper.toApiName(true, group.tag)))
                }
                jsonResponse(Response.Status.OK, JSONObject().put("groups", groupsArray).toString())
            }

            uri.startsWith("/group/") && uri.endsWith("/delay") -> {
                val ws = workspaceStore.load()
                val mapper = ClashTagMapper(ws)
                val ref = mapper.resolve(uri.removePrefix("/group/").removeSuffix("/delay"))
                val group = ref?.takeIf { it.isGroup }?.let { ws.groupByTag(it.tag) }
                    ?: return jsonResponse(Response.Status.NOT_FOUND, """{"message":"Group missing"}""")
                val url = session.parms["url"] ?: group.testUrl
                val timeout = session.parms["timeout"]?.toIntOrNull() ?: 5000
                val delays = runBlocking {
                    coroutineScope {
                        group.effectiveMembers().map { member ->
                            async {
                                mapper.toApiName(member.isGroup, member.tag) to
                                    GroupRuntimeStatus.measureTarget(member, url, timeout)
                            }
                        }.awaitAll()
                    }
                }
                jsonResponse(Response.Status.OK, JSONObject().apply {
                    delays.forEach { (name, measured) -> put(name, measured ?: 0) }
                }.toString())
            }

            uri.startsWith("/group/") -> {
                val ws = workspaceStore.load()
                val item = assembleAllProxies(ws, ClashTagMapper(ws)).optJSONObject(uri.removePrefix("/group/"))
                if (item == null) jsonResponse(Response.Status.NOT_FOUND, """{"message":"Group missing"}""")
                else jsonResponse(Response.Status.OK, item.toString())
            }

            uri == "/connections" -> jsonResponse(Response.Status.OK, runBlocking { connectionSnapshot() }.toString())

            uri == "/rules" -> {
                val routing = JSONObject(GlobalConfigStore(appContext).readText()).optJSONObject("routing")
                if (routing?.optString("script")?.isNotBlank() == true) {
                    jsonResponse(Response.Status.NOT_IMPLEMENTED, """{"message":"Lua routing is edited in application settings"}""")
                } else {
                    val rules = routing?.optJSONArray("rules") ?: JSONArray()
                    val mapped = JSONArray()
                    for (index in 0 until rules.length()) {
                        val rule = rules.getJSONObject(index)
                        mapped.put(JSONObject().put("type", "Xray").put("payload", rule.toString())
                            .put("proxy", rule.optString("outboundTag")).put("size", 1))
                    }
                    jsonResponse(Response.Status.OK, JSONObject().put("rules", mapped).toString())
                }
            }

            uri == "/providers/proxies" -> {
                val ws = workspaceStore.load()
                val root = assembleProviders(ws)
                jsonResponse(Response.Status.OK, JSONObject().put("providers", root).toString())
            }

            uri.startsWith("/providers/proxies/") -> {
                val providerName = uri.removePrefix("/providers/proxies/")
                val ws = workspaceStore.load()
                val root = assembleProviders(ws)
                val item = root.optJSONObject(providerName)
                if (item != null) {
                    jsonResponse(Response.Status.OK, item.toString())
                } else {
                    jsonResponse(Response.Status.NOT_FOUND, """{"message":"Provider not found"}""")
                }
            }

            else -> jsonResponse(Response.Status.NOT_FOUND, """{"message":"Endpoint not found"}""")
        }
    }

    private fun handlePut(uri: String, session: IHTTPSession): Response {
        val bodyText = readRequestBody(session)
        return when {
            uri == "/configs" -> {
                val bodyJson = runCatching { JSONObject(bodyText) }.getOrNull()
                val mode = bodyJson?.optString("mode")
                if (mode != null && mode in setOf("rule", "global", "direct")) {
                    workspaceStore.update { it.copy(routingMode = mode) }
                    emptyResponse(Response.Status.NO_CONTENT)
                } else {
                    jsonResponse(Response.Status.BAD_REQUEST, """{"message":"Invalid routing mode"}""")
                }
            }

            uri.startsWith("/proxies/") && !uri.endsWith("/delay") -> {
                val groupName = uri.removePrefix("/proxies/")
                val bodyJson = runCatching { JSONObject(bodyText) }.getOrNull()
                val selectedMemberName = bodyJson?.optString("name")
                    ?: return jsonResponse(Response.Status.BAD_REQUEST, """{"message":"Missing selection name"}""")

                val ws = workspaceStore.load()
                val mapper = ClashTagMapper(ws)
                if (groupName == "GLOBAL") {
                    val chosen = mapper.resolve(selectedMemberName)?.takeIf { it.isGroup && ws.groupByTag(it.tag) != null }
                        ?: return jsonResponse(Response.Status.BAD_REQUEST, """{"message":"Select a workspace group"}""")
                    workspaceStore.update { it.copy(defaultGroupTag = chosen.tag) }
                    return emptyResponse(Response.Status.NO_CONTENT)
                }
                val groupRef = mapper.resolve(groupName)?.takeIf { it.isGroup }
                val group = ws.groups.firstOrNull { it.tag == groupRef?.tag }
                    ?: return jsonResponse(Response.Status.NOT_FOUND, """{"message":"Proxy group not found"}""")

                val memberRef = mapper.resolve(selectedMemberName)
                    ?: return jsonResponse(Response.Status.BAD_REQUEST, """{"message":"Unknown target member"}""")

                if (memberRef !in group.effectiveMembers()) {
                    return jsonResponse(Response.Status.BAD_REQUEST, """{"message":"Select a member of this group"}""")
                }
                if (group.strategy == GroupStrategy.SELECT) workspaceStore.update { current ->
                    val updated = current.groups.map {
                        if (it.tag == group.tag) it.copy(selectedMember = memberRef) else it
                    }
                    current.copy(groups = updated)
                }
                GroupRuntimeStatus.switchGroupMemberLive(group.tag, memberRef)
                emptyResponse(Response.Status.NO_CONTENT)
            }

            uri.startsWith("/providers/proxies/") -> {
                val providerName = uri.removePrefix("/providers/proxies/")
                val ws = workspaceStore.load()
                val group = ws.groups.firstOrNull { it.tag == providerName && it.sourceType == GroupSourceType.SUBSCRIPTION }
                    ?: return jsonResponse(Response.Status.NOT_FOUND, """{"message":"Provider not found"}""")

                val result = runCatching { runBlocking { refreshSubscriptionGroup(group.tag, group.subscriptionUrl) } }
                if (result.isSuccess) emptyResponse(Response.Status.NO_CONTENT)
                else jsonResponse(Response.Status.BAD_GATEWAY, JSONObject().put("message", result.exceptionOrNull()?.message).toString())
            }

            else -> jsonResponse(Response.Status.NOT_FOUND, """{"message":"Endpoint not found"}""")
        }
    }

    private fun handlePatch(uri: String, session: IHTTPSession): Response {
        return if (uri == "/configs") {
            handlePut(uri, session)
        } else {
            jsonResponse(Response.Status.METHOD_NOT_ALLOWED, """{"message":"Method Not Allowed"}""")
        }
    }

    private fun handleDelete(uri: String): Response = when {
        uri == "/connections" -> {
            RelayConnectionRegistry.closeAll()
            emptyResponse(Response.Status.NO_CONTENT)
        }
        uri.startsWith("/connections/") -> {
            if (RelayConnectionRegistry.close(uri.removePrefix("/connections/"))) emptyResponse(Response.Status.NO_CONTENT)
            else jsonResponse(Response.Status.NOT_FOUND, """{"message":"Relay connection missing"}""")
        }
        else -> jsonResponse(Response.Status.NOT_FOUND, """{"message":"Endpoint not found"}""")
    }

    private fun assembleAllProxies(ws: GroupWorkspace, mapper: ClashTagMapper): JSONObject {
        val proxies = JSONObject()
        val activeNodes = GroupRuntimeStatus.activeNodes.value

        proxies.put(
            "DIRECT",
            JSONObject()
                .put("name", "DIRECT")
                .put("type", "Direct")
                .put("history", JSONArray())
        )
        proxies.put(
            "REJECT",
            JSONObject()
                .put("name", "REJECT")
                .put("type", "Reject")
                .put("history", JSONArray())
        )

        val defaultTarget = ws.effectiveDefaultGroupTag()?.let { mapper.toApiName(true, it) }.orEmpty()
        val globalAll = JSONArray()
        for (g in ws.groups) {
            globalAll.put(mapper.toApiName(true, g.tag))
        }

        proxies.put(
            "GLOBAL",
            JSONObject()
                .put("name", "GLOBAL")
                .put("type", "Selector")
                .put("now", defaultTarget)
                .put("all", globalAll)
                .put("history", JSONArray())
        )

        for (group in ws.groups) {
            val members = group.effectiveMembers()
            val memberNames = JSONArray()
            for (m in members) {
                memberNames.put(mapper.toApiName(m.isGroup, m.tag))
            }

            val activeTag = activeNodes[group.tag].orEmpty()
            fun containsLeaf(ref: GroupMemberRef, visited: Set<String> = emptySet()): Boolean {
                if (ref.isGroup == false) return ref.tag == activeTag
                if (ref.tag in visited) return false
                return ws.groupByTag(ref.tag)?.effectiveMembers()
                    ?.any { containsLeaf(it, visited + ref.tag) } == true
            }
            val activeMember = members.firstOrNull { containsLeaf(it) }
                ?: group.selectedMember?.takeIf { group.strategy == GroupStrategy.SELECT && it in members }
                ?: members.firstOrNull()?.takeIf { VpnStateHub.state.value != VpnRunningState.Connected }
            val activeApiName = activeMember?.let { mapper.toApiName(it.isGroup, it.tag) }.orEmpty()
            val groupName = mapper.toApiName(true, group.tag)

            proxies.put(
                groupName,
                JSONObject()
                    .put("name", groupName)
                    .put("type", mapStrategyName(group.strategy))
                    .put("now", activeApiName)
                    .put("all", memberNames)
                    .put("history", measurementHistory(activeTag))
                    .put("alive", activeTag.isNotEmpty())
                    .put("udp", true)
                    .put("activeLeaf", activeTag)
                    .put("routingTargets", JSONArray(group.routingTargets))
            )
        }

        for (node in ws.allNodes()) {
            val apiName = mapper.toApiName(false, node.tag)
            proxies.put(
                apiName,
                JSONObject()
                    .put("name", apiName)
                    .put("type", mapProtocolType(node.protocol))
                    .put("history", measurementHistory(node.tag))
                    .put("alive", GroupRuntimeStatus.nodeMeasurements.value[node.tag]?.delayMs != null)
                    .put("udp", node.protocol != "http")
            )
        }

        return proxies
    }

    private fun measurementHistory(node: String): JSONArray = JSONArray().apply {
        GroupRuntimeStatus.nodeMeasurements.value[node]?.let { sample ->
            put(JSONObject().put("time", Instant.ofEpochMilli(sample.completedAtMs).toString())
                .put("delay", sample.delayMs ?: 0L))
        }
    }

    private fun assembleProviders(ws: GroupWorkspace): JSONObject {
        val providers = JSONObject()
        val mapper = ClashTagMapper(ws)
        for (group in ws.groups) {
            if (group.sourceType != GroupSourceType.SUBSCRIPTION) continue
            val proxiesArray = JSONArray()
            for (node in group.nodes) {
                proxiesArray.put(
                    JSONObject()
                        .put("name", mapper.toApiName(false, node.tag))
                        .put("type", mapProtocolType(node.protocol))
                        .put("history", measurementHistory(node.tag))
                )
            }
            val updatedStr = if (group.subscriptionUpdatedAtMs > 0) {
                Instant.ofEpochMilli(group.subscriptionUpdatedAtMs).toString()
            } else ""

            providers.put(
                group.tag,
                JSONObject()
                    .put("name", group.tag)
                    .put("proxies", proxiesArray)
                    .put("type", "Proxy")
                    .put("vehicleType", "HTTP")
                    .put("updatedAt", updatedStr)
            )
        }
        return providers
    }

    private fun mapStrategyName(strategy: GroupStrategy): String {
        return when (strategy) {
            GroupStrategy.SELECT -> "Selector"
            GroupStrategy.FALLBACK -> "Fallback"
            GroupStrategy.URL_TEST -> "URLTest"
        }
    }

    private fun mapProtocolType(protocol: String): String {
        return when (protocol.lowercase(Locale.US)) {
            "vmess" -> "Vmess"
            "vless" -> "Vless"
            "shadowsocks" -> "Shadowsocks"
            "trojan" -> "Trojan"
            "socks" -> "Socks5"
            "http" -> "Http"
            else -> protocol.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }
    }

    private suspend fun refreshSubscriptionGroup(groupTag: String, url: String) {
        if (url.isBlank()) throw IOException("Subscription URL required")
        val client = if (VpnStateHub.state.value == VpnRunningState.Connected) {
            httpClient.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(corePrefs.socksAddress, corePrefs.socksPort)))
                .build()
        } else httpClient

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "clash-verge/v1.7.7 mihomo/1.19.34 SimpleXray")
            .build()

        val body =
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful == false) throw IOException("Subscription HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        val parsed = NodeImportConverter.parse(body)
        if (parsed.nodes.isEmpty()) throw IOException(
            parsed.skippedReasons.distinct().take(5).joinToString("; ").ifBlank { "Subscription has zero native Xray nodes" },
        )

        workspaceStore.update { current ->
            val otherTags = current.groups
                .filter { it.tag != groupTag }
                .flatMap { it.nodes }
                .map { it.tag }
                .toMutableSet()
            val uniqueNodes = parsed.nodes.map { node ->
                val unique = GroupWorkspace.uniqueTag(node.tag, otherTags)
                otherTags.add(unique)
                if (unique == node.tag) node else {
                    val json = runCatching {
                        JSONObject(node.outboundJson).put("tag", unique).toString()
                    }.getOrDefault(node.outboundJson)
                    node.copy(tag = unique, outboundJson = json)
                }
            }
            val updatedGroups = current.groups.map { g ->
                if (g.tag == groupTag) {
                    val members = uniqueNodes.map { GroupMemberRef(it.tag, isGroup = false) } +
                        g.members.filter { it.isGroup }
                    g.copy(
                        nodes = uniqueNodes,
                        members = members,
                        selectedMember = g.selectedMember?.takeIf { it in members } ?: members.firstOrNull(),
                        subscriptionUpdatedAtMs = System.currentTimeMillis()
                    )
                } else g
            }
            current.copy(groups = updatedGroups)
        }
    }

    private suspend fun connectionSnapshot(): JSONObject {
        val totals = obtainStatsClient()?.getTraffic()
        val ws = workspaceStore.load()
        val mapper = ClashTagMapper(ws)
        val connections = JSONArray()
        RelayConnectionRegistry.all().forEach { connection ->
            connections.put(
                JSONObject().put("id", connection.id)
                    .put("metadata", JSONObject()
                        .put("network", connection.network).put("type", "Socks5")
                        .put("sourceIP", connection.sourceIp).put("sourcePort", connection.sourcePort.toString())
                        .put("destinationIP", connection.destinationIp).put("destinationPort", connection.destinationPort.toString())
                        .put("host", connection.destinationHost).put("dnsMode", "normal")
                        .put("process", "").put("processPath", ""))
                    .put("upload", connection.uploadBytes).put("download", connection.downloadBytes)
                    .put("start", Instant.ofEpochMilli(connection.startTimestampMs).toString())
                    .put("chains", JSONArray().put(mapper.toApiName(false, connection.leafTag))
                        .put(mapper.toApiName(true, connection.groupTag)))
                    .put("rule", "Group").put("rulePayload", connection.groupTag),
            )
        }
        return JSONObject().put("downloadTotal", totals?.downlink ?: 0L)
            .put("uploadTotal", totals?.uplink ?: 0L).put("connections", connections)
            .put("memory", obtainStatsClient()?.getSystemStats()?.alloc ?: 0L)
            .put("scope", "group-relays")
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val socket = ClashWebSocket(handshake)
        activeSockets.add(socket)
        return socket
    }

    internal inner class ClashWebSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        private var streamJob: Job? = null
        private val uri = handshake.uri.trimEnd('/').ifEmpty { "/" }

        override fun onOpen() {
            if (!checkAuthorized(handshakeRequest)) {
                runCatching { close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "Unauthorized", false) }
                return
            }

            streamJob = serverScope.launch {
                when (uri) {
                    "/traffic" -> loopTraffic()
                    "/memory" -> loopMemory()
                    "/logs" -> loopLogs()
                    "/connections" -> loopConnections()
                    else -> {
                        send("""{"message":"Unsupported stream"}""")
                        close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "Unsupported", false)
                    }
                }
            }
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            streamJob?.cancel()
            activeSockets.remove(this)
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {}

        override fun onPong(pong: NanoWSD.WebSocketFrame) {}

        override fun onException(exception: IOException) {
            streamJob?.cancel()
            activeSockets.remove(this)
        }

        private suspend fun loopTraffic() {
            var lastUp = 0L
            var lastDown = 0L
            var initial = true

            var lastAt = android.os.SystemClock.elapsedRealtime()
            while (currentCoroutineContext().isActive && isOpen) {
                val client = obtainStatsClient()
                val traffic = client?.getTraffic()
                val up = traffic?.uplink ?: 0L
                val down = traffic?.downlink ?: 0L

                val now = android.os.SystemClock.elapsedRealtime()
                val elapsed = (now - lastAt).coerceAtLeast(1L)
                val upRate = if (initial || up < lastUp) 0L else (up - lastUp) * 1000 / elapsed
                val downRate = if (initial || down < lastDown) 0L else (down - lastDown) * 1000 / elapsed
                lastAt = now
                initial = false
                lastUp = up
                lastDown = down

                val json = JSONObject()
                    .put("up", upRate)
                    .put("down", downRate)

                runCatching { send(json.toString()) }
                delay(1000)
            }
        }

        private suspend fun loopMemory() {
            while (currentCoroutineContext().isActive && isOpen) {
                val stats = obtainStatsClient()?.getSystemStats()
                val json = JSONObject()
                    .put("inuse", stats?.alloc ?: 0L)
                    .put("oslimit", 0L)

                runCatching { send(json.toString()) }
                delay(1000)
            }
        }

        private suspend fun loopLogs() {
            VpnStateHub.logFlow.collect { line ->
                if (!isOpen) return@collect
                val json = JSONObject()
                    .put("type", "info")
                    .put("payload", line)
                runCatching { send(json.toString()) }
            }
        }

        private suspend fun loopConnections() {
            while (currentCoroutineContext().isActive && isOpen) {
                val json = connectionSnapshot()

                runCatching { send(json.toString()) }
                delay(1000)
            }
        }
    }
}
