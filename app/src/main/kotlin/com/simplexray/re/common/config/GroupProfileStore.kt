package com.simplexray.re.common.config

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "GroupProfileStore"

class GroupProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences(
        "${appContext.packageName}_group_profiles",
        Context.MODE_PRIVATE,
    )

    fun load(path: String, nodeTags: List<String>): GroupProfile {
        val file = File(path)
        if (file.isFile) {
            val content = sp.getString(file.absolutePath, null) ?: return defaultProfile(nodeTags)
            val profile = runCatching { decode(content) }.getOrElse { e ->
                Log.e(TAG, "Error reading group profile for ${file.name}", e)
                return defaultProfile(nodeTags)
            }
            return reconcile(profile, nodeTags)
        }
        return emptyProfile()
    }

    private fun emptyProfile() = GroupProfile(
        groups = emptyList(),
        targetStrategies = emptyMap(),
    )

    private fun autoGroup(tag: String) = OutboundGroup(
        tag = tag,
        primary = GroupTarget(tag = tag),
    )

    private fun defaultProfile(nodeTags: List<String>) = GroupProfile(
        groups = nodeTags.distinct().map(::autoGroup),
        targetStrategies = emptyMap(),
    )

    private fun reconcile(profile: GroupProfile, nodeTags: List<String>): GroupProfile {
        val nodes = nodeTags.toSet()
        val leafTags = nodes + BUILTIN_GROUP_TARGET_TAGS
        val savedGroupTags = profile.groups.map { it.tag }.toSet()
        var groups = profile.groups + nodes.filter { it !in savedGroupTags }.map(::autoGroup)
        var previousSize: Int
        do {
            previousSize = groups.size
            val groupTags = groups.map { it.tag }.toSet()
            groups = groups.mapNotNull { group ->
                val targets = (listOf(group.primary) + group.fallbacks).filter { target ->
                    if (target.isGroup) target.tag in groupTags else target.tag in leafTags
                }
                val primary = targets.firstOrNull()
                    ?: if (group.tag in nodes) GroupTarget(tag = group.tag) else null
                primary?.let { target ->
                    group.copy(
                        primary = target,
                        fallbacks = targets.drop(1),
                    )
                }
            }
        } while (groups.size < previousSize)
        return GroupProfile(
            groups = groups,
            targetStrategies = profile.targetStrategies.filterKeys { it in nodes },
        )
    }

    private fun decode(content: String): GroupProfile {
        val root = JSONObject(content)
        val groups = root.getJSONArray("groups")
        val strategies = root.getJSONObject("targetStrategies")
        return GroupProfile(
            groups = (0 until groups.length()).map { index ->
                val group = groups.getJSONObject(index)
                val fallbacks = group.optJSONArray("fallbacks") ?: JSONArray()
                OutboundGroup(
                    tag = group.getString("tag"),
                    primary = decodeTarget(group.getJSONObject("primary")),
                    fallbacks = (0 until fallbacks.length()).map {
                        decodeTarget(fallbacks.getJSONObject(it))
                    },
                    intervalSeconds = group.optInt("intervalSeconds", 60),
                    trustSeconds = group.optInt("trustSeconds", 120),
                    recoveryIntervalSeconds = group.optInt("recoveryIntervalSeconds", 300),
                )
            },
            targetStrategies = strategies.keys().asSequence().associateWith {
                strategies.getString(it)
            },
        )
    }

    private fun decodeTarget(target: JSONObject) = GroupTarget(
        tag = target.getString("tag"),
        isGroup = target.optBoolean("isGroup", false),
    )
}
