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
) {
    fun isValid(nodeTags: Set<String>): Boolean {
        val byTag = groups.associateBy { it.tag }
        if (byTag.size != groups.size || !byTag.keys.containsAll(nodeTags)) return false
        if (targetStrategies.any { (node, mode) -> node !in nodeTags || mode !in setOf("ForceIP", "AsIs") }) return false
        val leafTags = nodeTags + BUILTIN_GROUP_TARGET_TAGS
        val visited = mutableSetOf<String>()
        val path = mutableSetOf<String>()
        fun visit(tag: String): Boolean {
            if (tag in visited) return true
            val group = byTag[tag] ?: return false
            if (tag.isBlank() || !path.add(tag) || group.intervalSeconds < 1 || group.trustSeconds < 1) return false
            for (target in listOf(group.primary) + group.fallbacks) {
                if (target.isGroup) {
                    if (!visit(target.tag)) return false
                } else if (target.tag !in leafTags) return false
            }
            path.remove(tag)
            visited.add(tag)
            return true
        }
        return byTag.keys.all(::visit)
    }
}
