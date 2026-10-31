
package com.onlykdrama

import android.content.Context
import android.widget.Toast
import android.app.AlertDialog
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OnlyKdramaPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(OnlyKdrama())

        openSettings = { ctx ->
            AlertDialog.Builder(ctx)
                .setTitle("OnlyKDrama Settings")
                .setMessage(
                    "Open the in-app browser to visit OnlyKDrama " +
                    "and maintain its browser session."
                )
                .setPositiveButton("Open Browser") { _, _ ->
                    try {
                        OnlyKdramaWebView.open(ctx)
                    } catch (e: Exception) {
                        Toast.makeText(
                            ctx,
                            "Unable to open browser: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }
}
