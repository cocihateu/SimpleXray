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
import kotlin.coroutines.resume

internal object HttpLatencyMeasurement {
    suspend fun measure(client: OkHttpClient, url: String, timeoutMs: Int = 5_000): Long? {
        val pool = ConnectionPool(1, 1, TimeUnit.SECONDS)
        try {
            var latencyMs = 0L
            val measurementClient = client.newBuilder()
                .connectionPool(pool)
                .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .addInterceptor { chain ->
                    // One call retains the node-isolated connection between both HEAD exchanges.
                    chain.proceed(chain.request()).close()
                    chain.proceed(chain.request())
                }
                .addNetworkInterceptor { chain ->
                    // Measure request writes through response headers after connection setup.
                    val startedAt = SystemClock.elapsedRealtime()
                    val response = chain.proceed(chain.request())
                    latencyMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                    response
                }
                .build()
            val request = Request.Builder().url(url).head().build()
            val deadlineMs = if (timeoutMs == 0) Long.MAX_VALUE else timeoutMs.toLong()
            return withTimeoutOrNull(deadlineMs) {
                suspendCancellableCoroutine<Long?> { continuation ->
                    val call = measurementClient.newCall(request)
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            continuation.resume(null)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.use {
                                continuation.resume(if (it.code in 200..399) latencyMs else null)
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
