package com.simplexray.re.service

import com.simplexray.re.common.config.GroupProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Tags shared by config generation, health checks and the routing RPC for one core process. */
internal class OutboundGroupConfig(
    val nodes: List<String>,
    val profile: GroupProfile,
    val healthPort: Int,
    private val allocatePort: () -> Int,
) {
    private val prefix = "sx-${UUID.randomUUID()}-"
    val reject = "${prefix}unavailable"
    val password = UUID.randomUUID().toString()
    val users = nodes.mapIndexed { index, tag -> tag to "node-$index" }.toMap()
    val balancers = profile.groups.mapIndexed { index, group -> group.tag to "${prefix}group-$index" }.toMap()
    private val healthInbound = "${prefix}health"
    private var scriptFile: File? = null

    fun inject(root: JSONObject, filesDir: File, cacheDir: File) {
        val outbounds = root.getJSONArray("outbounds")
        val firstTag = outbounds.getJSONObject(0).optString("tag")
        val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
        // Business route and failure events are emitted by Xray at info severity.
        if (log.optString("loglevel") != "debug") log.put("loglevel", "info")
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(i)
            profile.targetStrategies[outbound.optString("tag")]?.let { outbound.put("targetStrategy", it) }
        }
        outbounds.put(JSONObject().put("tag", reject).put("protocol", "blackhole"))
        val builtinTargets = profile.groups.flatMap { listOf(it.primary) + it.fallbacks }
            .filter { it.isGroup == false && it.tag in setOf("direct", "block") }
            .map { it.tag }.toSet()
        for (tag in builtinTargets) {
            if ((0 until outbounds.length()).all { outbounds.getJSONObject(it).optString("tag") != tag }) {
                outbounds.put(JSONObject().put("tag", tag)
                    .put("protocol", if (tag == "direct") "freedom" else "blackhole"))
            }
        }
        root.getJSONObject("api").getJSONArray("services").put("RoutingService")
        val inbounds = root.array("inbounds")
        inbounds.put(listener(healthInbound, healthPort, "http", JSONObject().put("accounts",
            JSONArray(users.values.map { JSONObject().put("user", it).put("pass", password) })
        )))
        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val entries = routing.array("balancers")
        for (tag in balancers.values) {
            entries.put(JSONObject().put("tag", tag).put("selector", JSONArray().put(reject)))
        }

        val script = routing.optString("script")
        if (script.isNotBlank()) {
            val source = File(script).let { if (it.isAbsolute) it else File(filesDir, script) }.readText()
            val wrapper = File.createTempFile("group-routes-", ".lua", cacheDir)
            scriptFile = wrapper
            wrapper.writeText(wrapScript(source, firstTag))
            routing.put("script", wrapper.absolutePath)
        } else {
            val rules = JSONArray()
            users.forEach { (tag, user) ->
                rules.put(JSONObject().put("inboundTag", JSONArray().put(healthInbound))
                    .put("user", JSONArray().put(user)).put("outboundTag", tag)
                    .put("ruleTag", "$healthInbound/$user"))
            }
            val existing = routing.array("rules")
            for (i in 0 until existing.length()) {
                val rule = existing.getJSONObject(i)
                balancers[rule.optString("outboundTag")]?.let {
                    rule.remove("outboundTag")
                    rule.put("balancerTag", it)
                    rule.put("ruleTag", "$it/rule-$i/${rule.optString("ruleTag")}")
                }
                rules.put(rule)
            }
            balancers[firstTag]?.let { defaultGroup ->
                if (routing.optString("domainStrategy").equals("IPIfNonMatch", ignoreCase = true)) {
                    // A catch-all would intercept the first pass before Xray retries its IP rules.
                    // Re-enter through a local authenticated inbound only after both passes finish.
                    val id = UUID.randomUUID().toString()
                    val defaultTag = "${prefix}default"
                    val port = allocatePort()
                    inbounds.put(listener(defaultTag, port, "vless", JSONObject().put("decryption", "none")
                        .put("clients", JSONArray().put(JSONObject().put("id", id)))))
                    val entry = JSONObject().put("protocol", "vless").put("tag", defaultTag)
                        .put("settings", JSONObject().put("address", "127.0.0.1").put("port", port)
                            .put("id", id).put("encryption", "none"))
                    root.put("outbounds", JSONArray().put(entry).apply {
                        for (i in 0 until outbounds.length()) put(outbounds.get(i))
                    })
                    routing.put("rules", JSONArray().put(JSONObject()
                        .put("inboundTag", JSONArray().put(defaultTag)).put("balancerTag", defaultGroup)
                        .put("ruleTag", "$defaultGroup/default")).apply {
                        for (i in 0 until rules.length()) put(rules.get(i))
                    })
                    return
                }
                rules.put(JSONObject().put("network", "tcp,udp").put("balancerTag", defaultGroup)
                    .put("ruleTag", "$defaultGroup/default"))
            }
            routing.put("rules", rules)
        }
    }

    fun close() {
        scriptFile?.delete()
    }

    private fun wrapScript(source: String, firstTag: String): String = buildString {
        append("do\n").append(source).append("\nend\n")
        appendLine("local original = HandleRoute")
        appendLine("local pick = require('xray.router').PickOutbound")
        appendLine("local groups = ${luaTable(balancers)}")
        appendLine("local health = ${luaTable(users.entries.associate { (node, user) -> user to node })}")
        appendLine("function HandleRoute(ctx, inbound, sp, tp, lp, domain, net, proto, user, ...)")
        appendLine("  if inbound == ${luaString(healthInbound)} then return health[user], 'group-health', nil end")
        appendLine("  local tag, rule, err = original(ctx, inbound, sp, tp, lp, domain, net, proto, user, ...)")
        appendLine("  if err then return tag, rule, err end")
        appendLine("  local group = groups[(tag and tag ~= '' and tag) or ${luaString(firstTag)}]")
        appendLine("  if group then")
        appendLine("    local leaf, err = pick(group)")
        appendLine("    return leaf, group .. '/' .. (rule or 'default'), err")
        appendLine("  end")
        appendLine("  return tag, rule, nil")
        appendLine("end")
    }

    private fun luaTable(values: Map<String, String>) = values.entries.joinToString(",", "{", "}") {
        "[${luaString(it.key)}]=${luaString(it.value)}"
    }

    private fun luaString(value: String) = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"', '\\' -> { append('\\'); append(c) }
                in '\u0000'..'\u001f' -> append("\\${c.code.toString().padStart(3, '0')}")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun listener(tag: String, port: Int, protocol: String, settings: JSONObject) =
        JSONObject().put("tag", tag).put("listen", "127.0.0.1").put("port", port)
            .put("protocol", protocol).put("settings", settings)

    private fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray().also { put(key, it) }
}
