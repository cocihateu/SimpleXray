package com.simplexray.re.service

import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

internal object HttpLatencyMeasurement {
    // Redirects can be captive portals, and 407/4xx/5xx only prove a proxy or error
    // server answered. A health endpoint must itself return a successful response.
    internal fun isHealthyStatus(code: Int): Boolean = code in 200..299

    internal fun boundedTimeoutMs(requested: Int): Long =
        if (requested <= 0) 5_000L else requested.toLong().coerceAtMost(60_000L)

    suspend fun measure(
        client: OkHttpClient,
        url: String,
        timeoutMs: Int = 5_000,
        nowElapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    ): Long? {
        val request = try { Request.Builder().url(url).head().build() } catch (_: IllegalArgumentException) { return null }
        val deadlineMs = boundedTimeoutMs(timeoutMs)
        val pool = ConnectionPool(1, 1, TimeUnit.SECONDS)
        try {
            val latencyMs = AtomicLong(-1L)
            val measurementClient = client.newBuilder()
                .connectionPool(pool)
                .callTimeout(deadlineMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                // Retain the caller's TLS trust manager and hostname verifier unchanged.
                .addInterceptor { chain ->
                    val startedAt = nowElapsedRealtimeMs()
                    val response = chain.proceed(chain.request())
                    latencyMs.set((nowElapsedRealtimeMs() - startedAt).coerceAtLeast(1L))
                    response
                }
                .build()
            return withTimeoutOrNull(deadlineMs) {
                suspendCancellableCoroutine<Long?> { continuation ->
                    val call = measurementClient.newCall(request)
                    val completed = AtomicBoolean(false)
                    continuation.invokeOnCancellation {
                        completed.set(true)
                        call.cancel()
                    }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (completed.compareAndSet(false, true)) continuation.resume(null)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.use {
                                val result = latencyMs.get().takeIf { it > 0L && isHealthyStatus(response.code) }
                                // A late response is still closed, even if cancellation won.
                                if (completed.compareAndSet(false, true)) continuation.resume(result)
                            }
                        }
                    })
                }
            }
        } finally {
            pool.evictAll()
        }
    }
}
