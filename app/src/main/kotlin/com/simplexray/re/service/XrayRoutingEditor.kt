package com.simplexray.re.service

import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.TrafficRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal class XrayRoutingEditor(
    private val executable: String,
    private val filesDir: File,
    private val apiPort: Int,
    private val config: OutboundGroupConfig,
    initialRules: List<TrafficRule>,
) {
    private val mutex = Mutex()
    private val addedOutbounds = config.ruleOutbounds(config.workspace, initialRules).let { array ->
        (0 until array.length()).map { array.getJSONObject(it).getString("tag") }.toMutableSet()
    }

    suspend fun apply(workspace: GroupWorkspace, rules: List<TrafficRule>) = mutex.withLock {
        val outbounds = config.ruleOutbounds(workspace, rules)
        val additions = JSONArray()
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.getJSONObject(index)
            if (outbound.getString("tag") !in addedOutbounds) additions.put(outbound)
        }
        if (additions.length() > 0) {
            invoke("ado", JSONObject().put("outbounds", additions))
            for (index in 0 until additions.length()) addedOutbounds += additions.getJSONObject(index).getString("tag")
        }
        invoke("adrules", JSONObject().put("routing", config.routingConfig(workspace, rules)))
    }

    private suspend fun invoke(command: String, config: JSONObject) = coroutineScope {
        val args = mutableListOf(executable, "api", command, "--server=127.0.0.1:$apiPort", "--timeout=5")
        if (command == "adrules") args += "--append=false"
        args += "stdin:"
        val process = ProcessBuilder(args)
            .directory(filesDir).redirectErrorStream(true).apply {
                environment()["XRAY_LOCATION_ASSET"] = filesDir.absolutePath
            }.start()
        try {
            val output = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
            process.outputStream.bufferedWriter().use { it.write(config.toString()) }
            val message = output.await()
            if (process.waitFor() != 0) throw IOException(message.lineSequence().lastOrNull { it.isNotBlank() } ?: "Xray route update failed")
        } finally {
            process.destroy()
        }
    }
}
