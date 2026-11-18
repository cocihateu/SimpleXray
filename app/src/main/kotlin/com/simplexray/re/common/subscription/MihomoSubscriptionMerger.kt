package com.simplexray.re.common.subscription

import com.simplexray.re.common.ConfigUtils
import com.simplexray.re.common.config.GroupProfile
import com.simplexray.re.common.config.GroupTarget
import com.simplexray.re.common.config.OutboundGroup
import org.json.JSONArray
import org.json.JSONObject

internal data class MihomoMergedConfig(val content: String, val profile: GroupProfile)

internal object MihomoSubscriptionMerger {
    fun merge(
        root: JSONObject,
        previousProfile: GroupProfile,
        subscription: MihomoSubscription,
        creatingConfig: Boolean,
    ): MihomoMergedConfig {
        val incoming = subscription.outbounds.associateBy { it.getString("tag") }.toMutableMap()
        val existing = root.optJSONArray("outbounds") ?: JSONArray()
        val outbounds = JSONArray()
        for (index in 0 until existing.length()) {
            val outbound = existing.getJSONObject(index)
            outbounds.put(incoming.remove(outbound.optString("tag")) ?: outbound)
        }
        incoming.values.forEach { outbounds.put(it) }
        ensureBuiltin(outbounds, "direct", "freedom", setOf("freedom", "direct"))
        ensureBuiltin(outbounds, "block", "blackhole", setOf("blackhole", "block"))
        root.put("outbounds", outbounds)

        val incomingGroups = subscription.profile.groups.associateBy { it.tag }.toMutableMap()
        val groups = previousProfile.groups.map { group ->
            incomingGroups.remove(group.tag) ?: group
        }.toMutableList()
        groups += incomingGroups.values
        if (creatingConfig && groups.none { it.tag == "proxy" }) {
            val entry = subscription.policies.firstOrNull()?.tag ?: subscription.nodeTags.firstOrNull()
            groups += OutboundGroup(
                tag = "proxy",
                primary = if (entry != null) GroupTarget(entry, isGroup = true) else GroupTarget("direct"),
                recoveryIntervalSeconds = 300,
            )
        }
        val profile = GroupProfile(groups = groups, targetStrategies = previousProfile.targetStrategies)
        val nodeTags = ConfigUtils.extractOutbounds(root.toString()).map { it.tag }.toSet()
        if (profile.isValid(nodeTags) == false) {
            throw MihomoConversionException(MihomoConversionException.Kind.GRAPH, "groupProfile.groups")
        }
        return MihomoMergedConfig(root.toString(2), profile)
    }

    private fun ensureBuiltin(outbounds: JSONArray, tag: String, protocol: String, accepted: Set<String>) {
        val existing = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }
            .firstOrNull { it.optString("tag") == tag }
        if (existing != null) {
            if (existing.optString("protocol").lowercase() !in accepted) {
                throw MihomoConversionException(MihomoConversionException.Kind.CONFIG, tag, protocol)
            }
        } else {
            outbounds.put(JSONObject().put("tag", tag).put("protocol", protocol))
        }
    }
}
