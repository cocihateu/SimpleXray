package com.simplexray.re.viewmodel

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS
import com.simplexray.re.common.config.ConfigCodec
import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.common.config.GlobalConfigSummary
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupSourceType
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.GroupWorkspaceStore
import com.simplexray.re.common.config.ProxyGroup
import com.simplexray.re.common.config.ProxyNode
import com.simplexray.re.common.config.RuleWorkspaceStore
import com.simplexray.re.common.subscription.NodeImportConverter
import com.simplexray.re.common.subscription.SubscriptionService
import com.simplexray.re.service.FallbackRuntimeInfo
import com.simplexray.re.service.GroupRuntimeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private const val TAG = "GroupWorkspaceController"

internal class GroupWorkspaceController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val onGlobalConfigUpdated: (String) -> Unit,
    private val onEditFile: (String) -> Unit,
    private val showMessage: (String) -> Unit,
) {
    private val globalStore = GlobalConfigStore(application)
    private val workspaceStore = GroupWorkspaceStore(application)
    private val subscriptions = SubscriptionService(application)

    val workspace: StateFlow<GroupWorkspace> = workspaceStore.observe()
        .stateIn(scope, SharingStarted.Eagerly, GroupWorkspace())

    val globalSummary: StateFlow<GlobalConfigSummary> = GlobalConfigStore.version
        .map { globalStore.summary() }
        .stateIn(scope, SharingStarted.Eagerly, globalStore.summary())

    val activeGroupNodes: StateFlow<Map<String, String>> = GroupRuntimeStatus.activeNodes
    val fallbackStates: StateFlow<Map<String, FallbackRuntimeInfo>> = GroupRuntimeStatus.fallbackStates
    val runtimeNodeLatencies: StateFlow<Map<String, Long>> = GroupRuntimeStatus.nodeLatencies

    private val _refreshingGroups = MutableStateFlow<Set<String>>(emptySet())
    val refreshingGroups: StateFlow<Set<String>> = _refreshingGroups.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val globalFile = globalStore.file()
            onGlobalConfigUpdated(globalFile.absolutePath)
            workspaceStore.load()
        }
    }

    fun saveGroup(originalTag: String?, edited: ProxyGroup): String? {
        val trimmedTag = edited.tag.trim()
        if (trimmedTag.isEmpty() || trimmedTag in BUILTIN_GROUP_TARGET_TAGS) {
            return "请输入有效的组名"
        }
        val normalized = edited.copy(
            tag = trimmedTag,
            routingTargets = edited.routingTargets.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        )
        var validationError: String? = null
        workspaceStore.update { current ->
            val duplicate = current.groups.any { it.tag == trimmedTag && it.tag != originalTag }
            if (duplicate) {
                validationError = "组名已存在：$trimmedTag"
                return@update current
            }
            val savedGroup = if (originalTag != null && originalTag != trimmedTag &&
                current.effectiveRoutingBindings()[originalTag] == originalTag
            ) normalized.copy(routingTargets = (normalized.routingTargets + originalTag).distinct()) else normalized
            val updatedGroups = if (originalTag == null) {
                current.groups + savedGroup
            } else {
                current.groups.map { existing ->
                    if (existing.tag == originalTag) {
                        savedGroup
                    } else if (originalTag != trimmedTag) {
                        existing.copy(
                            frontProxy = existing.frontProxy?.let { front ->
                                if (front.isGroup && front.tag == originalTag) front.copy(tag = trimmedTag) else front
                            },
                            members = existing.members.map { m ->
                                if (m.isGroup && m.tag == originalTag) m.copy(tag = trimmedTag) else m
                            },
                            selectedMember = existing.selectedMember?.let { s ->
                                if (s.isGroup && s.tag == originalTag) s.copy(tag = trimmedTag) else s
                            },
                        )
                    } else {
                        existing
                    }
                }
            }
            val nextDefault = when {
                current.defaultGroupTag == null -> trimmedTag
                originalTag != null && current.defaultGroupTag == originalTag -> trimmedTag
                else -> current.defaultGroupTag
            }
            val candidate = current.copy(groups = updatedGroups, defaultGroupTag = nextDefault)
            if (candidate.isValid() == false) {
                validationError = "请检查循环嵌套、时间参数，以及重复或保留的路由目标"
                return@update current
            }
            candidate
        }
        if (validationError == null &&
            normalized.sourceType == GroupSourceType.SUBSCRIPTION &&
            normalized.subscriptionUrl.isNotBlank() &&
            (originalTag == null || normalized.nodes.isEmpty())
        ) {
            refreshSubscriptionGroup(trimmedTag)
        }
        return validationError
    }

    fun deleteGroup(tag: String) {
        scope.launch(Dispatchers.IO) {
            workspaceStore.update { current ->
                val deletedNodes = current.groupByTag(tag)?.nodes.orEmpty().map { it.tag }.toSet()
                val remaining = current.groups
                    .filter { it.tag != tag }
                    .map { group ->
                        group.copy(
                            frontProxy = group.frontProxy?.takeUnless { if (it.isGroup) it.tag == tag else it.tag in deletedNodes },
                            members = group.effectiveMembers().filterNot { if (it.isGroup) it.tag == tag else it.tag in deletedNodes },
                            selectedMember = group.selectedMember?.takeUnless { if (it.isGroup) it.tag == tag else it.tag in deletedNodes },
                        )
                    }
                val nextDefault = if (current.defaultGroupTag == tag) {
                    remaining.firstOrNull()?.tag
                } else {
                    current.defaultGroupTag
                }
                current.copy(groups = remaining, defaultGroupTag = nextDefault)
            }
            showMessage("已删除组：$tag")
        }
    }

    fun setDefaultGroup(tag: String) {
        scope.launch(Dispatchers.IO) {
            workspaceStore.selectDefaultGroup(tag)
            showMessage("默认组已设为 $tag，兜底规则跟随默认组")
        }
    }

    fun setRoutingMode(mode: String) {
        scope.launch(Dispatchers.IO) {
            workspaceStore.update { it.copy(routingMode = mode) }
        }
    }

    fun importFromClipboardToGroup(groupTag: String) {
        val cm = application.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(application)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            showMessage("剪贴板为空")
            return
        }
        scope.launch(Dispatchers.IO) {
            importTextIntoGroup(groupTag, text)
        }
    }

    fun importFromUriToGroup(groupTag: String, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                application.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            if (text.isBlank()) {
                showMessage("读取文件内容为空")
                return@launch
            }
            importTextIntoGroup(groupTag, text)
        }
    }

    fun refreshSubscriptionGroup(groupTag: String) {
        val group = workspaceStore.load().groupByTag(groupTag) ?: return
        val url = group.subscriptionUrl.trim()
        if (url.isEmpty()) {
            showMessage("请先为订阅组填写订阅链接")
            return
        }
        if (groupTag in _refreshingGroups.value) return
        _refreshingGroups.value = _refreshingGroups.value + groupTag
        scope.launch(Dispatchers.IO) {
            try {
                val result = subscriptions.refresh(groupTag)
                val skipHint = if (result.skippedCount > 0) {
                    "（跳过 ${result.skippedCount} 条）\n${result.skippedReasons.distinct().take(5).joinToString("\n")}"
                } else ""
                showMessage("订阅「$groupTag」已更新：${result.nodeCount} 个节点$skipHint")
            } catch (e: Exception) {
                Log.e(TAG, "Subscription refresh failed for $groupTag", e)
                showMessage("订阅更新失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                _refreshingGroups.value = _refreshingGroups.value - groupTag
            }
        }
    }

    fun importGlobalConfigFromUri(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                application.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            if (text.isBlank()) {
                showMessage("读取全局配置文件为空")
                return@launch
            }
            val parsed = NodeImportConverter.parse(text)
            val globalObj = parsed.extractedGlobalConfig ?: ConfigCodec.parse(text)
            if (globalObj == null || globalStore.importFromFullConfig(globalObj) == false) {
                showMessage("文件中缺少可识别的 dns / inbounds / routing 配置段")
                return@launch
            }
            onGlobalConfigUpdated(globalStore.file().absolutePath)
            RuleWorkspaceStore(application).importFromXrayRouting(globalObj)
            var importedNodes = 0
            if (parsed.nodes.isNotEmpty()) {
                importedNodes = mergeNodesAsGroupsOrLocal(parsed.nodes)
            }
            val suffix = if (importedNodes > 0) "，并同步导入 $importedNodes 个出站节点" else ""
            showMessage("已更新全局配置$suffix")
        }
    }

    fun openGlobalConfigEditor() {
        val file = globalStore.file()
        onEditFile(file.absolutePath)
    }

    fun openLuaScriptEditor(fileName: String) {
        val file = File(application.filesDir, fileName)
        if (file.exists()) {
            onEditFile(file.absolutePath)
        } else {
            showMessage("未找到脚本：$fileName")
        }
    }

    fun selectGroupMember(groupTag: String, member: GroupMemberRef) {
        scope.launch(Dispatchers.IO) {
            workspaceStore.update { current ->
                current.copy(
                    groups = current.groups.map { g ->
                        if (g.tag == groupTag && g.strategy == GroupStrategy.SELECT) {
                            g.copy(selectedMember = member)
                        } else {
                            g
                        }
                    }
                )
            }
            GroupRuntimeStatus.switchGroupMemberLive(groupTag, member)
        }
    }

    fun removeMemberFromGroup(groupTag: String, member: GroupMemberRef) {
        scope.launch(Dispatchers.IO) {
            workspaceStore.update { current ->
                current.copy(
                    groups = current.groups.map { g ->
                        if (g.tag != groupTag) {
                            g
                        } else {
                            val nextMembers = g.effectiveMembers().filterNot { it == member }
                            val nextNodes = if (member.isGroup == false) {
                                g.nodes.filterNot { it.tag == member.tag }
                            } else {
                                g.nodes
                            }
                            g.copy(
                                nodes = nextNodes,
                                members = nextMembers,
                                selectedMember = g.selectedMember?.takeIf { it in nextMembers }
                                    ?: nextMembers.firstOrNull(),
                            )
                        }
                    }
                )
            }
        }
    }

    fun moveMemberInGroup(groupTag: String, index: Int, delta: Int) {
        scope.launch(Dispatchers.IO) {
            workspaceStore.update { current ->
                current.copy(
                    groups = current.groups.map { g ->
                        if (g.tag != groupTag) {
                            g
                        } else {
                            val list = g.effectiveMembers().toMutableList()
                            val target = index + delta
                            if (index in list.indices && target in list.indices) {
                                val item = list.removeAt(index)
                                list.add(target, item)
                            }
                            g.copy(members = list)
                        }
                    }
                )
            }
        }
    }

    fun addMemberRefToGroup(groupTag: String, member: GroupMemberRef): String? {
        var err: String? = null
        workspaceStore.update { current ->
            val next = current.copy(
                groups = current.groups.map { g ->
                    if (g.tag != groupTag) {
                        g
                    } else {
                        val list = g.effectiveMembers()
                        if (member in list) g else g.copy(members = list + member)
                    }
                }
            )
            if (next.hasCycle()) {
                err = "添加嵌套组会形成循环引用，已取消"
                current
            } else {
                next
            }
        }
        return err
    }

    fun updateNodeTargetStrategy(nodeTag: String, strategy: String) {
        scope.launch(Dispatchers.IO) {
            workspaceStore.update { current ->
                current.copy(
                    groups = current.groups.map { g ->
                        g.copy(
                            nodes = g.nodes.map { n ->
                                if (n.tag != nodeTag) {
                                    n
                                } else {
                                    val updatedJson = runCatching {
                                        JSONObject(n.outboundJson).apply {
                                            if (strategy.isBlank()) remove("targetStrategy")
                                            else put("targetStrategy", strategy)
                                        }.toString()
                                    }.getOrDefault(n.outboundJson)
                                    n.copy(targetStrategy = strategy, outboundJson = updatedJson, userEdited = true)
                                }
                            }
                        )
                    }
                )
            }
        }
    }

    fun updateNodeJson(nodeTag: String, rawJson: String, udpOverTcpVersion: Int? = null,
        obfsMode: String? = null, obfsHost: String? = null): String? {
        val parsed = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return "JSON 格式无效"
        val protocol = parsed.optString("protocol").trim().lowercase()
        if (protocol.isEmpty()) return "缺少 protocol 字段"
        val newTag = parsed.optString("tag").trim().ifBlank { nodeTag }
        val workspace = workspaceStore.load()
        if (newTag != nodeTag && (newTag in BUILTIN_GROUP_TARGET_TAGS || workspace.nodeByTag(newTag) != null)) {
            return "节点名称已被使用：$newTag"
        }
        parsed.put("tag", newTag)
        val strategy = parsed.optString("targetStrategy")
        workspaceStore.update { current ->
            current.copy(
                groups = current.groups.map { g ->
                    g.copy(
                        frontProxy = g.frontProxy?.let { if (it.isGroup == false && it.tag == nodeTag) it.copy(tag = newTag) else it },
                        members = g.effectiveMembers().map { if (it.isGroup == false && it.tag == nodeTag) it.copy(tag = newTag) else it },
                        selectedMember = g.selectedMember?.let { if (it.isGroup == false && it.tag == nodeTag) it.copy(tag = newTag) else it },
                        nodes = g.nodes.map { n ->
                            if (n.tag == nodeTag) {
                                n.copy(
                                    tag = newTag,
                                    protocol = protocol,
                                    outboundJson = parsed.toString(),
                                    targetStrategy = strategy,
                                    userEdited = true,
                                    udpOverTcpVersion = udpOverTcpVersion ?: n.udpOverTcpVersion,
                                    obfsMode = obfsMode ?: n.obfsMode,
                                    obfsHost = obfsHost ?: n.obfsHost,
                                )
                            } else {
                                n
                            }
                        }
                    )
                }
            )
        }
        if (newTag != nodeTag && nodeTag !in workspace.effectiveRoutingBindings()) {
            RuleWorkspaceStore(application).update { rules ->
                rules.map { if (it.target == nodeTag) it.copy(target = newTag) else it }
            }
        }
        return null
    }

    fun addNodeToGroup(groupTag: String, rawJson: String): String? {
        val parsed = NodeImportConverter.parse(rawJson)
        if (parsed.nodes.isEmpty()) return parsed.skippedReasons.firstOrNull() ?: "请输入有效的 Xray 节点 JSON 或分享链接"
        workspaceStore.update { current ->
            val used = current.allNodes().map { it.tag }.toMutableSet()
            val nodes = parsed.nodes.map { node ->
                val tag = GroupWorkspace.uniqueTag(node.tag, used)
                node.copy(tag = tag, outboundJson = JSONObject(node.outboundJson).put("tag", tag).toString(), userEdited = true)
            }
            current.copy(groups = current.groups.map { group ->
                if (group.tag == groupTag) {
                    val members = group.effectiveMembers() + nodes.map { GroupMemberRef(it.tag) }
                    group.copy(nodes = group.nodes + nodes, members = members, selectedMember = group.selectedMember ?: members.firstOrNull())
                } else group
            })
        }
        return null
    }

    fun deleteNode(tag: String) {
        scope.launch(Dispatchers.IO) {
            workspaceStore.update { current ->
                current.copy(groups = current.groups.map { group ->
                    val members = group.effectiveMembers().filter { it.isGroup || it.tag != tag }
                    group.copy(
                        frontProxy = group.frontProxy?.takeUnless { it.isGroup == false && it.tag == tag },
                        nodes = group.nodes.filter { it.tag != tag },
                        members = members,
                        selectedMember = group.selectedMember?.takeIf { it in members } ?: members.firstOrNull(),
                    )
                })
            }
        }
    }

    private suspend fun importTextIntoGroup(groupTag: String, text: String) {
        val parsed = NodeImportConverter.parse(text)
        var updatedGlobal = false
        parsed.extractedGlobalConfig?.let { globalObj ->
            if (globalStore.importFromFullConfig(globalObj)) {
                RuleWorkspaceStore(application).importFromXrayRouting(globalObj)
                updatedGlobal = true
                onGlobalConfigUpdated(globalStore.file().absolutePath)
            }
        }
        if (parsed.nodes.isEmpty()) {
            if (updatedGlobal) {
                showMessage("已从文件更新全局配置（dns / inbounds / routing）")
            } else {
                showMessage("可转换节点为 0。\n${parsed.skippedReasons.distinct().take(5).joinToString("\n")}")
            }
            return
        }

        var addedCount = 0
        workspaceStore.update { current ->
            val usedTags = current.allNodes().map { it.tag }.toMutableSet()
            val dedupedNodes = parsed.nodes.map { node ->
                val unique = GroupWorkspace.uniqueTag(node.tag, usedTags)
                if (unique == node.tag) {
                    node.copy(userEdited = true)
                } else {
                    val json = JSONObject(node.outboundJson).put("tag", unique).toString()
                    node.copy(tag = unique, outboundJson = json, userEdited = true)
                }
            }
            addedCount = dedupedNodes.size
            val newRefs = dedupedNodes.map { GroupMemberRef(it.tag, isGroup = false) }
            current.copy(
                groups = current.groups.map { g ->
                    if (g.tag != groupTag) {
                        g
                    } else {
                        val nextMembers = (g.effectiveMembers() + newRefs).distinct()
                        g.copy(
                            nodes = g.nodes + dedupedNodes,
                            members = nextMembers,
                            selectedMember = g.selectedMember ?: nextMembers.firstOrNull(),
                        )
                    }
                }
            )
        }
        withContext(Dispatchers.Main) {
            val globalNote = if (updatedGlobal) "，已同步更新全局配置" else ""
            val skipNote = if (parsed.skippedCount > 0) {
                "（跳过 ${parsed.skippedCount} 条）\n${parsed.skippedReasons.distinct().take(5).joinToString("\n")}"
            } else ""
            showMessage("已导入 $addedCount 个节点到「$groupTag」$globalNote$skipNote")
        }
    }

    private fun mergeNodesAsGroupsOrLocal(nodes: List<ProxyNode>): Int {
        var added = 0
        workspaceStore.update { current ->
            val usedNodeTags = current.allNodes().map { it.tag }.toMutableSet()
            val usedGroupTags = current.groups.map { it.tag }.toMutableSet()
            val newGroups = mutableListOf<ProxyGroup>()
            for (node in nodes) {
                val uniqueNodeTag = GroupWorkspace.uniqueTag(node.tag, usedNodeTags)
                val normalizedNode = if (uniqueNodeTag == node.tag) {
                    node
                } else {
                    node.copy(
                        tag = uniqueNodeTag,
                        outboundJson = JSONObject(node.outboundJson).put("tag", uniqueNodeTag).toString(),
                    )
                }
                val groupTag = GroupWorkspace.uniqueTag(normalizedNode.tag, usedGroupTags)
                newGroups += ProxyGroup(
                    tag = groupTag,
                    sourceType = GroupSourceType.LOCAL,
                    strategy = GroupStrategy.FALLBACK,
                    nodes = listOf(normalizedNode),
                    members = listOf(GroupMemberRef(normalizedNode.tag, isGroup = false)),
                )
                added++
            }
            current.copy(
                groups = current.groups + newGroups,
                defaultGroupTag = current.defaultGroupTag ?: newGroups.firstOrNull()?.tag,
            )
        }
        return added
    }
}
