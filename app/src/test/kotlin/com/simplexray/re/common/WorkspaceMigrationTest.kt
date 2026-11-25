package com.simplexray.re.common

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.simplexray.re.common.config.GroupWorkspaceStore
import com.simplexray.re.common.config.RuleWorkspaceStore
import com.simplexray.re.prefs.FakeSharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceMigrationTest {
    @get:Rule val folder = TemporaryFolder()

    private fun context(): Context = object : ContextWrapper(null) {
        private val preferences = mutableMapOf<String?, FakeSharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = folder.root
        override fun getPackageName(): String = "com.LucX"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            preferences.getOrPut(name) { FakeSharedPreferences() }
    }

    private fun group(tag: String, aliases: List<String>? = emptyList()): JSONObject = JSONObject()
        .put("tag", tag).put("strategy", "FALLBACK").put("sourceType", "LOCAL")
        .put("members", JSONArray().put(JSONObject().put("tag", "primary").put("isGroup", false))
            .put(JSONObject().put("tag", "backup").put("isGroup", false)))
        .put("nodes", JSONArray()).apply { if (aliases != null) put("routingTargets", JSONArray(aliases)) }

    private fun writeGroups(vararg groups: JSONObject): File = File(folder.root, GroupWorkspaceStore.WORKSPACE_FILENAME).apply {
        writeText(JSONObject().put("groups", JSONArray(groups.toList())).toString())
    }

    private fun writeRules(vararg targets: String, version: Int = 1): File = File(folder.root, RuleWorkspaceStore.WORKSPACE_FILENAME).apply {
        writeText(JSONObject().put("version", version).put("rules", JSONArray(targets.mapIndexed { i, target ->
            JSONObject().put("id", "r$i").put("type", "Domain").put("payload", "example$i.com").put("target", target)
        })).toString())
    }

    private fun targets(file: File): List<String> = JSONObject(file.readText()).getJSONArray("rules").let { array ->
        (0 until array.length()).map { array.getJSONObject(it).getString("target") }
    }

    @Test fun explicitAliasesAreRewrittenOnceAndBuiltinsKeepTheirMeaning() {
        val groups = writeGroups(group("group-a", listOf("media", "GLOBAL", "DIRECT", "REJECT", "direct", "block")))
        val rules = writeRules("media", "group-a", "GLOBAL", "DIRECT", "REJECT", "direct", "block")
        val store = GroupWorkspaceStore(context())
        assertEquals("group-a", store.load().groups.single().tag)
        assertEquals(listOf("group-a", "group-a", "GLOBAL", "DIRECT", "REJECT", "direct", "block"), targets(rules))
        assertFalse(JSONObject(groups.readText()).getJSONArray("groups").getJSONObject(0).has("routingTargets"))
        val firstRules = rules.readText()
        store.load()
        assertEquals(firstRules, rules.readText())
    }

    @Test fun missingLegacyFieldInfersOnlyAnUnambiguousPrimaryOwner() {
        writeGroups(group("fallback", aliases = null))
        val rules = writeRules("primary", "backup")
        GroupWorkspaceStore(context()).load()
        assertEquals(listOf("fallback", "backup"), targets(rules))
    }

    @Test fun explicitEmptyAliasesNeverInferAPrimaryAlias() {
        writeGroups(group("fallback"))
        val rules = writeRules("primary")
        GroupWorkspaceStore(context()).load()
        assertEquals(listOf("primary"), targets(rules))
    }

    @Test fun twoFallbackGroupsWithTheSamePrimaryDoNotInferAnOwner() {
        writeGroups(group("a", aliases = null), group("b", aliases = null))
        val rules = writeRules("primary")
        GroupWorkspaceStore(context()).load()
        assertEquals(listOf("primary"), targets(rules))
    }

    @Test fun interruptedMigrationDoesNotRemapAGroupNameThroughAnotherAlias() {
        writeGroups(group("a", listOf("old")), group("b", listOf("a")))
        val rules = writeRules("a", version = 2)
        GroupWorkspaceStore(context()).load()
        assertEquals(listOf("a"), targets(rules))
    }

    @Test fun corruptRulesCannotCauseAliasesToBeDiscarded() {
        val groups = writeGroups(group("a", listOf("old")))
        val before = groups.readText()
        val rules = File(folder.root, RuleWorkspaceStore.WORKSPACE_FILENAME).apply { writeText("{broken") }
        runCatching { GroupWorkspaceStore(context()).load() }
        assertEquals("Migration must keep the source map until rules were written successfully", before, groups.readText())
        assertEquals("{broken", rules.readText())
    }

    @Test fun newerWorkspaceSchemaIsNotRewrittenOrDowngraded() {
        val groups = writeGroups(group("a"))
        val data = JSONObject(groups.readText()).put("version", 99).put("futureField", true)
        groups.writeText(data.toString())
        val before = groups.readText()
        val store = GroupWorkspaceStore(context())
        assertEquals("a", store.load().groups.single().tag)
        val result = runCatching { store.update { it.copy(routingMode = "direct") } }
        assertTrue("An older writer must reject a newer schema", result.isFailure)
        assertEquals(before, groups.readText())
    }

    @Test fun currentSchemaDoesNotInferAliasesAfterUpgrade() {
        val groups = writeGroups(group("a", aliases = null))
        groups.writeText(JSONObject(groups.readText()).put("version", 1).toString())
        val rules = writeRules("primary", version = 2)
        GroupWorkspaceStore(context()).load()
        assertEquals(listOf("primary"), targets(rules))
    }
}
