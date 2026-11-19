package com.simplexray.re.service

import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupWorkspace

internal class ClashTagMapper(workspace: GroupWorkspace) {
    private val forwardMap = mutableMapOf<Pair<Boolean, String>, String>()
    private val reverseMap = mutableMapOf<String, GroupMemberRef>()

    init {
        val allocatedNames = mutableSetOf<String>()

        val builtinEntries = listOf(
            Triple("GLOBAL", "GLOBAL", true),
            Triple("DIRECT", "direct", false),
            Triple("REJECT", "block", false),
        )
        for ((apiName, targetTag, isGroup) in builtinEntries) {
            allocatedNames.add(apiName)
            reverseMap[apiName] = GroupMemberRef(targetTag, isGroup = isGroup)
            reverseMap[targetTag] = GroupMemberRef(targetTag, isGroup = isGroup)
            forwardMap[Pair(isGroup, targetTag)] = apiName
        }

        for (group in workspace.groups) {
            var candidate = group.tag
            var suffixIndex = 2
            while (allocatedNames.contains(candidate)) {
                candidate = "${group.tag} (Group $suffixIndex)"
                suffixIndex++
            }
            allocatedNames.add(candidate)
            forwardMap[Pair(true, group.tag)] = candidate
            reverseMap[candidate] = GroupMemberRef(group.tag, isGroup = true)
        }

        for (node in workspace.allNodes()) {
            var candidate = node.tag
            if (allocatedNames.contains(candidate)) {
                candidate = "${node.tag} (Node)"
                var suffixIndex = 2
                while (allocatedNames.contains(candidate)) {
                    candidate = "${node.tag} (Node $suffixIndex)"
                    suffixIndex++
                }
            }
            allocatedNames.add(candidate)
            forwardMap[Pair(false, node.tag)] = candidate
            reverseMap[candidate] = GroupMemberRef(node.tag, isGroup = false)
        }
    }

    fun toApiName(isGroup: Boolean, tag: String): String =
        forwardMap[Pair(isGroup, tag)] ?: tag

    fun resolve(apiName: String): GroupMemberRef? =
        reverseMap[apiName]
}
