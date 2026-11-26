package com.simplexray.re.service

import java.io.IOException
import java.net.SocketTimeoutException

internal object FallbackPolicy {
    const val MAX_DIAL_ATTEMPTS = 3
    const val HOST_WINDOW_MS = 60_000L
    const val HOST_THRESHOLD = 3
    const val BASE_COOLDOWN_MS = 30_000L
    const val MAX_COOLDOWN_MS = 300_000L
    const val RECOVERY_STABLE_MS = 300_000L
    const val CLOSE_SAMPLE_DELAY_MS = 2_000L
    const val STRONG_CHECK_GAP_MS = 3_000L
    const val WEAK_CHECK_GAP_MS = 15_000L

    fun checkGapMs(strong: Boolean): Long = if (strong) STRONG_CHECK_GAP_MS else WEAK_CHECK_GAP_MS

    fun stallTimeoutMs(latencyMs: Long?): Int =
        ((latencyMs ?: 0L) * 3L + 2_000L).coerceIn(4_000L, 10_000L).toInt()

    fun isStrongFailure(error: IOException): Boolean {
        val message = error.message.orEmpty().lowercase()
        return error is SocketTimeoutException || listOf(
            "connection reset", "broken pipe", "connection refused", "i/o timeout", "timed out",
        ).any(message::contains)
    }
}
