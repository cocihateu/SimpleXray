package com.simplexray.re.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.simplexray.re.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal class VpnNotificationUpdater(
    context: Context,
    private val helper: VpnNotificationHelper,
) {
    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val directName = context.getString(R.string.routing_mode_direct)
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope, channelName: String) {
        stop()
        job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val updateJob = currentCoroutineContext()[Job]
            combine(
                GroupRuntimeStatus.appliedRouting,
                GroupRuntimeStatus.activeNodes,
            ) { routing, activeNodes ->
                val node = if (routing?.mode == "direct") {
                    "direct"
                } else {
                    routing?.defaultGroupTag?.let(activeNodes::get)
                }
                if (node == "direct") directName else node
            }.distinctUntilChanged().collect { node ->
                publish(updateJob, helper.buildForegroundNotification(channelName, node, connected = true))
            }
        }
        job?.start()
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    @Synchronized
    private fun publish(updateJob: Job?, notification: Notification) {
        if (job === updateJob && updateJob?.isActive == true) {
            manager.notify(VpnNotificationHelper.NOTIFICATION_ID, notification)
        }
    }
}
