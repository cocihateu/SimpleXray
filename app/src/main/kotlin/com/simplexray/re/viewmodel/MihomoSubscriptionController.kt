package com.simplexray.re.viewmodel

import android.app.Application
import android.net.Uri
import com.simplexray.re.R
import com.simplexray.re.common.ConfigUtils
import com.simplexray.re.common.config.ConfigCodec
import com.simplexray.re.common.config.GroupProfile
import com.simplexray.re.common.config.GroupProfileStore
import com.simplexray.re.common.subscription.MihomoConversionException
import com.simplexray.re.common.subscription.MihomoSubscriptionMerger
import com.simplexray.re.common.subscription.MihomoYamlSubscription
import com.simplexray.re.data.source.FileManager
import com.simplexray.re.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.coroutines.cancellation.CancellationException

data class MihomoSubscriptionSummary(
    val fileName: String,
    val nodeCount: Int,
    val groupCount: Int,
    val notes: List<String>,
)

data class MihomoSubscriptionState(
    val isImporting: Boolean = false,
    val progress: String? = null,
    val error: String? = null,
    val summary: MihomoSubscriptionSummary? = null,
)

internal class MihomoSubscriptionController(
    private val application: Application,
    private val prefs: Preferences,
    private val fileManager: FileManager,
    private val scope: CoroutineScope,
    private val selectedConfig: StateFlow<File?>,
    private val isVpnEnabled: () -> Boolean,
    private val onImported: (File) -> Unit,
) {
    private val profileStore = GroupProfileStore(application)
    private val _state = MutableStateFlow(MihomoSubscriptionState())
    val state: StateFlow<MihomoSubscriptionState> = _state.asStateFlow()

    fun importUrl(url: String) {
        importSubscription(R.string.subscription_progress_downloading) {
            val httpUrl = url.trim().toHttpUrlOrNull()
                ?: throw ImportProblem(R.string.subscription_error_url)
            val client = OkHttpClient.Builder().apply {
                if (isVpnEnabled()) {
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", prefs.socksPort)))
                }
            }.build()
            val request = Request.Builder()
                .url(httpUrl)
                .header("Accept", "application/yaml, text/yaml, text/plain, */*")
                .header("User-Agent", "clash.meta")
                .get()
                .build()
            client.newCall(request).await().use { response ->
                if (response.isSuccessful == false) {
                    throw ImportProblem(R.string.subscription_error_http, response.code)
                }
                response.body.string()
            }
        }
    }

    fun importFile(uri: Uri) {
        importSubscription(R.string.subscription_progress_reading) {
            val input = application.contentResolver.openInputStream(uri)
                ?: throw ImportProblem(R.string.subscription_error_document)
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun importSubscription(readingMessage: Int, readContent: suspend () -> String) {
        val selectedFile = selectedConfig.value
        scope.launch(Dispatchers.IO) {
            progress(readingMessage)
            try {
                val content = readContent()
                progress(R.string.subscription_progress_converting)
                val subscription = MihomoYamlSubscription.parse(content)

                progress(R.string.subscription_progress_merging)
                val original = if (selectedFile != null) {
                    selectedFile.readText()
                } else {
                    application.assets.open("template").bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val root = ConfigCodec.parse(original)
                    ?: throw ImportProblem(R.string.subscription_error_destination)
                val previousProfile = if (selectedFile != null) {
                    profileStore.load(
                        selectedFile.absolutePath,
                        ConfigUtils.extractOutbounds(original).map { it.tag },
                    )
                } else {
                    GroupProfile(groups = emptyList(), targetStrategies = emptyMap())
                }
                val merged = MihomoSubscriptionMerger.merge(
                    root = root,
                    previousProfile = previousProfile,
                    subscription = subscription,
                    creatingConfig = selectedFile == null,
                )

                progress(R.string.subscription_progress_saving)
                val target = if (selectedFile != null) {
                    if (fileManager.renameConfigFile(selectedFile, selectedFile, merged.content) == false) {
                        throw ImportProblem(R.string.subscription_error_save)
                    }
                    selectedFile
                } else {
                    val path = fileManager.importConfigFromContent(merged.content)
                        ?: throw ImportProblem(R.string.subscription_error_save)
                    File(path)
                }
                profileStore.save(target.absolutePath, merged.profile)
                withContext(Dispatchers.Main) { onImported(target) }

                val notes = buildList {
                    subscription.policies.forEach { policy ->
                        add(application.getString(R.string.subscription_policy_mapped, policy.tag, policy.sourceType))
                        if (policy.mappedOptions.isNotEmpty()) {
                            add(
                                application.getString(
                                    R.string.subscription_policy_options,
                                    policy.tag,
                                    policy.mappedOptions.joinToString(", "),
                                )
                            )
                        }
                    }
                    if (subscription.sourceSections.isNotEmpty()) {
                        add(
                            application.getString(
                                R.string.subscription_source_sections,
                                subscription.sourceSections.joinToString(", "),
                            )
                        )
                    }
                    if (isVpnEnabled()) add(application.getString(R.string.subscription_restart_notice))
                }
                _state.value = MihomoSubscriptionState(
                    summary = MihomoSubscriptionSummary(
                        fileName = target.name,
                        nodeCount = subscription.nodeTags.size,
                        groupCount = subscription.profile.groups.size,
                        notes = notes,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: MihomoConversionException) {
                _state.value = MihomoSubscriptionState(error = conversionMessage(e))
            } catch (e: ImportProblem) {
                _state.value = MihomoSubscriptionState(error = application.getString(e.resourceId, *e.arguments))
            } catch (_: IOException) {
                _state.value = MihomoSubscriptionState(
                    error = application.getString(R.string.subscription_error_io, _state.value.progress.orEmpty())
                )
            } catch (_: Exception) {
                _state.value = MihomoSubscriptionState(
                    error = application.getString(R.string.subscription_error_import, _state.value.progress.orEmpty())
                )
            }
        }
    }

    private fun progress(resourceId: Int) {
        _state.value = MihomoSubscriptionState(
            isImporting = true,
            progress = application.getString(resourceId),
        )
    }

    private fun conversionMessage(error: MihomoConversionException): String {
        val resourceId = when (error.kind) {
            MihomoConversionException.Kind.FORMAT -> R.string.subscription_error_format
            MihomoConversionException.Kind.REQUIRED -> R.string.subscription_error_required
            MihomoConversionException.Kind.FIELD_TYPE -> R.string.subscription_error_field_type
            MihomoConversionException.Kind.CAPABILITY -> R.string.subscription_error_capability
            MihomoConversionException.Kind.REFERENCE -> R.string.subscription_error_reference
            MihomoConversionException.Kind.DUPLICATE -> R.string.subscription_error_duplicate
            MihomoConversionException.Kind.GRAPH -> R.string.subscription_error_graph
            MihomoConversionException.Kind.CONFIG -> R.string.subscription_error_builtin
        }
        return application.getString(resourceId, error.location, featureLabel(error.feature))
    }

    private fun featureLabel(feature: String): String {
        val resourceId = when (feature) {
            "http-udp" -> R.string.subscription_feature_http_udp
            "outbound-udp-control" -> R.string.subscription_feature_outbound_udp
            "group-udp-control" -> R.string.subscription_feature_group_udp
            "legacy-vmess" -> R.string.subscription_feature_legacy_vmess
            "public-transport-security" -> R.string.subscription_feature_transport_security
            "vision-security" -> R.string.subscription_feature_vision
            "upgrade-fast-open" -> R.string.subscription_feature_upgrade
            "early-data-headers" -> R.string.subscription_feature_early_data
            "tls-verification" -> R.string.subscription_feature_tls_verification
            "reality-transports" -> R.string.subscription_feature_reality_transport
            "reality-policy" -> R.string.subscription_feature_reality_policy
            "provider-proxies" -> R.string.subscription_feature_provider
            "xhttp-host" -> R.string.subscription_feature_xhttp_host
            "xhttp-padding" -> R.string.subscription_feature_xhttp_padding
            else -> return feature
        }
        return application.getString(resourceId)
    }

    private class ImportProblem(val resourceId: Int, vararg val arguments: Any) : IOException()
}
