package com.simplexray.re.common

import android.content.Context
import android.content.Intent
import android.net.Uri

object ZashboardLauncher {
    fun open(context: Context, port: Int, secret: String) {
        val setup = Uri.Builder().path("/setup")
            .appendQueryParameter("hostname", "127.0.0.1")
            .appendQueryParameter("port", port.toString())
            .appendQueryParameter("http", "true")
            .appendQueryParameter("secret", secret)
            .build()
        // Setup credentials stay in the browser fragment for the local controller.
        val url = Uri.parse("http://127.0.0.1:$port/ui/").buildUpon()
            .encodedFragment(setup.toString()).build()
        context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
