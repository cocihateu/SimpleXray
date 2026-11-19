package com.simplexray.re.service

import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.TrafficRule
import org.json.JSONArray
import org.json.JSONObject

/** Converts the Xray workspace and runtime events into dashboard fields. */
internal object ClashPresentation {
    private val timestamp = Regex("""^\d{4}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?\s+""")
    private val severity = Regex("""^\[(Debug|Info|Warning|Warn|Error|Fatal)\]\s*""", RegexOption.IGNORE_CASE)
    private val sessionId = Regex("""^\[\d+\]\s*""")
    private val levels = listOf("debug", "info", "warning", "error", "silent")

    fun log(line: String, minimum: String): JSONObject? {
        if (line.contains("proxy/socks: Connection closed immediately, likely health check connection")) return null
        val message = timestamp.replaceFirst(line, "")
        val match = severity.find(message)
        val type = when (match?.groupValues?.get(1)?.lowercase()) {
            "debug" -> "debug"
            "warning", "warn" -> "warning"
            "error", "fatal" -> "error"
            else -> "info"
        }
        if (levels.indexOf(type) < levels.indexOf(minimum.lowercase())) return null
        val payload = sessionId.replaceFirst(match?.let { message.removeRange(it.range) } ?: message, "")
        return JSONObject().put("type", type).put("payload", payload)
    }

    fun target(workspace: GroupWorkspace, mapper: ClashTagMapper, name: String): String = when (name) {
        "direct", "DIRECT" -> "DIRECT"
        "block", "REJECT" -> "REJECT"
        "GLOBAL" -> "GLOBAL"
        else -> workspace.effectiveRoutingBindings()[name]?.let { mapper.toApiName(true, it) }
            ?: mapper.toApiName(false, name)
    }

    fun rule(rule: TrafficRule, index: Int, workspace: GroupWorkspace, mapper: ClashTagMapper): JSONObject =
        JSONObject().put("type", rule.type).put("payload", rule.payload)
            .put("proxy", target(workspace, mapper, rule.target))
            .put("size", 1).put("uuid", rule.id).put("index", index).put("disabled", rule.enabled == false)
            .put("extra", JSONObject().put("disabled", rule.enabled == false))

    fun chains(connection: RelayConnectionSnapshot, workspace: GroupWorkspace, mapper: ClashTagMapper): JSONArray {
        fun path(member: GroupMemberRef, visited: Set<String>): List<String>? {
            if (member.isGroup == false) {
                return if (member.tag == connection.leafTag) listOf(mapper.toApiName(false, member.tag)) else null
            }
            if (member.tag in visited) return null
            val group = workspace.groupByTag(member.tag) ?: return null
            val members = group.effectiveMembers().sortedBy {
                if (it == group.selectedMember) 0 else 1
            }
            return members.firstNotNullOfOrNull { path(it, visited + member.tag) }
                ?.plus(mapper.toApiName(true, member.tag))
        }
        val names = if (connection.groupTag.startsWith(OutboundGroupConfig.PHYSICAL_PREFIX)) {
            listOf(mapper.toApiName(false, connection.leafTag))
        } else {
            path(GroupMemberRef(connection.groupTag, true), emptySet())
                ?: listOf(mapper.toApiName(false, connection.leafTag), mapper.toApiName(true, connection.groupTag))
        }
        return JSONArray(names.filter { it.isNotBlank() })
    }
}
