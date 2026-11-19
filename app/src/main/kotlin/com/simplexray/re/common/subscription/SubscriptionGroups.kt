package com.simplexray.re.common.subscription

import com.simplexray.re.common.config.DEFAULT_URL_TEST
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.ProxyGroup
import org.json.JSONObject

internal object SubscriptionGroups {
    fun merge(
        workspace: GroupWorkspace,
        providerTag: String,
        definitions: List<JSONObject>,
        nodeNames: Map<String, String>,
    ): GroupWorkspace {
        val supported = definitions.filter { it.optString("type") in setOf("select", "fallback", "url-test") }
        val usedTags = workspace.groups.map { it.tag }.toMutableSet()
        val aliases = supported.associate { definition ->
            val name = definition.optString("name")
            name to GroupWorkspace.uniqueTag("$providerTag / $name", usedTags)
        }
        val groups = supported.map { definition ->
            val strategy = when (definition.optString("type")) {
                "fallback" -> GroupStrategy.FALLBACK
                "url-test" -> GroupStrategy.URL_TEST
                else -> GroupStrategy.SELECT
            }
            val source = definition.optJSONArray("proxies")
            val members = (0 until (source?.length() ?: 0)).mapNotNull { index ->
                val name = source!!.getString(index)
                when {
                    name == "DIRECT" -> GroupMemberRef("direct")
                    name in setOf("REJECT", "REJECT-DROP") -> GroupMemberRef("block")
                    name in aliases -> GroupMemberRef(aliases.getValue(name), true)
                    name in nodeNames -> GroupMemberRef(nodeNames.getValue(name))
                    else -> null
                }
            }
            val interval = definition.optInt("interval", if (strategy == GroupStrategy.FALLBACK) 300 else 60).coerceAtLeast(5)
            ProxyGroup(
                tag = aliases.getValue(definition.getString("name")),
                strategy = strategy,
                members = members,
                selectedMember = members.firstOrNull(),
                recoveryIntervalSeconds = interval,
                intervalSeconds = interval,
                trustSeconds = interval * 2,
                testUrl = definition.optString("url").ifBlank { DEFAULT_URL_TEST },
            )
        }
        return workspace.copy(groups = workspace.groups + groups)
    }
}
