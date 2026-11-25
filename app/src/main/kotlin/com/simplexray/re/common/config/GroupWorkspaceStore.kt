package com.simplexray.re.common.config

import android.content.Context
import android.util.Log
import com.simplexray.re.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "GroupWorkspaceStore"

class GroupWorkspaceStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, WORKSPACE_FILENAME)
    private val globalConfigStore = GlobalConfigStore(appContext)

    fun load(): GroupWorkspace {
        synchronized(lock) {
            ensureInitialized()
            val root = runCatching { JSONObject(file.readText()) }.getOrElse { e ->
                Log.e(TAG, "Error reading workspace", e)
                return GroupWorkspace()
            }
            val workspace = runCatching { decode(root) }.getOrElse { e ->
                Log.e(TAG, "Error decoding workspace", e)
                return GroupWorkspace()
            }
            if (root.optInt("version") < SCHEMA_VERSION) {
                // Rewrite rules before dropping stored aliases. The rule schema prevents a
                // second rewrite if the process stops between these two store writes.
                RuleWorkspaceStore(appContext).migrateRoutingTargets(legacyRoutingTargets(root, workspace))
                writeAtomic(encode(workspace))
                _version.value++
            }
            return workspace
        }
    }

    fun update(transform: (GroupWorkspace) -> GroupWorkspace): GroupWorkspace {
        synchronized(lock) {
            WorkspaceFiles.requireWritable(file, SCHEMA_VERSION)
            val updated = transform(load())
            writeAtomic(encode(updated))
            _version.value++
            return updated
        }
    }

    fun observe(): Flow<GroupWorkspace> = version
        .map { load() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    fun selectDefaultGroup(tag: String) {
        val updated = update { current ->
            if (current.groupByTag(tag) != null) current.copy(defaultGroupTag = tag) else current
        }
        if (updated.groupByTag(tag) == null) return
        RuleWorkspaceStore(appContext).update { rules ->
            val catchAll = rules.indexOfLast { it.enabled && it.type == TrafficRule.TYPE_MATCH }
            rules.mapIndexed { index, rule ->
                if (index == catchAll) rule.copy(target = TrafficRule.TARGET_GLOBAL) else rule
            }
        }
    }

    private fun ensureInitialized() {
        if (file.isFile) return
        synchronized(lock) {
            if (file.isFile) return
            val migrated = migrateFromLegacyConfigs()
            if (migrated.groups.isNotEmpty()) {
                val legacy = JSONObject().put("groups", JSONArray(migrated.groups.map { JSONObject().put("tag", it.tag) }))
                RuleWorkspaceStore(appContext).migrateRoutingTargets(legacyRoutingTargets(legacy, migrated))
            }
            writeAtomic(encode(migrated))
            _version.value++
        }
    }

    private fun migrateFromLegacyConfigs(): GroupWorkspace {
        val prefs = Preferences(appContext)
        val candidates = buildList {
            prefs.selectedConfigPath?.let { add(File(it)) }
            appContext.filesDir.listFiles { f ->
                f.isFile &&
                    f.name != GlobalConfigStore.GLOBAL_CONFIG_FILENAME &&
                    f.name != WORKSPACE_FILENAME &&
                    f.name != RuleWorkspaceStore.WORKSPACE_FILENAME &&
                    f.name != "extra_api.json" &&
                    (f.name.endsWith(".json", ignoreCase = true) ||
                        f.name.endsWith(".yaml", ignoreCase = true) ||
                        f.name.endsWith(".yml", ignoreCase = true))
            }?.sortedByDescending { it.lastModified() }?.let { addAll(it) }
        }.distinctBy { it.absolutePath }

        val (source, root) = candidates.firstNotNullOfOrNull { candidate ->
            if (!candidate.isFile) return@firstNotNullOfOrNull null
            val parsed = runCatching { ConfigCodec.parse(candidate.readText()) }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            if (parsed.optJSONArray("outbounds") == null) null else candidate to parsed
        } ?: return GroupWorkspace()
        globalConfigStore.importFromFullConfig(root)

        val outbounds = root.getJSONArray("outbounds")
        val excluded = setOf("freedom", "direct", "blackhole", "block", "dns", "loopback")
        val nodes = mutableListOf<ProxyNode>()
        val seenTags = mutableSetOf<String>()
        for (i in 0 until outbounds.length()) {
            val ob = outbounds.optJSONObject(i) ?: continue
            val protocol = ob.optString("protocol").lowercase()
            if (protocol in excluded) continue
            val tag = ob.optString("tag").trim()
            if (tag.isEmpty() || seenTags.add(tag) == false) continue
            nodes += ProxyNode(
                tag = tag,
                protocol = protocol,
                outboundJson = ob.toString(),
                targetStrategy = ob.optString("targetStrategy"),
            )
        }
        if (nodes.isEmpty()) return GroupWorkspace()

        val legacyStore = GroupProfileStore(appContext)
        val legacyProfile = runCatching {
            legacyStore.load(source.absolutePath, nodes.map { it.tag })
        }.getOrNull()

        val nodesByTag = nodes.associateBy { it.tag }
        val groups = if (legacyProfile != null && legacyProfile.groups.isNotEmpty()) {
            val owned = mutableSetOf<String>()
            legacyProfile.groups.map { oldGroup ->
                val primaryNode = if (oldGroup.primary.isGroup == false && oldGroup.tag == oldGroup.primary.tag) {
                    nodesByTag[oldGroup.primary.tag]?.takeIf { owned.add(it.tag) }
                } else {
                    null
                }
                val groupNodes = buildList {
                    primaryNode?.let { node ->
                        val strategy = legacyProfile.targetStrategies[node.tag] ?: node.targetStrategy
                        add(node.copy(targetStrategy = strategy))
                    }
                }
                val members = (listOf(oldGroup.primary) + oldGroup.fallbacks).map {
                    GroupMemberRef(tag = it.tag, isGroup = it.isGroup)
                }
                ProxyGroup(
                    tag = oldGroup.tag,
                    sourceType = GroupSourceType.LOCAL,
                    strategy = GroupStrategy.FALLBACK,
                    nodes = groupNodes,
                    members = members,
                    recoveryIntervalSeconds = oldGroup.recoveryIntervalSeconds.coerceAtLeast(5),
                    intervalSeconds = oldGroup.intervalSeconds.coerceAtLeast(5),
                    trustSeconds = oldGroup.trustSeconds.coerceAtLeast(5),
                )
            } + nodes.filter { it.tag !in owned }.map { node ->
                val strategy = legacyProfile.targetStrategies[node.tag] ?: node.targetStrategy
                ProxyGroup(
                    tag = node.tag,
                    sourceType = GroupSourceType.LOCAL,
                    strategy = GroupStrategy.FALLBACK,
                    nodes = listOf(node.copy(targetStrategy = strategy)),
                    members = listOf(GroupMemberRef(node.tag, isGroup = false)),
                )
            }
        } else {
            nodes.map { node ->
                ProxyGroup(
                    tag = node.tag,
                    sourceType = GroupSourceType.LOCAL,
                    strategy = GroupStrategy.FALLBACK,
                    nodes = listOf(node),
                    members = listOf(GroupMemberRef(node.tag, isGroup = false)),
                )
            }
        }
        val dedupGroups = groups.distinctBy { it.tag }
        Log.i(TAG, "Migrated ${dedupGroups.size} groups from ${source.name}")
        return GroupWorkspace(
            groups = dedupGroups,
            defaultGroupTag = dedupGroups.firstOrNull()?.tag,
        )
    }

    private fun writeAtomic(content: String) = WorkspaceFiles.write(file, content)

    private fun encode(workspace: GroupWorkspace): String {
        val groupsArray = JSONArray()
        workspace.groups.forEach { group ->
            val nodesArray = JSONArray()
            group.nodes.forEach { node ->
                nodesArray.put(
                    JSONObject()
                        .put("tag", node.tag)
                        .put("protocol", node.protocol)
                        .put("outboundJson", node.outboundJson)
                        .put("targetStrategy", node.targetStrategy)
                        .put("subscriptionName", node.subscriptionName)
                        .put("userEdited", node.userEdited)
                        .put("udpOverTcpVersion", node.udpOverTcpVersion)
                        .put("obfsMode", node.obfsMode)
                        .put("obfsHost", node.obfsHost)
                )
            }
            val membersArray = JSONArray()
            group.members.forEach { member ->
                membersArray.put(
                    JSONObject()
                        .put("tag", member.tag)
                        .put("isGroup", member.isGroup)
                )
            }
            val groupObj = JSONObject()
                .put("tag", group.tag)
                .put("sourceType", group.sourceType.name)
                .put("strategy", group.strategy.name)
                .put("subscriptionUrl", group.subscriptionUrl)
                .put("subscriptionUpdatedAtMs", group.subscriptionUpdatedAtMs)
                .put("nodes", nodesArray)
                .put("members", membersArray)
                .put("failoverTimeoutSeconds", group.failoverTimeoutSeconds)
                .put("recoveryIntervalSeconds", group.recoveryIntervalSeconds)
                .put("intervalSeconds", group.intervalSeconds)
                .put("trustSeconds", group.trustSeconds)
                .put("testUrl", group.testUrl)
            group.selectedMember?.let { selected ->
                groupObj.put(
                    "selectedMember",
                    JSONObject().put("tag", selected.tag).put("isGroup", selected.isGroup)
                )
            }
            group.frontProxy?.let { front ->
                groupObj.put("frontProxy", JSONObject().put("tag", front.tag).put("isGroup", front.isGroup))
            }
            groupsArray.put(groupObj)
        }
        return JSONObject()
            .put("version", SCHEMA_VERSION)
            .put("groups", groupsArray)
            .put("routingMode", workspace.routingMode)
            .apply {
                workspace.defaultGroupTag?.let { put("defaultGroupTag", it) }
            }
            .toString(2)
    }

    private fun decode(root: JSONObject): GroupWorkspace {
        val groupsArray = root.optJSONArray("groups") ?: JSONArray()
        val groups = buildList {
            for (i in 0 until groupsArray.length()) {
                val obj = groupsArray.optJSONObject(i) ?: continue
                val tag = obj.optString("tag").trim()
                if (tag.isEmpty()) continue
                val sourceType = runCatching {
                    GroupSourceType.valueOf(obj.optString("sourceType", GroupSourceType.LOCAL.name))
                }.getOrDefault(GroupSourceType.LOCAL)
                val strategy = runCatching {
                    GroupStrategy.valueOf(obj.optString("strategy", GroupStrategy.FALLBACK.name))
                }.getOrDefault(GroupStrategy.FALLBACK)
                val nodesArray = obj.optJSONArray("nodes") ?: JSONArray()
                val nodes = buildList {
                    for (n in 0 until nodesArray.length()) {
                        val nodeObj = nodesArray.optJSONObject(n) ?: continue
                        val nodeTag = nodeObj.optString("tag").trim()
                        val outboundJson = nodeObj.optString("outboundJson")
                        if (nodeTag.isEmpty() || outboundJson.isBlank()) continue
                        add(
                            ProxyNode(
                                tag = nodeTag,
                                protocol = nodeObj.optString("protocol", "unknown"),
                                outboundJson = outboundJson,
                                targetStrategy = nodeObj.optString("targetStrategy"),
                                subscriptionName = nodeObj.optString("subscriptionName"),
                                userEdited = nodeObj.optBoolean("userEdited"),
                                udpOverTcpVersion = nodeObj.optInt("udpOverTcpVersion"),
                                obfsMode = nodeObj.optString("obfsMode"),
                                obfsHost = nodeObj.optString("obfsHost"),
                            )
                        )
                    }
                }
                val membersArray = obj.optJSONArray("members") ?: JSONArray()
                val members = buildList {
                    for (m in 0 until membersArray.length()) {
                        val mObj = membersArray.optJSONObject(m) ?: continue
                        val mTag = mObj.optString("tag").trim()
                        if (mTag.isEmpty()) continue
                        add(GroupMemberRef(tag = mTag, isGroup = mObj.optBoolean("isGroup", false)))
                    }
                }
                val selected = obj.optJSONObject("selectedMember")?.let { sObj ->
                    val sTag = sObj.optString("tag").trim()
                    if (sTag.isNotEmpty()) GroupMemberRef(sTag, sObj.optBoolean("isGroup", false)) else null
                }
                add(
                    ProxyGroup(
                        tag = tag,
                        sourceType = sourceType,
                        strategy = strategy,
                        subscriptionUrl = obj.optString("subscriptionUrl"),
                        subscriptionUpdatedAtMs = obj.optLong("subscriptionUpdatedAtMs", 0L),
                        nodes = nodes,
                        members = members,
                        selectedMember = selected,
                        frontProxy = obj.optJSONObject("frontProxy")?.let {
                            GroupMemberRef(it.getString("tag"), it.optBoolean("isGroup"))
                        },
                        failoverTimeoutSeconds = obj.optInt("failoverTimeoutSeconds", 3).coerceAtLeast(1),
                        recoveryIntervalSeconds = obj.optInt("recoveryIntervalSeconds", 300).coerceAtLeast(5),
                        intervalSeconds = obj.optInt("intervalSeconds", 60).coerceAtLeast(5),
                        trustSeconds = obj.optInt("trustSeconds", 120).coerceAtLeast(5),
                        testUrl = obj.optString("testUrl").ifBlank { DEFAULT_URL_TEST },
                    )
                )
            }
        }
        val defaultGroupTag = root.optString("defaultGroupTag").takeIf { it.isNotBlank() }
        return GroupWorkspace(groups = groups, defaultGroupTag = defaultGroupTag, routingMode = root.optString("routingMode", "rule"))
    }

    /**
     * One-time input for dropping routing aliases (workspace schema 0 -> 1): the stored
     * alias -> owning group map, so rules that target an alias can be pointed at the group.
     * Covers explicit "routingTargets" entries and, for groups stored without that field, the
     * alias that used to be inferred (the primary leaf of a multi-member FALLBACK group).
     */
    private fun legacyRoutingTargets(root: JSONObject, workspace: GroupWorkspace): Map<String, String> {
        val groupsArray = root.optJSONArray("groups") ?: return emptyMap()
        val explicit = linkedMapOf<String, String>()
        val withoutField = mutableSetOf<String>()
        for (i in 0 until groupsArray.length()) {
            val obj = groupsArray.optJSONObject(i) ?: continue
            val tag = obj.optString("tag").trim()
            if (tag.isEmpty()) continue
            val targets = obj.optJSONArray("routingTargets")
            if (!obj.has("routingTargets")) withoutField += tag
            if (targets != null) {
                for (t in 0 until targets.length()) explicit[targets.getString(t)] = tag
            }
        }
        fun primaryLeaf(member: GroupMemberRef, visited: Set<String> = emptySet()): String? {
            if (member.isGroup == false) return member.tag
            if (member.tag in visited) return null
            val child = workspace.groupByTag(member.tag) ?: return null
            if (child.strategy == GroupStrategy.URL_TEST) return null
            val primary = if (child.strategy == GroupStrategy.SELECT) {
                child.selectedMember ?: child.effectiveMembers().firstOrNull()
            } else child.effectiveMembers().firstOrNull()
            return primary?.let { primaryLeaf(it, visited + member.tag) }
        }
        val owners = workspace.groups
            .filter { it.strategy == GroupStrategy.FALLBACK && it.effectiveMembers().size > 1 }
            .mapNotNull { group -> group.effectiveMembers().firstOrNull()?.let { primaryLeaf(it) }?.let { it to group.tag } }
            .groupBy({ it.first }, { it.second })
        val inferred = workspace.groups.mapNotNull { group ->
            val primary = group.effectiveMembers().firstOrNull()?.let { primaryLeaf(it) }
            if (group.tag in withoutField && primary != null &&
                owners[primary] == listOf(group.tag) && primary !in explicit
            ) primary to group.tag else null
        }
        // Reserved targets were resolved before aliases, and an alias equal to its own group changes nothing.
        return (explicit + inferred).filter { (alias, group) ->
            alias.isNotEmpty() && alias != group && alias !in RESERVED_ROUTING_TARGET_TAGS
        }
    }

    companion object {
        const val WORKSPACE_FILENAME = "groups_workspace.json"
        /** 1 = routing aliases removed; rules that targeted an alias were rewritten to the group tag. */
        private const val SCHEMA_VERSION = 1
        /** Shared with [RuleWorkspaceStore]: the two files are migrated together. */
        internal val lock = Any()
        private val _version = MutableStateFlow(0L)
        val version: StateFlow<Long> = _version.asStateFlow()
    }
}
