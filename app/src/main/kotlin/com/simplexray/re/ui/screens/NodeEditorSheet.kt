package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.simplexray.re.common.config.ProxyNode
import com.simplexray.re.common.subscription.NodeImportConverter
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val nodeEditorProtocols = listOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http")
private const val nodeEditorUotKey = "x-simplexray-uot-version"
private const val nodeEditorObfsModeKey = "x-simplexray-obfs-mode"
private const val nodeEditorObfsHostKey = "x-simplexray-obfs-host"

@Composable
internal fun NodeConfigSheet(
    node: ProxyNode?,
    onSave: (String, Int?, String?, String?) -> String?,
    onDismiss: () -> Unit,
) {
    var outbound by remember(node) {
        mutableStateOf(
            if (node == null) {
                JSONObject().put("protocol", "vless").put("settings", JSONObject())
            } else {
                JSONObject(node.outboundJson).apply {
                    if (node.targetStrategy.isNotEmpty() && has("targetStrategy") == false) {
                        put("targetStrategy", node.targetStrategy)
                    }
                    if (node.udpOverTcpVersion > 0 && has(nodeEditorUotKey) == false) {
                        put(nodeEditorUotKey, node.udpOverTcpVersion)
                    }
                    if (node.obfsMode.isNotEmpty() && has(nodeEditorObfsModeKey) == false) {
                        put(nodeEditorObfsModeKey, node.obfsMode)
                    }
                    if (node.obfsHost.isNotEmpty() && has(nodeEditorObfsHostKey) == false) {
                        put(nodeEditorObfsHostKey, node.obfsHost)
                    }
                }
            }
        )
    }
    var advanced by remember(node) { mutableStateOf(outbound.editorProtocol() !in nodeEditorProtocols) }
    var jsonText by remember(node) { mutableStateOf(outbound.toString(2)) }
    var error by remember(node) { mutableStateOf<String?>(null) }
    var visible by remember(node) { mutableStateOf(true) }
    val edit: (JSONObject.() -> Unit) -> Unit = { change ->
        outbound = JSONObject(outbound.toString()).apply(change)
        error = null
    }

    GlassBottomSheet(
        show = visible,
        backdrop = LocalLayerBackdrop.current,
        title = if (node == null) "新增节点" else "编辑节点 · ${node.tag}",
        onDismissRequest = { visible = false },
        onDismissFinished = onDismiss,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    title = "编辑方式",
                    items = listOf("表单配置", "高级 JSON"),
                    selectedIndex = if (advanced) 1 else 0,
                    onSelectedIndexChange = { index ->
                        if (index == 1) {
                            if (advanced == false) jsonText = outbound.toString(2)
                            advanced = true
                            error = null
                        } else if (advanced) {
                            runCatching { JSONObject(jsonText) }
                                .onSuccess { outbound = it; advanced = false; error = null }
                                .onFailure { error = "请先完成 Xray 出站 JSON 编辑" }
                        }
                    },
                )
            }
            if (advanced) {
                Text("编辑完整 Xray 出站配置。表单配置保留额外字段、服务器、用户及高级参数。",
                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TextField(
                    value = jsonText,
                    onValueChange = { jsonText = it; error = null },
                    label = "Xray 出站 JSON",
                    textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 400.dp),
                )
            } else {
                NodeEditorBasics(outbound, edit)
                if (outbound.editorProtocol() in nodeEditorProtocols) {
                    NodeEditorTransport(outbound, edit)
                    NodeEditorSecurity(outbound, edit)
                } else {
                    Text("此协议的完整参数通过高级 JSON 编辑。", style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
            Button(
                onClick = {
                    val source = if (advanced) {
                        runCatching { JSONObject(jsonText) }.getOrElse {
                            error = "JSON 格式无效"
                            return@Button
                        }
                    } else JSONObject(outbound.toString())
                    val imported = NodeImportConverter.parse(source.toString())
                    if (imported.nodes.isEmpty()) {
                        error = imported.skippedReasons.firstOrNull() ?: "请输入有效的 Xray 节点配置"
                        return@Button
                    }
                    val shadowsocks = source.editorProtocol() == "shadowsocks"
                    val uot = if (node == null) null else if (shadowsocks) source.optInt(nodeEditorUotKey) else 0
                    val obfsMode = if (node == null) null else if (shadowsocks) source.optString(nodeEditorObfsModeKey) else ""
                    val obfsHost = if (node == null) null else if (shadowsocks) source.optString(nodeEditorObfsHostKey) else ""
                    if (node != null || shadowsocks == false) {
                        source.remove(nodeEditorUotKey)
                        source.remove(nodeEditorObfsModeKey)
                        source.remove(nodeEditorObfsHostKey)
                    }
                    error = onSave(source.toString(2), uot, obfsMode, obfsHost)
                    if (error == null) visible = false
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text("保存") }
        }
    }
}

@Composable
private fun NodeEditorBasics(outbound: JSONObject, edit: (JSONObject.() -> Unit) -> Unit) {
    val protocol = outbound.editorProtocol()
    val endpoint = outbound.editorEndpoint()
    val user = if (protocol in listOf("vless", "vmess") && outbound.editorUsesFlatSettings()) endpoint
        else endpoint?.optJSONArray("users")?.optJSONObject(0)
    NodeEditorSection("基础")
    Card(Modifier.fillMaxWidth()) {
        NodeEditorChoice("协议", protocol, nodeEditorProtocols, editValue = { value ->
            edit { put("protocol", value) }
        }, label = { if (it == "shadowsocks") "Shadowsocks" else it.uppercase() })
    }
    NodeEditorField("名称", outbound.optString("tag")) { value -> edit { editorPutText("tag", value) } }
    if (protocol in nodeEditorProtocols) {
        NodeEditorField("服务器地址", endpoint?.optString("address").orEmpty()) { value ->
            edit { editorEndpointObject().editorPutText("address", value) }
        }
        NodeEditorField("端口", endpoint?.optString("port").orEmpty(), keyboardType = KeyboardType.Number) { value ->
            edit {
                val server = editorEndpointObject()
                if (value.isEmpty()) server.remove("port") else server.put("port", value.toIntOrNull() ?: value)
            }
        }
        when (protocol) {
            "vless", "vmess" -> {
                NodeEditorField("UUID", user?.optString("id").orEmpty()) { value ->
                    edit { editorUserObject().editorPutText("id", value) }
                }
                Card(Modifier.fillMaxWidth()) {
                    if (protocol == "vless") {
                        NodeEditorChoice("加密", user?.optString("encryption").orEmpty().ifEmpty { "none" }, listOf("none"),
                            editValue = { value -> edit { editorUserObject().put("encryption", value) } })
                        NodeEditorChoice("Flow", user?.optString("flow").orEmpty(),
                            listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443"),
                            editValue = { value -> edit { editorUserObject().editorPutText("flow", value) } },
                            label = { it.ifEmpty { "服务端默认" } })
                    } else {
                        NodeEditorChoice("加密", user?.optString("security").orEmpty(),
                            listOf("", "auto", "aes-128-gcm", "chacha20-poly1305"),
                            editValue = { value -> edit { editorUserObject().editorPutText("security", value) } },
                            label = { it.ifEmpty { "原生默认 · auto" } })
                    }
                }
            }
            "trojan", "shadowsocks" -> {
                NodeEditorField("密码", endpoint?.optString("password").orEmpty(), password = true) { value ->
                    edit { editorEndpointObject().editorPutText("password", value) }
                }
                if (protocol == "shadowsocks") {
                    Card(Modifier.fillMaxWidth()) {
                        NodeEditorChoice("加密方式", endpoint?.optString("method").orEmpty(), listOf(
                            "", "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
                            "xchacha20-poly1305", "xchacha20-ietf-poly1305", "2022-blake3-aes-128-gcm",
                            "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
                        ), editValue = { value -> edit { editorEndpointObject().editorPutText("method", value) } },
                            label = { it.ifEmpty { "选择服务端加密方式" } })
                    }
                }
            }
            "socks", "http" -> {
                NodeEditorField("用户名（可选认证）", user?.optString("user").orEmpty()) { value ->
                    edit { editorPutAuth("user", value) }
                }
                NodeEditorField("密码（可选认证）", user?.optString("pass").orEmpty(), password = true) { value ->
                    edit { editorPutAuth("pass", value) }
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        NodeEditorChoice("目标 DNS 解析 · targetStrategy", outbound.optString("targetStrategy"),
            listOf("", "AsIs", "UseIP", "UseIPv4", "UseIPv6", "ForceIP", "ForceIPv4", "ForceIPv6"),
            editValue = { value -> edit { editorPutText("targetStrategy", value) } }, label = ::nodeEditorStrategyLabel)
    }
}

@Composable
private fun NodeEditorTransport(outbound: JSONObject, edit: (JSONObject.() -> Unit) -> Unit) {
    val stream = outbound.optJSONObject("streamSettings")
    val network = stream?.optString("network").orEmpty()
    val settingsKey = stream.editorTransportKey(network)
    val settings = stream?.optJSONObject(settingsKey)
    NodeEditorSection("传输")
    Card(Modifier.fillMaxWidth()) {
        NodeEditorChoice("传输协议", network, listOf("", "tcp", "raw", "ws", "grpc", "xhttp", "splithttp", "httpupgrade"),
            editValue = { value -> edit { editorObject("streamSettings").editorPutText("network", value) } },
            label = {
                when (it) {
                    "" -> "原生默认 · TCP"
                    "tcp" -> "TCP"
                    "raw" -> "TCP · raw"
                    "ws" -> "WebSocket"
                    "grpc" -> "gRPC"
                    "xhttp" -> "XHTTP"
                    "splithttp" -> "XHTTP · SplitHTTP"
                    "httpupgrade" -> "HTTPUpgrade"
                    else -> it
                }
            })
    }
    when (network) {
        "", "tcp", "raw" -> {
            val header = settings?.optJSONObject("header")
            val headerType = header?.optString("type").orEmpty()
            Card(Modifier.fillMaxWidth()) {
                NodeEditorChoice("TCP 报文头", headerType, listOf("", "none", "http"),
                    editValue = { value -> edit {
                        editorObject("streamSettings").editorObject(settingsKey).editorObject("header")
                            .editorPutText("type", value)
                    } }, label = { when (it) { "", "none" -> "原生 TCP"; "http" -> "HTTP 伪装"; else -> it } })
            }
            if (headerType == "http") {
                val request = header?.optJSONObject("request")
                val headers = request?.optJSONObject("headers")
                val hostKey = headers.editorHostKey() ?: "Host"
                NodeEditorField("HTTP Host", headers?.optJSONArray(hostKey)?.optString(0).orEmpty()) { value ->
                    edit {
                        editorObject("streamSettings").editorObject(settingsKey).editorObject("header")
                            .editorObject("request").editorObject("headers").editorPutFirstText(hostKey, value)
                    }
                }
                NodeEditorField("HTTP 路径", request?.optJSONArray("path")?.optString(0).orEmpty()) { value ->
                    edit {
                        editorObject("streamSettings").editorObject(settingsKey).editorObject("header")
                            .editorObject("request").editorPutFirstText("path", value)
                    }
                }
            }
        }
        "ws", "httpupgrade", "xhttp", "splithttp" -> {
            val headers = settings?.optJSONObject("headers")
            val headerHostKey = headers.editorHostKey() ?: "Host"
            val hostInHeader = network in listOf("ws", "httpupgrade") &&
                settings?.has("host") == false && headers?.has(headerHostKey) == true
            val host = if (hostInHeader) headers?.optString(headerHostKey).orEmpty() else settings?.optString("host").orEmpty()
            NodeEditorField("Host", host) { value ->
                edit {
                    val options = editorObject("streamSettings").editorObject(settingsKey)
                    if (hostInHeader) options.editorObject("headers").editorPutText(headerHostKey, value)
                    else options.editorPutText("host", value)
                }
            }
            NodeEditorField("路径", settings?.optString("path").orEmpty()) { value ->
                edit { editorObject("streamSettings").editorObject(settingsKey).editorPutText("path", value) }
            }
            if (network in listOf("xhttp", "splithttp")) {
                Card(Modifier.fillMaxWidth()) {
                    NodeEditorChoice("XHTTP 模式", settings?.optString("mode").orEmpty(),
                        listOf("", "auto", "packet-up", "stream-up", "stream-one"),
                        editValue = { value -> edit {
                            editorObject("streamSettings").editorObject(settingsKey).editorPutText("mode", value)
                        } }, label = { it.ifEmpty { "原生默认 · auto" } })
                }
            }
        }
        "grpc" -> {
            NodeEditorField("服务名 · serviceName", settings?.optString("serviceName").orEmpty()) { value ->
                edit { editorObject("streamSettings").editorObject(settingsKey).editorPutText("serviceName", value) }
            }
            NodeEditorField("Authority / Host（可选）", settings?.optString("authority").orEmpty()) { value ->
                edit { editorObject("streamSettings").editorObject(settingsKey).editorPutText("authority", value) }
            }
        }
    }
    if (outbound.editorProtocol() == "shadowsocks") {
        Card(Modifier.fillMaxWidth()) {
            NodeEditorChoice("UDP 传输", outbound.optInt(nodeEditorUotKey).toString(), listOf("0", "1", "2"),
                editValue = { value -> edit {
                    if (value == "0") remove(nodeEditorUotKey) else put(nodeEditorUotKey, value.toInt())
                } }, label = { if (it == "0") "原生 UDP" else "UDP over TCP v$it" })
            NodeEditorChoice("Shadowsocks 封装", outbound.optString(nodeEditorObfsModeKey), listOf("", "http", "tls"),
                editValue = { value -> edit { editorPutText(nodeEditorObfsModeKey, value) } },
                label = { when (it) { "" -> "原生 SS"; "http" -> "simple-obfs HTTP"; "tls" -> "simple-obfs TLS"; else -> it } })
        }
        if (outbound.optString(nodeEditorObfsModeKey).isNotEmpty()) {
            NodeEditorField("simple-obfs Host", outbound.optString(nodeEditorObfsHostKey)) { value ->
                edit { editorPutText(nodeEditorObfsHostKey, value) }
            }
        }
    }
}

@Composable
private fun NodeEditorSecurity(outbound: JSONObject, edit: (JSONObject.() -> Unit) -> Unit) {
    val stream = outbound.optJSONObject("streamSettings")
    val security = stream?.optString("security").orEmpty()
    val settingsKey = if (security == "reality") "realitySettings" else "tlsSettings"
    val settings = stream?.optJSONObject(settingsKey)
    NodeEditorSection("安全")
    Card(Modifier.fillMaxWidth()) {
        val choices = listOf("", "none", "tls") + if (outbound.editorProtocol() in listOf("vless", "trojan")) listOf("reality") else emptyList()
        NodeEditorChoice("连接安全", security, choices,
            editValue = { value -> edit { editorObject("streamSettings").editorPutText("security", value) } },
            label = { when (it) { "" -> "原生默认"; "none" -> "明文传输"; "tls" -> "TLS"; "reality" -> "REALITY"; else -> it } })
    }
    if (security in listOf("tls", "reality")) {
        NodeEditorField("SNI · serverName", settings?.optString("serverName").orEmpty()) { value ->
            edit { editorObject("streamSettings").editorObject(settingsKey).editorPutText("serverName", value) }
        }
        Card(Modifier.fillMaxWidth()) {
            NodeEditorChoice("客户端指纹", settings?.optString("fingerprint").orEmpty(), listOf(
                "", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", "randomizednoalpn",
            ), editValue = { value -> edit {
                editorObject("streamSettings").editorObject(settingsKey).editorPutText("fingerprint", value)
            } }, label = { it.ifEmpty { "原生默认" } })
        }
        if (security == "tls") {
            val alpn = settings?.optJSONArray("alpn")
            val alpnText = alpn?.let { array -> (0 until array.length()).joinToString(",") { array.optString(it) } }.orEmpty()
            NodeEditorField("ALPN（逗号分隔）", alpnText) { value ->
                edit {
                    val options = editorObject("streamSettings").editorObject(settingsKey)
                    if (value.isEmpty()) options.remove("alpn")
                    else options.put("alpn", JSONArray(value.split(',').map { it.trim() }))
                }
            }
        } else {
            val publicKeyField = if (settings?.has("publicKey") == true && settings.has("password") == false) "publicKey" else "password"
            NodeEditorField("REALITY 公钥", settings?.optString(publicKeyField).orEmpty()) { value ->
                edit { editorObject("streamSettings").editorObject(settingsKey).editorPutText(publicKeyField, value) }
            }
            NodeEditorField("Short ID", settings?.optString("shortId").orEmpty()) { value ->
                edit { editorObject("streamSettings").editorObject(settingsKey).editorPutText("shortId", value) }
            }
        }
    }
}

@Composable
private fun NodeEditorSection(title: String) {
    Text(title, modifier = Modifier.padding(top = 4.dp), style = MiuixTheme.textStyles.title3)
}

@Composable
private fun NodeEditorField(
    label: String,
    value: String,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NodeEditorChoice(
    title: String,
    value: String,
    choices: List<String>,
    editValue: (String) -> Unit,
    label: (String) -> String = { it },
) {
    val values = if (value in choices) choices else choices + value
    OverlayDropdownPreference(title = title, items = values.map(label), selectedIndex = values.indexOf(value),
        onSelectedIndexChange = { editValue(values[it]) })
}

private fun JSONObject.editorProtocol(): String = when (val protocol = optString("protocol").lowercase()) {
    "ss" -> "shadowsocks"
    "socks5" -> "socks"
    else -> protocol
}

private fun JSONObject.editorEndpointKey(): String = if (editorProtocol() in listOf("vless", "vmess")) "vnext" else "servers"

private fun JSONObject.editorUsesFlatSettings(): Boolean {
    val settings = optJSONObject("settings") ?: return false
    return listOf("address", "port", "id", "encryption", "method", "password").any(settings::has)
}

private fun JSONObject.editorEndpoint(): JSONObject? = if (editorUsesFlatSettings()) optJSONObject("settings")
    else optJSONObject("settings")?.optJSONArray(editorEndpointKey())?.optJSONObject(0)

private fun JSONObject.editorEndpointObject(): JSONObject = if (editorUsesFlatSettings()) editorObject("settings")
    else editorObject("settings").editorFirstObject(editorEndpointKey())

private fun JSONObject.editorUserObject(): JSONObject {
    val vless = editorProtocol() == "vless"
    val server = editorEndpointObject()
    if (editorUsesFlatSettings()) return server.apply {
        if (vless && has("encryption") == false) put("encryption", "none")
    }
    val existing = server.optJSONArray("users")?.optJSONObject(0)
    return existing ?: server.editorFirstObject("users").apply {
        if (vless) put("encryption", "none")
    }
}

private fun JSONObject.editorPutAuth(key: String, value: String) {
    val server = editorEndpointObject()
    val user = server.editorFirstObject("users")
    user.editorPutText(key, value)
    if (user.length() == 0) {
        val users = server.getJSONArray("users")
        users.remove(0)
        if (users.length() == 0) server.remove("users")
    }
}

private fun JSONObject.editorObject(key: String): JSONObject = optJSONObject(key) ?: JSONObject().also { put(key, it) }

private fun JSONObject.editorFirstObject(key: String): JSONObject {
    val array = optJSONArray(key) ?: JSONArray().also { put(key, it) }
    return array.optJSONObject(0) ?: JSONObject().also { array.put(0, it) }
}

private fun JSONObject.editorPutText(key: String, value: String) {
    if (value.isEmpty()) remove(key) else put(key, value)
}

private fun JSONObject.editorPutFirstText(key: String, value: String) {
    val array = optJSONArray(key) ?: JSONArray().also { put(key, it) }
    if (value.isEmpty()) {
        array.remove(0)
        if (array.length() == 0) remove(key)
    } else array.put(0, value)
}

private fun JSONObject?.editorHostKey(): String? = this?.keys()?.asSequence()?.firstOrNull { it.equals("host", ignoreCase = true) }

private fun JSONObject?.editorTransportKey(network: String): String = when (network) {
    "ws" -> "wsSettings"
    "httpupgrade" -> "httpupgradeSettings"
    "grpc" -> "grpcSettings"
    "xhttp", "splithttp" -> if (this?.has("xhttpSettings") == true) "xhttpSettings"
        else if (this?.has("splithttpSettings") == true) "splithttpSettings" else "xhttpSettings"
    else -> if (this?.has("rawSettings") == true) "rawSettings" else "tcpSettings"
}

private fun nodeEditorStrategyLabel(value: String): String = when (value) {
    "" -> "默认 · AsIs"
    "AsIs" -> "远端解析 · AsIs"
    "UseIP" -> "本地解析 IPv4 / IPv6 · UseIP"
    "UseIPv4" -> "本地解析 IPv4 · UseIPv4"
    "UseIPv6" -> "本地解析 IPv6 · UseIPv6"
    "ForceIP" -> "强制本地解析 · ForceIP"
    "ForceIPv4" -> "强制本地 IPv4 · ForceIPv4"
    "ForceIPv6" -> "强制本地 IPv6 · ForceIPv6"
    else -> value
}
