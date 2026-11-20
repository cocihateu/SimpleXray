package com.simplexray.re.common.config

import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_URL_TEST = "https://www.gstatic.com/generate_204"
const val DEFAULT_DOH_BOOTSTRAP_DNS = "223.5.5.5"

enum class GroupSourceType {
    LOCAL,
    SUBSCRIPTION,
}

enum class GroupStrategy {
    FALLBACK,
    URL_TEST,
    SELECT,
}

data class GroupMemberRef(
    val tag: String,
    val isGroup: Boolean = false,
)

data class ProxyNode(
    val tag: String,
    val protocol: String,
    val outboundJson: String,
    val targetStrategy: String = "",
    val subscriptionName: String = "",
    val userEdited: Boolean = false,
    val udpOverTcpVersion: Int = 0,
    val obfsMode: String = "",
    val obfsHost: String = "",
)

data class ProxyGroup(
    val tag: String,
    val sourceType: GroupSourceType = GroupSourceType.LOCAL,
    val strategy: GroupStrategy = GroupStrategy.FALLBACK,
    val subscriptionUrl: String = "",
    val subscriptionUpdatedAtMs: Long = 0L,
    val nodes: List<ProxyNode> = emptyList(),
    val members: List<GroupMemberRef> = emptyList(),
    val selectedMember: GroupMemberRef? = null,
    val failoverTimeoutSeconds: Int = 3,
    val recoveryIntervalSeconds: Int = 300,
    val intervalSeconds: Int = 60,
    val trustSeconds: Int = 120,
    val testUrl: String = DEFAULT_URL_TEST,
    val routingTargets: List<String> = emptyList(),
    val frontProxy: GroupMemberRef? = null,
) {
    fun effectiveMembers(): List<GroupMemberRef> =
        if (members.isNotEmpty()) members else nodes.map { GroupMemberRef(it.tag, isGroup = false) }
}

data class GroupWorkspace(
    val groups: List<ProxyGroup> = emptyList(),
    val defaultGroupTag: String? = null,
    val routingMode: String = "rule",
) {
    fun allNodes(): List<ProxyNode> {
        val seen = linkedMapOf<String, ProxyNode>()
        for (group in groups) {
            for (node in group.nodes) {
                seen.putIfAbsent(node.tag, node)
            }
        }
        return seen.values.toList()
    }

    fun groupByTag(tag: String): ProxyGroup? = groups.firstOrNull { it.tag == tag }

    fun nodeByTag(tag: String): ProxyNode? = allNodes().firstOrNull { it.tag == tag }

    fun effectiveDefaultGroupTag(): String? =
        defaultGroupTag?.takeIf { tag -> groups.any { it.tag == tag } }
            ?: groups.firstOrNull()?.tag

    /** Business routing aliases are applied after the internal physical-node routing branch. */
    fun effectiveRoutingBindings(): Map<String, String> = buildMap {
        groups.forEach { put(it.tag, it.tag) }
        groups.forEach { group ->
            group.routingTargets.forEach { target -> put(target, group.tag) }
        }
    }

    fun hasCycle(): Boolean {
        val byTag = groups.associateBy { it.tag }
        val visited = mutableSetOf<String>()
        val path = mutableSetOf<String>()
        fun visit(tag: String): Boolean {
            if (tag in visited) return false
            if (path.add(tag) == false) return true
            val group = byTag[tag]
            if (group != null) {
                group.frontProxy?.let { front ->
                    val frontGroup = if (front.isGroup) front.tag else groups.firstOrNull { owner ->
                        owner.nodes.any { it.tag == front.tag }
                    }?.tag
                    if (frontGroup != null && visit(frontGroup)) return true
                }
                for (member in group.effectiveMembers()) {
                    if (member.isGroup && visit(member.tag)) return true
                }
            }
            path.remove(tag)
            visited.add(tag)
            return false
        }
        return groups.any { visit(it.tag) }
    }

    fun isValid(): Boolean {
        if (routingMode !in setOf("rule", "global", "direct")) return false
        if (groups.any { it.tag.isBlank() || it.tag in BUILTIN_GROUP_TARGET_TAGS }) return false
        if (groups.map { it.tag }.toSet().size != groups.size) return false
        val targets = groups.flatMap { it.routingTargets }
        if (targets.any { it.isBlank() || it in BUILTIN_GROUP_TARGET_TAGS }) return false
        if (targets.distinct().size != targets.size) return false
        if (groups.any {
                it.failoverTimeoutSeconds < 1 ||
                    it.recoveryIntervalSeconds < 5 ||
                    it.intervalSeconds < 5 ||
                    it.trustSeconds < 5
            }
        ) return false
        return hasCycle() == false
    }

    /**
     * Produces a unified Xray JSON combining global settings with all physical proxy outbounds
     * across all groups plus builtin direct/block outbounds.
     */
    fun assembleRuntimeConfig(
        globalRoot: JSONObject,
        dohBootstrapDns: String = DEFAULT_DOH_BOOTSTRAP_DNS,
    ): JSONObject {
        val runtime = JSONObject(globalRoot.toString())
        val outbounds = JSONArray()
        val addedTags = mutableSetOf<String>()

        val nodes = allNodes()
        for (node in nodes) {
            val obj = runCatching { JSONObject(node.outboundJson) }.getOrNull() ?: continue
            obj.put("tag", node.tag)
            if (node.targetStrategy.isNotBlank()) {
                obj.put("targetStrategy", node.targetStrategy)
            }
            if (addedTags.add(node.tag)) {
                outbounds.put(obj)
            }
        }

        val globalOutbounds = globalRoot.optJSONArray("outbounds")
        if (globalOutbounds != null) {
            for (i in 0 until globalOutbounds.length()) {
                val ob = globalOutbounds.optJSONObject(i) ?: continue
                val tag = ob.optString("tag")
                if (tag.isNotBlank() && addedTags.add(tag)) {
                    outbounds.put(JSONObject(ob.toString()))
                }
            }
        }

        if (addedTags.add("direct")) {
            outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        }
        if (addedTags.add("block")) {
            outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        }

        runtime.put("outbounds", outbounds)
        DnsBootstrap.inject(runtime, dohBootstrapDns)
        return runtime
    }

    companion object {
        fun uniqueTag(desired: String, existing: MutableSet<String>): String {
            val base = desired.trim().ifEmpty { "node" }
            if (base !in BUILTIN_GROUP_TARGET_TAGS && existing.add(base)) return base
            var index = 2
            while (true) {
                val candidate = "$base-$index"
                if (candidate !in BUILTIN_GROUP_TARGET_TAGS && existing.add(candidate)) {
                    return candidate
                }
                index++
            }
        }
    }
}
