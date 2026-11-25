package com.simplexray.re.common.config

internal val BUILTIN_GROUP_TARGET_TAGS = setOf("direct", "block")

data class GroupTarget(val tag: String, val isGroup: Boolean = false)

data class OutboundGroup(
    val tag: String,
    val primary: GroupTarget,
    val fallbacks: List<GroupTarget> = emptyList(),
    // Standby checks use this interval.
    val intervalSeconds: Int = 60,
    val trustSeconds: Int = 120,
    // Primary recovery checks use this interval while the group uses a fallback.
    val recoveryIntervalSeconds: Int = 300,
)

data class GroupProfile(
    val groups: List<OutboundGroup> = emptyList(),
    val targetStrategies: Map<String, String> = emptyMap(),
)
