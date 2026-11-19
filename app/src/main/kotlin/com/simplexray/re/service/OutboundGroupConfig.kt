package com.simplexray.re.service

import com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.TrafficRule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Business routes enter the shared connection model; internal measurements select physical nodes. */
internal class OutboundGroupConfig(
    val workspace: GroupWorkspace,
    val nodeEntryPort: Int,
    val healthPort: Int,
    private val allocatePort: () -> Int,
) {
    private val prefix = "sx-${UUID.randomUUID()}-"
    val password = UUID.randomUUID().toString()
    val physicalNodes = workspace.allNodes().map { it.tag }
    val allLeafTargets = (physicalNodes + BUILTIN_GROUP_TARGET_TAGS).distinct()
    val routingBindings = workspace.effectiveRoutingBindings()
    val users = allLeafTargets.mapIndexed { index, tag -> tag to "node-$index" }.toMap()
    val exitInfoUser = "exit-info"
    private val targets = workspace.groups.map { it.tag } + allLeafTargets.map(::physicalTarget)
    val businessRelayPort = allocatePort()
    val fallbackRelaySpecs = targets.mapIndexed { index, target ->
        FallbackRelaySpec(target, businessRelayPort, "business-$index", UUID.randomUUID().toString())
    }
    val fallbackRelayOutbounds = fallbackRelaySpecs.mapIndexed { index, spec ->
        spec.groupTag to "${prefix}business-$index"
    }.toMap()
    val balancers: Map<String, String> = emptyMap()
    private val nodeEntryInbound = "${prefix}node-entry"
    private val healthInbound = "${prefix}health"
    val obfsPort = if (workspace.allNodes().any { it.obfsMode.isNotEmpty() }) allocatePort() else 0
    val obfsSpecs = workspace.allNodes().filter { it.obfsMode.isNotEmpty() }.mapIndexed { index, node ->
        val front = workspace.groups.firstOrNull { it.nodes.any { member -> member.tag == node.tag } }?.frontProxy
        val frontTarget = front?.let { if (it.isGroup) it.tag else physicalTarget(it.tag) }
        SimpleObfsSpec(node.tag, "obfs-$index", UUID.randomUUID().toString(), node.obfsMode, node.obfsHost,
            fallbackRelaySpecs.firstOrNull { it.groupTag == frontTarget }?.let {
                it.copy(username = "${it.username}/dialer")
            })
    }
    private val obfsOutbounds = obfsSpecs.associate { it.nodeTag to "${prefix}${it.username}" }
    private val nativeUdpOutbounds = obfsSpecs.associate { it.nodeTag to "${prefix}native-udp-${it.username}" }
    private val uotOutbounds = workspace.allNodes().filter { it.udpOverTcpVersion > 0 }
        .mapIndexed { index, node -> node.tag to "${prefix}uot-$index" }.toMap()
    private var domainStrategy = "IPIfNonMatch"
    private var routingOptions = JSONObject()
    private val logTargetPattern = Regex("${Regex.escape(prefix)}(?:rule-.*?)?business-(\\d+)")

    fun formatLogLine(line: String): String = logTargetPattern.replace(line) { match ->
        val target = fallbackRelaySpecs.getOrNull(match.groupValues[1].toInt())?.groupTag
            ?.removePrefix(PHYSICAL_PREFIX) ?: return@replace match.value
        when (target) { "direct" -> "DIRECT"; "block" -> "REJECT"; else -> target }
    }.replace(nodeEntryInbound, "node-entry").replace(healthInbound, "health-check")

    fun inject(root: JSONObject, filesDir: File, cacheDir: File, rules: List<TrafficRule>) {
        val outbounds = root.array("outbounds")
        val chainOutbounds = mutableListOf<JSONObject>()
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(index)
            val nodeTag = outbound.optString("tag")
            val owner = workspace.groups.firstOrNull { group -> group.nodes.any { it.tag == nodeTag } }
            val front = owner?.frontProxy ?: continue
            val target = if (front.isGroup) front.tag else physicalTarget(front.tag)
            val stream = outbound.optJSONObject("streamSettings") ?: JSONObject().also { outbound.put("streamSettings", it) }
            val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
            val spec = fallbackRelaySpecs.first { it.groupTag == target }
            val chainTag = "${prefix}chain-${chainOutbounds.size}"
            sockopt.put("dialerProxy", chainTag)
            chainOutbounds += relayOutbound(spec, chainTag, "${spec.username}/dialer")
        }
        chainOutbounds.forEach { outbounds.put(it) }
        obfsSpecs.forEach { spec ->
            val original = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
                .first { it.optString("tag") == spec.nodeTag }
            outbounds.put(JSONObject(original.toString()).put("tag", nativeUdpOutbounds.getValue(spec.nodeTag)))
            val stream = original.optJSONObject("streamSettings") ?: JSONObject().also { original.put("streamSettings", it) }
            val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
            sockopt.put("dialerProxy", obfsOutbounds.getValue(spec.nodeTag))
            outbounds.put(relayOutbound(FallbackRelaySpec(spec.nodeTag, obfsPort, spec.username, spec.password),
                obfsOutbounds.getValue(spec.nodeTag), spec.username))
        }
        uotOutbounds.forEach { (node, target) ->
            val original = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
                .first { it.optString("tag") == node }
            outbounds.put(JSONObject(original.toString()).put("tag", target).put("targetStrategy", "AsIs"))
        }
        for (builtin in BUILTIN_GROUP_TARGET_TAGS) {
            if ((0 until outbounds.length()).none { outbounds.getJSONObject(it).optString("tag") == builtin }) {
                outbounds.put(JSONObject().put("tag", builtin).put("protocol", if (builtin == "direct") "freedom" else "blackhole"))
            }
        }
        fallbackRelaySpecs.forEach { spec ->
            outbounds.put(relayOutbound(spec, fallbackRelayOutbounds.getValue(spec.groupTag), spec.username))
        }
        val services = root.getJSONObject("api").getJSONArray("services")
        services.put("RoutingService").put("HandlerService")
        val accounts = JSONArray(users.values.map { JSONObject().put("user", it).put("pass", password) })
        root.array("inbounds").put(listener(nodeEntryInbound, nodeEntryPort, "socks",
            JSONObject().put("auth", "password").put("accounts", accounts).put("udp", true)))
        root.array("inbounds").put(listener(healthInbound, healthPort, "http",
            JSONObject().put("accounts", JSONArray(accounts.toString())
                .put(JSONObject().put("user", exitInfoUser).put("pass", password)))))
        domainStrategy = root.optJSONObject("routing")?.optString("domainStrategy", "IPIfNonMatch") ?: "IPIfNonMatch"
        routingOptions = JSONObject(root.optJSONObject("routing")?.toString() ?: "{}").apply {
            remove("script")
            remove("rules")
            remove("balancers")
        }
        ruleOutbounds(workspace, rules).let { added ->
            for (i in 0 until added.length()) outbounds.put(added.get(i))
        }
        root.put("routing", routingConfig(workspace, rules))
    }

    fun ruleOutbounds(current: GroupWorkspace, rules: List<TrafficRule>): JSONArray = JSONArray().apply {
        rules.forEach { rule ->
            val spec = targetSpec(current, rule.target) ?: return@forEach
            put(relayOutbound(spec, ruleOutboundTag(rule.id, spec), "${spec.username}/${rule.id}"))
        }
    }

    fun routingConfig(current: GroupWorkspace, rules: List<TrafficRule>): JSONObject {
        val compiled = JSONArray()
        nativeUdpOutbounds.forEach { (node, target) ->
            compiled.put(JSONObject().put("type", "field")
                .put("inboundTag", JSONArray().put(nodeEntryInbound))
                .put("network", "udp").put("user", JSONArray().put(users.getValue(node)))
                .put("outboundTag", target).put("ruleTag", "sx-native-udp/${users.getValue(node)}"))
        }
        uotOutbounds.forEach { (node, target) ->
            compiled.put(JSONObject().put("type", "field")
                .put("inboundTag", JSONArray().put(nodeEntryInbound))
                .put("user", JSONArray().put(users.getValue(node)))
                .put("domain", JSONArray().put("full:sp.udp-over-tcp.arpa").put("full:sp.v2.udp-over-tcp.arpa"))
                .put("outboundTag", target).put("ruleTag", "sx-uot/${users.getValue(node)}"))
        }
        users.forEach { (target, user) ->
            compiled.put(JSONObject()
                .put("type", "field")
                .put("inboundTag", JSONArray().put(nodeEntryInbound).put(healthInbound))
                .put("user", JSONArray().put(user)).put("outboundTag", target)
                .put("ruleTag", "sx-internal/$user"))
        }
        targetSpec(current, if (current.routingMode == "direct") "direct" else "GLOBAL")?.let { spec ->
            compiled.put(JSONObject().put("type", "field")
                .put("inboundTag", JSONArray().put(healthInbound))
                .put("user", JSONArray().put(exitInfoUser))
                .put("outboundTag", fallbackRelayOutbounds.getValue(spec.groupTag))
                .put("ruleTag", "exit-info"))
        }
        if (current.routingMode == "rule") {
            rules.filter { it.enabled }.forEach { rule ->
                targetSpec(current, rule.target)?.let { spec ->
                    compiled.put(rule.toXrayRule(ruleOutboundTag(rule.id, spec)))
                }
            }
        }
        val defaultTarget = if (current.routingMode == "direct") "direct" else "GLOBAL"
        targetSpec(current, defaultTarget)?.let { spec ->
            compiled.put(JSONObject().put("type", "field").put("network", "tcp,udp")
                .put("outboundTag", fallbackRelayOutbounds.getValue(spec.groupTag)).put("ruleTag", "default"))
        }
        return JSONObject(routingOptions.toString()).put("domainStrategy", domainStrategy).put("rules", compiled)
    }

    private fun ruleOutboundTag(id: String, spec: FallbackRelaySpec): String = "${prefix}rule-$id-${spec.username}"

    private fun targetSpec(current: GroupWorkspace, requested: String): FallbackRelaySpec? {
        val target = when (requested) {
            "GLOBAL" -> current.effectiveDefaultGroupTag()
            "DIRECT" -> physicalTarget("direct")
            "REJECT" -> physicalTarget("block")
            "direct", "block" -> physicalTarget(requested)
            else -> current.effectiveRoutingBindings()[requested] ?: physicalTarget(requested)
        }
        return fallbackRelaySpecs.firstOrNull { it.groupTag == target }
    }

    private fun relayOutbound(spec: FallbackRelaySpec, tag: String, user: String) =
        JSONObject().put("tag", tag).put("protocol", "socks").put("targetStrategy", "AsIs")
            .put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", "127.0.0.1").put("port", spec.port)
                    .put("users", JSONArray().put(JSONObject().put("user", user).put("pass", spec.password))),
            )))

    private fun listener(tag: String, port: Int, protocol: String, settings: JSONObject) =
        JSONObject().put("tag", tag).put("listen", "127.0.0.1").put("port", port)
            .put("protocol", protocol).put("settings", settings)

    fun close() = Unit
    private fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray().also { put(key, it) }

    companion object {
        const val PHYSICAL_PREFIX = "@node:"
        fun physicalTarget(tag: String) = "$PHYSICAL_PREFIX$tag"
    }
}
