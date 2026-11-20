package com.simplexray.re.common.config

import android.net.InetAddresses
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal object DnsBootstrap {
    fun inject(root: JSONObject, bootstrapDns: String = DEFAULT_DOH_BOOTSTRAP_DNS) {
        val dns = root.optJSONObject("dns") ?: return
        if (dns.optString("script").isNotBlank()) return
        val configured = dns.optJSONArray("servers") ?: return
        val servers = (0 until configured.length()).map { configured.get(it) }
        val hosts = dns.optJSONObject("hosts")
        val domains = servers.mapNotNull { server ->
            val uri = Uri.parse(address(server))
            if (uri.scheme.equals("https", ignoreCase = true)) uri.host?.removeSurrounding("[", "]") else null
        }.distinct().filter { host ->
            InetAddresses.isNumericAddress(host) == false &&
                hosts?.has(host) != true && hosts?.has("full:$host") != true &&
                servers.none { server ->
                    val entry = server as? JSONObject
                    val matches = entry?.optJSONArray("domains")
                    entry?.optBoolean("finalQuery") == true &&
                        Uri.parse(address(server)).host.equals(host, ignoreCase = true) == false &&
                        (0 until (matches?.length() ?: 0)).any { matches?.optString(it) == "full:$host" }
                }
        }
        if (domains.isEmpty()) return

        val ip = bootstrapDns.removeSurrounding("[", "]")
        val host = if (ip.contains(':')) "[$ip]" else ip
        val bootstrap = JSONObject()
            .put("address", "tcp+local://$host:53")
            .put("domains", JSONArray(domains.map { "full:$it" }))
            .put("skipFallback", true)
            .put("finalQuery", true)
        val effective = JSONArray().put(bootstrap)
        servers.forEach { effective.put(it) }
        dns.put("servers", effective)
    }

    private fun address(server: Any): String =
        if (server is JSONObject) server.optString("address") else server.toString()
}
