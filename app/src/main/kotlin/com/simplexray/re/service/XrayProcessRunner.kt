package com.simplexray.re.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.simplexray.re.common.ConfigUtils
import com.simplexray.re.common.ConfigUtils.extractPortsFromJson
import com.simplexray.re.common.CoreStatsClient
import com.simplexray.re.common.config.GlobalConfigStore
import com.simplexray.re.common.config.GroupWorkspaceStore
import com.simplexray.re.common.config.RuntimeLogSettings
import com.simplexray.re.data.source.LogFileManager
import com.simplexray.re.prefs.Preferences
import com.simplexray.re.prefs.TunnelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.Volatile

/**
 * Startup-probe deadline measured with a clock that stops while the device is
 * suspended. A wall-clock deadline made a restart issued during Doze fail as
 * soon as the device woke up, even though the core had barely been given CPU.
 */
internal class StartupProbeDeadline(
    timeoutMs: Long,
    private val nowMs: () -> Long = { SystemClock.uptimeMillis() },
) {
    private val expiresAtMs = nowMs() + timeoutMs

    val isExpired: Boolean
        get() = nowMs() >= expiresAtMs
}

internal class XrayProcessRunner(
    private val application: Context,
    private val logFileManager: LogFileManager,
    private val spawn: (xrayPath: String, assetDir: String, vpnFd: Int) -> IntArray?,
    private val reap: (pid: Int) -> Unit,
    private val callbacks: Callbacks,
    private val tag: String,
) {
    interface Callbacks {
        fun isStopping(): Boolean
        fun hasStartupFailed(): Boolean
        fun hasVpnTunnel(): Boolean
        fun vpnFd(): Int?
        fun onCoreReady()
        fun onStartFailure(reason: String)
        fun onUnexpectedExit()
        fun emitLog(line: String)
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    var pid: Int = -1
        private set

    @Volatile
    var isStarted: Boolean = false
        private set

    @Volatile private var job: Job? = null
    private var startAttempt = 0
    private var scope: CoroutineScope? = null
    @Volatile private var groupSession: GroupCoreSession? = null

    fun resetAttempt() {
        startAttempt = 0
    }

    @Synchronized fun launch(scope: CoroutineScope) {
        this.scope = scope
        job?.cancel()
        kill()
        job = scope.launch(start = CoroutineStart.LAZY) { run(scope) }
        job?.start()
    }

    fun restart(scope: CoroutineScope) {
        launch(scope)
    }

    @Synchronized fun kill() {
        groupSession?.close()
        groupSession = null
        isStarted = false
        process?.destroy()
        process = null
        val currentPid = pid
        if (currentPid > 0) {
            pid = -1
            try {
                Os.kill(currentPid, OsConstants.SIGKILL)
            } catch (e: ErrnoException) {
                Log.w(tag, "Failed to kill xray pid $currentPid: ${e.message}")
            }
            runCatching { reap(currentPid) }
        }
    }

    @Synchronized fun stop() {
        job?.cancel()
        job = null
        kill()
    }

    private suspend fun run(scope: CoroutineScope) {
        val runJob = currentCoroutineContext().job
        synchronized(this) {
            requireCurrentRun(runJob)
            isStarted = false
        }
        val runScope = CoroutineScope(currentCoroutineContext())
        var stdoutPfd: ParcelFileDescriptor? = null
        var currentProcess: Process? = null
        var currentPid = -1
        var reader: BufferedReader? = null
        var session: GroupCoreSession? = null

        try {
            val prefs = Preferences(application)
            if (callbacks.isStopping()) {
                Log.d(tag, "Aborting Xray process startup after intentional stop.")
                return
            }
            if (!prefs.disableVpn && !callbacks.hasVpnTunnel()) {
                failCurrentRun(runJob, "VPN tunnel setup is required.")
                return
            }
            Log.d(tag, "Attempting to start native Xray process with TUN fd & local gRPC API.")
            val libraryDir = TProxyService.getNativeLibraryDir(application)
            val xrayPath = "$libraryDir/libxray.so"
            val globalStore = GlobalConfigStore(application)
            val workspaceStore = GroupWorkspaceStore(application)
            val workspace = workspaceStore.load()
            val ruleStore = com.simplexray.re.common.config.RuleWorkspaceStore(application)
            val trafficRules = ruleStore.load()
            val unsupported = com.simplexray.re.common.subscription.NodeImportConverter.findUnsupportedNodeReasons(workspace.allNodes())
            if (unsupported.isNotEmpty()) {
                failCurrentRun(runJob, unsupported.joinToString("\n"))
                return
            }
            if (workspace.groups.isEmpty() && workspace.allNodes().isEmpty()) {
                failCurrentRun(runJob, "Add a proxy group with at least one node before starting Xray.")
                return
            }
            val assembled = workspace.assembleRuntimeConfig(globalStore.loadJson(), prefs.dohBootstrapDns)
            if (assembled.optJSONObject("routing")?.optString("script")?.isNotBlank() == true) {
                assembled.getJSONObject("routing").put("domainStrategy", "IPOnDemand")
            }
            assembled.optJSONObject("routing")?.remove("script")
            val rawConfigContent = assembled.toString()
            Log.d(tag, "Assembled global + workspace config: ${workspace.groups.size} groups, ${workspace.allNodes().size} nodes")

            val sanitizedConfigContent = ConfigUtils.sanitizeConfig(rawConfigContent, prefs)
            val format = "json"

            val ports = runCatching { extractPortsFromJson(sanitizedConfigContent) }.getOrDefault(emptySet())
            val apiPort = findAvailablePort(ports) ?: run {
                failCurrentRun(runJob, "Local port allocation failed for the stats API.")
                return
            }
            val configWithApi = synchronized(this) {
                requireCurrentRun(runJob)
                prefs.apiPort = apiPort
                prefs.apiAddress = "127.0.0.1"
                ConfigUtils.injectStatsServiceIntoSanitized(prefs, sanitizedConfigContent)
            }
            val usedPorts = (ports + apiPort + prefs.httpPort).toMutableSet()
            fun allocatePort(): Int = requireNotNull(findAvailablePort(usedPorts)) {
                "Local port allocation failed for runtime proxy listeners."
            }.also { usedPorts.add(it) }
            val runtimeConfig = JSONObject(configWithApi)
            RuntimeLogSettings(application).applyTo(runtimeConfig)
            if (workspace.groups.isNotEmpty()) {
                val groups = OutboundGroupConfig(workspace, allocatePort(), allocatePort(), ::allocatePort)
                groups.inject(runtimeConfig, application.filesDir, application.cacheDir, trafficRules)
                session = GroupCoreSession(
                    store = workspaceStore,
                    config = groups,
                    apiPort = apiPort,
                    ruleStore = ruleStore,
                    initialRules = trafficRules,
                    routingEditor = XrayRoutingEditor(xrayPath, application.filesDir, apiPort, groups, trafficRules),
                    logEvent = { message ->
                        val line = formatAppLogLine(message)
                        logFileManager.appendLog(line)
                        callbacks.emitLog(line)
                    },
                )
                synchronized(this) {
                    requireCurrentRun(runJob)
                    groupSession = session
                }
                session.startRelayBeforeCore(runScope)
            }
            val finalConfigContent = runtimeConfig.toString()
            Log.d(tag, "Injected final config (${finalConfigContent.length} chars) ready for stdin ($format)")
            requireCurrentRun(runJob)

            val useXrayTun = prefs.tunnelMode == TunnelMode.XrayTun && !prefs.disableVpn

            if (useXrayTun) {
                val vpnFd = callbacks.vpnFd() ?: run {
                    failCurrentRun(runJob, "A VPN descriptor is required for Xray TUN mode.")
                    return
                }
                val spawnResult = synchronized(this) {
                    requireCurrentRun(runJob)
                    spawn(xrayPath, application.filesDir.path, vpnFd)?.also {
                        currentPid = it[0]
                        pid = currentPid
                    }
                }
                    ?: run {
                        failCurrentRun(runJob, "Native Xray process startup failed.")
                        return
                    }
                val stdoutReadFd = spawnResult[1]
                val stdinWriteFd = spawnResult[2]
                Log.d(tag, "Xray TUN process started: pid=$currentPid")

                // Write the config on a separate IO job so the parent starts
                // draining stdout immediately. A synchronous write can deadlock
                // when both pipe buffers fill up (large config + early logs).
                runScope.launch(Dispatchers.IO) {
                    runCatching {
                        ParcelFileDescriptor.adoptFd(stdinWriteFd).use { pfd ->
                            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
                                out.write(finalConfigContent.toByteArray(Charsets.UTF_8))
                                out.flush()
                            }
                        }
                    }.onFailure {
                        Log.e(tag, "Failed to write configuration to Xray stdin", it)
                    }
                }
                stdoutPfd = ParcelFileDescriptor.adoptFd(stdoutReadFd)
                reader = BufferedReader(
                    InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(stdoutPfd))
                )
            } else {
                val processBuilder = getProcessBuilder(xrayPath)
                val processRef = synchronized(this) {
                    requireCurrentRun(runJob)
                    processBuilder.start().also {
                        currentProcess = it
                        process = it
                    }
                }
                Log.d(tag, "Xray child process started successfully via ProcessBuilder.")

                runScope.launch(Dispatchers.IO) {
                    runCatching {
                        processRef.outputStream.use { os ->
                            os.write(finalConfigContent.toByteArray(Charsets.UTF_8))
                            os.flush()
                        }
                    }.onFailure {
                        Log.e(tag, "Failed to write configuration to Xray stdin", it)
                    }
                }
                reader = BufferedReader(InputStreamReader(processRef.inputStream))
            }

            runScope.launch {
                val probeProcess = currentProcess
                val client = CoreStatsClient.create("127.0.0.1", apiPort)
                try {
                    val deadline = StartupProbeDeadline(STARTUP_PROBE_TIMEOUT_MS)
                    while (!isStarted &&
                        !callbacks.hasStartupFailed() &&
                        !callbacks.isStopping() &&
                        (probeProcess?.isAlive == true || currentPid > 0) &&
                        job === runJob &&
                        !deadline.isExpired
                    ) {
                        if (client.getSystemStats() != null) {
                            synchronized(this@XrayProcessRunner) {
                                requireCurrentRun(runJob)
                                if (callbacks.hasStartupFailed() || callbacks.isStopping()) return@launch
                                isStarted = true
                                startAttempt = 0
                                Log.d(tag, "Xray core ready (gRPC API reachable), updating VpnStateHub.")
                                val readySession = session
                                if (readySession != null) {
                                    readySession.start(scope, onReady = {
                                        synchronized(this@XrayProcessRunner) {
                                            if (job === runJob && callbacks.isStopping() == false) callbacks.onCoreReady()
                                        }
                                    }, reload = { restart(scope) })
                                } else {
                                    callbacks.onCoreReady()
                                }
                            }
                            break
                        }
                        delay(STARTUP_PROBE_INTERVAL_MS)
                    }

                    // The probe deadline expiring while the child process is still
                    // alive used to leave the service stuck in Connecting forever.
                    // Treat it as a normal startup failure so the retry/failure
                    // pipeline runs and the lock is released.
                    if (!isStarted &&
                        !callbacks.hasStartupFailed() &&
                        !callbacks.isStopping() &&
                        (probeProcess?.isAlive == true || currentPid > 0) &&
                        job === runJob &&
                        deadline.isExpired
                    ) {
                        Log.e(tag, "Xray startup probe timed out after ${STARTUP_PROBE_TIMEOUT_MS}ms.")
                        failCurrentRun(
                            runJob,
                            "Xray core startup timed out after ${STARTUP_PROBE_TIMEOUT_MS / 1000}s.",
                        )
                    }
                } finally {
                    client.close()
                }
            }

            Log.d(tag, "Reading native Xray process log stream.")
            val activeReader = reader
            var line = activeReader.readLine()
            while (line != null) {
                val batch = mutableListOf<String>()
                fun acceptLog(raw: String) {
                    if (raw.contains("proxy/socks: Connection closed immediately, likely health check connection")) return
                    if (raw.endsWith("[Warning] common/errors: The feature Shadowsocks (with no Forward Secrecy, etc.) is deprecated, not recommended for using and might be removed. Please migrate to VLESS Encryption as soon as possible.")) return
                    val stamped = stampLogLine(session?.formatLogLine(raw) ?: raw)
                    Log.d(tag, "XrayLog: $stamped")
                    batch.add(stamped)
                    callbacks.emitLog(stamped)
                }
                try {
                    acceptLog(line)
                    while (activeReader.ready() && batch.size < 50) {
                        val nextLine = activeReader.readLine() ?: break
                        acceptLog(nextLine)
                    }
                } finally {
                    logFileManager.appendLogs(batch)
                }
                line = activeReader.readLine()
            }
            Log.d(tag, "Native Xray process log stream finished.")
            if (currentProcess != null) {
                onXrayExited(currentProcess, -1, runJob)
            } else {
                onXrayExited(null, currentPid, runJob)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedIOException) {
            Log.d(tag, "Xray log stream interrupted during shutdown.")
            if (currentProcess != null) {
                onXrayExited(currentProcess, -1, runJob)
            } else if (currentPid > 0) {
                onXrayExited(null, currentPid, runJob)
            }
        } catch (e: Exception) {
            Log.e(tag, "Executing native Xray failed (${e.javaClass.simpleName}).")
            if (currentProcess != null) {
                onXrayExited(currentProcess, -1, runJob)
            } else if (currentPid > 0) {
                onXrayExited(null, currentPid, runJob)
            } else {
                failCurrentRun(runJob, "Executing native Xray failed (${e.javaClass.simpleName}).")
            }
        } finally {
            session?.close()
            synchronized(this) {
                if (groupSession === session) groupSession = null
                if (currentProcess != null && process === currentProcess) {
                    process = null
                }
                if (pid > 0 && pid == currentPid) {
                    pid = -1
                }
            }
            runCatching { reader?.close() }
            runCatching { stdoutPfd?.close() }
            Log.d(tag, "Native Xray process task finished.")
            if (currentPid > 0) {
                runCatching { reap(currentPid) }
            }
        }
    }

    private fun requireCurrentRun(runJob: Job) {
        runJob.ensureActive()
        if (job !== runJob) throw CancellationException("Xray startup superseded by a newer run.")
    }

    @Synchronized private fun failCurrentRun(runJob: Job, reason: String) {
        if (job === runJob && runJob.isActive && callbacks.isStopping() == false &&
            callbacks.hasStartupFailed() == false
        ) {
            callbacks.onStartFailure(reason)
        }
    }

    @Synchronized private fun onXrayExited(process: Process?, pid: Int, runJob: Job) {
        if (pid > 0) {
            runCatching { reap(pid) }
        }
        if (job !== runJob || runJob.isActive == false) return
        if (callbacks.isStopping()) {
            Log.d(tag, "Xray process exited after intentional stop, ignoring.")
            return
        }
        if ((process != null && process !== this.process) || (process == null && pid != this.pid)) {
            Log.d(tag, "Xray process superseded by a newer one, ignoring.")
            return
        }
        if (isStarted) {
            Log.e(tag, "Xray process exited unexpectedly, stopping service.")
            val line = formatAppLogLine("[Error] Xray core exited unexpectedly.")
            logFileManager.appendLog(line)
            callbacks.emitLog(line)
            callbacks.onUnexpectedExit()
            return
        }
        if (startAttempt < MAX_START_ATTEMPTS) {
            startAttempt++
            Log.w(tag, "Xray failed to start, retrying (attempt $startAttempt/$MAX_START_ATTEMPTS).")
            scope?.let { launch(it) }
        } else {
            callbacks.onStartFailure("Xray failed to start after $MAX_START_ATTEMPTS attempts")
        }
    }

    private fun getProcessBuilder(xrayPath: String): ProcessBuilder {
        val filesDir = application.filesDir
        val command = mutableListOf(xrayPath, "-config", "stdin:")
        val processBuilder = ProcessBuilder(command)
        val environment = processBuilder.environment()
        environment["XRAY_LOCATION_ASSET"] = filesDir.path
        processBuilder.directory(filesDir)
        processBuilder.redirectErrorStream(true)
        return processBuilder
    }

    private fun findAvailablePort(excludedPorts: Set<Int>): Int? {
        repeat(5) {
            val port = runCatching {
                ServerSocket(0).use { socket ->
                    socket.reuseAddress = true
                    socket.localPort
                }
            }.onFailure {
                Log.d(tag, "Ephemeral port allocation failed: ${it.message}")
            }.getOrNull()
            if (port != null && port !in excludedPorts) {
                return port
            }
        }
        return null
    }

    private fun stampLogLine(line: String): String {
        val message = GO_LOG_TIMESTAMP_PREFIX.replaceFirst(line, "")
        return if (message === line) line else formatAppLogLine(message)
    }

    companion object {
        private const val MAX_START_ATTEMPTS = 2
        private const val STARTUP_PROBE_TIMEOUT_MS: Long = 15000
        private const val STARTUP_PROBE_INTERVAL_MS: Long = 500
        private val GO_LOG_TIMESTAMP_PREFIX = Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)? """)
        private val logTimestampFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS", Locale.US)

        fun formatAppLogLine(message: String): String =
            "${LocalDateTime.now().format(logTimestampFormat)} $message"
    }
}
