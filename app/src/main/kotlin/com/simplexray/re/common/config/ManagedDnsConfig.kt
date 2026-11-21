package com.simplexray.re.common.config

import org.json.JSONArray
import org.json.JSONObject

/** Connects application DNS traffic to the configured Xray resolver. */
internal class ManagedDnsConfig(private val prefix: String) {
    val outboundTag = "${prefix}dns"
    private var inboundTags: List<String> = emptyList()

    fun inject(root: JSONObject) {
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray()
        inboundTags = (0 until inbounds.length()).mapNotNull { index ->
            val inbound = inbounds.getJSONObject(index)
            if (inbound.optString("protocol").lowercase() in setOf("tun", "socks", "http")) {
                inbound.optString("tag").ifBlank {
                    "${prefix}client-$index".also { inbound.put("tag", it) }
                }
            } else null
        }
        root.getJSONArray("outbounds").put(
            JSONObject().put("tag", outboundTag).put("protocol", "dns")
                .put("settings", JSONObject()),
        )
    }

    fun routingRule(): JSONObject? {
        if (inboundTags.isEmpty()) return null
        return JSONObject().put("type", "field")
            .put("inboundTag", JSONArray(inboundTags))
            .put("network", "tcp,udp")
            .put("port", "53")
            .put("outboundTag", outboundTag)
            .put("ruleTag", "managed-dns")
    }

    companion object {
        const val IPV4_ADDRESS = "198.18.0.2"
        const val IPV6_ADDRESS = "fc00::2"

        fun configureResolver(root: JSONObject) {
            val dns = root.optJSONObject("dns") ?: return
            if (dns.optString("script").isBlank()) dns.put("enableParallelQuery", true)
        }
    }
}
