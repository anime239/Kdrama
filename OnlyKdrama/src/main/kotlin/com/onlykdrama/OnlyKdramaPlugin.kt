
package com.onlykdrama

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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

            val density = ctx.resources.displayMetrics.density

            fun dp(value: Int): Int =
                (value * density).toInt()

            val backgroundColor = 0xFF09090F.toInt()
            val panelColor = 0xFF171421.toInt()
            val purple = 0xFF9B55E8.toInt()
            val textColor = 0xFFF5F0FA.toInt()
            val mutedColor = 0xFFA69CAF.toInt()
            val green = 0xFF8ED6A5.toInt()

            fun rounded(
                color: Int,
                radius: Int,
                stroke: Int? = null
            ) = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                stroke?.let { setStroke(dp(1), it) }
            }

            fun text(
                value: String,
                size: Float,
                color: Int,
                bold: Boolean = false
            ) = TextView(ctx).apply {
                this.text = value
                textSize = size
                setTextColor(color)

                if (bold) {
                    setTypeface(null, Typeface.BOLD)
                }
            }

            val saved =
                OnlyKdramaSession.hasSavedSession(ctx)

            val clearance =
                OnlyKdramaWebView.hasSiteCookies()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(18),
                    dp(18),
                    dp(18),
                    dp(15)
                )
                background = rounded(
                    backgroundColor,
                    20,
                    0xFF332744.toInt()
                )
            }

            // Header

            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val logo = TextView(ctx).apply {
                text = "K"
                textSize = 23f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(purple, 13)
            }

            header.addView(
                logo,
                LinearLayout.LayoutParams(
                    dp(48),
                    dp(48)
                )
            )

            val titleArea = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }

            titleArea.addView(
                text(
                    "OnlyKDrama",
                    19f,
                    textColor,
                    true
                )
            )

            titleArea.addView(
                text(
                    "SESSION SETTINGS",
                    10f,
                    mutedColor,
                    true
                )
            )

            header.addView(
                titleArea,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            val badge = TextView(ctx).apply {
                text = "● ACTIVE"
                textSize = 10f
                setTextColor(green)
                setPadding(
                    dp(10),
                    dp(8),
                    dp(10),
                    dp(8)
                )
                background = rounded(
                    0xFF192D22.toInt(),
                    25
                )
            }

            header.addView(badge)
            root.addView(header)

            // Status panel

            val statusPanel = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(15),
                    dp(14),
                    dp(15),
                    dp(14)
                )
                background = rounded(
                    panelColor,
                    15,
                    0xFF30243F.toInt()
                )
            }

            statusPanel.addView(
                text(
                    "Session status",
                    15f,
                    textColor,
                    true
                )
            )

            fun addStatus(
                label: String,
                available: Boolean
            ) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(13), 0, 0)
                }

                val dot = TextView(ctx).apply {
                    text = if (available) "●" else "●"
                    textSize = 12f
                    setTextColor(
                        if (available) green
                        else 0xFFE58B8B.toInt()
                    )
                }

                row.addView(
                    dot,
                    LinearLayout.LayoutParams(
                        dp(24),
                        -2
                    )
                )

                row.addView(
                    text(
                        label,
                        13f,
                        textColor
                    ),
                    LinearLayout.LayoutParams(0, -2, 1f)
                )

                row.addView(
                    text(
                        if (available) "Available" else "Not available",
                        12f,
                        if (available) green else mutedColor,
                        true
                    )
                )

                statusPanel.addView(row)
            }

            addStatus(
                "Saved HTTP session",
                saved
            )

            addStatus(
                "Cloudflare browser cookies",
                clearance
            )

            val info = text(
                "Verification and session saving happen automatically when required.",
                12f,
                mutedColor
            ).apply {
                setPadding(0, dp(13), 0, 0)
            }

            statusPanel.addView(info)

            root.addView(
                statusPanel,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                ).apply {
                    topMargin = dp(20)
                }
            )

            // Actions

            fun makeButton(
                label: String,
                color: Int,
                foreground: Int
            ) = Button(ctx).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(foreground)
                minHeight = dp(48)
                minimumHeight = dp(48)
                background = rounded(color, 11)
                stateListAnimator = null
                elevation = 0f
            }

            val openButton = makeButton(
                "Open Website",
                purple,
                Color.WHITE
            )

            val clearButton = makeButton(
                "Clear Session",
                0xFF29202F.toInt(),
                0xFFFFA0A0.toInt()
            )

            val closeButton = makeButton(
                "Close",
                0xFF211B2B.toInt(),
                textColor
            )

            val actions = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }

            actions.addView(
                openButton,
                LinearLayout.LayoutParams(-1, dp(49)).apply {
                    topMargin = dp(18)
                }
            )

            val secondary = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            secondary.addView(
                clearButton,
                LinearLayout.LayoutParams(0, dp(47), 1f).apply {
                    marginEnd = dp(5)
                }
            )

            secondary.addView(
                closeButton,
                LinearLayout.LayoutParams(0, dp(47), 1f).apply {
                    marginStart = dp(5)
                }
            )

            actions.addView(
                secondary,
                LinearLayout.LayoutParams(-1, dp(47)).apply {
                    topMargin = dp(9)
                }
            )

            root.addView(actions)

            val footer = text(
                "ONLYKDRAMA  •  AUTOMATIC SESSION MANAGEMENT",
                9f,
                mutedColor,
                true
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(17), 0, 0)
            }

            root.addView(footer)

            val dialog = AlertDialog.Builder(ctx)
                .setView(root)
                .create()

            openButton.setOnClickListener {
                dialog.dismiss()

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

            clearButton.setOnClickListener {
                clearButton.isEnabled = false

                OnlyKdramaWebView.clearSiteCookies(ctx) {
                    Toast.makeText(
                        ctx,
                        "OnlyKDrama session cleared",
                        Toast.LENGTH_SHORT
                    ).show()

                    dialog.dismiss()
                }
            }

            closeButton.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()

            val screenWidth =
                ctx.resources.displayMetrics.widthPixels

            dialog.window?.apply {
                setBackgroundDrawableResource(
                    android.R.color.transparent
                )

                setGravity(Gravity.CENTER)

                setLayout(
                    minOf(dp(440), screenWidth - dp(32)),
                    -2
                )
            }
        }
    }
}
