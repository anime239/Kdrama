
package com.onlykdrama

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

object OnlyKdramaWebView {

    const val HOME_URL = "https://onlykdrama.shop/"

    private const val PURPLE = 0xFF9B55E8.toInt()
    private const val DARK = 0xFF09090F.toInt()
    private const val PANEL = 0xFF171421.toInt()
    private const val TEXT = 0xFFF5F0FA.toInt()
    private const val MUTED = 0xFFA69CAF.toInt()

    private const val CHECK_INTERVAL = 2000L

    fun hasSiteCookies(): Boolean {
        return CookieManager.getInstance()
            .getCookie(HOME_URL)
            .orEmpty()
            .isNotBlank()
    }

    fun clearSiteCookies(
        context: Context,
        onDone: (() -> Unit)? = null
    ) {
        val manager = CookieManager.getInstance()
        val cookies = manager.getCookie(HOME_URL).orEmpty()

        cookies.split(";")
            .map { it.substringBefore("=").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { name ->
                manager.setCookie(
                    HOME_URL,
                    "$name=; Max-Age=0; path=/"
                )
            }

        manager.flush()
        OnlyKdramaSession.clear(context)
        onDone?.invoke()
    }

    private fun roundedBackground(
        color: Int,
        radius: Float,
        stroke: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            stroke?.let {
                setStroke(1, it)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(
        context: Context,
        onClosed: (() -> Unit)? = null
    ) {
        OnlyKdramaSession.initialize(context)

        val density = context.resources.displayMetrics.density

        fun dp(value: Int): Int =
            (value * density).toInt()

        val screenWidth =
            context.resources.displayMetrics.widthPixels

        val screenHeight =
            context.resources.displayMetrics.heightPixels

        val windowWidth = minOf(
            dp(440),
            screenWidth - dp(32)
        ).coerceAtLeast(dp(280))

        val windowHeight = minOf(
            dp(760),
            screenHeight - dp(64)
        ).coerceAtLeast(dp(400))

        val webViewHeight = (
            windowHeight - dp(270)
        ).coerceAtLeast(dp(130))

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val handler = Handler(Looper.getMainLooper())

        var cleaned = false
        var saved = false
        var checking = false
        var rateLimited = false

        lateinit var dialog: AlertDialog

        fun makeText(
            value: String,
            size: Float,
            color: Int,
            bold: Boolean = false
        ): TextView {
            return TextView(context).apply {
                text = value
                textSize = size
                setTextColor(color)

                if (bold) {
                    setTypeface(null, Typeface.BOLD)
                }
            }
        }

        // Header

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(14), 0)
            background = roundedBackground(
                DARK,
                dp(16).toFloat()
            )
        }

        val logo = TextView(context).apply {
            text = "K"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(
                PURPLE,
                dp(12).toFloat()
            )
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(dp(44), dp(44))
        )

        val titleArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        titleArea.addView(
            makeText("OnlyKDrama", 18f, TEXT, true)
        )

        titleArea.addView(
            makeText(
                "SECURITY VERIFICATION",
                10f,
                MUTED,
                true
            )
        )

        header.addView(
            titleArea,
            LinearLayout.LayoutParams(0, -1, 1f)
        )

        val secureBadge = TextView(context).apply {
            text = "● SECURE"
            textSize = 11f
            setTextColor(0xFFBBA0FF.toInt())
            setPadding(
                dp(11),
                dp(9),
                dp(11),
                dp(9)
            )
            background = roundedBackground(
                0xFF29203A.toInt(),
                dp(24).toFloat()
            )
        }

        header.addView(secureBadge)

        // Status panel

        val statusPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(15),
                dp(13),
                dp(15),
                dp(12)
            )
            background = roundedBackground(
                PANEL,
                dp(15).toFloat(),
                0xFF30243F.toInt()
            )
        }

        statusPanel.addView(
            makeText(
                "Website verification",
                15f,
                TEXT,
                true
            )
        )

        val status = makeText(
            "Checking website security automatically...",
            12f,
            MUTED
        ).apply {
            setPadding(0, dp(6), 0, 0)
        }

        statusPanel.addView(status)

        // Progress

        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            progressTintList =
                ColorStateList.valueOf(PURPLE)
        }

        // WebView

        val webView = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.VISIBLE
        }

        cookieManager.setAcceptThirdPartyCookies(
            webView,
            true
        )

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)

            mixedContentMode =
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        // Automatic session saving

        fun saveVerifiedSession() {
            if (saved || cleaned || rateLimited) return

            val currentHost = runCatching {
                Uri.parse(webView.url.orEmpty()).host
            }.getOrNull()

            if (currentHost != Uri.parse(HOME_URL).host) {
                return
            }

            cookieManager.flush()

            val cookies = cookieManager.getCookie(HOME_URL)

            val hasClearance = cookies.orEmpty()
                .split(";")
                .any { cookie ->
                    cookie.trim()
                        .substringBefore("=")
                        .equals("cf_clearance", true)
                }

            if (!hasClearance) {
                status.text =
                    "Waiting for Cloudflare clearance cookie..."
                return
            }

            val result = OnlyKdramaSession.save(
                context,
                cookies,
                webView.settings.userAgentString
            )

            if (!result) {
                status.text =
                    "Session saving failed. Retrying..."
                return
            }

            saved = true

            OnlyKdramaVerification.sessionVerified()

            status.text =
                "Verification complete. Session saved!"

            secureBadge.text = "✓ VERIFIED"

            secureBadge.setTextColor(
                0xFF9BE8B1.toInt()
            )

            handler.postDelayed({
                if (!cleaned && dialog.isShowing) {
                    dialog.dismiss()
                }
            }, 900L)
        }

        // Automatic Cloudflare detection

        val autoCheck = object : Runnable {

            override fun run() {
                if (
                    cleaned ||
                    saved ||
                    rateLimited ||
                    !dialog.isShowing
                ) {
                    return
                }

                if (checking) {
                    handler.postDelayed(
                        this,
                        CHECK_INTERVAL
                    )
                    return
                }

                checking = true

                val currentHost = runCatching {
                    Uri.parse(webView.url.orEmpty()).host
                }.getOrNull()

                if (currentHost != Uri.parse(HOME_URL).host) {
                    checking = false
                    handler.postDelayed(
                        this,
                        CHECK_INTERVAL
                    )
                    return
                }

                webView.evaluateJavascript(
                    """
                    (function() {
                        var html = document.documentElement
                            ? document.documentElement.innerHTML.toLowerCase()
                            : "";

                        var body = document.body
                            ? document.body.innerText.toLowerCase()
                            : "";

                        if (
                            html.includes("error 1015") ||
                            body.includes("error 1015") ||
                            body.includes("you are being rate limited") ||
                            body.includes("has banned you temporarily")
                        ) {
                            return 2;
                        }

                        var challenge =
                            html.includes("cf-chl-") ||
                            html.includes("challenge-platform") ||
                            body.includes("just a moment") ||
                            body.includes("checking your browser") ||
                            body.includes("verify you are human");

                        if (challenge) return 0;

                        return document.readyState === "complete" &&
                               body.length > 100 ? 1 : 0;
                    })();
                    """.trimIndent()
                ) { result ->

                    checking = false

                    if (cleaned || saved) {
                        return@evaluateJavascript
                    }

                    when (result) {

                        "2" -> {
                            rateLimited = true

                            handler.removeCallbacks(this)

                            progress.visibility = View.GONE

                            status.text =
                                "Cloudflare Error 1015: This connection is temporarily rate-limited. Close the popup and wait before retrying."

                            secureBadge.text = "RATE LIMITED"

                            secureBadge.setTextColor(
                                0xFFFF9999.toInt()
                            )
                        }

                        "1" -> {
                            saveVerifiedSession()
                        }

                        else -> {
                            status.text =
                                "Waiting for website verification..."
                        }
                    }

                    if (
                        !saved &&
                        !cleaned &&
                        !rateLimited
                    ) {
                        handler.postDelayed(
                            this,
                            CHECK_INTERVAL
                        )
                    }
                }
            }
        }

        // WebChromeClient

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int
            ) {
                progress.progress = newProgress

                progress.visibility =
                    if (newProgress >= 100) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
            }
        }

        // WebViewClient

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                status.text = "Loading OnlyKDrama..."
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                cookieManager.flush()

                progress.visibility = View.GONE

                if (!saved && !rateLimited) {
                    status.text =
                        "Checking website security automatically..."

                    handler.removeCallbacks(autoCheck)

                    handler.postDelayed(
                        autoCheck,
                        1000L
                    )
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    status.text =
                        "Connection error: ${error?.description}"
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                sslHandler: SslErrorHandler?,
                error: SslError?
            ) {
                sslHandler?.cancel()
                status.text = "Secure connection failed."
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false

                return uri.scheme != "https" &&
                    uri.scheme != "http"
            }
        }

        // Bottom controls

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(
                dp(4),
                dp(5),
                dp(4),
                dp(5)
            )
            background = roundedBackground(
                DARK,
                dp(14).toFloat()
            )
        }

        fun makeButton(
            label: String,
            color: Int,
            textColor: Int
        ): Button {
            return Button(context).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD

                setTextColor(textColor)

                minHeight = dp(48)
                minimumHeight = dp(48)

                background = roundedBackground(
                    color,
                    dp(11).toFloat()
                )

                stateListAnimator = null
                elevation = 0f
            }
        }

        val back = makeButton(
            "‹  Back",
            0xFF211B2B.toInt(),
            TEXT
        )

        val refresh = makeButton(
            "↻  Refresh",
            0xFF211B2B.toInt(),
            TEXT
        )

        val close = makeButton(
            "Close",
            PURPLE,
            Color.WHITE
        )

        controls.addView(
            back,
            LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            ).apply {
                marginEnd = dp(5)
            }
        )

        controls.addView(
            refresh,
            LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            ).apply {
                marginEnd = dp(5)
            }
        )

        controls.addView(
            close,
            LinearLayout.LayoutParams(
                0,
                dp(50),
                1f
            )
        )

        // Footer

        val footer = makeText(
            "Session saving and closing happen automatically",
            10f,
            MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(2))
        }

        // Main popup layout

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                dp(10),
                dp(10),
                dp(10),
                dp(8)
            )

            background = roundedBackground(
                DARK,
                dp(19).toFloat(),
                0xFF332744.toInt()
            )

            addView(
                header,
                LinearLayout.LayoutParams(
                    -1,
                    dp(58)
                )
            )

            addView(
                statusPanel,
                LinearLayout.LayoutParams(
                    -1,
                    dp(76)
                ).apply {
                    topMargin = dp(9)
                    bottomMargin = dp(8)
                }
            )

            addView(
                progress,
                LinearLayout.LayoutParams(
                    -1,
                    dp(3)
                )
            )

            addView(
                webView,
                LinearLayout.LayoutParams(
                    -1,
                    webViewHeight
                ).apply {
                    topMargin = dp(5)
                    bottomMargin = dp(8)
                }
            )

            addView(
                controls,
                LinearLayout.LayoutParams(
                    -1,
                    dp(58)
                )
            )

            addView(
                footer,
                LinearLayout.LayoutParams(
                    -1,
                    dp(24)
                )
            )
        }

        // Dialog

        dialog = AlertDialog.Builder(context)
            .setView(content)
            .create()

        fun cleanup() {
            if (cleaned) return

            cleaned = true

            handler.removeCallbacks(autoCheck)
            cookieManager.flush()

            webView.stopLoading()
            webView.onPause()
            webView.pauseTimers()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()

            onClosed?.invoke()
        }

        // Button actions

        back.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        refresh.setOnClickListener {
            if (saved || cleaned) return@setOnClickListener

            rateLimited = false
            checking = false

            secureBadge.text = "● SECURE"
            secureBadge.setTextColor(
                0xFFBBA0FF.toInt()
            )

            status.text = "Refreshing website..."

            handler.removeCallbacks(autoCheck)
            webView.reload()
        }

        close.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnKeyListener { _, keyCode, event ->

            if (
                keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP
            ) {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    dialog.dismiss()
                }

                true
            } else {
                false
            }
        }

        dialog.setOnDismissListener {
            cleanup()
        }

        // Show and measure popup before loading the website

        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawableResource(
                android.R.color.transparent
            )

            setGravity(Gravity.CENTER)

            setLayout(
                windowWidth,
                windowHeight
            )
        }

        // First layout pass

        content.post {
            if (cleaned || !dialog.isShowing) {
                return@post
            }

            content.requestLayout()
            webView.requestLayout()
            webView.invalidate()

            webView.onResume()
            webView.resumeTimers()

            webView.loadUrl(HOME_URL)

            handler.postDelayed(
                autoCheck,
                3000L
            )
        }
    }
}
