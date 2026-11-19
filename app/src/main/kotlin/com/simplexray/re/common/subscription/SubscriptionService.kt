package com.simplexray.re.common.subscription

import android.content.Context
import android.net.Uri
import android.util.Log
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.GroupWorkspaceStore
import com.simplexray.re.common.config.ProxyNode
import com.simplexray.re.prefs.Preferences
import com.simplexray.re.service.VpnRunningState
import com.simplexray.re.service.VpnStateHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

enum class SubscriptionDownloadPolicy {
    AUTO,
    DIRECT,
    PROXY,
}

enum class SubscriptionErrorCategory {
    NETWORK_OR_HTTP,
    NO_SUPPORTED_NODES,
    GROUP_MISSING,
    INVALID_URL,
    STORAGE_ERROR,
}

class SubscriptionException(
    val code: Int,
    val category: SubscriptionErrorCategory,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class SubscriptionRefreshResult(
    val groupTag: String,
    val nodes: List<ProxyNode>,
    val nodeCount: Int,
    val skippedCount: Int,
    val skippedReasons: List<String>,
    val messages: List<String> = emptyList(),
    val extractedProxyGroups: List<JSONObject> = emptyList(),
)

class SubscriptionService(context: Context) {
    private val appContext: Context = context.applicationContext
    private val workspaceStore: GroupWorkspaceStore = GroupWorkspaceStore(appContext)
    private val prefs: Preferences = Preferences(appContext)
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun refresh(
        groupTag: String,
        downloadPolicy: SubscriptionDownloadPolicy = SubscriptionDownloadPolicy.AUTO,
    ): SubscriptionRefreshResult = withContext(Dispatchers.IO) {
        val ws = workspaceStore.load()
        val group = ws.groupByTag(groupTag)
            ?: throw SubscriptionException(
                code = 404,
                category = SubscriptionErrorCategory.GROUP_MISSING,
                message = "目标分组缺失：$groupTag",
            )

        val url = group.subscriptionUrl.trim()
        if (url.isEmpty()) {
            throw SubscriptionException(
                code = 400,
                category = SubscriptionErrorCategory.INVALID_URL,
                message = "订阅链接缺失：$groupTag",
            )
        }

        val body = downloadContent(url, downloadPolicy)
        val parsed = NodeImportConverter.parse(body)
        if (parsed.nodes.isEmpty()) {
            val reasonsSummary = parsed.skippedReasons.distinct().take(5).joinToString("; ")
            throw SubscriptionException(
                code = 422,
                category = SubscriptionErrorCategory.NO_SUPPORTED_NODES,
                message = reasonsSummary.ifBlank { "订阅有效节点数为零，已跳过全部条目" },
            )
        }

        var resultNodes: List<ProxyNode> = emptyList()
        workspaceStore.update { current ->
            val existing = current.groupByTag(groupTag)
                ?: throw SubscriptionException(
                    code = 404,
                    category = SubscriptionErrorCategory.GROUP_MISSING,
                    message = "目标分组缺失：$groupTag",
                )

            val otherTags = current.groups
                .filter { it.tag != groupTag }
                .flatMap { it.nodes }
                .map { it.tag }
                .toMutableSet()
            val existingMap = existing.nodes.associateBy { it.subscriptionName.ifBlank { it.tag } }

            val uniqueNodes = parsed.nodes.map { node ->
                val oldNode = existingMap[node.tag]
                val tagToUse = if (oldNode != null && oldNode.tag !in otherTags) {
                    oldNode.tag
                } else {
                    GroupWorkspace.uniqueTag(node.tag, otherTags)
                }
                otherTags.add(tagToUse)
                if (oldNode?.userEdited == true) {
                    return@map oldNode.copy(subscriptionName = node.tag)
                }
                val targetStrategy = oldNode?.targetStrategy?.takeIf { it.isNotBlank() } ?: node.targetStrategy

                val json = if (tagToUse == node.tag) {
                    node.outboundJson
                } else {
                    runCatching {
                        JSONObject(node.outboundJson).put("tag", tagToUse).toString()
                    }.getOrDefault(node.outboundJson)
                }
                node.copy(tag = tagToUse, outboundJson = json, targetStrategy = targetStrategy, subscriptionName = node.tag)
            }

            resultNodes = uniqueNodes
            val newNodeRefs = uniqueNodes.map { GroupMemberRef(it.tag, isGroup = false) }
            val ownedTags = existing.nodes.map { it.tag }.toSet()
            val preservedMembers = existing.effectiveMembers().filter {
                it.isGroup || it.tag !in ownedTags || it in newNodeRefs
            }
            val mergedMembers = (preservedMembers + newNodeRefs).distinct()

            val selectedMember = existing.selectedMember?.takeIf { it in mergedMembers }
                ?: mergedMembers.firstOrNull()

            val updatedGroup = existing.copy(
                nodes = uniqueNodes,
                members = mergedMembers,
                selectedMember = selectedMember,
                subscriptionUpdatedAtMs = System.currentTimeMillis(),
            )

            val updated = current.copy(
                groups = current.groups.map { if (it.tag == groupTag) updatedGroup else it }
            )
            if (existing.subscriptionUpdatedAtMs == 0L) {
                SubscriptionGroups.merge(updated, groupTag, parsed.extractedProxyGroups,
                    uniqueNodes.associate { it.subscriptionName to it.tag })
            } else updated
        }

        val messages = mutableListOf<String>()
        messages += "已更新 $groupTag：共 ${resultNodes.size} 个有效节点"
        if (parsed.skippedCount > 0) {
            messages += "跳过 ${parsed.skippedCount} 条节点"
        }

        SubscriptionRefreshResult(
            groupTag = groupTag,
            nodes = resultNodes,
            nodeCount = resultNodes.size,
            skippedCount = parsed.skippedCount,
            skippedReasons = parsed.skippedReasons,
            messages = messages,
            extractedProxyGroups = parsed.extractedProxyGroups,
        )
    }

    suspend fun downloadContent(
        url: String,
        policy: SubscriptionDownloadPolicy = SubscriptionDownloadPolicy.AUTO,
    ): String = withContext(Dispatchers.IO) {
        val sanitized = sanitizeUrl(url)
        val isVpnConnected = VpnStateHub.state.value == VpnRunningState.Connected

        fun buildClient(useProxy: Boolean): OkHttpClient {
            return if (useProxy) {
                httpClient.newBuilder()
                    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(prefs.socksAddress, prefs.socksPort)))
                    .build()
            } else {
                httpClient
            }
        }

        fun executeCall(client: OkHttpClient): String {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "clash-verge/v1.7.7 mihomo/1.19.34 SimpleXray")
                .build()
            val response = client.newCall(req).execute()
            return response.use { resp ->
                if (resp.isSuccessful == false) {
                    throw SubscriptionException(
                        code = if (resp.code in 400..599) resp.code else 502,
                        category = SubscriptionErrorCategory.NETWORK_OR_HTTP,
                        message = "订阅拉取 HTTP 状态码异常：${resp.code}",
                    )
                }
                resp.body?.string().orEmpty()
            }
        }

        when (policy) {
            SubscriptionDownloadPolicy.DIRECT -> {
                try {
                    executeCall(buildClient(false))
                } catch (e: SubscriptionException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Direct download failed for $sanitized: ${e.message}")
                    throw SubscriptionException(
                        code = 502,
                        category = SubscriptionErrorCategory.NETWORK_OR_HTTP,
                        message = "直连拉取失败：${e.message ?: e.javaClass.simpleName}",
                        cause = e,
                    )
                }
            }

            SubscriptionDownloadPolicy.PROXY -> {
                try {
                    executeCall(buildClient(true))
                } catch (e: SubscriptionException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Proxy download failed for $sanitized: ${e.message}")
                    throw SubscriptionException(
                        code = 502,
                        category = SubscriptionErrorCategory.NETWORK_OR_HTTP,
                        message = "代理拉取失败：${e.message ?: e.javaClass.simpleName}",
                        cause = e,
                    )
                }
            }

            SubscriptionDownloadPolicy.AUTO -> {
                if (isVpnConnected) {
                    try {
                        executeCall(buildClient(true))
                    } catch (e: Exception) {
                        Log.w(TAG, "Proxy fetch failed for $sanitized, falling back to direct: ${e.message}")
                        try {
                            executeCall(buildClient(false))
                        } catch (e2: SubscriptionException) {
                            throw e2
                        } catch (e2: Exception) {
                            Log.w(TAG, "Direct fallback failed for $sanitized: ${e2.message}")
                            throw SubscriptionException(
                                code = 502,
                                category = SubscriptionErrorCategory.NETWORK_OR_HTTP,
                                message = "代理及直连重试均失败：${e2.message ?: e2.javaClass.simpleName}",
                                cause = e2,
                            )
                        }
                    }
                } else {
                    try {
                        executeCall(buildClient(false))
                    } catch (e: SubscriptionException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Direct fetch failed for $sanitized: ${e.message}")
                        throw SubscriptionException(
                            code = 502,
                            category = SubscriptionErrorCategory.NETWORK_OR_HTTP,
                            message = "直连拉取失败：${e.message ?: e.javaClass.simpleName}",
                            cause = e,
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "SubscriptionService"

        fun sanitizeUrl(rawUrl: String): String {
            return runCatching {
                val uri = Uri.parse(rawUrl)
                val scheme = uri.scheme ?: "https"
                val host = uri.host.orEmpty()
                val path = uri.path.orEmpty()
                if (host.isEmpty()) return "***"
                if (uri.query.isNullOrEmpty()) "$scheme://$host$path" else "$scheme://$host$path?***"
            }.getOrDefault("***")
        }
    }
}
