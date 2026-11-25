package com.simplexray.re.common

import com.simplexray.re.common.config.ConfigInjector
import com.simplexray.re.common.config.ConfigSanitizer
import com.simplexray.re.prefs.Preferences
import org.json.JSONException

object ConfigUtils {
    fun extractTunMtu(configContent: String): Int? =
        ConfigInjector.extractTunMtu(configContent)

    fun sanitizeConfig(content: String, prefs: Preferences): String =
        ConfigSanitizer.sanitize(content, prefs)

    @Throws(JSONException::class)
    internal fun injectStatsServiceIntoSanitized(prefs: Preferences, sanitizedConfigContent: String): String =
        ConfigInjector.injectStatsServiceIntoSanitized(prefs, sanitizedConfigContent)

    fun extractHttpProxyEndpoint(configContent: String): Pair<String, Int>? =
        ConfigInjector.extractHttpProxyEndpoint(configContent)

    fun extractPrimarySocksCredentials(configContent: String): Pair<String, String>? =
        ConfigInjector.extractPrimarySocksCredentials(configContent)

    fun extractPortsFromJson(jsonContent: String): Set<Int> =
        ConfigInjector.extractPortsFromJson(jsonContent)

}
