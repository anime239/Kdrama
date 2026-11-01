
package com.onlykdrama

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OnlyKdramaPlugin : Plugin() {

    override fun load(context: Context) {

        OnlyKdramaSession.initialize(context)

        registerMainAPI(OnlyKdrama())

        openSettings = { ctx ->

            val saved = OnlyKdramaSession.hasSavedSession(ctx)

            val browserCookies = OnlyKdramaWebView.hasSiteCookies()

            val status = buildString {
                append("Saved HTTP session: ")
                append(if (saved) "Available" else "Not available")

                append("\nBrowser cookies: ")
                append(if (browserCookies) "Available" else "Not available")

                append("\n\n")
                append("Open the website, complete any verification ")
                append("requested by the website, then press SAVE SESSION.")
            }

            AlertDialog.Builder(ctx)
                .setTitle("OnlyKDrama · Session Settings")
                .setMessage(status)
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
                .setNeutralButton("CLEAR SESSION") { _, _ ->
                    OnlyKdramaWebView.clearSiteCookies(ctx) {
                        Toast.makeText(
                            ctx,
                            "OnlyKDrama session cleared",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton("CLOSE", null)
                .show()
        }
    }
}
