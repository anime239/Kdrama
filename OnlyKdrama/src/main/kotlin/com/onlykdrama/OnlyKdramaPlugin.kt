
package com.onlykdrama

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.AlertDialog
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OnlyKdramaPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(OnlyKdrama())

        openSettings = { ctx ->
            AlertDialog.Builder(ctx)
                .setTitle("OnlyKDrama")
                .setMessage(
                    "Open OnlyKDrama in your browser to complete " +
                    "any required website verification."
                )
                .setPositiveButton("Open Website") { _, _ ->
                    try {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://onlykdrama.shop/")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(
                            ctx,
                            "Unable to open browser",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }
}
