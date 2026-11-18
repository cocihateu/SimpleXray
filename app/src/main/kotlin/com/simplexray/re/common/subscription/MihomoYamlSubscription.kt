package com.simplexray.re.common.subscription

import com.simplexray.re.common.config.ConfigCodec
import com.simplexray.re.common.config.GroupProfile
import com.simplexray.re.common.config.GroupTarget
import com.simplexray.re.common.config.OutboundGroup
import org.json.JSONObject

internal data class MihomoGroupPolicy(
    val tag: String,
    val sourceType: String,
    val mappedOptions: List<String>,
)

internal data class MihomoSubscription(
    val outbounds: List<JSONObject>,
    val profile: GroupProfile,
    val policies: List<MihomoGroupPolicy>,
    val sourceSections: List<String>,
) {
    val nodeTags: List<String> get() = outbounds.map { it.getString("tag") }
}

internal object MihomoYamlSubscription {
    private val builtins = mapOf(
        "DIRECT" to "direct",
        "REJECT" to "block",
        "REJECT-DROP" to "block",
    )
    private val groupTypes = setOf("select", "fallback", "url-test", "load-balance")
    private val mappedGroupKeys = setOf(
        "url", "lazy", "tolerance", "strategy", "expected-status", "hidden", "icon",
    )

    fun parse(content: String): MihomoSubscription {
        val root = ConfigCodec.parse(content)
            ?: throw MihomoConversionException(MihomoConversionException.Kind.FORMAT, "subscription")
        val fields = MihomoFields(root, "subscription")
        if (fields.has("proxies") == false && fields.has("proxy-groups") == false) {
            throw MihomoConversionException(MihomoConversionException.Kind.FORMAT, "subscription")
        }
        if (fields.has("proxy-providers")) {
            fields.capability("proxy-providers", "provider-proxies")
        }
        val proxies = fields.array("proxies")
        val outbounds = mutableListOf<JSONObject>()
        val aliases = mutableMapOf<String, String>()
        val names = mutableSetOf<String>()
        for (index in 0 until proxies.length()) {
            val proxy = MihomoFields(
                proxies.optJSONObject(index) ?: fields.typeError("proxies[$index]"),
                "proxies[$index]",
            )
            val name = proxy.requiredText("name")
            if (names.add(name) == false) duplicate(name)
            val type = proxy.requiredText("type").lowercase()
            if (type in setOf("direct", "reject", "reject-drop")) {
                proxy.allow(setOf("name", "type"))
                val target = if (type == "direct") "direct" else "block"
                val reservedTarget = builtins[name] ?: name.takeIf { it in setOf("direct", "block") }
                if (reservedTarget != null && reservedTarget != target) duplicate(name)
                aliases[name] = target
            } else {
                if (name in builtins || name in setOf("direct", "block")) duplicate(name)
                outbounds += MihomoProxyConverter.convert(proxy)
            }
        }
        val groupArray = fields.array("proxy-groups")
        val groupFields = (0 until groupArray.length()).map { index ->
            MihomoFields(
                groupArray.optJSONObject(index) ?: fields.typeError("proxy-groups[$index]"),
                "proxy-groups[$index]",
            )
        }
        val groupNames = groupFields.map { group ->
            val name = group.requiredText("name")
            if (names.add(name) == false || name in builtins || name in setOf("direct", "block")) duplicate(name)
            name
        }.toSet()
        val physicalNames = outbounds.map { it.getString("tag") }.toSet()
        val policies = mutableListOf<MihomoGroupPolicy>()
        val groups = groupFields.map { group ->
            group.allow(setOf("name", "type", "proxies", "interval", "disable-udp") + mappedGroupKeys)
            val name = group.requiredText("name")
            val type = group.requiredText("type").lowercase()
            if (type !in groupTypes) group.capability("type", type)
            if (group.boolean("disable-udp")) group.capability("disable-udp", "group-udp-control")
            val members = group.strings("proxies").map { member ->
                when {
                    member in builtins -> GroupTarget(builtins.getValue(member))
                    member in aliases -> GroupTarget(aliases.getValue(member))
                    member in setOf("direct", "block") -> GroupTarget(member)
                    member in groupNames -> GroupTarget(member, isGroup = true)
                    member in physicalNames -> GroupTarget(member)
                    else -> throw MihomoConversionException(
                        MihomoConversionException.Kind.REFERENCE, "$name.proxies", member
                    )
                }
            }
            val primary = members.firstOrNull()
                ?: throw MihomoConversionException(MihomoConversionException.Kind.REQUIRED, "$name.proxies")
            for (key in listOf("url", "strategy", "expected-status", "icon")) {
                if (group.has(key)) group.text(key)
            }
            for (key in listOf("lazy", "hidden")) {
                if (group.has(key)) group.boolean(key)
            }
            if (group.has("tolerance")) group.integer("tolerance", 0)
            policies += MihomoGroupPolicy(name, type, mappedGroupKeys.filter(group::has))
            OutboundGroup(
                tag = name,
                primary = primary,
                fallbacks = members.drop(1),
                intervalSeconds = group.integer("interval", 60),
                trustSeconds = 120,
                recoveryIntervalSeconds = 300,
            )
        }
        val profile = GroupProfile(
            groups = physicalNames.map { node ->
                OutboundGroup(tag = node, primary = GroupTarget(node), recoveryIntervalSeconds = 300)
            } + groups,
            targetStrategies = emptyMap(),
        )
        if (profile.isValid(physicalNames) == false) {
            throw MihomoConversionException(MihomoConversionException.Kind.GRAPH, "proxy-groups")
        }
        return MihomoSubscription(
            outbounds = outbounds,
            profile = profile,
            policies = policies,
            sourceSections = root.keys().asSequence()
                .filter { it !in setOf("proxies", "proxy-groups") }.toList(),
        )
    }

    private fun duplicate(name: String): Nothing =
        throw MihomoConversionException(MihomoConversionException.Kind.DUPLICATE, name)
}
