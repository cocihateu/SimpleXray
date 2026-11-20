package com.simplexray.re.service

import android.content.Context
import android.content.SharedPreferences
import com.simplexray.re.prefs.LogLevel
import java.util.UUID

class ClashApiPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var logLevel: LogLevel
        get() = LogLevel.fromString(prefs.getString(KEY_LOG_LEVEL, "error") ?: "error")
        set(value) = prefs.edit().putString(KEY_LOG_LEVEL, value.value).apply()

    var secret: String
        get() {
            val existing = prefs.getString(KEY_SECRET, null)
            if (!existing.isNullOrBlank()) {
                return existing
            }
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_SECRET, generated).apply()
            return generated
        }
        set(value) = prefs.edit().putString(KEY_SECRET, value).apply()

    fun resetSecret(): String {
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_SECRET, generated).apply()
        return generated
    }

    companion object {
        private const val PREFS_NAME = "simplexray_clash_api_prefs"
        private const val KEY_ENABLED = "clash_api_enabled"
        private const val KEY_PORT = "clash_api_port"
        private const val KEY_SECRET = "clash_api_secret"
        private const val KEY_LOG_LEVEL = "clash_api_log_level"

        const val DEFAULT_ENABLED = false
        const val DEFAULT_PORT = 9090
    }
}
