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

private const val TAG = "RuleWorkspaceStore"

public class RuleWorkspaceStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, WORKSPACE_FILENAME)
    private val globalConfigStore = GlobalConfigStore(appContext)

    var migrationNotice: String? = null
        private set

    fun load(): List<TrafficRule> {
        synchronized(lock) {
            GroupWorkspaceStore(appContext).load()
            ensureInitialized()
            val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
            val (rules, notice) = runCatching { decode(raw) }.getOrElse { e ->
                Log.e(TAG, "Error decoding rules workspace", e)
                emptyList<TrafficRule>() to null
            }
            migrationNotice = notice
            return rules
        }
    }

    fun save(rules: List<TrafficRule>) {
        synchronized(lock) {
            GroupWorkspaceStore(appContext).load()
            WorkspaceFiles.requireWritable(file, SCHEMA_VERSION)
            writeAtomic(encode(rules, migrationNotice))
            _version.value++
        }
    }

    /**
     * One-time step of the routing-alias removal, called by [GroupWorkspaceStore] before it drops the
     * stored aliases: points every rule that targets an alias at the owning group and stamps schema 2.
     * A file already at schema 2 is left alone, so an interrupted migration is never applied twice.
     */
    internal fun migrateRoutingTargets(targets: Map<String, String>) {
        synchronized(lock) {
            // Do not discard the alias map when reading or decoding rules fails. Propagate the
            // error so GroupWorkspaceStore keeps the old group file intact and can retry later.
            val raw = if (file.exists()) file.readText() else null
            if (raw != null && raw.trimStart().startsWith("{") &&
                JSONObject(raw).optInt("version") >= SCHEMA_VERSION
            ) return
            val (rules, notice) = if (raw == null) migrateRules() else decode(raw)
            migrationNotice = notice
            writeAtomic(encode(rules.map { rule ->
                targets[rule.target]?.let { rule.copy(target = it) } ?: rule
            }, notice))
            _version.value++
        }
    }

    fun update(transform: (List<TrafficRule>) -> List<TrafficRule>): List<TrafficRule> {
        synchronized(lock) {
            val current = load()
            val updated = transform(current)
            save(updated)
            return updated
        }
    }

    fun observe(): Flow<List<TrafficRule>> = version
        .map { load() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    private fun ensureInitialized() {
        if (file.isFile && file.length() > 0L) return
        synchronized(lock) {
            if (file.isFile && file.length() > 0L) return
            val (migratedRules, notice) = migrateRules()
            migrationNotice = notice
            writeAtomic(encode(migratedRules, notice))
            _version.value++
        }
    }

    private fun migrateRules(): Pair<List<TrafficRule>, String?> {
        // 1. Check GlobalConfigStore routing
        val globalRoot = runCatching { globalConfigStore.loadJson() }.getOrNull()
        val routing = globalRoot?.optJSONObject("routing")
        val scriptName = routing?.optString("script")?.takeIf { it.isNotBlank() }

        if (scriptName != null) {
            val luaFile = findLuaScriptFile(scriptName)
            if (luaFile != null && luaFile.isFile) {
                val luaText = runCatching { luaFile.readText() }.getOrNull()
                if (!luaText.isNullOrBlank()) {
                    val result = LegacyLuaRuleImporter.importFromLua(luaText)
                    if (result.rules.isNotEmpty()) {
                        Log.i(TAG, "Migrated ${result.rules.size} rules from Lua script: ${luaFile.name}")
                        return result.rules to result.migrationNotice
                    }
                }
            }
        }

        // 2. Check routing.rules in GlobalConfigStore
        val xrayRules = routing?.optJSONArray("rules")
        if (xrayRules != null && xrayRules.length() > 0) {
            val rules = parseXrayRulesArray(xrayRules)
            if (rules.isNotEmpty()) {
                Log.i(TAG, "Migrated ${rules.size} rules from global routing.rules")
                return rules to null
            }
        }

        // 3. Check legacy full config candidates
        val candidates = findCandidateConfigFiles()
        for (candidate in candidates) {
            if (!candidate.isFile) continue
            val text = runCatching { candidate.readText() }.getOrNull() ?: continue
            val root = runCatching { ConfigCodec.parse(text) }.getOrNull() ?: continue
            val candRouting = root.optJSONObject("routing") ?: continue

            val candScript = candRouting.optString("script").takeIf { it.isNotBlank() }
            if (candScript != null) {
                val luaFile = findLuaScriptFile(candScript)
                if (luaFile != null && luaFile.isFile) {
                    val luaText = runCatching { luaFile.readText() }.getOrNull()
                    if (!luaText.isNullOrBlank()) {
                        val result = LegacyLuaRuleImporter.importFromLua(luaText)
                        if (result.rules.isNotEmpty()) {
                            Log.i(TAG, "Migrated rules from candidate Lua script: ${luaFile.name}")
                            return result.rules to result.migrationNotice
                        }
                    }
                }
            }

            val candRules = candRouting.optJSONArray("rules")
            if (candRules != null && candRules.length() > 0) {
                val rules = parseXrayRulesArray(candRules)
                if (rules.isNotEmpty()) {
                    Log.i(TAG, "Migrated rules from candidate config: ${candidate.name}")
                    return rules to null
                }
            }
        }

        // 4. Default fallback rules
        return LegacyLuaRuleImporter.defaultFallbackRules() to null
    }

    private fun findLuaScriptFile(name: String): File? {
        val target = File(appContext.filesDir, name)
        if (target.isFile) return target

        val files = appContext.filesDir.listFiles { f ->
            f.isFile && f.name.equals(name, ignoreCase = true)
        }
        if (!files.isNullOrEmpty()) return files.first()

        val anyLua = appContext.filesDir.listFiles { f ->
            f.isFile && f.name.endsWith(".lua", ignoreCase = true)
        }
        return anyLua?.firstOrNull()
    }

    private fun findCandidateConfigFiles(): List<File> {
        val prefs = Preferences(appContext)
        return buildList {
            prefs.selectedConfigPath?.let { add(File(it)) }
            appContext.filesDir.listFiles { f ->
                f.isFile &&
                    f.name != GlobalConfigStore.GLOBAL_CONFIG_FILENAME &&
                    f.name != GroupWorkspaceStore.WORKSPACE_FILENAME &&
                    f.name != WORKSPACE_FILENAME &&
                    f.name != "extra_api.json" &&
                    (f.name.endsWith(".json", ignoreCase = true) ||
                        f.name.endsWith(".yaml", ignoreCase = true) ||
                        f.name.endsWith(".yml", ignoreCase = true))
            }?.sortedByDescending { it.lastModified() }?.let { addAll(it) }
        }.distinctBy { it.absolutePath }
    }

    private fun parseXrayRulesArray(rulesArray: JSONArray): List<TrafficRule> {
        val rules = mutableListOf<TrafficRule>()
        for (i in 0 until rulesArray.length()) {
            val ruleObj = rulesArray.optJSONObject(i) ?: continue
            rules.addAll(TrafficRule.fromXrayRule(ruleObj))
        }
        if (rules.none { it.type == TrafficRule.TYPE_MATCH }) {
            rules.add(TrafficRule(type = TrafficRule.TYPE_MATCH, payload = "", target = TrafficRule.TARGET_GLOBAL))
        }
        return rules
    }

    private fun writeAtomic(content: String) = synchronized(lock) {
        WorkspaceFiles.write(file, content)
    }

    private fun encode(rules: List<TrafficRule>, notice: String?): String {
        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        if (!notice.isNullOrBlank()) {
            root.put("migrationNotice", notice)
        }
        val array = JSONArray()
        for (rule in rules) {
            array.put(rule.toJson())
        }
        root.put("rules", array)
        return root.toString(2)
    }

    private fun decode(content: String): Pair<List<TrafficRule>, String?> {
        val trimmed = content.trim()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            val rules = mutableListOf<TrafficRule>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                rules.add(TrafficRule.fromJson(obj))
            }
            return rules to null
        }
        val root = JSONObject(trimmed)
        val notice = root.optString("migrationNotice").takeIf { it.isNotBlank() }
        val array = root.optJSONArray("rules") ?: JSONArray()
        val rules = mutableListOf<TrafficRule>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            rules.add(TrafficRule.fromJson(obj))
        }
        return rules to notice
    }

    companion object {
        const val WORKSPACE_FILENAME = "rules_workspace.json"
        private const val SCHEMA_VERSION = 2
        private val lock = GroupWorkspaceStore.lock
        private val _version = MutableStateFlow(0L)
        val version: StateFlow<Long> = _version.asStateFlow()
    }
}
