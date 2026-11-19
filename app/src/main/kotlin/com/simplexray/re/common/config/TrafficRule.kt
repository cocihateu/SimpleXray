package com.simplexray.re.common.config

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

public data class TrafficRule(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val payload: String,
    val target: String,
    val enabled: Boolean = true
)
{
    fun toXrayRule(outboundTag: String = target): JSONObject {
        val effectiveOutbound = if (outboundTag.isBlank() || outboundTag == TARGET_GLOBAL) {
            if (target == TARGET_GLOBAL) outboundTag else target
        } else {
            outboundTag
        }

        val ruleObj = if (type.equals(TYPE_XRAY, ignoreCase = true)) {
            runCatching { JSONObject(payload) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }

        ruleObj.put("type", "field")
        if (id.isNotBlank()) {
            ruleObj.put("ruleTag", id)
        }
        ruleObj.put("outboundTag", effectiveOutbound)

        val items = payload.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

        when (type) {
            TYPE_DOMAIN -> {
                val list = items.map { if (it.startsWith("full:")) it else "full:$it" }
                ruleObj.put("domain", JSONArray(list))
            }
            TYPE_DOMAIN_SUFFIX -> {
                val list = items.map { if (it.startsWith("domain:")) it else "domain:$it" }
                ruleObj.put("domain", JSONArray(list))
            }
            TYPE_DOMAIN_KEYWORD -> {
                val list = items.map { if (it.startsWith("keyword:")) it else "keyword:$it" }
                ruleObj.put("domain", JSONArray(list))
            }
            TYPE_GEO_SITE -> {
                val list = items.map { if (it.startsWith("geosite:")) it else "geosite:$it" }
                ruleObj.put("domain", JSONArray(list))
            }
            TYPE_IP_CIDR -> {
                ruleObj.put("ip", JSONArray(items))
            }
            TYPE_GEO_IP -> {
                val list = items.map { if (it.startsWith("geoip:")) it else "geoip:$it" }
                ruleObj.put("ip", JSONArray(list))
            }
            TYPE_DST_PORT -> {
                ruleObj.put("port", payload.trim())
            }
            TYPE_NETWORK -> {
                ruleObj.put("network", payload.trim().lowercase())
            }
            TYPE_MATCH -> {
                ruleObj.put("network", "tcp,udp")
            }
            TYPE_XRAY -> {
                // Retain raw matching object payload
            }
        }
        return ruleObj
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("payload", payload)
        put("target", target)
        put("enabled", enabled)
    }

    companion object {
        const val TYPE_DOMAIN = "Domain"
        const val TYPE_DOMAIN_SUFFIX = "DomainSuffix"
        const val TYPE_DOMAIN_KEYWORD = "DomainKeyword"
        const val TYPE_IP_CIDR = "IPCIDR"
        const val TYPE_GEO_IP = "GeoIP"
        const val TYPE_GEO_SITE = "GeoSite"
        const val TYPE_DST_PORT = "DstPort"
        const val TYPE_NETWORK = "Network"
        const val TYPE_MATCH = "Match"
        const val TYPE_XRAY = "Xray"

        const val TARGET_DIRECT = "direct"
        const val TARGET_BLOCK = "block"
        const val TARGET_GLOBAL = "GLOBAL"

        fun fromJson(obj: JSONObject): TrafficRule {
            return TrafficRule(
                id = obj.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                type = obj.optString("type", TYPE_MATCH),
                payload = obj.optString("payload", ""),
                target = obj.optString("target", TARGET_GLOBAL),
                enabled = obj.optBoolean("enabled", true)
            )
        }

        fun fromXrayRule(ruleObj: JSONObject): List<TrafficRule> {
            val id = ruleObj.optString("ruleTag").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val target = ruleObj.optString("outboundTag").ifBlank { TARGET_GLOBAL }
            val enabled = ruleObj.optBoolean("enabled", true)

            val metadata = setOf("type", "ruleTag", "outboundTag", "balancerTag", "enabled")
            val conditionKeys = ruleObj.keys().asSequence().filter { it !in metadata }.toList()

            if (conditionKeys.size > 1) {
                val payloadObj = JSONObject()
                for (key in conditionKeys) {
                    payloadObj.put(key, ruleObj.get(key))
                }
                return listOf(TrafficRule(id = id, type = TYPE_XRAY, payload = payloadObj.toString(), target = target, enabled = enabled))
            }

            if (conditionKeys.isEmpty()) {
                return listOf(TrafficRule(id = id, type = TYPE_MATCH, payload = "", target = target, enabled = enabled))
            }

            return when (conditionKeys.first()) {
                "domain" -> {
                    val domains = ruleObj.optJSONArray("domain") ?: JSONArray()
                    if ((0 until domains.length()).any { domains.optString(it).startsWith("regexp:") }) {
                        return listOf(TrafficRule(id, TYPE_XRAY,
                            JSONObject().put("domain", domains).toString(), target, enabled))
                    }
                    val result = mutableListOf<TrafficRule>()
                    for (i in 0 until domains.length()) {
                        val d = domains.optString(i).trim()
                        if (d.isEmpty()) continue
                        val rowId = if (i == 0) id else UUID.randomUUID().toString()
                        when {
                            d.startsWith("geosite:") -> result.add(TrafficRule(id = rowId, type = TYPE_GEO_SITE, payload = d.removePrefix("geosite:"), target = target, enabled = enabled))
                            d.startsWith("domain:") -> result.add(TrafficRule(id = rowId, type = TYPE_DOMAIN_SUFFIX, payload = d.removePrefix("domain:"), target = target, enabled = enabled))
                            d.startsWith("full:") -> result.add(TrafficRule(id = rowId, type = TYPE_DOMAIN, payload = d.removePrefix("full:"), target = target, enabled = enabled))
                            d.startsWith("keyword:") -> result.add(TrafficRule(id = rowId, type = TYPE_DOMAIN_KEYWORD, payload = d.removePrefix("keyword:"), target = target, enabled = enabled))
                            else -> result.add(TrafficRule(id = rowId, type = TYPE_DOMAIN_SUFFIX, payload = d, target = target, enabled = enabled))
                        }
                    }
                    if (result.isEmpty()) listOf(TrafficRule(id = id, type = TYPE_MATCH, payload = "", target = target, enabled = enabled)) else result
                }
                "ip" -> {
                    val ips = ruleObj.optJSONArray("ip") ?: JSONArray()
                    val result = mutableListOf<TrafficRule>()
                    for (i in 0 until ips.length()) {
                        val ipStr = ips.optString(i).trim()
                        if (ipStr.isEmpty()) continue
                        val rowId = if (i == 0) id else UUID.randomUUID().toString()
                        if (ipStr.startsWith("geoip:")) {
                            result.add(TrafficRule(id = rowId, type = TYPE_GEO_IP, payload = ipStr.removePrefix("geoip:"), target = target, enabled = enabled))
                        } else {
                            result.add(TrafficRule(id = rowId, type = TYPE_IP_CIDR, payload = ipStr, target = target, enabled = enabled))
                        }
                    }
                    if (result.isEmpty()) listOf(TrafficRule(id = id, type = TYPE_MATCH, payload = "", target = target, enabled = enabled)) else result
                }
                "port" -> {
                    val p = ruleObj.optString("port").trim()
                    listOf(TrafficRule(id = id, type = TYPE_DST_PORT, payload = p, target = target, enabled = enabled))
                }
                "network" -> {
                    val net = ruleObj.optString("network").trim()
                    if (net.contains("tcp") && net.contains("udp")) {
                        listOf(TrafficRule(id = id, type = TYPE_MATCH, payload = "", target = target, enabled = enabled))
                    } else {
                        listOf(TrafficRule(id = id, type = TYPE_NETWORK, payload = net, target = target, enabled = enabled))
                    }
                }
                else -> {
                    val payloadObj = JSONObject()
                    payloadObj.put(conditionKeys.first(), ruleObj.get(conditionKeys.first()))
                    listOf(TrafficRule(id = id, type = TYPE_XRAY, payload = payloadObj.toString(), target = target, enabled = enabled))
                }
            }
        }
    }
}
