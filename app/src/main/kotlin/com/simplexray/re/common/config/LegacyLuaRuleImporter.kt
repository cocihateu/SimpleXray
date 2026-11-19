package com.simplexray.re.common.config

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object LegacyLuaRuleImporter {
    data class ImportResult(val rules: List<TrafficRule>, val migrationNotice: String? = null)
    private data class MatcherDef(val isDomain: Boolean, val items: List<String>)
    private val quoted = Regex("""["']([^"']+)["']""")
    private val returns = Regex("""return\s+["']([^"']+)["'](?:\s*,\s*["']([^"']+)["'])?""")
    private val calls = Regex("""([a-zA-Z0-9_]+)\s*:\s*(?:MatchAny|AnyMatch|Match|match)\b""")

    fun importFromLua(luaContent: String): ImportResult {
        val source = luaContent.lineSequence().map { it.substringBefore("--") }.joinToString("\n")
        val definitions = Regex("""(?:local\s+)?([a-zA-Z0-9_]+)\s*=\s*geodata\.(BuildDomainMatcher|BuildIPMatcher)\s*\(([\s\S]*?)\)""")
        val matchers = definitions.findAll(source).associate { match ->
            match.groupValues[1] to MatcherDef(
                match.groupValues[2] == "BuildDomainMatcher",
                quoted.findAll(match.groupValues[3]).map { it.groupValues[1] }.toList(),
            )
        }
        val function = Regex("""function\s+HandleRoute\s*\([^)]*\)""").find(source)
            ?: return ImportResult(defaultFallbackRules(), "Review migrated rules: HandleRoute entry is absent.")
        val conditions = mutableListOf<String>()
        val rules = mutableListOf<TrafficRule>()
        var skipped = 0
        for (raw in source.substring(function.range.last + 1).lines()) {
            val line = raw.trim()
            when {
                line.startsWith("elseif ") -> {
                    if (conditions.isNotEmpty()) conditions.removeAt(conditions.lastIndex)
                    conditions += line.removePrefix("elseif ").substringBefore(" then")
                }
                line.startsWith("if ") -> conditions += line.removePrefix("if ").substringBefore(" then")
                line == "else" -> {
                    if (conditions.isNotEmpty()) conditions[conditions.lastIndex] = "else"
                }
                line == "end" -> {
                    if (conditions.isEmpty()) break
                    conditions.removeAt(conditions.lastIndex)
                    continue
                }
            }
            val returned = returns.find(line) ?: continue
            val target = returned.groupValues[1]
            val id = returned.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            if (conditions.isEmpty()) {
                rules += TrafficRule(id, TrafficRule.TYPE_MATCH, "", target)
                continue
            }
            val condition = conditions.joinToString(" and ")
            val matching = calls.findAll(condition).mapNotNull { matchers[it.groupValues[1]] }.toList()
            val field = JSONObject()
            val inbound = Regex("""inboundTag\s*==\s*["']([^"']+)["']""").find(condition)?.groupValues?.get(1)
            if (inbound != null) field.put("inboundTag", JSONArray().put(inbound))
            val port = Regex("""targetPort\s*==\s*(\d+)""").find(condition)?.groupValues?.get(1)
            if (port != null) field.put("port", port)
            val network = when {
                condition.contains("NetworkUDP") -> "udp"
                condition.contains("NetworkTCP") -> "tcp"
                else -> null
            }
            if (network != null) field.put("network", network)
            matching.forEach { matcher ->
                field.put(if (matcher.isDomain) "domain" else "ip", JSONArray(matcher.items.map { item ->
                    if (matcher.isDomain && ':' !in item) "domain:$item" else item
                }))
            }
            if (field.length() == 0 || conditions.any { it == "else" }) {
                skipped++
                continue
            }
            field.put("ruleTag", id).put("outboundTag", target)
            rules += TrafficRule.fromXrayRule(field)
        }
        val ordered = rules.distinctBy { Triple(it.type, it.payload, it.target) }
        val result = if (ordered.isEmpty()) defaultFallbackRules()
        else if (ordered.any { it.type == TrafficRule.TYPE_MATCH }) ordered
        else ordered + TrafficRule(type = TrafficRule.TYPE_MATCH, payload = "", target = TrafficRule.TARGET_GLOBAL)
        val notice = if (skipped > 0) "Review $skipped Lua branches with custom conditions in the rules page." else null
        return ImportResult(result, notice)
    }

    fun defaultFallbackRules(): List<TrafficRule> = listOf(
        TrafficRule(type = TrafficRule.TYPE_GEO_IP, payload = "private", target = TrafficRule.TARGET_DIRECT),
        TrafficRule(type = TrafficRule.TYPE_GEO_IP, payload = "cn", target = TrafficRule.TARGET_DIRECT),
        TrafficRule(type = TrafficRule.TYPE_MATCH, payload = "", target = TrafficRule.TARGET_GLOBAL),
    )
}
