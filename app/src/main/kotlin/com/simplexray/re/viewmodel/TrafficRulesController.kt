package com.simplexray.re.viewmodel

import android.content.Context
import android.net.Uri
import com.simplexray.re.common.config.RuleWorkspaceStore
import com.simplexray.re.common.config.TrafficRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal class TrafficRulesController(private val context: Context, private val scope: CoroutineScope) {
    private val store = RuleWorkspaceStore(context)
    val rules = store.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun save(rule: TrafficRule) = scope.launch(Dispatchers.IO) {
        store.update { rows ->
            if (rows.any { it.id == rule.id }) rows.map { if (it.id == rule.id) rule else it }
            else rows.toMutableList().apply { add(indexOfFirst { it.type == "Match" }.takeIf { it >= 0 } ?: size, rule) }
        }
    }
    fun delete(id: String) = scope.launch(Dispatchers.IO) { store.update { it.filter { row -> row.id != id } } }
    fun move(id: String, delta: Int) = scope.launch(Dispatchers.IO) {
        store.update { rows ->
            rows.toMutableList().apply {
                val from = indexOfFirst { it.id == id }
                val to = from + delta
                if (from in indices && to in indices) add(to, removeAt(from))
            }
        }
    }

    fun reorderRules(ids: List<String>) = scope.launch(Dispatchers.IO) {
        store.update { rows ->
            val ruleMap = rows.associateBy { it.id }
            val reordered = ids.mapNotNull { ruleMap[it] }.toMutableList()
            val remaining = rows.filter { it.id !in ids }
            reordered.addAll(remaining)
            reordered
        }
    }

    fun importRules(uri: Uri) = scope.launch(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text.isNullOrBlank()) {
                _message.value = "导入内容为空"
                return@launch
            }
            val trimmed = text.trim()
            val parsedList = mutableListOf<TrafficRule>()

            fun parseRuleItem(obj: JSONObject): List<TrafficRule> {
                val isXrayField = obj.optString("type") == "field" ||
                    obj.has("outboundTag") ||
                    (!obj.has("payload") && (obj.has("domain") || obj.has("ip") || obj.has("port") || obj.has("network")))
                return if (isXrayField) {
                    TrafficRule.fromXrayRule(obj)
                } else {
                    listOf(TrafficRule.fromJson(obj))
                }
            }

            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    parsedList.addAll(parseRuleItem(item))
                }
            } else {
                val root = JSONObject(trimmed)
                val routingRules = root.optJSONObject("routing")?.optJSONArray("rules")
                if (routingRules != null) {
                    for (i in 0 until routingRules.length()) {
                        val item = routingRules.optJSONObject(i) ?: continue
                        parsedList.addAll(TrafficRule.fromXrayRule(item))
                    }
                } else {
                    val array = root.optJSONArray("rules")
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i) ?: continue
                            parsedList.addAll(parseRuleItem(item))
                        }
                    } else if (root.has("type") || root.has("payload") || root.has("outboundTag")) {
                        parsedList.addAll(parseRuleItem(root))
                    }
                }
            }
            if (parsedList.isEmpty()) {
                _message.value = "未找到可导入的规则"
                return@launch
            }
            store.save(parsedList)
            _message.value = "已导入 ${parsedList.size} 条规则，替换原规则列表"
        } catch (e: Exception) {
            _message.value = "导入失败：${e.message ?: "解析错误"}"
        }
    }

    fun exportRules(uri: Uri) = scope.launch(Dispatchers.IO) {
        try {
            val current = store.load()
            val root = JSONObject()
            root.put("version", 1)
            val array = JSONArray()
            for (rule in current) {
                array.put(rule.toJson())
            }
            root.put("rules", array)
            val stream = context.contentResolver.openOutputStream(uri) ?: error("无法打开文件进行写入")
            stream.bufferedWriter().use { writer ->
                writer.write(root.toString(2))
            }
            _message.value = "已导出 ${current.size} 条规则"
        } catch (e: Exception) {
            _message.value = "导出失败：${e.message ?: "写入错误"}"
        }
    }

}
