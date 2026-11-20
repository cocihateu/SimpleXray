package com.simplexray.re.common.config

import android.content.Context
import com.simplexray.re.prefs.LogLevel
import com.simplexray.re.prefs.Preferences
import com.simplexray.re.service.ClashApiPreferences
import org.json.JSONObject

internal class RuntimeLogSettings(context: Context) {
    private val prefs = Preferences(context)
    private val dashboardPrefs = ClashApiPreferences(context)
    private val globalStore = GlobalConfigStore(context)

    fun appLevel(): LogLevel {
        val configured = prefs.logLevel
        if (configured != LogLevel.Auto) return configured
        val global = globalStore.loadJson().optJSONObject("log")?.optString("loglevel", "warning")
        return LogLevel.fromString(global ?: "warning").takeUnless { it == LogLevel.Auto }
            ?: LogLevel.Warning
    }

    fun coreLevel(): LogLevel {
        val app = appLevel()
        val dashboard = if (dashboardPrefs.enabled) {
            minOf(dashboardPrefs.logLevel, dashboardStreamLevel)
        } else LogLevel.None
        return minOf(app, dashboard)
    }

    fun applyTo(root: JSONObject) {
        val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
        log.put("loglevel", coreLevel().value)
    }

    companion object {
        @Volatile
        var dashboardStreamLevel: LogLevel = LogLevel.None
    }
}
