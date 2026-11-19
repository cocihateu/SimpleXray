package com.simplexray.re.service

import com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Tags shared by config generation, health checks and the routing RPC for one core process. */
internal class OutboundGroupConfig(
    val workspace: GroupWorkspace,
    val nodeEntryPort: Int,
    val healthPort: Int,
    private val allocatePort: () -> Int,
) {
    private val prefix = "sx-${UUID.randomUUID()}-"
    val password = UUID.randomUUID().toString()
    val physicalNodes: List<String> = workspace.allNodes().map { it.tag }
    val allLeafTargets: List<String> = (physicalNodes + BUILTIN_GROUP_TARGET_TAGS).distinct()
    val users: Map<String, String> = allLeafTargets.mapIndexed { index, tag -> tag to "node-$index" }.toMap()

    val fallbackRelaySpecs: List<FallbackRelaySpec> = workspace.groups.mapIndexedNotNull { index, group ->
        if (group.strategy == GroupStrategy.FALLBACK && group.effectiveMembers().size >= 2) {
            FallbackRelaySpec(
                groupTag = group.tag,
                port = allocatePort(),
                username = "fb-$index",
                password = UUID.randomUUID().toString(),
            )
        } else {
            null
        }
    }
    val fallbackRelayOutbounds: Map<String, String> = fallbackRelaySpecs.mapIndexed { index, spec ->
        spec.groupTag to "${prefix}fb-$index"
    }.toMap()

    val balancers: Map<String, String> = workspace.groups
        .filter { it.tag !in fallbackRelayOutbounds }
        .mapIndexed { index, group -> group.tag to "${prefix}group-$index" }
        .toMap()

    private val nodeEntryInbound = "${prefix}node-entry"
    private val healthInbound = "${prefix}health"
    private var scriptFile: File? = null

    fun inject(root: JSONObject, filesDir: File, cacheDir: File) {
        val outbounds = root.array("outbounds")
        val defaultGroupTag = workspace.effectiveDefaultGroupTag()
            ?: outbounds.optJSONObject(0)?.optString("tag").orEmpty()

        for (builtinTag in BUILTIN_GROUP_TARGET_TAGS) {
            if ((0 until outbounds.length()).all { outbounds.getJSONObject(it).optString("tag") != builtinTag }) {
                outbounds.put(
                    JSONObject()
                        .put("tag", builtinTag)
                        .put("protocol", if (builtinTag == "direct") "freedom" else "blackhole")
                )
            }
        }

        for (spec in fallbackRelaySpecs) {
            val outboundTag = fallbackRelayOutbounds.getValue(spec.groupTag)
            outbounds.put(
                JSONObject()
                    .put("tag", outboundTag)
                    .put("protocol", "socks")
                    .put("targetStrategy", "AsIs")
                    .put(
                        "settings",
                        JSONObject().put(
                            "servers",
                            JSONArray().put(
                                JSONObject()
                                    .put("address", "127.0.0.1")
                                    .put("port", spec.port)
                                    .put(
                                        "users",
                                        JSONArray().put(
                                            JSONObject()
                                                .put("user", spec.username)
                                                .put("pass", spec.password)
                                        )
                                    )
                            )
                        )
                    )
            )
        }

        root.getJSONObject("api").getJSONArray("services").put("RoutingService")
        val inbounds = root.array("inbounds")
        val accountsArray = JSONArray(
            users.values.map { JSONObject().put("user", it).put("pass", password) }
        )
        inbounds.put(
            listener(
                nodeEntryInbound,
                nodeEntryPort,
                "socks",
                JSONObject()
                    .put("auth", "password")
                    .put("accounts", accountsArray)
                    .put("udp", true)
            )
        )
        inbounds.put(
            listener(
                healthInbound,
                healthPort,
                "http",
                JSONObject().put("accounts", JSONArray(accountsArray.toString()))
            )
        )

        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val entries = routing.array("balancers")
        for ((groupTag, balancerTag) in balancers) {
            val initialTarget = initialTargetForGroup(groupTag)
            entries.put(
                JSONObject()
                    .put("tag", balancerTag)
                    .put("selector", JSONArray().put(initialTarget))
            )
        }

        val script = routing.optString("script")
        if (script.isNotBlank()) {
            val source = File(script).let { if (it.isAbsolute) it else File(filesDir, script) }.readText()
            val wrapper = File.createTempFile("group-routes-", ".lua", cacheDir)
            scriptFile = wrapper
            wrapper.writeText(wrapScript(source, defaultGroupTag))
            routing.put("script", wrapper.absolutePath)
        } else {
            val rules = JSONArray()
            users.forEach { (tag, user) ->
                rules.put(
                    JSONObject()
                        .put("inboundTag", JSONArray().put(nodeEntryInbound).put(healthInbound))
                        .put("user", JSONArray().put(user))
                        .put("outboundTag", tag)
                        .put("ruleTag", "sx-internal/$user")
                )
            }
            val existing = routing.array("rules")
            for (i in 0 until existing.length()) {
                val rule = existing.getJSONObject(i)
                val targetTag = rule.optString("outboundTag")
                fallbackRelayOutbounds[targetTag]?.let { relayOutbound ->
                    rule.put("outboundTag", relayOutbound)
                } ?: balancers[targetTag]?.let { balancerTag ->
                    rule.remove("outboundTag")
                    rule.put("balancerTag", balancerTag)
                    rule.put("ruleTag", "$balancerTag/rule-$i/${rule.optString("ruleTag")}")
                }
                rules.put(rule)
            }
            val defaultRelay = fallbackRelayOutbounds[defaultGroupTag]
            val defaultBalancer = balancers[defaultGroupTag]
            if (defaultRelay != null || defaultBalancer != null) {
                appendDefaultRule(root, routing, inbounds, outbounds, rules, defaultRelay, defaultBalancer)
                return
            }
            routing.put("rules", rules)
        }
    }

    fun close() {
        scriptFile?.delete()
    }

    private fun appendDefaultRule(
        root: JSONObject,
        routing: JSONObject,
        inbounds: JSONArray,
        outbounds: JSONArray,
        rules: JSONArray,
        defaultRelay: String?,
        defaultBalancer: String?,
    ) {
        if (routing.optString("domainStrategy").equals("IPIfNonMatch", ignoreCase = true)) {
            val id = UUID.randomUUID().toString()
            val defaultTag = "${prefix}default"
            val port = allocatePort()
            inbounds.put(
                listener(
                    defaultTag,
                    port,
                    "vless",
                    JSONObject()
                        .put("decryption", "none")
                        .put("clients", JSONArray().put(JSONObject().put("id", id)))
                )
            )
            val entry = JSONObject()
                .put("protocol", "vless")
                .put("tag", defaultTag)
                .put(
                    "settings",
                    JSONObject()
                        .put("address", "127.0.0.1")
                        .put("port", port)
                        .put("id", id)
                        .put("encryption", "none")
                )
            root.put("outbounds", JSONArray().put(entry).apply {
                for (i in 0 until outbounds.length()) put(outbounds.get(i))
            })
            val catchRule = JSONObject().put("inboundTag", JSONArray().put(defaultTag))
            if (defaultRelay != null) {
                catchRule.put("outboundTag", defaultRelay).put("ruleTag", "$defaultRelay/default")
            } else {
                catchRule.put("balancerTag", defaultBalancer).put("ruleTag", "$defaultBalancer/default")
            }
            routing.put("rules", JSONArray().put(catchRule).apply {
                for (i in 0 until rules.length()) put(rules.get(i))
            })
            return
        }
        val catchRule = JSONObject().put("network", "tcp,udp")
        if (defaultRelay != null) {
            catchRule.put("outboundTag", defaultRelay).put("ruleTag", "$defaultRelay/default")
        } else {
            catchRule.put("balancerTag", defaultBalancer).put("ruleTag", "$defaultBalancer/default")
        }
        rules.put(catchRule)
        routing.put("rules", rules)
    }

    private fun initialTargetForGroup(groupTag: String, visited: MutableSet<String> = mutableSetOf()): String {
        if (visited.add(groupTag) == false) return physicalNodes.firstOrNull() ?: "direct"
        fallbackRelayOutbounds[groupTag]?.let { return it }
        val group = workspace.groupByTag(groupTag) ?: return physicalNodes.firstOrNull() ?: "direct"
        val members = group.effectiveMembers()
        val preferred = if (group.strategy == GroupStrategy.SELECT) {
            group.selectedMember?.takeIf { it in members } ?: members.firstOrNull()
        } else {
            members.firstOrNull()
        } ?: return physicalNodes.firstOrNull() ?: "direct"
        return if (preferred.isGroup) initialTargetForGroup(preferred.tag, visited) else preferred.tag
    }

    private fun wrapScript(source: String, defaultGroupTag: String): String = buildString {
        append("do\n").append(source).append("\nend\n")
        appendLine("local original = HandleRoute")
        appendLine("local pick = require('xray.router').PickOutbound")
        appendLine("local relays = ${luaTable(fallbackRelayOutbounds)}")
        appendLine("local balancers = ${luaTable(balancers)}")
        appendLine("local internal = ${luaTable(users.entries.associate { (node, user) -> user to node })}")
        appendLine("function HandleRoute(ctx, inbound, sp, tp, lp, domain, net, proto, user, ...)")
        appendLine("  if inbound == ${luaString(nodeEntryInbound)} or inbound == ${luaString(healthInbound)} then return internal[user], 'sx-internal', nil end")
        appendLine("  local tag, rule, err = original(ctx, inbound, sp, tp, lp, domain, net, proto, user, ...)")
        appendLine("  if err then return tag, rule, err end")
        appendLine("  local effective = (tag and tag ~= '' and tag) or ${luaString(defaultGroupTag)}")
        appendLine("  local relay = relays[effective]")
        appendLine("  if relay then return relay, effective .. '/' .. (rule or 'default'), nil end")
        appendLine("  local bal = balancers[effective]")
        appendLine("  if bal then")
        appendLine("    local leaf, perr = pick(bal)")
        appendLine("    return leaf, effective .. '/' .. (rule or 'default'), perr")
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
