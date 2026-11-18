package com.simplexray.re.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Owns the authenticated SOCKS adapters for one Xray process's extension outbounds. */
internal class MihomoOutboundProcess private constructor(
    private val executable: File,
    private val workDir: File,
    private val config: JSONObject,
    private val listeners: Map<String, String>,
    private val onUnexpectedExit: (MihomoOutboundProcess, Int) -> Unit,
) : Closeable {
    private val lifecycleLock = Any()
    private val ready = CompletableDeferred<Unit>()
    private var process: Process? = null
    private var outputJob: Job? = null
    private var inputJob: Job? = null
    private var exitJob: Job? = null
    private var running = false
    private var closed = false

    suspend fun start() {
        try {
            currentCoroutineContext().ensureActive()
            val scope = CoroutineScope(currentCoroutineContext())
            val helper = synchronized(lifecycleLock) {
                if (closed) throw CancellationException("Mihomo startup cancelled.")
                workDir.mkdirs()
                // The child keeps the app UID covered by the service's VPN package exclusion.
                ProcessBuilder(executable.absolutePath, "-d", workDir.absolutePath, "-f", "-")
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .start()
                    .also { process = it }
            }
            val jobs = synchronized(lifecycleLock) {
                if (closed) throw CancellationException("Mihomo startup cancelled.")
                outputJob = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        runInterruptible(Dispatchers.IO) { readOutput(helper.inputStream) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ready.completeExceptionally(StartupException("Reading Mihomo startup output failed."))
                    }
                }
                inputJob = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        runInterruptible(Dispatchers.IO) {
                            OutputStreamWriter(helper.outputStream, Charsets.UTF_8).use {
                                it.write(config.toString())
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ready.completeExceptionally(StartupException("Writing Mihomo runtime configuration failed."))
                    }
                }
                exitJob = scope.launch(start = CoroutineStart.LAZY) {
                    var report = false
                    try {
                        val code = runInterruptible(Dispatchers.IO) { helper.waitFor() }
                        withTimeoutOrNull(OUTPUT_DRAIN_TIMEOUT_MS) { outputJob?.join() }
                        report = synchronized(lifecycleLock) {
                            ready.completeExceptionally(
                                StartupException("Mihomo helper exited during startup (code $code).")
                            )
                            running && closed == false
                        }
                        if (report) onUnexpectedExit(this@MihomoOutboundProcess, code)
                    } finally {
                        if (report || currentCoroutineContext()[Job]?.isActive == false) close()
                    }
                }
                listOf(outputJob!!, exitJob!!, inputJob!!)
            }
            jobs.forEach { it.start() }
            withTimeout(STARTUP_TIMEOUT_MS) { ready.await() }
            currentCoroutineContext().ensureActive()
            synchronized(lifecycleLock) {
                if (closed) throw CancellationException("Mihomo startup cancelled.")
                if (helper.isAlive == false) {
                    throw StartupException("Mihomo helper exited before Xray startup.")
                }
                running = true
            }
        } catch (e: TimeoutCancellationException) {
            close()
            throw StartupException("Mihomo SOCKS listener startup timed out after 15 seconds.")
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: StartupException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw StartupException("Starting the packaged Mihomo helper failed.")
        }
    }

    fun requireAlive() {
        synchronized(lifecycleLock) {
            if (closed || process?.isAlive != true) {
                throw StartupException("Mihomo helper exited before Xray startup.")
            }
        }
    }

    override fun close() {
        val helper = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            running = false
            ready.cancel()
            outputJob?.cancel()
            inputJob?.cancel()
            exitJob?.cancel()
            process.also { process = null }
        }
        if (helper != null) {
            runCatching { helper.destroyForcibly() }
            runCatching { helper.waitFor(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
        workDir.deleteRecursively()
    }

    private fun readOutput(input: InputStream) {
        val pending = listeners.toMutableMap()
        InputStreamReader(input, Charsets.UTF_8).use { reader ->
            val buffer = CharArray(OUTPUT_BUFFER_CHARS)
            val line = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                for (i in 0 until count) {
                    val char = buffer[i]
                    if (char == '\n') {
                        acceptOutput(line.toString(), pending)
                        line.setLength(0)
                    } else if (line.length < MAX_OUTPUT_LINE_CHARS) {
                        line.append(char)
                    }
                }
            }
            if (line.isNotEmpty()) acceptOutput(line.toString(), pending)
        }
    }

    private fun acceptOutput(line: String, pending: MutableMap<String, String>) {
        // Inspect fixed startup markers and keep proxy settings and raw output private.
        if (line.contains("Parse config error:") || line.contains("Initial configuration error:")) {
            ready.completeExceptionally(StartupException("Mihomo rejected its extension proxy configuration."))
            return
        }
        if (listeners.keys.any { line.contains("Listener $it listen err:") }) {
            ready.completeExceptionally(StartupException("Mihomo SOCKS listener startup failed."))
            return
        }
        if (line.contains("level=fatal") || line.startsWith("panic:") || line.startsWith("fatal error:")) {
            ready.completeExceptionally(StartupException("Mihomo helper failed during startup."))
            return
        }
        val match = LISTENER_READY.find(line) ?: return
        val name = match.groupValues[1]
        val address = match.groupValues[2]
        if (pending[name] == address) {
            pending.remove(name)
            if (pending.isEmpty()) ready.complete(Unit)
        }
    }

    class StartupException(message: String) : IOException(message)

    companion object {
        private const val STARTUP_TIMEOUT_MS = 15_000L
        private const val SHUTDOWN_TIMEOUT_MS = 1_000L
        private const val OUTPUT_DRAIN_TIMEOUT_MS = 1_000L
        private const val OUTPUT_BUFFER_CHARS = 2_048
        private const val MAX_OUTPUT_LINE_CHARS = 4_096
        private val LISTENER_READY =
            Regex("""SOCKS\[(sx-mihomo-\d+)] proxy listening at: (127\.0\.0\.1:\d+)""")

        fun prepare(
            root: JSONObject,
            executable: File,
            cacheDir: File,
            allocatePort: () -> Int,
            dnsServers: List<String>,
            ipv6: Boolean,
            onUnexpectedExit: (MihomoOutboundProcess, Int) -> Unit,
        ): MihomoOutboundProcess? {
            val outbounds = root.optJSONArray("outbounds") ?: return null
            val extensions = (0 until outbounds.length()).filter {
                outbounds.getJSONObject(it).optString("protocol") == "mihomo"
            }
            if (extensions.isEmpty()) return null

            try {
                val proxies = JSONArray()
                val socksListeners = JSONArray()
                val expected = mutableMapOf<String, String>()
                for ((index, outboundIndex) in extensions.withIndex()) {
                    val outbound = outbounds.getJSONObject(outboundIndex)
                    val proxy = JSONObject(outbound.getJSONObject("settings").toString())
                    val name = "sx-mihomo-$index"
                    val password = UUID.randomUUID().toString()
                    val port = allocatePort()
                    proxies.put(proxy)
                    socksListeners.put(
                        JSONObject()
                            .put("type", "socks")
                            .put("name", name)
                            .put("listen", "127.0.0.1")
                            .put("port", port.toString())
                            .put("proxy", proxy.getString("name"))
                            .put("udp", true)
                            .put("users", JSONArray().put(
                                JSONObject().put("username", name).put("password", password)
                            ))
                    )
                    expected[name] = "127.0.0.1:$port"
                    // Keep the outbound tag, target strategy, and other Xray options in memory.
                    outbound.put("protocol", "socks").put(
                        "settings",
                        JSONObject().put("servers", JSONArray().put(
                            JSONObject()
                                .put("address", "127.0.0.1")
                                .put("port", port)
                                .put("users", JSONArray().put(
                                    JSONObject().put("user", name).put("pass", password)
                                ))
                        ))
                    )
                }
                val config = JSONObject()
                    .put("proxies", proxies)
                    .put("listeners", socksListeners)
                    .put("ipv6", ipv6)
                    .put("dns", JSONObject()
                        .put("enable", true)
                        .put("ipv6", ipv6)
                        .put("nameserver", JSONArray(dnsServers))
                        .put("default-nameserver", JSONArray(dnsServers))
                        .put("proxy-server-nameserver", JSONArray(dnsServers))
                    )
                return MihomoOutboundProcess(
                    executable,
                    File(cacheDir, "mihomo-${UUID.randomUUID()}"),
                    config,
                    expected,
                    onUnexpectedExit,
                )
            } catch (e: Exception) {
                throw StartupException("Preparing Mihomo extension outbounds failed.")
            }
        }
    }
}
