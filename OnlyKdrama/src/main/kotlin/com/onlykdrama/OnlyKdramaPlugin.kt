package com.onlykdrama

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
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

            OnlyKdramaSession.initialize(ctx)
            OnlyKdramaVerification.initialize(ctx)

            val density = ctx.resources.displayMetrics.density

            fun dp(value: Int): Int =
                (value * density).toInt()

            fun dpF(value: Float): Float =
                value * density

            // Creamy Strawberry Rom-Com Palette
            val creamCanvas = 0xFFFFF7F9.toInt()
            val creamBorder = 0xFFFFD1DF.toInt()
            val roseBanner = 0xFFFF7597.toInt()
            val blushTile = 0xFFFFE3EC.toInt()
            val blushStroke = 0xFFFFC2D6.toInt()
            val lavenderTile = 0xFFF4E4FF.toInt()
            val lavenderStroke = 0xFFE2C6FF.toInt()
            val espressoInk = 0xFF1E0F16.toInt()
            val mutedPlum = 0xFF7A5666.toInt()
            val darkDock = 0xFF1A0E14.toInt()
            val darkPillSecondary = 0xFF331B26.toInt()
            val softRoseText = 0xFFFFB3C6.toInt()

            fun rounded(
                color: Int,
                radiusDp: Int,
                strokeColor: Int? = null,
                strokeWidthDp: Float = 1.2f
            ) = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dpF(radiusDp.toFloat())
                strokeColor?.let {
                    setStroke(dpF(strokeWidthDp).toInt().coerceAtLeast(1), it)
                }
            }

            fun loveBannerDrawable(radiusDp: Int): Drawable = object : Drawable() {
                private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = roseBanner
                    style = Paint.Style.FILL
                }
                private val softCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x26FFFFFF
                    style = Paint.Style.FILL
                }
                private val blushCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x38FFE3EC
                    style = Paint.Style.FILL
                }
                private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x55FFF7F9
                    style = Paint.Style.FILL
                }
                private val clipPath = Path()
                private val rect = RectF()

                private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, size: Float) {
                    val path = Path()
                    val topCurve = size * 0.3f
                    path.moveTo(cx, cy + topCurve)
                    path.cubicTo(cx, cy, cx - size / 2f, cy, cx - size / 2f, cy + topCurve)
                    path.cubicTo(
                        cx - size / 2f,
                        cy + (size + topCurve) / 2f,
                        cx,
                        cy + (size + topCurve) / 1.4f,
                        cx,
                        cy + size
                    )
                    path.cubicTo(
                        cx,
                        cy + (size + topCurve) / 1.4f,
                        cx + size / 2f,
                        cy + (size + topCurve) / 2f,
                        cx + size / 2f,
                        cy + topCurve
                    )
                    path.cubicTo(cx + size / 2f, cy, cx, cy, cx, cy + topCurve)
                    path.close()
                    canvas.drawPath(path, artPaint)
                }

                private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
                    val path = Path().apply {
                        moveTo(cx, cy - r)
                        quadTo(cx, cy, cx + r, cy)
                        quadTo(cx, cy, cx, cy + r)
                        quadTo(cx, cy, cx - r, cy)
                        quadTo(cx, cy, cx, cy - r)
                        close()
                    }
                    canvas.drawPath(path, artPaint)
                }

                override fun draw(canvas: Canvas) {
                    val w = bounds.width().toFloat()
                    val h = bounds.height().toFloat()
                    if (w <= 0f || h <= 0f) return

                    rect.set(0f, 0f, w, h)
                    val radius = dpF(radiusDp.toFloat())

                    canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    canvas.drawRect(rect, bgPaint)

                    canvas.drawCircle(w * 0.88f, h * 0.18f, h * 0.52f, softCirclePaint)
                    canvas.drawCircle(w * 0.72f, h * 0.92f, h * 0.38f, blushCirclePaint)
                    canvas.drawCircle(w * 0.12f, h * 0.95f, h * 0.28f, softCirclePaint)

                    drawHeart(canvas, w * 0.63f, h * 0.22f, dpF(14f))
                    drawHeart(canvas, w * 0.91f, h * 0.62f, dpF(10f))
                    drawSparkle(canvas, w * 0.54f, h * 0.68f, dpF(8f))
                    drawSparkle(canvas, w * 0.76f, h * 0.30f, dpF(6f))

                    canvas.restore()
                }

                override fun setAlpha(alpha: Int) {
                    bgPaint.alpha = alpha
                }

                override fun setColorFilter(colorFilter: ColorFilter?) {
                    bgPaint.colorFilter = colorFilter
                }

                @Deprecated("Deprecated in Java")
                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }

            fun makeText(
                value: String,
                size: Float,
                color: Int,
                bold: Boolean = false
            ) = TextView(ctx).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) {
                    typeface = Typeface.DEFAULT_BOLD
                }
            }

            val saved = OnlyKdramaSession.hasSavedSession(ctx)
            val clearance = OnlyKdramaWebView.hasSiteCookies()

            // Outer Creamy Card Shell
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = rounded(
                    color = creamCanvas,
                    radiusDp = 28,
                    strokeColor = creamBorder,
                    strokeWidthDp = 1.5f
                )
            }

            // Top Love-Art Banner Card
            val heroBanner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = loveBannerDrawable(22)
            }

            val bannerTopRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val titleCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }

            titleCol.addView(
                makeText(
                    "OnlyKDrama ♡",
                    20f,
                    creamCanvas,
                    bold = true
                )
            )

            titleCol.addView(
                makeText(
                    "ROM-COM SESSION PASS",
                    10f,
                    0xFFFFE6EE.toInt(),
                    bold = true
                ).apply {
                    setPadding(0, dp(2), 0, 0)
                }
            )

            bannerTopRow.addView(
                titleCol,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            val emblem = TextView(ctx).apply {
                text = "K♡"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(roseBanner)
                background = rounded(creamCanvas, 22)
            }

            bannerTopRow.addView(
                emblem,
                LinearLayout.LayoutParams(dp(44), dp(44))
            )

            heroBanner.addView(bannerTopRow)

            // Embedded dark pill button inside banner
            val bannerActionRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, 0)
            }

            val openButton = Button(ctx).apply {
                text = "Open Website  ↗"
                isAllCaps = false
                maxLines = 1
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(creamCanvas)
                minHeight = dp(38)
                minimumHeight = dp(38)
                setPadding(dp(16), 0, dp(16), 0)
                background = rounded(espressoInk, 20)
                stateListAnimator = null
                elevation = 0f
            }

            bannerActionRow.addView(
                openButton,
                LinearLayout.LayoutParams(-2, dp(38))
            )

            val activeTag = TextView(ctx).apply {
                text = if (saved || clearance) "● READY" else "● STANDBY"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(creamCanvas)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = rounded(0x331E0F16, 16)
            }

            bannerActionRow.addView(
                LinearLayout(ctx),
                LinearLayout.LayoutParams(0, 1, 1f)
            )

            bannerActionRow.addView(activeTag)

            heroBanner.addView(bannerActionRow)

            root.addView(
                heroBanner,
                LinearLayout.LayoutParams(-1, -2)
            )

            // Section Title
            val sectionHeader = makeText(
                "Session status",
                15f,
                espressoInk,
                bold = true
            ).apply {
                setPadding(dp(4), dp(16), dp(4), dp(10))
            }
            root.addView(sectionHeader)

            // 2-Column Pastel Bento Status Grid
            fun buildBentoTile(
                iconSymbol: String,
                iconBg: Int,
                iconColor: Int,
                label: String,
                available: Boolean,
                tileBg: Int,
                tileBorder: Int
            ): LinearLayout {
                return LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = rounded(tileBg, 20, tileBorder, 1.2f)

                    val topRow = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val iconBadge = TextView(ctx).apply {
                        text = iconSymbol
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setTextColor(iconColor)
                        background = rounded(iconBg, 17)
                    }

                    topRow.addView(
                        iconBadge,
                        LinearLayout.LayoutParams(dp(34), dp(34))
                    )

                    val dot = TextView(ctx).apply {
                        text = "●"
                        textSize = 11f
                        gravity = Gravity.END
                        setTextColor(
                            if (available) 0xFFE63973.toInt()
                            else 0xFFB89AA7.toInt()
                        )
                    }

                    topRow.addView(
                        dot,
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )

                    addView(topRow)

                    addView(
                        makeText(
                            if (available) "Ready" else "Empty",
                            18f,
                            espressoInk,
                            bold = true
                        ).apply {
                            setPadding(0, dp(10), 0, dp(2))
                        }
                    )

                    addView(
                        makeText(
                            label,
                            11f,
                            mutedPlum,
                            bold = true
                        )
                    )
                }
            }

            val bentoRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val httpTile = buildBentoTile(
                iconSymbol = "♥",
                iconBg = roseBanner,
                iconColor = creamCanvas,
                label = "HTTP Session",
                available = saved,
                tileBg = blushTile,
                tileBorder = blushStroke
            )

            val cookieTile = buildBentoTile(
                iconSymbol = "✦",
                iconBg = 0xFFB983FF.toInt(),
                iconColor = creamCanvas,
                label = "Browser Cookies",
                available = clearance,
                tileBg = lavenderTile,
                tileBorder = lavenderStroke
            )

            bentoRow.addView(
                httpTile,
                LinearLayout.LayoutParams(0, -1, 1f).apply {
                    marginEnd = dp(6)
                }
            )

            bentoRow.addView(
                cookieTile,
                LinearLayout.LayoutParams(0, -1, 1f).apply {
                    marginStart = dp(6)
                }
            )

            root.addView(
                bentoRow,
                LinearLayout.LayoutParams(-1, -2)
            )

            // Cute helper note
            val noteText = makeText(
                "Verification & session saving run automatically when needed ♡",
                11f,
                mutedPlum
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(14))
            }
            root.addView(noteText)

            // Floating Dark Espresso Pill Dock
            val floatingDock = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(darkDock, 28)
            }

            val clearButton = Button(ctx).apply {
                text = "Clear Session"
                isAllCaps = false
                maxLines = 1
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(softRoseText)
                minHeight = dp(42)
                minimumHeight = dp(42)
                background = rounded(darkPillSecondary, 21)
                stateListAnimator = null
                elevation = 0f
            }

            val closeButton = Button(ctx).apply {
                text = "✕  Close"
                isAllCaps = false
                maxLines = 1
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(espressoInk)
                minHeight = dp(42)
                minimumHeight = dp(42)
                background = rounded(creamCanvas, 21)
                stateListAnimator = null
                elevation = 0f
            }

            floatingDock.addView(
                clearButton,
                LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    marginEnd = dp(6)
                }
            )

            floatingDock.addView(
                closeButton,
                LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    marginStart = dp(6)
                }
            )

            root.addView(
                floatingDock,
                LinearLayout.LayoutParams(-1, -2)
            )

            val dialog = AlertDialog.Builder(ctx)
                .setView(root)
                .create()

            openButton.setOnClickListener {
                dialog.dismiss()

                try {
                    OnlyKdramaWebView.open(
                        context = ctx,
                        clearStaleClearance = false
                    )
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
                        "OnlyKDrama session cleared ♡",
                        Toast.LENGTH_SHORT
                    ).show()

                    dialog.dismiss()
                }
            }

            closeButton.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()

            val screenWidth = ctx.resources.displayMetrics.widthPixels

            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                setGravity(Gravity.CENTER)
                setLayout(
                    minOf(dp(430), screenWidth - dp(32)),
                    -2
                )
            }
        }
    }
}
