package com.simplexray.re.common.subscription

import android.net.Uri
import android.util.Base64
import com.simplexray.re.common.config.ConfigCodec
import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.common.config.ProxyNode
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

data class NodeImportResult(
    val nodes: List<ProxyNode> = emptyList(),
    val extractedGlobalConfig: JSONObject? = null,
    val skippedCount: Int = 0,
)

/**
 * Unified importer converting V2Ray links/subscriptions, Mihomo YAML proxies,
 * Xray JSON configs/fragments, and sing-box outbound snippets into Xray-supported ProxyNodes.
 */
internal object NodeImportConverter {
    private val EXCLUDED_PROTOCOLS = setOf("freedom", "direct", "blackhole", "block", "dns", "loopback", "selector", "urltest")
    private val LINK_SCHEMES = listOf("vless://", "vmess://", "trojan://", "ss://", "socks://", "socks5://", "http://", "https://")

    fun parse(rawInput: String): NodeImportResult {
        val trimmed = rawInput.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return NodeImportResult()

        // 1. Try direct JSON or brace-less JSON fragment (e.g. sing-box snippet).
        tryParseJsonOrFragment(trimmed)?.let { result ->
            if (result.nodes.isNotEmpty() || result.extractedGlobalConfig != null) {
                return result
            }
        }

        // 2. Try V2Ray share links (raw or Base64 subscription payload).
        val linkResult = tryParseShareLinks(trimmed)
        if (linkResult.nodes.isNotEmpty()) {
            return linkResult
        }

        // 3. Try Mihomo YAML (extract ONLY proxies section, ignoring proxy-groups and extra keys).
        tryParseMihomoYaml(trimmed)?.let { result ->
            if (result.nodes.isNotEmpty() || result.skippedCount > 0) {
                return result
            }
        }

        return NodeImportResult()
    }

    private fun tryParseJsonOrFragment(trimmed: String): NodeImportResult? {
        // JSON array of outbounds
        if (trimmed.startsWith("[")) {
            val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
            return parseOutboundArray(array, extractedGlobal = null)
        }

        // Brace-less JSON fragment such as a pasted sing-box outbound snippet ("method": ..., "type": "shadowsocks", ...)
        val candidateJson = if (trimmed.startsWith("{")) {
            trimmed
        } else if (trimmed.startsWith("\"") && trimmed.contains(":")) {
            "{$trimmed}"
        } else {
            return null
        }

        val root = ConfigCodec.parse(candidateJson) ?: runCatching { JSONObject(candidateJson) }.getOrNull() ?: return null

        // Mihomo JSON with "proxies" array
        if (root.optJSONArray("proxies") != null && root.optJSONArray("outbounds") == null) {
            return parseMihomoProxiesArray(root.getJSONArray("proxies"))
        }

        // Single sing-box outbound object (may also contain stray log/inbounds keys)
        if (root.has("type") && root.has("server") && root.has("server_port")) {
            val converted = convertSingBoxOutbound(root)
            return if (converted != null) {
                NodeImportResult(nodes = listOf(converted))
            } else {
                NodeImportResult(skippedCount = 1)
            }
        }

        // Single Xray outbound object
        if (root.has("protocol") && root.has("settings") && root.has("outbounds") == false) {
            val node = xrayOutboundToProxyNode(root)
            return if (node != null) NodeImportResult(nodes = listOf(node)) else NodeImportResult(skippedCount = 1)
        }

        val globalCandidate = if (GlobalConfigStore.hasGlobalSections(root)) root else null
        val outbounds = root.optJSONArray("outbounds")
        if (outbounds != null) {
            return parseOutboundArray(outbounds, extractedGlobal = globalCandidate)
        }

        return if (globalCandidate != null) {
            NodeImportResult(extractedGlobalConfig = globalCandidate)
        } else {
            null
        }
    }

    private fun parseOutboundArray(outbounds: JSONArray, extractedGlobal: JSONObject?): NodeImportResult {
        val nodes = mutableListOf<ProxyNode>()
        var skipped = 0
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            if (ob.has("protocol")) {
                val protocol = ob.optString("protocol").lowercase()
                if (protocol in EXCLUDED_PROTOCOLS) continue
                val node = xrayOutboundToProxyNode(ob)
                if (node != null) nodes += node else skipped++
            } else if (ob.has("type")) {
                val type = ob.optString("type").lowercase()
                if (type in EXCLUDED_PROTOCOLS) continue
                val node = convertSingBoxOutbound(ob)
                if (node != null) nodes += node else skipped++
            }
        }
        return NodeImportResult(nodes = nodes, extractedGlobalConfig = extractedGlobal, skippedCount = skipped)
    }

    private fun xrayOutboundToProxyNode(ob: JSONObject): ProxyNode? {
        val protocol = ob.optString("protocol").trim().lowercase()
        if (protocol.isEmpty() || protocol in EXCLUDED_PROTOCOLS) return null
        val clone = JSONObject(ob.toString())
        val tag = clone.optString("tag").trim().ifEmpty { deriveFallbackTag(clone, protocol) }
        clone.put("tag", tag)
        return ProxyNode(
            tag = tag,
            protocol = protocol,
            outboundJson = clone.toString(),
            targetStrategy = clone.optString("targetStrategy"),
        )
    }

    private fun deriveFallbackTag(ob: JSONObject, protocol: String): String {
        val settings = ob.optJSONObject("settings")
        val host = settings?.optJSONArray("vnext")?.optJSONObject(0)?.optString("address")
            ?: settings?.optJSONArray("servers")?.optJSONObject(0)?.optString("address")
            ?: settings?.optString("address")
            ?: ""
        return if (host.isNotBlank()) "$protocol-$host" else protocol
    }

    // -------------------------------------------------------------------------
    // Mihomo YAML: extract ONLY proxies array, ignoring proxy-groups & extras
    // -------------------------------------------------------------------------

    private fun tryParseMihomoYaml(trimmed: String): NodeImportResult? {
        val root = ConfigCodec.parse(trimmed) ?: return null
        val proxies = root.optJSONArray("proxies") ?: return null
        return parseMihomoProxiesArray(proxies)
    }

    private fun parseMihomoProxiesArray(proxies: JSONArray): NodeImportResult {
        val nodes = mutableListOf<ProxyNode>()
        var skipped = 0
        for (i in 0 until proxies.length()) {
            val obj = proxies.optJSONObject(i) ?: run {
                skipped++
                continue
            }
            val type = obj.optString("type").trim().lowercase()
            if (type in setOf("direct", "reject", "reject-drop", "pass")) continue
            val converted = runCatching { convertMihomoProxy(obj) }.getOrNull()
            if (converted != null) {
                nodes += converted
            } else {
                skipped++
            }
        }
        return NodeImportResult(nodes = nodes, skippedCount = skipped)
    }

    private fun convertMihomoProxy(src: JSONObject): ProxyNode? {
        val sourceType = src.optString("type").trim().lowercase()
        val name = src.optString("name").trim().ifEmpty {
            "${sourceType}-${src.optString("server")}"
        }
        val server = src.optString("server").trim()
        val port = src.optInt("port", 0)
        if (server.isEmpty() || port !in 1..65535) return null

        // Protocols that require the bundled Mihomo helper because Xray-core lacks wire support
        if (sourceType in setOf("hysteria", "hysteria2", "tuic", "anytls", "snell", "ssr")) {
            return buildMihomoAdapterNode(name, sourceType, src)
        }

        val protocol = when (sourceType) {
            "vless", "vmess", "trojan", "http", "wireguard" -> sourceType
            "ss", "shadowsocks" -> "shadowsocks"
            "socks", "socks5" -> "socks"
            else -> return null
        }

        if (protocol == "wireguard") {
            return convertMihomoWireGuard(name, src)
        }

        // Check Shadowsocks plugins
        var ssHttpObfsHost: String? = null
        if (protocol == "shadowsocks") {
            val plugin = src.optString("plugin").trim().lowercase()
            if (plugin.isNotEmpty()) {
                val opts = src.optJSONObject("plugin-opts")
                val mode = opts?.optString("mode")?.trim()?.lowercase().orEmpty()
                if ((plugin == "obfs" || plugin == "obfs-local" || plugin == "simple-obfs") && mode == "http") {
                    ssHttpObfsHost = opts?.optString("host")?.trim().orEmpty()
                } else {
                    // obfs=tls, v2ray-plugin, shadow-tls -> delegate cleanly to bundled Mihomo adapter
                    val clone = JSONObject(src.toString()).put("type", "ss").put("name", name)
                    return buildMihomoAdapterNode(name, "ss", clone)
                }
            }
        }

        val outbound = JSONObject().put("tag", name).put("protocol", protocol)
        val settings = JSONObject()
        when (protocol) {
            "vless" -> {
                val uuid = src.optString("uuid").trim()
                if (uuid.isEmpty()) return null
                val user = JSONObject()
                    .put("id", uuid)
                    .put("encryption", src.optString("encryption").ifBlank { "none" })
                src.optString("flow").trim().takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }
                settings.put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", server)
                            .put("port", port)
                            .put("users", JSONArray().put(user))
                    )
                )
            }

            "vmess" -> {
                val uuid = src.optString("uuid").trim()
                if (uuid.isEmpty()) return null
                val user = JSONObject()
                    .put("id", uuid)
                    .put("security", src.optString("cipher").ifBlank { "auto" })
                val alterId = src.optInt("alterId", 0)
                if (alterId > 0) user.put("alterId", alterId)
                settings.put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", server)
                            .put("port", port)
                            .put("users", JSONArray().put(user))
                    )
                )
            }

            "trojan" -> {
                val password = src.optString("password")
                if (password.isEmpty()) return null
                settings.put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", server)
                            .put("port", port)
                            .put("password", password)
                    )
                )
            }

            "shadowsocks" -> {
                val method = src.optString("cipher").ifBlank { src.optString("method") }.trim()
                val password = src.optString("password")
                if (method.isEmpty() || password.isEmpty()) return null
                val srv = JSONObject()
                    .put("address", server)
                    .put("port", port)
                    .put("method", method)
                    .put("password", password)
                if (src.optBoolean("udp-over-tcp", false)) {
                    srv.put("uot", true)
                }
                settings.put("servers", JSONArray().put(srv))
            }

            "socks", "http" -> {
                val srv = JSONObject().put("address", server).put("port", port)
                val username = src.optString("username")
                val password = src.optString("password")
                if (username.isNotEmpty() || password.isNotEmpty()) {
                    srv.put(
                        "users",
                        JSONArray().put(JSONObject().put("user", username).put("pass", password))
                    )
                }
                settings.put("servers", JSONArray().put(srv))
            }
        }
        outbound.put("settings", settings)

        val stream = buildMihomoStreamSettings(src, protocol, server, ssHttpObfsHost)
        if (stream.length() > 0) {
            outbound.put("streamSettings", stream)
        }
        return ProxyNode(tag = name, protocol = protocol, outboundJson = outbound.toString())
    }

    private fun convertMihomoWireGuard(name: String, src: JSONObject): ProxyNode? {
        val server = src.optString("server").trim()
        val port = src.optInt("port", 0)
        val privateKey = src.optString("private-key").trim()
        val publicKey = src.optString("public-key").trim()
        if (server.isEmpty() || port !in 1..65535 || privateKey.isEmpty() || publicKey.isEmpty()) return null
        val addresses = JSONArray()
        src.optString("ip").trim().takeIf { it.isNotEmpty() }?.let {
            addresses.put(if (it.contains("/")) it else "$it/32")
        }
        src.optString("ipv6").trim().takeIf { it.isNotEmpty() }?.let {
            addresses.put(if (it.contains("/")) it else "$it/128")
        }
        if (addresses.length() == 0) addresses.put("172.16.0.2/32")
        val peer = JSONObject()
            .put("publicKey", publicKey)
            .put("endpoint", "$server:$port")
        src.optString("pre-shared-key").trim().takeIf { it.isNotEmpty() }?.let {
            peer.put("preSharedKey", it)
        }
        val settings = JSONObject()
            .put("secretKey", privateKey)
            .put("address", addresses)
            .put("peers", JSONArray().put(peer))
            .put("mtu", src.optInt("mtu", 1420))
        src.optJSONArray("reserved")?.let { settings.put("reserved", it) }
        val outbound = JSONObject()
            .put("tag", name)
            .put("protocol", "wireguard")
            .put("settings", settings)
        return ProxyNode(tag = name, protocol = "wireguard", outboundJson = outbound.toString())
    }

    private fun buildMihomoStreamSettings(
        src: JSONObject,
        protocol: String,
        server: String,
        ssHttpObfsHost: String?,
    ): JSONObject {
        val stream = JSONObject()
        val rawNet = src.optString("network").trim().lowercase().ifEmpty { "tcp" }
        when (rawNet) {
            "ws" -> {
                val wsOpts = src.optJSONObject("ws-opts")
                val wsSettings = JSONObject()
                val path = wsOpts?.optString("path").orEmpty().ifBlank { "/" }
                wsSettings.put("path", path)
                val headers = wsOpts?.optJSONObject("headers") ?: src.optJSONObject("ws-headers")
                val host = headers?.optString("Host")?.takeIf { it.isNotBlank() }
                    ?: headers?.optString("host")?.takeIf { it.isNotBlank() }
                if (host != null) wsSettings.put("host", host)
                if (headers != null && headers.length() > 0) {
                    wsSettings.put("headers", JSONObject(headers.toString()))
                }
                if (wsOpts?.optBoolean("v2ray-http-upgrade", false) == true) {
                    stream.put("network", "httpupgrade").put("httpupgradeSettings", wsSettings)
                } else {
                    stream.put("network", "ws").put("wsSettings", wsSettings)
                }
            }

            "httpupgrade" -> {
                val opts = src.optJSONObject("ws-opts") ?: src.optJSONObject("http-opts")
                val settings = JSONObject().put("path", opts?.optString("path").orEmpty().ifBlank { "/" })
                opts?.optJSONObject("headers")?.optString("Host")?.takeIf { it.isNotBlank() }?.let {
                    settings.put("host", it)
                }
                stream.put("network", "httpupgrade").put("httpupgradeSettings", settings)
            }

            "grpc" -> {
                val grpcOpts = src.optJSONObject("grpc-opts")
                val settings = JSONObject().put(
                    "serviceName",
                    grpcOpts?.optString("grpc-service-name").orEmpty()
                )
                stream.put("network", "grpc").put("grpcSettings", settings)
            }

            "xhttp", "splithttp" -> {
                val opts = src.optJSONObject("xhttp-opts")
                val settings = JSONObject()
                opts?.optString("path")?.takeIf { it.isNotBlank() }?.let { settings.put("path", it) }
                opts?.optString("host")?.takeIf { it.isNotBlank() }?.let { settings.put("host", it) }
                opts?.optString("mode")?.takeIf { it.isNotBlank() }?.let { settings.put("mode", it) }
                stream.put("network", "xhttp").put("xhttpSettings", settings)
            }

            "http", "h2" -> {
                val httpOpts = src.optJSONObject("http-opts") ?: src.optJSONObject("h2-opts")
                val rawSettings = JSONObject()
                val header = JSONObject().put("type", "http")
                val request = JSONObject()
                val paths = httpOpts?.optJSONArray("path")
                if (paths != null && paths.length() > 0) {
                    request.put("path", paths)
                } else {
                    val singlePath = httpOpts?.optString("path").orEmpty().ifBlank { "/" }
                    request.put("path", JSONArray().put(singlePath))
                }
                val headers = httpOpts?.optJSONObject("headers")
                if (headers != null && headers.length() > 0) {
                    request.put("headers", JSONObject(headers.toString()))
                }
                header.put("request", request)
                rawSettings.put("header", header)
                stream.put("network", "raw").put("rawSettings", rawSettings)
            }

            else -> {
                stream.put("network", "raw")
                if (ssHttpObfsHost != null) {
                    stream.put("rawSettings", buildHttpObfsRawSettings(ssHttpObfsHost))
                }
            }
        }

        val reality = src.optJSONObject("reality-opts")
        val tls = src.optBoolean("tls", protocol == "trojan")
        val sni = src.optString("sni").ifBlank {
            src.optString("servername").ifBlank { server }
        }
        val fp = src.optString("client-fingerprint").trim()

        if (reality != null) {
            val realitySettings = JSONObject()
                .put("serverName", sni)
                .put("fingerprint", fp.ifEmpty { "chrome" })
                .put("publicKey", reality.optString("public-key"))
                .put("shortId", reality.optString("short-id"))
            stream.put("security", "reality").put("realitySettings", realitySettings)
        } else if (tls) {
            val tlsSettings = JSONObject().put("serverName", sni)
            if (src.optBoolean("skip-cert-verify", false)) {
                tlsSettings.put("allowInsecure", true)
            }
            if (fp.isNotEmpty()) {
                tlsSettings.put("fingerprint", fp)
            }
            src.optJSONArray("alpn")?.takeIf { it.length() > 0 }?.let {
                tlsSettings.put("alpn", JSONArray(it.toString()))
            }
            stream.put("security", "tls").put("tlsSettings", tlsSettings)
        }

        val sockopt = JSONObject()
        if (src.has("tfo")) sockopt.put("tcpFastOpen", src.optBoolean("tfo"))
        if (src.has("mptcp")) sockopt.put("tcpMptcp", src.optBoolean("mptcp"))
        if (sockopt.length() > 0) stream.put("sockopt", sockopt)

        return stream
    }

    // -------------------------------------------------------------------------
    // Sing-box outbound JSON converter (handles 3.jpg & sing-box outbounds)
    // -------------------------------------------------------------------------

    private fun convertSingBoxOutbound(src: JSONObject): ProxyNode? {
        val type = src.optString("type").trim().lowercase()
        if (type.isEmpty() || type in EXCLUDED_PROTOCOLS) return null
        val server = src.optString("server").trim()
        val port = src.optInt("server_port", 0)
        val tag = src.optString("tag").trim().ifEmpty { "$type-$server" }
        if (server.isEmpty() || port !in 1..65535) return null

        if (type in setOf("hysteria2", "tuic", "anytls")) {
            val mihomoObj = convertSingBoxToMihomoProxy(tag, type, src) ?: return null
            return buildMihomoAdapterNode(tag, type, mihomoObj)
        }

        when (type) {
            "shadowsocks" -> {
                val method = src.optString("method").trim()
                val password = src.optString("password")
                if (method.isEmpty() || password.isEmpty()) return null
                val plugin = src.optString("plugin").trim().lowercase()
                val pluginOpts = parseKeyValueOpts(src.optString("plugin_opts"))
                val uot = src.optBoolean("udp_over_tcp", false)

                if (plugin.isNotEmpty()) {
                    val obfsMode = pluginOpts["obfs"]?.lowercase().orEmpty()
                    val obfsHost = pluginOpts["obfs-host"].orEmpty()
                    if ((plugin == "obfs-local" || plugin == "simple-obfs" || plugin == "obfs") && obfsMode == "http") {
                        val srv = JSONObject()
                            .put("address", server)
                            .put("port", port)
                            .put("method", method)
                            .put("password", password)
                        if (uot) srv.put("uot", true)
                        val outbound = JSONObject()
                            .put("tag", tag)
                            .put("protocol", "shadowsocks")
                            .put("settings", JSONObject().put("servers", JSONArray().put(srv)))
                            .put(
                                "streamSettings",
                                JSONObject()
                                    .put("network", "raw")
                                    .put("rawSettings", buildHttpObfsRawSettings(obfsHost))
                            )
                        return ProxyNode(tag = tag, protocol = "shadowsocks", outboundJson = outbound.toString())
                    } else {
                        val mihomoProxy = JSONObject()
                            .put("name", tag)
                            .put("type", "ss")
                            .put("server", server)
                            .put("port", port)
                            .put("cipher", method)
                            .put("password", password)
                            .put("udp", true)
                        if (uot) mihomoProxy.put("udp-over-tcp", true)
                        if (plugin == "obfs-local" || plugin == "simple-obfs" || plugin == "obfs") {
                            mihomoProxy.put("plugin", "obfs")
                            mihomoProxy.put(
                                "plugin-opts",
                                JSONObject()
                                    .put("mode", obfsMode.ifEmpty { "tls" })
                                    .apply { if (obfsHost.isNotEmpty()) put("host", obfsHost) }
                            )
                        } else {
                            mihomoProxy.put("plugin", plugin)
                            val optsObj = JSONObject()
                            pluginOpts.forEach { (k, v) -> optsObj.put(k, v) }
                            if (optsObj.length() > 0) mihomoProxy.put("plugin-opts", optsObj)
                        }
                        return buildMihomoAdapterNode(tag, "ss", mihomoProxy)
                    }
                }

                val srv = JSONObject()
                    .put("address", server)
                    .put("port", port)
                    .put("method", method)
                    .put("password", password)
                if (uot) srv.put("uot", true)
                val outbound = JSONObject()
                    .put("tag", tag)
                    .put("protocol", "shadowsocks")
                    .put("settings", JSONObject().put("servers", JSONArray().put(srv)))
                return ProxyNode(tag = tag, protocol = "shadowsocks", outboundJson = outbound.toString())
            }

            "vless", "vmess" -> {
                val uuid = src.optString("uuid").trim()
                if (uuid.isEmpty()) return null
                val user = JSONObject().put("id", uuid)
                if (type == "vless") {
                    user.put("encryption", "none")
                    src.optString("flow").trim().takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }
                } else {
                    user.put("security", src.optString("security").ifBlank { "auto" })
                    val alterId = src.optInt("alter_id", 0)
                    if (alterId > 0) user.put("alterId", alterId)
                }
                val outbound = JSONObject()
                    .put("tag", tag)
                    .put("protocol", type)
                    .put(
                        "settings",
                        JSONObject().put(
                            "vnext",
                            JSONArray().put(
                                JSONObject()
                                    .put("address", server)
                                    .put("port", port)
                                    .put("users", JSONArray().put(user))
                            )
                        )
                    )
                val stream = buildSingBoxStreamSettings(src, server)
                if (stream.length() > 0) outbound.put("streamSettings", stream)
                return ProxyNode(tag = tag, protocol = type, outboundJson = outbound.toString())
            }

            "trojan" -> {
                val password = src.optString("password")
                if (password.isEmpty()) return null
                val outbound = JSONObject()
                    .put("tag", tag)
                    .put("protocol", "trojan")
                    .put(
                        "settings",
                        JSONObject().put(
                            "servers",
                            JSONArray().put(
                                JSONObject()
                                    .put("address", server)
                                    .put("port", port)
                                    .put("password", password)
                            )
                        )
                    )
                val stream = buildSingBoxStreamSettings(src, server)
                if (stream.length() > 0) outbound.put("streamSettings", stream)
                return ProxyNode(tag = tag, protocol = "trojan", outboundJson = outbound.toString())
            }

            "socks", "http" -> {
                val srv = JSONObject().put("address", server).put("port", port)
                val username = src.optString("username")
                val password = src.optString("password")
                if (username.isNotEmpty() || password.isNotEmpty()) {
                    srv.put("users", JSONArray().put(JSONObject().put("user", username).put("pass", password)))
                }
                val outbound = JSONObject()
                    .put("tag", tag)
                    .put("protocol", type)
                    .put("settings", JSONObject().put("servers", JSONArray().put(srv)))
                return ProxyNode(tag = tag, protocol = type, outboundJson = outbound.toString())
            }

            else -> return null
        }
    }

    private fun buildSingBoxStreamSettings(src: JSONObject, server: String): JSONObject {
        val stream = JSONObject()
        val transport = src.optJSONObject("transport")
        when (transport?.optString("type")?.lowercase()) {
            "ws" -> {
                val ws = JSONObject().put("path", transport.optString("path").ifBlank { "/" })
                transport.optJSONObject("headers")?.let { headers ->
                    ws.put("headers", JSONObject(headers.toString()))
                    headers.optString("Host").takeIf { it.isNotBlank() }?.let { ws.put("host", it) }
                }
                stream.put("network", "ws").put("wsSettings", ws)
            }

            "httpupgrade" -> {
                val hu = JSONObject().put("path", transport.optString("path").ifBlank { "/" })
                transport.optString("host").takeIf { it.isNotBlank() }?.let { hu.put("host", it) }
                stream.put("network", "httpupgrade").put("httpupgradeSettings", hu)
            }

            "grpc" -> {
                stream.put(
                    "network",
                    "grpc"
                ).put("grpcSettings", JSONObject().put("serviceName", transport.optString("service_name")))
            }

            else -> stream.put("network", "raw")
        }

        val tls = src.optJSONObject("tls")
        if (tls != null && tls.optBoolean("enabled", false)) {
            val sni = tls.optString("server_name").ifBlank { server }
            val fp = tls.optJSONObject("utls")?.optString("fingerprint").orEmpty()
            val reality = tls.optJSONObject("reality")
            if (reality != null && reality.optBoolean("enabled", false)) {
                stream.put("security", "reality").put(
                    "realitySettings",
                    JSONObject()
                        .put("serverName", sni)
                        .put("fingerprint", fp.ifBlank { "chrome" })
                        .put("publicKey", reality.optString("public_key"))
                        .put("shortId", reality.optString("short_id"))
                )
            } else {
                val tlsSettings = JSONObject().put("serverName", sni)
                if (tls.optBoolean("insecure", false)) tlsSettings.put("allowInsecure", true)
                if (fp.isNotBlank()) tlsSettings.put("fingerprint", fp)
                tls.optJSONArray("alpn")?.takeIf { it.length() > 0 }?.let {
                    tlsSettings.put("alpn", JSONArray(it.toString()))
                }
                stream.put("security", "tls").put("tlsSettings", tlsSettings)
            }
        }
        return stream
    }

    private fun convertSingBoxToMihomoProxy(tag: String, type: String, src: JSONObject): JSONObject? {
        val obj = JSONObject()
            .put("name", tag)
            .put("type", type)
            .put("server", src.optString("server"))
            .put("port", src.optInt("server_port"))
            .put("udp", true)
        src.optString("password").takeIf { it.isNotEmpty() }?.let { obj.put("password", it) }
        src.optString("uuid").takeIf { it.isNotEmpty() }?.let { obj.put("uuid", it) }
        src.optJSONObject("tls")?.let { tls ->
            tls.optString("server_name").takeIf { it.isNotBlank() }?.let { obj.put("sni", it) }
            if (tls.optBoolean("insecure", false)) obj.put("skip-cert-verify", true)
            tls.optJSONArray("alpn")?.takeIf { it.length() > 0 }?.let { obj.put("alpn", it) }
        }
        return obj
    }

    // -------------------------------------------------------------------------
    // V2Ray share links & Base64 subscription converter
    // -------------------------------------------------------------------------

    private fun tryParseShareLinks(trimmed: String): NodeImportResult {
        val decoded = if (containsShareLink(trimmed)) {
            trimmed
        } else {
            decodeBase64Text(trimmed)?.takeIf { containsShareLink(it) } ?: return NodeImportResult()
        }
        val nodes = mutableListOf<ProxyNode>()
        var skipped = 0
        for (rawLine in decoded.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (LINK_SCHEMES.none { line.startsWith(it, ignoreCase = true) }) continue
            val node = runCatching { parseSingleShareLink(line) }.getOrNull()
            if (node != null) nodes += node else skipped++
        }
        return NodeImportResult(nodes = nodes, skippedCount = skipped)
    }

    private fun containsShareLink(text: String): Boolean =
        LINK_SCHEMES.any { text.contains(it, ignoreCase = true) }

    private fun decodeBase64Text(input: String): String? {
        val compact = input.replace("\\s+".toRegex(), "")
        if (compact.length < 12) return null
        val normalized = compact.replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized
        }
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun parseSingleShareLink(line: String): ProxyNode? {
        val lower = line.lowercase()
        return when {
            lower.startsWith("vless://") -> parseVlessLink(line)
            lower.startsWith("vmess://") -> parseVmessLink(line)
            lower.startsWith("trojan://") -> parseTrojanLink(line)
            lower.startsWith("ss://") -> parseShadowsocksLink(line)
            lower.startsWith("socks://") || lower.startsWith("socks5://") -> parseSocksOrHttpLink(line, "socks")
            lower.startsWith("http://") || lower.startsWith("https://") -> parseSocksOrHttpLink(line, "http")
            else -> null
        }
    }

    private fun parseVlessLink(line: String): ProxyNode? {
        val uri = Uri.parse(line)
        val uuid = uri.userInfo?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val tag = decodeFragment(uri.fragment).ifBlank { "vless-$host:$port" }
        val user = JSONObject()
            .put("id", uuid)
            .put("encryption", uri.getQueryParameter("encryption").orEmpty().ifBlank { "none" })
        uri.getQueryParameter("flow")?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }

        val outbound = JSONObject()
            .put("tag", tag)
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", host)
                            .put("port", port)
                            .put("users", JSONArray().put(user))
                    )
                )
            )
            .put("streamSettings", buildShareLinkStreamSettings(uri, host, defaultSecurity = "none"))
        return ProxyNode(tag = tag, protocol = "vless", outboundJson = outbound.toString())
    }

    private fun parseTrojanLink(line: String): ProxyNode? {
        val uri = Uri.parse(line)
        val password = uri.userInfo?.let { urlDecode(it) }?.takeIf { it.isNotBlank() } ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: 443
        val tag = decodeFragment(uri.fragment).ifBlank { "trojan-$host:$port" }

        val outbound = JSONObject()
            .put("tag", tag)
            .put("protocol", "trojan")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", host)
                            .put("port", port)
                            .put("password", password)
                    )
                )
            )
            .put("streamSettings", buildShareLinkStreamSettings(uri, host, defaultSecurity = "tls"))
        return ProxyNode(tag = tag, protocol = "trojan", outboundJson = outbound.toString())
    }

    private fun parseVmessLink(line: String): ProxyNode? {
        val payload = line.substringAfter("://").trim()
        val decoded = decodeBase64Text(payload) ?: return null
        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        val host = json.optString("add").trim()
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port", 0)
        val uuid = json.optString("id").trim()
        if (host.isEmpty() || port !in 1..65535 || uuid.isEmpty()) return null
        val tag = json.optString("ps").trim().ifEmpty { "vmess-$host:$port" }
        val user = JSONObject()
            .put("id", uuid)
            .put("security", json.optString("scy").ifBlank { "auto" })
        val aid = json.optString("aid").toIntOrNull() ?: json.optInt("aid", 0)
        if (aid > 0) user.put("alterId", aid)

        val stream = JSONObject()
        val net = json.optString("net").lowercase().ifBlank { "tcp" }
        val path = json.optString("path")
        val reqHost = json.optString("host")
        when (net) {
            "ws" -> {
                val ws = JSONObject().put("path", path.ifBlank { "/" })
                if (reqHost.isNotBlank()) ws.put("host", reqHost)
                stream.put("network", "ws").put("wsSettings", ws)
            }

            "httpupgrade" -> {
                val hu = JSONObject().put("path", path.ifBlank { "/" })
                if (reqHost.isNotBlank()) hu.put("host", reqHost)
                stream.put("network", "httpupgrade").put("httpupgradeSettings", hu)
            }

            "grpc" -> {
                stream.put("network", "grpc").put("grpcSettings", JSONObject().put("serviceName", path))
            }

            "xhttp", "splithttp" -> {
                val xhttp = JSONObject().put("path", path.ifBlank { "/" })
                if (reqHost.isNotBlank()) xhttp.put("host", reqHost)
                stream.put("network", "xhttp").put("xhttpSettings", xhttp)
            }

            else -> {
                stream.put("network", "raw")
                if (json.optString("type").equals("http", ignoreCase = true)) {
                    stream.put("rawSettings", buildHttpObfsRawSettings(reqHost, path.ifBlank { "/" }))
                }
            }
        }
        if (json.optString("tls").equals("tls", ignoreCase = true)) {
            val sni = json.optString("sni").ifBlank { reqHost.ifBlank { host } }
            val tlsSettings = JSONObject().put("serverName", sni)
            json.optString("fp").takeIf { it.isNotBlank() }?.let { tlsSettings.put("fingerprint", it) }
            json.optString("alpn").takeIf { it.isNotBlank() }?.let { alpn ->
                tlsSettings.put("alpn", JSONArray(alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            }
            stream.put("security", "tls").put("tlsSettings", tlsSettings)
        }

        val outbound = JSONObject()
            .put("tag", tag)
            .put("protocol", "vmess")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", host)
                            .put("port", port)
                            .put("users", JSONArray().put(user))
                    )
                )
            )
            .put("streamSettings", stream)
        return ProxyNode(tag = tag, protocol = "vmess", outboundJson = outbound.toString())
    }

    private fun parseShadowsocksLink(line: String): ProxyNode? {
        val withoutScheme = line.substringAfter("://")
        val fragment = withoutScheme.substringAfter('#', "")
        val beforeFragment = withoutScheme.substringBefore('#')
        val queryStr = beforeFragment.substringAfter('?', "")
        val authorityPart = beforeFragment.substringBefore('?')
        val tag = decodeFragment(fragment)

        val method: String
        val password: String
        val host: String
        val port: Int

        if (authorityPart.contains('@')) {
            val userInfoRaw = authorityPart.substringBefore('@')
            val hostPort = authorityPart.substringAfter('@')
            val decodedUserInfo = if (userInfoRaw.contains(':')) {
                urlDecode(userInfoRaw)
            } else {
                decodeBase64Text(userInfoRaw) ?: urlDecode(userInfoRaw)
            }
            method = decodedUserInfo.substringBefore(':')
            password = decodedUserInfo.substringAfter(':', "")
            val parsedHostPort = splitHostPort(hostPort) ?: return null
            host = parsedHostPort.first
            port = parsedHostPort.second
        } else {
            val decoded = decodeBase64Text(authorityPart) ?: return null
            val userPart = decoded.substringBeforeLast('@', "")
            val hostPort = decoded.substringAfterLast('@', "")
            if (userPart.isEmpty() || hostPort.isEmpty()) return null
            method = userPart.substringBefore(':')
            password = userPart.substringAfter(':', "")
            val parsedHostPort = splitHostPort(hostPort) ?: return null
            host = parsedHostPort.first
            port = parsedHostPort.second
        }
        if (method.isBlank() || password.isEmpty() || host.isBlank() || port !in 1..65535) return null
        val finalTag = tag.ifBlank { "ss-$host:$port" }

        val pluginParam = queryStr.split('&')
            .mapNotNull { kv ->
                val k = kv.substringBefore('=')
                val v = kv.substringAfter('=', "")
                if (k == "plugin") urlDecode(v) else null
            }.firstOrNull().orEmpty()

        if (pluginParam.isNotBlank()) {
            val pluginName = pluginParam.substringBefore(';').lowercase()
            val opts = parseKeyValueOpts(pluginParam.substringAfter(';', ""))
            val obfsMode = opts["obfs"]?.lowercase().orEmpty()
            val obfsHost = opts["obfs-host"].orEmpty()
            if ((pluginName == "obfs-local" || pluginName == "simple-obfs") && obfsMode == "http") {
                val outbound = JSONObject()
                    .put("tag", finalTag)
                    .put("protocol", "shadowsocks")
                    .put(
                        "settings",
                        JSONObject().put(
                            "servers",
                            JSONArray().put(
                                JSONObject()
                                    .put("address", host)
                                    .put("port", port)
                                    .put("method", method)
                                    .put("password", password)
                            )
                        )
                    )
                    .put(
                        "streamSettings",
                        JSONObject()
                            .put("network", "raw")
                            .put("rawSettings", buildHttpObfsRawSettings(obfsHost))
                    )
                return ProxyNode(tag = finalTag, protocol = "shadowsocks", outboundJson = outbound.toString())
            } else {
                val mihomoProxy = JSONObject()
                    .put("name", finalTag)
                    .put("type", "ss")
                    .put("server", host)
                    .put("port", port)
                    .put("cipher", method)
                    .put("password", password)
                    .put("udp", true)
                if (pluginName == "obfs-local" || pluginName == "simple-obfs") {
                    mihomoProxy.put("plugin", "obfs").put(
                        "plugin-opts",
                        JSONObject()
                            .put("mode", obfsMode.ifEmpty { "tls" })
                            .apply { if (obfsHost.isNotEmpty()) put("host", obfsHost) }
                    )
                }
                return buildMihomoAdapterNode(finalTag, "ss", mihomoProxy)
            }
        }

        val outbound = JSONObject()
            .put("tag", finalTag)
            .put("protocol", "shadowsocks")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", host)
                            .put("port", port)
                            .put("method", method)
                            .put("password", password)
                    )
                )
            )
        return ProxyNode(tag = finalTag, protocol = "shadowsocks", outboundJson = outbound.toString())
    }

    private fun parseSocksOrHttpLink(line: String, protocol: String): ProxyNode? {
        val uri = Uri.parse(line)
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: if (protocol == "socks") 1080 else 8080
        val tag = decodeFragment(uri.fragment).ifBlank { "$protocol-$host:$port" }
        val srv = JSONObject().put("address", host).put("port", port)
        uri.userInfo?.takeIf { it.isNotBlank() }?.let { raw ->
            val decoded = if (raw.contains(':')) urlDecode(raw) else (decodeBase64Text(raw) ?: urlDecode(raw))
            val u = decoded.substringBefore(':')
            val p = decoded.substringAfter(':', "")
            srv.put("users", JSONArray().put(JSONObject().put("user", u).put("pass", p)))
        }
        val outbound = JSONObject()
            .put("tag", tag)
            .put("protocol", protocol)
            .put("settings", JSONObject().put("servers", JSONArray().put(srv)))
        return ProxyNode(tag = tag, protocol = protocol, outboundJson = outbound.toString())
    }

    private fun buildShareLinkStreamSettings(uri: Uri, defaultHost: String, defaultSecurity: String): JSONObject {
        val stream = JSONObject()
        val type = uri.getQueryParameter("type").orEmpty().lowercase().ifBlank { "raw" }
        val host = uri.getQueryParameter("host").orEmpty()
        val path = uri.getQueryParameter("path").orEmpty()

        when (type) {
            "ws" -> {
                val ws = JSONObject().put("path", path.ifBlank { "/" })
                if (host.isNotBlank()) ws.put("host", host)
                stream.put("network", "ws").put("wsSettings", ws)
            }

            "httpupgrade" -> {
                val hu = JSONObject().put("path", path.ifBlank { "/" })
                if (host.isNotBlank()) hu.put("host", host)
                stream.put("network", "httpupgrade").put("httpupgradeSettings", hu)
            }

            "grpc" -> {
                val grpc = JSONObject().put(
                    "serviceName",
                    uri.getQueryParameter("serviceName").orEmpty().ifBlank { path }
                )
                uri.getQueryParameter("authority")?.takeIf { it.isNotBlank() }?.let { grpc.put("authority", it) }
                if (uri.getQueryParameter("mode").equals("multi", ignoreCase = true)) {
                    grpc.put("multiMode", true)
                }
                stream.put("network", "grpc").put("grpcSettings", grpc)
            }

            "xhttp", "splithttp" -> {
                val xhttp = JSONObject().put("path", path.ifBlank { "/" })
                if (host.isNotBlank()) xhttp.put("host", host)
                uri.getQueryParameter("mode")?.takeIf { it.isNotBlank() }?.let { xhttp.put("mode", it) }
                uri.getQueryParameter("extra")?.takeIf { it.isNotBlank() }?.let { extraRaw ->
                    runCatching { JSONObject(extraRaw) }.getOrNull()?.let { xhttp.put("extra", it) }
                }
                stream.put("network", "xhttp").put("xhttpSettings", xhttp)
            }

            else -> {
                stream.put("network", "raw")
                if (uri.getQueryParameter("headerType").equals("http", ignoreCase = true)) {
                    stream.put("rawSettings", buildHttpObfsRawSettings(host, path.ifBlank { "/" }))
                }
            }
        }

        val security = uri.getQueryParameter("security").orEmpty().lowercase().ifBlank { defaultSecurity }
        val sni = uri.getQueryParameter("sni").orEmpty().ifBlank { host.ifBlank { defaultHost } }
        val fp = uri.getQueryParameter("fp").orEmpty()
        when (security) {
            "reality" -> {
                val reality = JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", fp.ifBlank { "chrome" })
                    .put("publicKey", uri.getQueryParameter("pbk").orEmpty())
                    .put("shortId", uri.getQueryParameter("sid").orEmpty())
                uri.getQueryParameter("spx")?.takeIf { it.isNotBlank() }?.let { reality.put("spiderX", it) }
                stream.put("security", "reality").put("realitySettings", reality)
            }

            "tls" -> {
                val tls = JSONObject().put("serverName", sni)
                if (fp.isNotBlank()) tls.put("fingerprint", fp)
                val allowInsecure = uri.getQueryParameter("allowInsecure")
                if (allowInsecure == "1" || allowInsecure.equals("true", ignoreCase = true)) {
                    tls.put("allowInsecure", true)
                }
                uri.getQueryParameter("alpn")?.takeIf { it.isNotBlank() }?.let { alpn ->
                    tls.put("alpn", JSONArray(alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                }
                stream.put("security", "tls").put("tlsSettings", tls)
            }
        }
        return stream
    }

    private fun buildHttpObfsRawSettings(host: String, path: String = "/"): JSONObject {
        val request = JSONObject().put("path", JSONArray().put(path))
        if (host.isNotBlank()) {
            request.put("headers", JSONObject().put("Host", JSONArray().put(host)))
        }
        return JSONObject().put(
            "header",
            JSONObject().put("type", "http").put("request", request)
        )
    }

    private fun buildMihomoAdapterNode(tag: String, mihomoType: String, proxyConfig: JSONObject): ProxyNode {
        val settings = JSONObject(proxyConfig.toString())
            .put("name", tag)
            .put("type", mihomoType)
        val outbound = JSONObject()
            .put("tag", tag)
            .put("protocol", "mihomo")
            .put("settings", settings)
        return ProxyNode(tag = tag, protocol = "mihomo", outboundJson = outbound.toString())
    }

    private fun parseKeyValueOpts(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val map = linkedMapOf<String, String>()
        for (part in raw.split(';')) {
            val item = part.trim()
            if (item.isEmpty()) continue
            val eq = item.indexOf('=')
            if (eq > 0) {
                map[item.substring(0, eq).trim()] = item.substring(eq + 1).trim()
            } else {
                map[item] = "true"
            }
        }
        return map
    }

    private fun splitHostPort(hostPort: String): Pair<String, Int>? {
        val trimmed = hostPort.trim()
        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            if (close < 0) return null
            val host = trimmed.substring(1, close)
            val port = trimmed.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
            return host to port
        }
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0) return null
        val host = trimmed.substring(0, colon)
        val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun decodeFragment(fragment: String?): String =
        fragment?.takeIf { it.isNotBlank() }?.let { urlDecode(it).trim() }.orEmpty()

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
