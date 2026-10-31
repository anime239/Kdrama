package com.onlykdrama

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OnlyKdramaPlugin : Plugin() {

    override fun load(context: Context) {

        registerMainAPI(OnlyKdrama())

        openSettings = { ctx ->

            val status = if (OnlyKdramaWebView.hasSiteCookies()) {
                "Browser session: Cookies detected"
            } else {
                "Browser session: No cookies detected"
            }

            AlertDialog.Builder(ctx)
                .setTitle("OnlyKDrama Settings")
                .setMessage(
                    "$status\n\n" +
                        "1. Open the website.\n" +
                        "2. Complete any verification displayed.\n" +
                        "3. Return to CloudStream and retry.\n\n" +
                        "Note: WebView cookies are not automatically shared with HTTP requests."
                )
                .setPositiveButton("OPEN WEBSITE") { _, _ ->
                    try {
                        OnlyKdramaWebView.open(ctx)
                    } catch (error: Exception) {
                        Toast.makeText(
                            ctx,
                            "Unable to open website: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNeutralButton("CLEAR COOKIES") { _, _ ->
                    OnlyKdramaWebView.clearSiteCookies(ctx) {
                        Toast.makeText(
                            ctx,
                            "Cookie deletion completed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton("CLOSE", null)
                .show()
        }
    }
}