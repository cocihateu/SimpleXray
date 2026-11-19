package com.simplexray.re.viewmodel

import android.content.Context
import com.simplexray.re.common.config.RuleWorkspaceStore
import com.simplexray.re.common.config.TrafficRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal class TrafficRulesController(context: Context, private val scope: CoroutineScope) {
    private val store = RuleWorkspaceStore(context)
    val rules = store.observe().stateIn(scope, SharingStarted.Eagerly, emptyList())
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
}
