
package com.onlykdrama

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
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

        manager.removeAllCookies {
            manager.flush()
            OnlyKdramaSession.clear(context)
            onDone?.invoke()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(
        context: Context,
        onClosed: (() -> Unit)? = null
    ) {
        OnlyKdramaSession.initialize(context)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val header = TextView(context).apply {
            text = "OnlyKDrama Verification"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
            setBackgroundColor(Color.rgb(45, 45, 45))
        }

        val status = TextView(context).apply {
            text = "Checking website security automatically..."
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(65, 65, 65))
        }

        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
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

        val handler = Handler(Looper.getMainLooper())

        lateinit var dialog: AlertDialog

        var cleaned = false
        var saved = false
        var checks = 0

        fun saveVerifiedSession() {
            if (saved || cleaned) return

            val currentUrl = webView.url.orEmpty()
            val host = runCatching {
                Uri.parse(currentUrl).host
            }.getOrNull()

            if (host != Uri.parse(HOME_URL).host) return

            cookieManager.flush()

            val cookies = cookieManager.getCookie(HOME_URL)
            val hasClearance = cookies.orEmpty()
                .split(";")
                .any {
                    it.trim().substringBefore("=")
                        .equals("cf_clearance", true) &&
                    it.contains("=")
                }

            if (!hasClearance) {
                status.text =
                    "Verification finished. Waiting for Cloudflare clearance cookie..."
                return
            }

            val userAgent = webView.settings.userAgentString

            if (
                !OnlyKdramaSession.save(
                    context,
                    cookies,
                    userAgent
                )
            ) {
                status.text = "Session saving failed. Retrying..."
                return
            }

            saved = true
            OnlyKdramaVerification.sessionVerified()

            status.text = "Verification complete. Session saved automatically."

            handler.postDelayed({
                if (!cleaned && dialog.isShowing) {
                    dialog.dismiss()
                }
            }, 700L)
        }

        val autoCheck = object : Runnable {

            override fun run() {
                if (cleaned || saved || !dialog.isShowing) return

                checks++

                if (checks > MAX_CHECKS) {
                    status.text =
                        "Verification is taking longer than expected. Complete the website check and wait."
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

                        var ready =
                            document.readyState === "complete" &&
                            body.length > 100;

                        return ready && !challenge;
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
                            "Waiting for Cloudflare verification..."
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
                        "Checking verification automatically..."

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
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
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
            setBackgroundColor(Color.WHITE)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        fun makeButton(label: String) = Button(context).apply {
            text = label
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            textSize = 11f
        }

        val back = makeButton("BACK")
        val refresh = makeButton("REFRESH")
        val close = makeButton("CLOSE")

        controls.addView(
            back,
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        controls.addView(
            refresh,
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        controls.addView(
            close,
            LinearLayout.LayoutParams(0, dp(54), 1f)
        )

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)

            addView(header, LinearLayout.LayoutParams(-1, dp(54)))
            addView(status, LinearLayout.LayoutParams(-1, dp(52)))
            addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
            addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, dp(60)))
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

        dialog.show()

        val metrics = context.resources.displayMetrics

        dialog.window?.apply {
            setBackgroundDrawableResource(
                android.R.color.transparent
            )
            setGravity(Gravity.CENTER)
            setLayout(
                metrics.widthPixels - dp(24),
                metrics.heightPixels - dp(100)
            )
        }

        webView.onResume()
        webView.resumeTimers()
        webView.loadUrl(HOME_URL)
    }
}
