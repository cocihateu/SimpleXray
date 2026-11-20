package com.simplexray.re.common.config

import android.content.Context
import com.simplexray.re.prefs.LogLevel
import com.simplexray.re.prefs.Preferences
import org.json.JSONObject

internal class RuntimeLogSettings(context: Context) {
    private val prefs = Preferences(context)
    private val globalStore = GlobalConfigStore(context)

    fun appLevel(): LogLevel {
        val configured = prefs.logLevel
        if (configured != LogLevel.Auto) return configured
        val global = globalStore.loadJson().optJSONObject("log")?.optString("loglevel", "warning")
        return LogLevel.fromString(global ?: "warning").takeUnless { it == LogLevel.Auto }
            ?: LogLevel.Warning
    }

    fun applyTo(root: JSONObject) {
        val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
        // Keep collection stable while App persistence and dashboard streams filter independently.
        log.put("loglevel", LogLevel.Debug.value)
    }
}
