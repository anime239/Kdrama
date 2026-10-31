
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

            fun showSettings() {
                val hasCookies = OnlyKdramaWebView.hasSiteCookies()

                val status = if (hasCookies) {
                    "Browser session: Cookies stored"
                } else {
                    "Browser session: No cookies stored"
                }

                AlertDialog.Builder(ctx)
                    .setTitle("OnlyKDrama · Cloudflare Protection")
                    .setMessage(
                        "$status\n\n" +
                        "If OnlyKDrama displays a security verification page, " +
                        "open the website below and complete the verification manually.\n\n" +
                        "Browser cookies are stored by Android WebView. " +
                        "They are not automatically shared with CloudStream requests."
                    )
                    .setPositiveButton("BYPASS CLOUDFLARE") { _, _ ->
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
                    .setNeutralButton("CLEAR COOKIES") { _, _ ->
                        OnlyKdramaWebView.clearSiteCookies(ctx) {
                            Toast.makeText(
                                ctx,
                                "Browser cookies cleared",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .setNegativeButton("CLOSE", null)
                    .show()
            }

            showSettings()
        }
    }
}
