package com.simplexray.re.common

import com.simplexray.re.prefs.LogLevel

internal data class LogLine(val level: LogLevel, val message: String) {
    fun meets(minimum: LogLevel): Boolean = level.ordinal >= minimum.ordinal

    companion object {
        private val timestamp = Regex("""^\d{4}/\d{2}/\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?\s+""")
        private val severity = Regex("""^\[(Debug|Info|Warning|Warn|Error|Fatal)\]\s*""", RegexOption.IGNORE_CASE)
        private val sessionId = Regex("""^\[\d+\]\s*""")

        fun parse(line: String): LogLine {
            val content = timestamp.replaceFirst(line, "")
            val match = severity.find(content)
            val level = when (match?.groupValues?.get(1)?.lowercase()) {
                "debug" -> LogLevel.Debug
                "warning", "warn" -> LogLevel.Warning
                "error", "fatal" -> LogLevel.Error
                else -> LogLevel.Info
            }
            val message = match?.let { content.removeRange(it.range) } ?: content
            return LogLine(level, sessionId.replaceFirst(message, ""))
        }
    }
}
