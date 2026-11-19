package com.simplexray.re.common.config

import android.content.Context
import android.util.Log
import com.simplexray.re.prefs.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "GlobalConfigStore"

data class GlobalConfigSummary(
    val dnsServerCount: Int,
    val inboundCount: Int,
    val routingScript: String?,
    val routingRuleCount: Int,
)

class GlobalConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, GLOBAL_CONFIG_FILENAME)

    fun file(): File {
        ensureInitialized()
        return file
    }

    fun readText(): String {
        ensureInitialized()
        return runCatching { file.readText() }.getOrElse { defaultGlobalJson().toString(2) }
    }

    fun loadJson(): JSONObject {
        ensureInitialized()
        val raw = runCatching { file.readText() }.getOrNull()
        val parsed = raw?.let { ConfigCodec.parse(it) }
        if (parsed != null) return parsed
        val fallback = defaultGlobalJson()
        writeText(fallback.toString(2))
        return fallback
    }

    fun saveRaw(content: String): Boolean {
        val parsed = ConfigCodec.parse(content) ?: return false
        writeText(parsed.toString(2))
        notifyModified()
        return true
    }

    fun updateDns(edit: (JSONObject) -> Unit) {
        synchronized(lock) {
            val root = loadJson()
            val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
            edit(dns)
            writeText(root.toString(2))
            notifyModified()
        }
    }

    /**
     * Extracts global sections (log, dns, inbounds, routing, fakedns, policy, and builtin outbounds)
     * from a full Xray JSON configuration and saves them as the active global config.
     */
    fun importFromFullConfig(root: JSONObject): Boolean {
        if (hasGlobalSections(root) == false) return false
        val extracted = extractGlobalSections(root)
        writeText(extracted.toString(2))
        notifyModified()
        return true
    }

    fun summary(): GlobalConfigSummary {
        val root = loadJson()
        val dnsServers = root.optJSONObject("dns")?.optJSONArray("servers")?.length() ?: 0
        val inbounds = root.optJSONArray("inbounds")?.length() ?: 0
        val routing = root.optJSONObject("routing")
        val script = routing?.optString("script")?.takeIf { it.isNotBlank() }
        val rules = routing?.optJSONArray("rules")?.length() ?: 0
        return GlobalConfigSummary(
            dnsServerCount = dnsServers,
            inboundCount = inbounds,
            routingScript = script,
            routingRuleCount = rules,
        )
    }

    private fun ensureInitialized() {
        if (file.isFile && file.length() > 0L) return
        synchronized(lock) {
            if (file.isFile && file.length() > 0L) return
            val seeded = seedFromExistingConfig() ?: defaultGlobalJson()
            writeText(seeded.toString(2))
        }
    }

    private fun seedFromExistingConfig(): JSONObject? {
        val prefs = Preferences(appContext)
        val candidates = buildList {
            prefs.selectedConfigPath?.let { add(File(it)) }
            appContext.filesDir.listFiles { f ->
                f.isFile &&
                    f.name != GLOBAL_CONFIG_FILENAME &&
                    f.name != GroupWorkspaceStore.WORKSPACE_FILENAME &&
                    f.name != "extra_api.json" &&
                    (f.name.endsWith(".json", ignoreCase = true) ||
                        f.name.endsWith(".yaml", ignoreCase = true) ||
                        f.name.endsWith(".yml", ignoreCase = true))
            }?.sortedByDescending { it.lastModified() }?.let { addAll(it) }
        }
        for (candidate in candidates) {
            if (candidate.isFile == false) continue
            val parsed = runCatching { ConfigCodec.parse(candidate.readText()) }.getOrNull() ?: continue
            if (hasGlobalSections(parsed)) {
                Log.i(TAG, "Seeded global config from ${candidate.name}")
                return extractGlobalSections(parsed)
            }
        }
        return null
    }

    private fun writeText(content: String) {
        synchronized(lock) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content)
            if (tmp.renameTo(file) == false) {
                file.writeText(content)
                tmp.delete()
            }
        }
    }

    companion object {
        const val GLOBAL_CONFIG_FILENAME = "global_config.json"
        private val GLOBAL_KEYS = listOf("log", "dns", "inbounds", "routing", "fakedns", "policy")
        private val BUILTIN_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")
        private val BUILTIN_TAGS = setOf("direct", "block")
        private val lock = Any()
        private val _version = MutableStateFlow(0L)
        val version: StateFlow<Long> = _version.asStateFlow()

        fun notifyModified() {
            _version.value = System.currentTimeMillis()
        }

        fun hasGlobalSections(root: JSONObject): Boolean =
            root.has("dns") || root.has("inbounds") || root.has("routing") || root.has("fakedns")

        fun extractGlobalSections(root: JSONObject): JSONObject {
            val out = JSONObject()
            for (key in GLOBAL_KEYS) {
                val value = root.opt(key) ?: continue
                if (value != JSONObject.NULL) {
                    out.put(key, cloneJsonValue(value))
                }
            }
            val builtins = JSONArray()
            val outbounds = root.optJSONArray("outbounds")
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val ob = outbounds.optJSONObject(i) ?: continue
                    val protocol = ob.optString("protocol").lowercase()
                    val tag = ob.optString("tag").lowercase()
                    if (protocol in BUILTIN_PROTOCOLS || tag in BUILTIN_TAGS) {
                        builtins.put(JSONObject(ob.toString()))
                    }
                }
            }
            if (builtins.length() > 0) {
                out.put("outbounds", builtins)
            }
            if (out.has("log") == false) {
                out.put("log", JSONObject().put("loglevel", "warning"))
            }
            if (out.has("inbounds") == false) {
                out.put("inbounds", defaultInbounds())
            }
            return out
        }

        fun defaultGlobalJson(): JSONObject = JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            put(
                "dns",
                JSONObject().apply {
                    put("queryStrategy", "UseIP")
                    put("servers", JSONArray().put("https://1.1.1.1/dns-query").put("223.5.5.5"))
                }
            )
            put("inbounds", defaultInbounds())
            put(
                "outbounds",
                JSONArray()
                    .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
                    .put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
            )
            put(
                "routing",
                JSONObject().apply {
                    put("domainStrategy", "IPIfNonMatch")
                    put(
                        "rules",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "field")
                                .put("ip", JSONArray().put("geoip:private").put("geoip:cn"))
                                .put("outboundTag", "direct")
                        )
                    )
                }
            )
        }

        private fun defaultInbounds(): JSONArray = JSONArray()
            .put(
                JSONObject().apply {
                    put("tag", "socks-in")
                    put("port", 10808)
                    put("listen", "127.0.0.1")
                    put("protocol", "socks")
                    put("settings", JSONObject().put("auth", "noauth").put("udp", true))
                    put(
                        "sniffing",
                        JSONObject()
                            .put("enabled", true)
                            .put("destOverride", JSONArray().put("http").put("tls"))
                            .put("routeOnly", false)
                    )
                }
            )
            .put(
                JSONObject().apply {
                    put("tag", "http-in")
                    put("port", 10809)
                    put("listen", "127.0.0.1")
                    put("protocol", "http")
                }
            )

        private fun cloneJsonValue(value: Any): Any = when (value) {
            is JSONObject -> JSONObject(value.toString())
            is JSONArray -> JSONArray(value.toString())
            else -> value
        }
    }
}
