package com.simplexray.re.common.subscription

import android.net.InetAddresses
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address

/** Builds an Xray outbound or retains the Mihomo wire options for its local adapter. */
internal object MihomoProxyConverter {
    private val baseKeys = setOf(
        "name", "type", "server", "port", "udp", "tfo", "mptcp", "interface-name",
        "routing-mark", "ip-version",
    )
    private val tlsKeys = setOf(
        "tls", "sni", "servername", "alpn", "skip-cert-verify",
        "client-fingerprint", "name-cert-verify", "reality-opts",
    )
    private val transportKeys = setOf(
        "network", "ws-opts", "ws-headers", "http-opts", "grpc-opts", "xhttp-opts",
    )
    private val shadowsocksMethods = setOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
        "xchacha20-poly1305", "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
    )
    private val clientFingerprints = setOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
        "random", "randomized", "randomizednoalpn",
    )
    private val privateDomainSuffixes = setOf(
        "lan", "localdomain", "example", "invalid", "localhost", "test",
        "local", "home.arpa", "internal",
    )
    private val dotlessPrivateDomain = Regex("^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$")

    fun convert(fields: MihomoFields): JSONObject {
        val sourceType = fields.requiredText("type").lowercase()
        val protocol = when (sourceType) {
            "vless", "vmess", "trojan", "http" -> sourceType
            "ss", "shadowsocks" -> "shadowsocks"
            "socks", "socks5" -> "socks"
            else -> fields.capability("type", sourceType)
        }
        val protocolKeys = when (protocol) {
            "vless" -> setOf("uuid", "flow", "encryption")
            "vmess" -> setOf("uuid", "alterId", "cipher")
            "trojan" -> setOf("password")
            "shadowsocks" -> setOf(
                "password", "cipher", "udp-over-tcp", "udp-over-tcp-version", "plugin", "plugin-opts",
            )
            "http" -> setOf("username", "password", "headers")
            else -> setOf("username", "password")
        }
        fields.allow(baseKeys + tlsKeys + transportKeys + protocolKeys)
        val tag = fields.requiredText("name")
        val address = fields.requiredText("server")
        val port = fields.integer("port", 0)
        if (port !in 1..65535) fields.typeError("port")

        val plugin = if (protocol == "shadowsocks") fields.text("plugin").lowercase() else ""
        val pluginOptions = if (plugin.isNotBlank()) fields.objectValue("plugin-opts") else null
        val obfsMode = pluginOptions?.text("mode").orEmpty().lowercase()
        if (plugin.isNotBlank() &&
            (plugin !in setOf("obfs", "obfs-local", "simple-obfs") || obfsMode !in setOf("http", "tls"))
        ) fields.capability("plugin", "$plugin/$obfsMode")

        if (fields.has("udp")) {
            val udp = fields.boolean("udp")
            if (protocol == "http" && udp) fields.capability("udp", "http-udp")
        }

        val endpoint = JSONObject().put("address", address).put("port", port)
        val settings = when (protocol) {
            "vless" -> {
                val encryption = fields.text("encryption", "none")
                if (encryption != "none") fields.capability("encryption", encryption)
                val flow = fields.text("flow")
                if (flow !in setOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")) {
                    fields.capability("flow", flow)
                }
                val user = JSONObject()
                    .put("id", fields.requiredText("uuid"))
                    .put("encryption", encryption)
                if (flow.isNotEmpty()) user.put("flow", flow)
                endpoint.put("users", JSONArray().put(user))
                JSONObject().put("vnext", JSONArray().put(endpoint))
            }
            "vmess" -> {
                if (fields.integer("alterId", 0) != 0) {
                    fields.capability("alterId", "legacy-vmess")
                }
                val cipher = fields.text("cipher", "auto").lowercase()
                if (cipher !in setOf("auto", "aes-128-gcm", "chacha20-poly1305")) {
                    fields.capability("cipher", cipher)
                }
                endpoint.put(
                    "users",
                    JSONArray().put(JSONObject().put("id", fields.requiredText("uuid")).put("security", cipher)),
                )
                JSONObject().put("vnext", JSONArray().put(endpoint))
            }
            "trojan" -> {
                endpoint.put("password", fields.requiredText("password"))
                JSONObject().put("servers", JSONArray().put(endpoint))
            }
            "shadowsocks" -> {
                val method = fields.requiredText("cipher").lowercase()
                if (method !in shadowsocksMethods) fields.capability("cipher", method)
                endpoint.put("method", method).put("password", fields.requiredText("password"))
                JSONObject().put("servers", JSONArray().put(endpoint))
            }
            else -> {
                val username = fields.text("username")
                val password = fields.text("password")
                if (username.isNotEmpty() || password.isNotEmpty()) {
                    endpoint.put("users", JSONArray().put(JSONObject().put("user", username).put("pass", password)))
                }
                JSONObject().put("servers", JSONArray().put(endpoint)).apply {
                    if (protocol == "http" && fields.has("headers")) put("headers", fields.stringMap("headers"))
                }
            }
        }
        val stream = transport(fields)
        security(fields, protocol, address, stream)
        if (protocol in setOf("vless", "trojan") && stream.optString("security").isEmpty() &&
            supportsPlaintextEndpoint(address) == false
        ) {
            fields.capability("tls", "public-transport-security")
        }
        if (protocol == "vless" && fields.text("flow").isNotEmpty() &&
            stream.optString("security") !in setOf("tls", "reality")
        ) {
            fields.capability("flow", "vision-security")
        }
        socketOptions(fields, stream)
        return JSONObject()
            .put("tag", tag)
            .put("protocol", protocol)
            .put("settings", settings)
            .put("streamSettings", stream)
            .apply {
                if (plugin.isNotBlank()) {
                    put("x-simplexray-obfs-mode", obfsMode)
                    put("x-simplexray-obfs-host", pluginOptions?.text("host").orEmpty().ifBlank { address })
                }
                if (protocol == "shadowsocks" && fields.boolean("udp-over-tcp")) {
                    put("x-simplexray-uot-version", if (fields.integer("udp-over-tcp-version", 2) == 1) 1 else 2)
                }
            }
    }

    private fun transport(fields: MihomoFields): JSONObject {
        val network = fields.text("network", "tcp").lowercase().ifEmpty { "tcp" }
        val activeOption = when (network) {
            "tcp" -> ""
            "http" -> "http-opts"
            "ws", "websocket", "httpupgrade" -> "ws-opts"
            "grpc" -> "grpc-opts"
            "xhttp", "splithttp" -> "xhttp-opts"
            else -> fields.capability("network", network)
        }
        for (option in listOf("ws-opts", "http-opts", "grpc-opts", "xhttp-opts")) {
            if (fields.has(option) && option != activeOption) fields.capability(option, "network=$network")
        }
        if (fields.has("ws-headers") && activeOption != "ws-opts") {
            fields.capability("ws-headers", "network=$network")
        }
        val stream = JSONObject()
        when (activeOption) {
            "" -> stream.put("network", "tcp")
            "http-opts" -> {
                val options = fields.objectValue(activeOption)
                    ?: MihomoFields(JSONObject(), "${fields.location}.$activeOption")
                options.allow(setOf("method", "path", "headers"))
                val path = if (options.has("path")) options.strings("path").firstOrNull().orEmpty().ifBlank { "/" } else options.text("path", "/")
                val hostArray = JSONArray().put(fields.requiredText("server"))
                val httpSettings = JSONObject().put("path", path).put("host", hostArray)
                stream.put("network", "http").put("httpSettings", httpSettings)
            }
            "ws-opts" -> {
                val options = fields.objectValue(activeOption)
                    ?: MihomoFields(JSONObject(), "${fields.location}.$activeOption")
                options.allow(
                    setOf("path", "headers", "max-early-data", "early-data-header-name",
                        "v2ray-http-upgrade", "v2ray-http-upgrade-fast-open")
                )
                if (options.boolean("v2ray-http-upgrade-fast-open")) {
                    options.capability("v2ray-http-upgrade-fast-open", "upgrade-fast-open")
                }
                val upgrade = network == "httpupgrade" || options.boolean("v2ray-http-upgrade")
                var path = options.text("path", "/")
                val uri = Uri.parse(path)
                val queryEarlyData = uri.getQueryParameter("ed")?.takeIf { it.isNotEmpty() }?.let {
                    it.toIntOrNull() ?: options.typeError("path.ed")
                }
                val earlyData = queryEarlyData ?: options.integer("max-early-data", 0)
                val earlyDataHeader = if (queryEarlyData != null) {
                    "Sec-WebSocket-Protocol"
                } else {
                    options.text("early-data-header-name")
                }
                if (earlyData > 0 && earlyDataHeader.equals("Sec-WebSocket-Protocol", ignoreCase = true) == false) {
                    options.capability("early-data-header-name", "early-data-headers")
                }
                if (earlyData > 0 || queryEarlyData != null) {
                    val builder = uri.buildUpon().clearQuery()
                    uri.queryParameterNames.filter { it != "ed" }.forEach { key ->
                        uri.getQueryParameters(key).forEach { value -> builder.appendQueryParameter(key, value) }
                    }
                    if (earlyData > 0) builder.appendQueryParameter("ed", earlyData.toString())
                    path = builder.build().toString()
                }
                val headers = fields.stringMap("ws-headers")
                val nestedHeaders = options.stringMap("headers")
                nestedHeaders.keys().forEach { key -> headers.put(key, nestedHeaders.getString(key)) }
                val hostKey = headers.keys().asSequence().firstOrNull { it.equals("host", ignoreCase = true) }
                val settings = JSONObject().put("path", path)
                hostKey?.let {
                    settings.put("host", headers.getString(it))
                    headers.remove(it)
                }
                settings.put("headers", headers)
                stream.put("network", if (upgrade) "httpupgrade" else "ws")
                    .put(if (upgrade) "httpupgradeSettings" else "wsSettings", settings)
            }
            "grpc-opts" -> {
                val options = fields.objectValue(activeOption)
                    ?: MihomoFields(JSONObject(), "${fields.location}.$activeOption")
                options.allow(setOf("grpc-service-name", "grpc-user-agent", "ping-interval"))
                val settings = JSONObject().put("serviceName", options.text("grpc-service-name"))
                if (options.has("grpc-user-agent")) settings.put("user_agent", options.text("grpc-user-agent"))
                if (options.has("ping-interval")) settings.put("idle_timeout", options.integer("ping-interval", 0))
                stream.put("network", "grpc").put("grpcSettings", settings)
            }
            "xhttp-opts" -> {
                val options = fields.objectValue(activeOption)
                    ?: MihomoFields(JSONObject(), "${fields.location}.$activeOption")
                options.allow(setOf("host", "path", "mode", "headers", "no-grpc-header", "x-padding-bytes"))
                val mode = options.text("mode", "auto")
                if (mode !in setOf("auto", "packet-up", "stream-up", "stream-one")) {
                    options.capability("mode", mode)
                }
                val headers = options.stringMap("headers")
                headers.keys().asSequence().firstOrNull { it.equals("host", ignoreCase = true) }?.let {
                    options.capability("headers.$it", "xhttp-host")
                }
                val settings = JSONObject()
                    .put("host", options.text("host"))
                    .put("path", options.text("path", "/"))
                    .put("mode", mode)
                    .put("headers", headers)
                if (options.has("no-grpc-header")) settings.put("noGRPCHeader", options.boolean("no-grpc-header"))
                if (options.has("x-padding-bytes")) {
                    val padding = options.text("x-padding-bytes")
                    val bounds = padding.split("-", limit = 2).map {
                        it.toIntOrNull() ?: options.typeError("x-padding-bytes")
                    }
                    if (bounds.any { it <= 0 }) options.capability("x-padding-bytes", "xhttp-padding")
                    settings.put("xPaddingBytes", padding)
                }
                stream.put("network", "xhttp").put("xhttpSettings", settings)
            }
        }
        return stream
    }

    private fun security(fields: MihomoFields, protocol: String, address: String, stream: JSONObject) {
        val reality = fields.objectValue("reality-opts")
        val tls = fields.boolean("tls", protocol == "trojan")
        val transportHost = stream.optJSONObject("wsSettings")?.optString("host")
            ?: stream.optJSONObject("httpupgradeSettings")?.optString("host")
        val sni = fields.text("sni").ifEmpty {
            fields.text("servername").ifEmpty { transportHost?.takeIf { it.isNotEmpty() } ?: address }
        }
        val fingerprint = fields.text("client-fingerprint", if (reality != null) "chrome" else "").lowercase()
        if (fingerprint.isNotEmpty() && fingerprint !in clientFingerprints) {
            fields.capability("client-fingerprint", fingerprint)
        }
        if (reality != null) {
            reality.allow(setOf("public-key", "short-id"))
            if (stream.getString("network") !in setOf("tcp", "xhttp", "grpc")) {
                fields.capability("reality-opts", "reality-transports")
            }
            if (fields.has("alpn") || fields.has("name-cert-verify")) {
                fields.capability("reality-opts", "reality-policy")
            }
            stream.put("security", "reality")
                .put(
                    "realitySettings",
                    JSONObject().put("serverName", sni)
                        .put("fingerprint", fingerprint)
                        .put("password", reality.requiredText("public-key"))
                        .put("shortId", reality.text("short-id")),
                )
        } else if (tls) {
            val settings = JSONObject().put("serverName", sni)
            if (fields.boolean("skip-cert-verify")) {
                settings.put("allowInsecure", true)
            }
            if (fields.has("alpn")) {
                settings.put("alpn", JSONArray(fields.strings("alpn")))
            } else {
                when (stream.getString("network")) {
                    "ws", "httpupgrade" -> settings.put("alpn", JSONArray().put("http/1.1"))
                    "grpc" -> settings.put("alpn", JSONArray().put("h2"))
                }
            }
            if (fingerprint.isNotEmpty()) settings.put("fingerprint", fingerprint)
            if (fields.has("name-cert-verify")) settings.put("verifyPeerCertByName", fields.text("name-cert-verify"))
            stream.put("security", "tls").put("tlsSettings", settings)
        } else {
            for (key in listOf("sni", "servername", "alpn", "client-fingerprint", "name-cert-verify")) {
                if (fields.has(key)) fields.capability(key, "TLS/REALITY")
            }
        }
    }

    private fun socketOptions(fields: MihomoFields, stream: JSONObject) {
        val options = JSONObject()
        if (fields.has("tfo")) options.put("tcpFastOpen", fields.boolean("tfo"))
        if (fields.has("mptcp")) options.put("tcpMptcp", fields.boolean("mptcp"))
        if (fields.has("interface-name")) options.put("interface", fields.text("interface-name"))
        if (fields.has("routing-mark")) options.put("mark", fields.integer("routing-mark", 0))
        if (fields.has("ip-version")) {
            val strategy = when (val version = fields.text("ip-version")) {
                "dual" -> "UseIP"
                "ipv4" -> "UseIPv4"
                "ipv6" -> "UseIPv6"
                "ipv4-prefer" -> "UseIPv4v6"
                "ipv6-prefer" -> "UseIPv6v4"
                else -> fields.capability("ip-version", version)
            }
            options.put("domainStrategy", strategy)
        }
        if (options.length() > 0) stream.put("sockopt", options)
    }

    private fun supportsPlaintextEndpoint(address: String): Boolean {
        val host = address.removeSurrounding("[", "]")
        if (InetAddresses.isNumericAddress(host)) {
            val ip = InetAddresses.parseNumericAddress(host)
            val privateIp = if (ip is Inet6Address) {
                (ip.address[0].toInt() and 0xfe) == 0xfc
            } else {
                ip.isSiteLocalAddress
            }
            return privateIp || ip.isLoopbackAddress || ip.isLinkLocalAddress
        }
        val domain = host.lowercase().removeSuffix(".")
        return dotlessPrivateDomain.matches(domain) ||
            privateDomainSuffixes.any { domain == it || domain.endsWith(".$it") }
    }
}
