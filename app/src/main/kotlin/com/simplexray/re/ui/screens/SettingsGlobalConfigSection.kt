package com.simplexray.re.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.ui.scaffold.LocalLayerBackdrop
import com.simplexray.re.viewmodel.MainViewModel
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private val dnsStrategies = listOf("UseIP", "UseIPv4", "UseIPv6")

@Composable
internal fun SettingsGlobalConfigSection(mainViewModel: MainViewModel) {
    val context = LocalContext.current
    val store = remember(context) { GlobalConfigStore(context) }
    val version by GlobalConfigStore.version.collectAsStateWithLifecycle()
    val dns = remember(version) { store.loadJson().optJSONObject("dns") ?: JSONObject() }
    val servers = dns.optJSONArray("servers") ?: JSONArray()
    val hosts = dns.optJSONObject("hosts") ?: JSONObject()
    var serverIndex by remember { mutableStateOf<Int?>(null) }
    var hostName by remember { mutableStateOf<String?>(null) }
    SmallTitle("DNS")
    Card(Modifier.fillMaxWidth()) {
        OverlayDropdownPreference(title = "解析策略 · queryStrategy", items = dnsStrategies,
            selectedIndex = dnsStrategies.indexOf(dns.optString("queryStrategy", "UseIP")).coerceAtLeast(0),
            onSelectedIndexChange = { index -> store.updateDns { it.put("queryStrategy", dnsStrategies[index]) } })
    }
    SmallTitle("服务器 · servers")
    Card(Modifier.fillMaxWidth()) {
        for (index in 0 until servers.length()) {
            val value = servers.get(index)
            val server = value as? JSONObject
            ArrowPreference(title = server?.optString("address") ?: value.toString(),
                summary = server?.optString("queryStrategy")?.ifBlank { "跟随全局解析策略" }
                    ?: "跟随全局解析策略",
                onClick = { serverIndex = index })
        }
        ArrowPreference(title = "添加 DNS 服务器", onClick = { serverIndex = servers.length() })
    }
    SmallTitle("静态映射 · hosts")
    Card(Modifier.fillMaxWidth()) {
        hosts.keys().forEach { domain ->
            ArrowPreference(title = domain, summary = dnsLines(hosts.opt(domain)).replace("\n", " · "),
                onClick = { hostName = domain })
        }
        ArrowPreference(title = "添加 hosts 映射", onClick = { hostName = "" })
    }
    serverIndex?.let { index ->
        DnsServerSheet(servers.opt(index), onSave = { value ->
            store.updateDns { current ->
                val entries = current.optJSONArray("servers") ?: JSONArray()
                entries.put(index, value)
                current.put("servers", entries)
            }
        }, onDelete = if (index < servers.length()) {
            { store.updateDns { it.optJSONArray("servers")?.remove(index) } }
        } else null, onDismiss = { serverIndex = null })
    }
    hostName?.let { original ->
        DnsHostSheet(original, dnsLines(hosts.opt(original)), onSave = { domain, values ->
            store.updateDns { current ->
                val entries = current.optJSONObject("hosts") ?: JSONObject().also { current.put("hosts", it) }
                if (original.isNotEmpty()) entries.remove(original)
                entries.put(domain, if (values.size == 1) values.first() else JSONArray(values))
            }
        }, onDelete = if (original.isNotEmpty()) {
            { store.updateDns { it.optJSONObject("hosts")?.remove(original) } }
        } else null, onDismiss = { hostName = null })
    }
}

@Composable
private fun DnsServerSheet(
    original: Any?,
    onSave: (Any) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val originalObject = original as? JSONObject
    var address by remember { mutableStateOf(originalObject?.optString("address") ?: original?.toString().orEmpty()) }
    var port by remember { mutableStateOf(originalObject?.opt("port")?.toString().orEmpty()) }
    var strategy by remember { mutableStateOf(originalObject?.optString("queryStrategy").orEmpty()) }
    var domains by remember { mutableStateOf(dnsLines(originalObject?.opt("domains"))) }
    var expectIPs by remember { mutableStateOf(dnsLines(originalObject?.opt("expectIPs"))) }
    var clientIp by remember { mutableStateOf(originalObject?.optString("clientIp").orEmpty()) }
    var skipFallback by remember { mutableStateOf(originalObject?.optBoolean("skipFallback") == true) }
    var visible by remember { mutableStateOf(true) }
    val strategies = listOf("") + dnsStrategies
    GlassBottomSheet(show = visible, backdrop = LocalLayerBackdrop.current, title = "DNS 服务器",
        onDismissRequest = { visible = false }, onDismissFinished = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(address, { address = it }, label = "地址 · IP / DoH / DoT", modifier = Modifier.fillMaxWidth())
            TextField(port, { port = it }, label = "端口 · 留空使用协议默认值",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Card {
                OverlayDropdownPreference(title = "解析策略", items = strategies.map { it.ifBlank { "跟随全局" } },
                    selectedIndex = strategies.indexOf(strategy).coerceAtLeast(0),
                    onSelectedIndexChange = { strategy = strategies[it] })
                SwitchPreference(title = "仅处理匹配域名", summary = "skipFallback",
                    checked = skipFallback, onCheckedChange = { skipFallback = it })
            }
            TextField(domains, { domains = it }, label = "匹配域名 · 每行一条", modifier = Modifier.fillMaxWidth())
            TextField(expectIPs, { expectIPs = it }, label = "期望 IP · 每行一条", modifier = Modifier.fillMaxWidth())
            TextField(clientIp, { clientIp = it }, label = "客户端 IP · 可留空", modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val edited = originalObject?.let { JSONObject(it.toString()) } ?: JSONObject()
                edited.put("address", address.trim())
                if (port.isBlank()) edited.remove("port") else edited.put("port", port.toInt())
                if (strategy.isBlank()) edited.remove("queryStrategy") else edited.put("queryStrategy", strategy)
                putDnsLines(edited, "domains", domains)
                putDnsLines(edited, "expectIPs", expectIPs)
                if (clientIp.isBlank()) edited.remove("clientIp") else edited.put("clientIp", clientIp.trim())
                if (skipFallback) edited.put("skipFallback", true) else edited.remove("skipFallback")
                onSave(if (originalObject == null && edited.length() == 1) address.trim() else edited)
                visible = false
            }, enabled = address.isNotBlank() && (port.isBlank() || port.toIntOrNull() != null),
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存") }
            onDelete?.let { delete ->
                Button(onClick = { delete(); visible = false }, modifier = Modifier.fillMaxWidth()) { Text("删除服务器") }
            }
        }
    }
}

@Composable
private fun DnsHostSheet(
    original: String,
    originalValues: String,
    onSave: (String, List<String>) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var domain by remember { mutableStateOf(original) }
    var values by remember { mutableStateOf(originalValues) }
    var visible by remember { mutableStateOf(true) }
    GlassBottomSheet(show = visible, backdrop = LocalLayerBackdrop.current, title = "hosts 映射",
        onDismissRequest = { visible = false }, onDismissFinished = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(domain, { domain = it }, label = "域名或匹配表达式", modifier = Modifier.fillMaxWidth())
            TextField(values, { values = it }, label = "目标 IP 或域名 · 每行一条", modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSave(domain.trim(), splitDnsLines(values)); visible = false },
                enabled = domain.isNotBlank() && values.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存") }
            onDelete?.let { delete ->
                Button(onClick = { delete(); visible = false }, modifier = Modifier.fillMaxWidth()) { Text("删除映射") }
            }
        }
    }
}

private fun dnsLines(value: Any?): String = when (value) {
    is JSONArray -> (0 until value.length()).joinToString("\n") { value.getString(it) }
    is String -> value
    else -> ""
}

private fun splitDnsLines(value: String): List<String> = value.lines().map { it.trim() }.filter { it.isNotEmpty() }

private fun putDnsLines(target: JSONObject, name: String, value: String) {
    val entries = splitDnsLines(value)
    if (entries.isEmpty()) target.remove(name) else target.put(name, JSONArray(entries))
}
