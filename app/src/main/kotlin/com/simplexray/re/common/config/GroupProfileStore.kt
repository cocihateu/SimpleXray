package com.simplexray.re.common.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

    fun save(path: String, profile: GroupProfile) {
        val file = File(path)
        if (file.isFile) {
            sp.edit { putString(file.absolutePath, encode(profile)) }
        }
    }

    fun observe(path: String, nodeTags: List<String>): Flow<GroupProfile> {
        val key = File(path).absolutePath
        return callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                if (changedKey == key || changedKey == null) {
                    trySend(Unit)
                }
            }
            sp.registerOnSharedPreferenceChangeListener(listener)
            trySend(Unit)
            awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
        }
            .conflate()
            .map { load(path, nodeTags) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
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

    private fun encode(profile: GroupProfile): String {
        val groups = JSONArray()
        profile.groups.forEach { group ->
            groups.put(
                JSONObject()
                    .put("tag", group.tag)
                    .put("primary", encodeTarget(group.primary))
                    .put("fallbacks", JSONArray(group.fallbacks.map { encodeTarget(it) }))
                    .put("intervalSeconds", group.intervalSeconds)
                    .put("trustSeconds", group.trustSeconds)
                    .put("recoveryIntervalSeconds", group.recoveryIntervalSeconds)
            )
        }
        return JSONObject()
            .put("groups", groups)
            .put("targetStrategies", JSONObject(profile.targetStrategies))
            .toString()
    }

    private fun encodeTarget(target: GroupTarget): JSONObject = JSONObject()
        .put("tag", target.tag)
        .put("isGroup", target.isGroup)

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
