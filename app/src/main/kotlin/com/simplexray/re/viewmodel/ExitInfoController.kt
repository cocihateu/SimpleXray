package com.simplexray.re.viewmodel

import com.simplexray.re.service.GroupRuntimeStatus
import com.simplexray.re.service.VpnRunningState
import com.simplexray.re.service.VpnStateHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal const val EXIT_INFO_HOST = "ipwho.is"

data class ExitInfoState(
    val loading: Boolean = false,
    val ip: String = "",
    val country: String = "",
    val countryCode: String = "",
    val region: String = "",
    val city: String = "",
    val asn: String = "",
    val organization: String = "",
    val isp: String = "",
    val error: String = "",
    val routedLookup: Boolean = false,
)

internal class ExitInfoController(private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(ExitInfoState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            mutableState.value = ExitInfoState(loading = true)
            val connected = VpnStateHub.state.value == VpnRunningState.Connected
            val endpoint = if (connected) GroupRuntimeStatus.exitInfoProxy() else null
            val routing = GroupRuntimeStatus.appliedRouting.value
            if (connected && (endpoint == null || routing == null)) {
                mutableState.value = ExitInfoState(error = "出口正在准备，稍后刷新")
                return@launch
            }
            val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).apply {
                if (endpoint == null) proxy(Proxy.NO_PROXY)
                else {
                    proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", endpoint.port)))
                    proxyAuthenticator { _, response ->
                        response.request.newBuilder().header("Proxy-Authorization",
                            Credentials.basic(endpoint.username, endpoint.password)).build()
                    }
                }
            }.build()
            try {
                val request = Request.Builder()
                    .url("https://$EXIT_INFO_HOST/?fields=success,message,ip,country,country_code,region,city,connection").build()
                val body = client.newCall(request).await().use { response ->
                    if (response.isSuccessful) response.body.string()
                    else throw IOException("出口信息 HTTP ${response.code}")
                }
                val root = JSONObject(body)
                if (root.optBoolean("success") == false) throw IOException(root.optString("message", "出口信息查询失败"))
                val network = root.optJSONObject("connection") ?: JSONObject()
                currentCoroutineContext().ensureActive()
                mutableState.value = ExitInfoState(
                    ip = root.optString("ip"),
                    country = root.optString("country"),
                    countryCode = root.optString("country_code"),
                    region = root.optString("region"),
                    city = root.optString("city"),
                    asn = network.optString("asn"),
                    organization = network.optString("org"),
                    isp = network.optString("isp"),
                    routedLookup = endpoint != null && routing?.mode != "direct",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                mutableState.value = ExitInfoState(error = e.message ?: "出口信息查询失败")
            } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
            }
        }
    }
}
