package com.simplexray.re.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.simplexray.re.service.ClashApiPreferences

object ZashboardLauncher {
    fun open(context: Context) {
        val prefs = ClashApiPreferences(context)
        val setup = Uri.Builder().path("/setup")
            .appendQueryParameter("hostname", "127.0.0.1")
            .appendQueryParameter("port", prefs.port.toString())
            .appendQueryParameter("http", "true")
            .appendQueryParameter("secret", prefs.secret)
            .build()
        // Setup credentials stay in the browser fragment for the local controller.
        val url = Uri.parse("http://board.zash.run.place/").buildUpon()
            .encodedFragment(setup.toString()).build()
        context.startActivity(Intent(Intent.ACTION_VIEW, url))
    }
}
