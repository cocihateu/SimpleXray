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
import com.simplexray.re.common.config.RuleWorkspaceStore
import com.simplexray.re.common.config.TrafficRule
import com.simplexray.re.common.subscription.SubscriptionException
import com.simplexray.re.common.subscription.SubscriptionService
import com.simplexray.re.prefs.Preferences
import com.simplexray.re.prefs.LogLevel
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.time.Instant
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
    private val ruleStore = RuleWorkspaceStore(appContext)
    private val subscriptions = SubscriptionService(appContext)
    private val corePrefs: Preferences = Preferences(appContext)
    private val serverScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSockets = CopyOnWriteArrayList<ClashWebSocket>()

    private enum class GatewayStatus(private val code: Int, private val label: String) : Response.IStatus {
        BAD_GATEWAY(502, "Bad Gateway"),
        GATEWAY_TIMEOUT(504, "Gateway Timeout");
        override fun getRequestStatus() = code
        override fun getDescription() = "$code $label"
    }

    private class SubscriptionStatus(private val code: Int) : Response.IStatus {
        override fun getRequestStatus() = code
        override fun getDescription() = "$code Subscription Refresh"
    }

    @Volatile
    private var coreStatsClient: CoreStatsClient? = null
    private var statsPort = 0

    override fun start() {
        start(0, false)
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
        if (session.method == Method.PATCH) {
            val size = session.headers["content-length"]?.toIntOrNull() ?: 0
            val bytes = ByteArray(size)
            java.io.DataInputStream(session.inputStream).readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }
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
                    .put("connectionScope", "business-connections")
                jsonResponse(Response.Status.OK, versionJson.toString())
            }

            uri == "/configs" -> {
                val ws = workspaceStore.load()
                val global = GlobalConfigStore(appContext).loadJson()
                val configuredLevel = corePrefs.logLevel.value.let {
                    if (it == "auto") global.optJSONObject("log")?.optString("loglevel", "warning") ?: "warning" else it
                }
                val cfg = JSONObject()
                    .put("port", if (corePrefs.httpProxyEnabled) corePrefs.httpPort else 0)
                    .put("socks-port", corePrefs.socksPort)
                    .put("redir-port", 0)
                    .put("tproxy-port", 0)
                    .put("mixed-port", 0)
                    .put("mode", ws.routingMode)
                    .put("log-level", if (configuredLevel == "none") "silent" else configuredLevel)
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
                    jsonResponse(GatewayStatus.GATEWAY_TIMEOUT, """{"message":"Timeout or measure failure"}""")
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
                val workspace = workspaceStore.load()
                val mapper = ClashTagMapper(workspace)
                val mapped = JSONArray()
                ruleStore.load().forEachIndexed { index, rule ->
                    mapped.put(ClashPresentation.rule(rule, index, workspace, mapper))
                }
                jsonResponse(Response.Status.OK, JSONObject().put("rules", mapped).toString())
            }

            uri == "/providers/proxies" -> {
                val ws = workspaceStore.load()
                val root = assembleProviders(ws)
                jsonResponse(Response.Status.OK, JSONObject().put("providers", root).toString())
            }

            uri == "/providers/rules" ->
                jsonResponse(Response.Status.OK, JSONObject().put("providers", JSONObject()).toString())

            uri.startsWith("/providers/proxies/") && uri.endsWith("/healthcheck") -> {
                val path = uri.removePrefix("/providers/proxies/").removeSuffix("/healthcheck")
                val ws = workspaceStore.load()
                val group = ws.groups.filter { it.sourceType == GroupSourceType.SUBSCRIPTION }
                    .sortedByDescending { it.tag.length }.firstOrNull { path == it.tag || path.startsWith("${it.tag}/") }
                    ?: return jsonResponse(Response.Status.NOT_FOUND, """{"message":"Provider missing"}""")
                val mapper = ClashTagMapper(ws)
                val nodeName = path.removePrefix(group.tag).removePrefix("/")
                val nodes = if (nodeName.isBlank()) group.nodes else group.nodes.filter {
                    mapper.toApiName(false, it.tag) == nodeName
                }
                if (nodes.isEmpty()) return jsonResponse(Response.Status.NOT_FOUND, """{"message":"Provider node missing"}""")
                val url = session.parms["url"] ?: group.testUrl
                val timeout = session.parms["timeout"]?.toIntOrNull() ?: 5000
                val results = runBlocking {
                    coroutineScope {
                        nodes.map { node -> async {
                            mapper.toApiName(false, node.tag) to GroupRuntimeStatus.measureTarget(GroupMemberRef(node.tag), url, timeout)
                        } }.awaitAll()
                    }
                }
                val json = if (nodeName.isNotBlank()) JSONObject().put("delay", results.first().second ?: 0L)
                else JSONObject().apply { results.forEach { (name, latency) -> put(name, latency ?: 0L) } }
                jsonResponse(Response.Status.OK, json.toString())
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
                    ?: return jsonResponse(Response.Status.BAD_REQUEST, """{"message":"JSON settings required"}""")
                if (bodyJson.has("mode")) {
                    val mode = bodyJson.getString("mode")
                    if (mode !in setOf("rule", "global", "direct")) {
                        return jsonResponse(Response.Status.BAD_REQUEST, """{"message":"Invalid routing mode"}""")
                    }
                    workspaceStore.update { it.copy(routingMode = mode) }
                }
                if (bodyJson.has("log-level")) {
                    val level = bodyJson.getString("log-level")
                    corePrefs.logLevel = LogLevel.fromString(if (level == "silent") "none" else level)
                    GlobalConfigStore.notifyModified()
                }
                emptyResponse(Response.Status.NO_CONTENT)
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

                val result = runCatching { runBlocking { subscriptions.refresh(group.tag) } }
                result.fold(
                    onSuccess = { refreshed ->
                        if (refreshed.skippedCount > 0) {
                            VpnStateHub.emitLog("[Warning] Subscription '${group.tag}': ${refreshed.nodeCount} nodes; " +
                                "${refreshed.skippedCount} skipped. ${refreshed.skippedReasons.distinct().joinToString("; ")}")
                        } else {
                            VpnStateHub.emitLog("[Info] Subscription '${group.tag}': ${refreshed.nodeCount} nodes updated.")
                        }
                        emptyResponse(Response.Status.NO_CONTENT)
                    },
                    onFailure = { error ->
                        val status = (error as? SubscriptionException)?.let { SubscriptionStatus(it.code) }
                            ?: Response.Status.INTERNAL_ERROR
                        jsonResponse(status, JSONObject().put("message", error.message).toString())
                    },
                )
            }

            else -> jsonResponse(Response.Status.NOT_FOUND, """{"message":"Endpoint not found"}""")
        }
    }

    private fun handlePatch(uri: String, session: IHTTPSession): Response {
        return when (uri) {
            "/configs" -> handlePut(uri, session)
            "/rules/disable" -> {
                val changes = JSONObject(readRequestBody(session))
                ruleStore.update { rules ->
                    rules.mapIndexed { index, rule ->
                        if (changes.has(index.toString())) rule.copy(enabled = changes.getBoolean(index.toString()) == false)
                        else rule
                    }
                }
                emptyResponse(Response.Status.NO_CONTENT)
            }
            else -> jsonResponse(Response.Status.METHOD_NOT_ALLOWED, """{"message":"Method Not Allowed"}""")
        }
    }

    private fun handleDelete(uri: String): Response = when {
        uri.startsWith("/proxies/") -> {
            val ws = workspaceStore.load()
            val ref = ClashTagMapper(ws).resolve(uri.removePrefix("/proxies/"))
            if (ref?.isGroup == true && ws.groupByTag(ref.tag) != null) {
                GroupRuntimeStatus.clearGroupSelection(ref.tag)
                emptyResponse(Response.Status.NO_CONTENT)
            } else jsonResponse(Response.Status.NOT_FOUND, """{"message":"Group missing"}""")
        }
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
                    .put("alive", GroupRuntimeStatus.nodeMeasurements.value[node.tag]?.let { it.delayMs != null } ?: true)
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

    private suspend fun connectionSnapshot(): JSONObject {
        val totals = obtainStatsClient()?.getTraffic()
        val ws = workspaceStore.load()
        val mapper = ClashTagMapper(ws)
        val rules = ruleStore.load().associateBy { it.id }
        val connections = JSONArray()
        RelayConnectionRegistry.all().forEach { connection ->
            val rule = rules[connection.ruleId]
            connections.put(
                JSONObject().put("id", connection.id)
                    .put("metadata", JSONObject()
                        .put("network", connection.network).put("type", "Socks5")
                        .put("sourceIP", connection.sourceIp).put("sourcePort", connection.sourcePort.toString())
                        .put("destinationIP", connection.destinationIp).put("destinationPort", connection.destinationPort.toString())
                        .put("host", connection.destinationHost).put("dnsMode", "normal")
                        .put("process", "").put("processPath", "").put("uid", 0)
                        .put("inboundName", "Xray").put("inboundIP", "127.0.0.1")
                        .put("inboundPort", "").put("inboundUser", "")
                        .put("sourceGeoIP", "").put("sourceIPASN", "")
                        .put("destinationGeoIP", "").put("destinationIPASN", "")
                        .put("remoteDestination", "").put("sniffHost", "")
                        .put("specialProxy", "").put("specialRules", "").put("dscp", 0))
                    .put("upload", connection.uploadBytes).put("download", connection.downloadBytes)
                    .put("start", Instant.ofEpochMilli(connection.startTimestampMs).toString())
                    .put("chains", ClashPresentation.chains(connection, ws, mapper))
                    .put("rule", rule?.type ?: TrafficRule.TYPE_MATCH).put("rulePayload", rule?.payload.orEmpty()),
            )
        }
        return JSONObject().put("downloadTotal", totals?.downlink ?: 0L)
            .put("uploadTotal", totals?.uplink ?: 0L).put("connections", connections)
            .put("memory", obtainStatsClient()?.getSystemStats()?.alloc ?: 0L)
            .put("scope", "business-connections")
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
            val minimum = handshakeRequest.parms["level"] ?: "info"
            VpnStateHub.logFlow.collect { line ->
                if (!isOpen) return@collect
                val json = ClashPresentation.log(line, minimum) ?: return@collect
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
