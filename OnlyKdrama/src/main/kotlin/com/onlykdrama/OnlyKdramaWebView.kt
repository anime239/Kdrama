
package com.onlykdrama

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

object OnlyKdramaWebView {

    private const val TAG = "OnlyKDramaWebView"

    const val HOME_URL = "https://onlykdrama.shop/"

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
        onDone?.invoke()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(context: Context) {

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val density = context.resources.displayMetrics.density

        fun dp(value: Int): Int {
            return (value * density).toInt()
        }

        // Header

        val header = TextView(context).apply {
            text = "OnlyKDrama · Cloudflare Verification"
            textSize = 19f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
            setBackgroundColor(Color.rgb(45, 45, 45))
        }

        // Status

        val status = TextView(context).apply {
            text = "Opening OnlyKDrama..."
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(65, 65, 65))
        }

        // Loading progress

        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
        }

        // WebView

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

        // Browser console and progress

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int
            ) {
                progress.progress = newProgress

                progress.visibility =
                    if (newProgress >= 100) View.GONE
                    else View.VISIBLE

                if (newProgress < 100) {
                    status.text = "Loading website... $newProgress%"
                }
            }

            override fun onReceivedTitle(
                view: WebView?,
                title: String?
            ) {
                if (!title.isNullOrBlank()) {
                    Log.d(TAG, "Page title: $title")
                }
            }

            override fun onConsoleMessage(
                consoleMessage: ConsoleMessage
            ): Boolean {

                val message =
                    "${consoleMessage.message()} " +
                    "(line ${consoleMessage.lineNumber()}, " +
                    "${consoleMessage.sourceId()})"

                Log.e(TAG, message)

                status.post {
                    status.text = "Website script: ${consoleMessage.message()}"
                }

                return true
            }
        }

        // Browser navigation and errors

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?
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

                status.text =
                    "Website loaded. Complete verification if requested."

                view?.post {
                    view.requestLayout()
                    view.invalidate()

                    view.evaluateJavascript(
                        "window.dispatchEvent(new Event('resize'));",
                        null
                    )
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val message =
                        error?.description?.toString() ?: "Unknown error"

                    status.text = "Page error: $message"
                    Log.e(TAG, "Main frame error: $message")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                response: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    val code = response?.statusCode ?: -1

                    status.text = "Website HTTP error: $code"
                    Log.e(TAG, "Main frame HTTP error: $code")
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()

                status.text = "Secure connection failed."

                Log.e(TAG, "SSL error: $error")
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {

                val uri: Uri = request?.url ?: return false

                return uri.scheme != "https" &&
                    uri.scheme != "http"
            }
        }

        // Bottom controls

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        fun makeButton(label: String): Button {
            return Button(context).apply {
                text = label
                isAllCaps = false
                minHeight = dp(48)
                minimumHeight = dp(48)
            }
        }

        val back = makeButton("BACK")
        val refresh = makeButton("REFRESH")
        val close = makeButton("CLOSE")

        controls.addView(
            back,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        controls.addView(
            refresh,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        controls.addView(
            close,
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )

        // Main layout

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)

            addView(
                header,
                LinearLayout.LayoutParams(-1, dp(54))
            )

            addView(
                status,
                LinearLayout.LayoutParams(-1, dp(48))
            )

            addView(
                progress,
                LinearLayout.LayoutParams(-1, dp(3))
            )

            addView(
                webView,
                LinearLayout.LayoutParams(-1, 0, 1f)
            )

            addView(
                controls,
                LinearLayout.LayoutParams(-1, dp(60))
            )
        }

        // Dialog

        val dialog = AlertDialog.Builder(context)
            .setView(content)
            .create()

        var cleaned = false

        fun cleanup() {
            if (cleaned) return

            cleaned = true

            cookieManager.flush()

            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }

        // Button actions

        back.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        refresh.setOnClickListener {
            status.text = "Refreshing website..."
            webView.reload()
        }

        close.setOnClickListener {
            dialog.dismiss()
        }

        // Android back button

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

        // Display with stable dimensions

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
