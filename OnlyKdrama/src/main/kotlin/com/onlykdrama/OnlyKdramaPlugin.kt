
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
            val hasCookies = OnlyKdramaWebView.hasSiteCookies()

            val status = if (hasCookies) {
                "Browser session: Cookies stored"
            } else {
                "Browser session: No cookies stored"
            }

            AlertDialog.Builder(ctx)
                .setTitle("OnlyKDrama Settings")
                .setMessage(
                    "$status\n\n" +
                    "Complete website verification in the browser if required.\n\n" +
                    "WebView cookies are not automatically shared with CloudStream HTTP requests."
                )
                .setPositiveButton("OPEN WEBSITE") { _, _ ->
                    try {
                        OnlyKdramaWebView.open(ctx)
                    } catch (e: Exception) {
                        Toast.makeText(
                            ctx,
                            "Unable to open website: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNeutralButton("CLEAR COOKIES") { _, _ ->
                    OnlyKdramaWebView.clearSiteCookies(ctx) {
                        Toast.makeText(
                            ctx,
                            "Cookies cleared",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton("CLOSE", null)
                .show()
        }
    }
}
