package com.simplexray.re.service

import com.simplexray.re.common.LogLine
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.TrafficRule
import com.simplexray.re.prefs.LogLevel
import org.json.JSONArray
import org.json.JSONObject

/** Converts the Xray workspace and runtime events into dashboard fields. */
internal object ClashPresentation {
    fun log(line: String, minimum: String): JSONObject? {
        if (line.contains("proxy/socks: Connection closed immediately, likely health check connection")) return null
        val entry = LogLine.parse(line)
        val level = LogLevel.fromString(if (minimum == "silent") "none" else minimum)
        if (entry.meets(level) == false) return null
        return JSONObject().put("type", entry.level.value).put("payload", entry.message)
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
