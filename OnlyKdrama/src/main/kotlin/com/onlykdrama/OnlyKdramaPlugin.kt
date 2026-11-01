
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
        OnlyKdramaVerification.initialize(context)

        registerMainAPI(OnlyKdrama())

        openSettings = { ctx ->

            val saved =
                OnlyKdramaSession.hasSavedSession(ctx)

            val clearance =
                OnlyKdramaSession.hasCloudflareClearance(ctx)

            val status = buildString {
                append("Saved session: ")
                append(if (saved) "Available" else "Not available")
                append("\nCloudflare clearance: ")
                append(if (clearance) "Available" else "Not available")
                append("\n\n")
                append("Verification and session saving happen automatically when required.")
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
