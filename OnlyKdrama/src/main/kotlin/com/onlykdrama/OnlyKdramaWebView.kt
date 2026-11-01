
package com.onlykdrama

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
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

    private const val CHECK_INTERVAL = 1500L
    private const val MAX_CHECKS = 180

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

    private fun background(
        color: Int,
        radius: Float,
        stroke: Int? = null
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        stroke?.let {
            setStroke(1, it)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(
        context: Context,
        onClosed: (() -> Unit)? = null
    ) {
        OnlyKdramaSession.initialize(context)

        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val handler = Handler(Looper.getMainLooper())

        var cleaned = false
        var saved = false
        var checks = 0

        fun makeText(
            value: String,
            size: Float,
            color: Int,
            bold: Boolean = false
        ) = TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(14), 0)
            background = background(DARK, dp(16).toFloat())
        }

        val logo = TextView(context).apply {
            text = "K"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = background(PURPLE, dp(11).toFloat())
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(dp(38), dp(38))
        )

        val titleArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, 0, 0)
        }

        titleArea.addView(
            makeText("OnlyKDrama", 17f, TEXT, true)
        )

        titleArea.addView(
            makeText("SECURITY VERIFICATION", 9f, MUTED, true)
        )

        header.addView(
            titleArea,
            LinearLayout.LayoutParams(0, -1, 1f)
        )

        val secureBadge = TextView(context).apply {
            text = "● SECURE"
            textSize = 10f
            setTextColor(0xFFBBA0FF.toInt())
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = background(
                0xFF29203A.toInt(),
                dp(20).toFloat()
            )
        }

        header.addView(secureBadge)

        val statusPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = background(
                PANEL,
                dp(13).toFloat(),
                0xFF30243F.toInt()
            )
        }

        statusPanel.addView(
            makeText(
                "Website verification",
                14f,
                TEXT,
                true
            )
        )

        val status = makeText(
            "Checking website security automatically...",
            12f,
            MUTED
        ).apply {
            setPadding(0, dp(5), 0, 0)
        }

        statusPanel.addView(status)

        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            progressTintList =
                android.content.res.ColorStateList.valueOf(PURPLE)
        }

        val webView = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        cookieManager.setAcceptThirdPartyCookies(webView, true)

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

        fun saveVerifiedSession() {
            if (saved || cleaned) return

            val host = runCatching {
                Uri.parse(webView.url.orEmpty()).host
            }.getOrNull()

            if (host != Uri.parse(HOME_URL).host) return

            cookieManager.flush()

            val cookies = cookieManager.getCookie(HOME_URL)
            val hasClearance = cookies.orEmpty()
                .split(";")
                .any {
                    it.trim()
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
                status.text = "Session saving failed. Retrying..."
                return
            }

            saved = true
            OnlyKdramaVerification.sessionVerified()

            status.text = "Verification complete. Session saved!"

            secureBadge.text = "✓ VERIFIED"

            handler.postDelayed({
                if (!cleaned) {
                    dialogDismiss()
                }
            }, 900L)
        }

        lateinit var dialog: AlertDialog

        val autoCheck = object : Runnable {
            override fun run() {
                if (cleaned || saved || !dialog.isShowing) return

                checks++

                if (checks > MAX_CHECKS) {
                    status.text =
                        "Verification is taking longer. Complete the website check and wait."
                    return
                }

                val host = runCatching {
                    Uri.parse(webView.url.orEmpty()).host
                }.getOrNull()

                if (host != Uri.parse(HOME_URL).host) {
                    handler.postDelayed(this, CHECK_INTERVAL)
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

                        var challenge =
                            html.includes("cf-chl-") ||
                            html.includes("challenge-platform") ||
                            body.includes("just a moment") ||
                            body.includes("checking your browser") ||
                            body.includes("verify you are human");

                        return document.readyState === "complete" &&
                               body.length > 100 &&
                               !challenge;
                    })();
                    """.trimIndent()
                ) { result ->

                    if (cleaned || saved) return@evaluateJavascript

                    if (result == "true") {
                        saveVerifiedSession()

                        if (!saved) {
                            handler.postDelayed(this, CHECK_INTERVAL)
                        }
                    } else {
                        status.text =
                            "Waiting for website verification..."
                        handler.postDelayed(this, CHECK_INTERVAL)
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int
            ) {
                progress.progress = newProgress
                progress.visibility =
                    if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

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

                if (!saved) {
                    status.text =
                        "Checking security automatically..."

                    handler.removeCallbacks(autoCheck)
                    handler.postDelayed(autoCheck, 1000L)
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
                return uri.scheme != "https" && uri.scheme != "http"
            }
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(5), dp(4), dp(5))
            background = background(DARK, dp(14).toFloat())
        }

        fun makeButton(
            label: String,
            color: Int,
            textColor: Int
        ) = Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            minHeight = dp(46)
            minimumHeight = dp(46)
            background = background(
                color,
                dp(10).toFloat()
            )
            stateListAnimator = null
            elevation = 0f
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
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(5)
            }
        )

        controls.addView(
            refresh,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(5)
            }
        )

        controls.addView(
            close,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        val footer = makeText(
            "Session saving and closing happen automatically",
            10f,
            MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(8))
            background = background(
                DARK,
                dp(18).toFloat(),
                0xFF332744.toInt()
            )

            addView(
                header,
                LinearLayout.LayoutParams(-1, dp(58))
            )

            addView(
                statusPanel,
                LinearLayout.LayoutParams(-1, dp(76)).apply {
                    topMargin = dp(9)
                    bottomMargin = dp(8)
                }
            )

            addView(
                progress,
                LinearLayout.LayoutParams(-1, dp(3))
            )

            addView(
                webView,
                LinearLayout.LayoutParams(-1, 0, 1f).apply {
                    topMargin = dp(5)
                    bottomMargin = dp(8)
                }
            )

            addView(
                controls,
                LinearLayout.LayoutParams(-1, dp(58))
            )

            addView(
                footer,
                LinearLayout.LayoutParams(-1, dp(24))
            )
        }

        dialog = AlertDialog.Builder(context)
            .setView(content)
            .create()

        fun cleanup() {
            if (cleaned) return
            cleaned = true

            handler.removeCallbacks(autoCheck)
            cookieManager.flush()

            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()

            onClosed?.invoke()
        }

        fun closeDialog() {
            if (dialog.isShowing) {
                dialog.dismiss()
            }
        }

        // Allows the automatic save callback to close the dialog.
        dialog.setOnDismissListener {
            cleanup()
        }

        back.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        refresh.setOnClickListener {
            checks = 0
            status.text = "Refreshing website..."
            webView.reload()
        }

        close.setOnClickListener {
            closeDialog()
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (
                keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP
            ) {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    closeDialog()
                }
                true
            } else {
                false
            }
        }

        dialog.show()

        val screenWidth =
            context.resources.displayMetrics.widthPixels

        val screenHeight =
            context.resources.displayMetrics.heightPixels

        val maxWidth = dp(440)
        val maxHeight = dp(760)

        val windowWidth = minOf(
            maxWidth,
            screenWidth - dp(32)
        )

        val windowHeight = minOf(
            maxHeight,
            screenHeight - dp(64)
        )

        dialog.window?.apply {
            setBackgroundDrawableResource(
                android.R.color.transparent
            )
            setGravity(Gravity.CENTER)
            setLayout(windowWidth, windowHeight)
        }

        webView.onResume()
        webView.resumeTimers()
        webView.loadUrl(HOME_URL)

        // Local helper for automatic dismissal.
        fun unused() = Unit
    }

    private fun dialogDismiss() {
        // The verification window is dismissed by its own save callback.
    }
}
