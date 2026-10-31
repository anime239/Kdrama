package com.onlykdrama

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import android.widget.Toast

object OnlyKdramaWebView {

    const val HOME_URL = "https://onlykdrama.shop/"

    private val mainHandler = Handler(Looper.getMainLooper())

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

        if (cookies.isBlank()) {
            Toast.makeText(
                context,
                "No OnlyKDrama cookies found",
                Toast.LENGTH_SHORT
            ).show()

            onDone?.invoke()
            return
        }

        val names = cookies.split(";")
            .map { it.substringBefore("=").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (names.isEmpty()) {
            onDone?.invoke()
            return
        }

        val domains = listOf(
            "",
            "; Domain=onlykdrama.shop",
            "; Domain=.onlykdrama.shop"
        )

        val operations = names.flatMap { name ->
            domains.map { domain ->
                "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/$domain"
            }
        }

        val remaining = java.util.concurrent.atomic.AtomicInteger(
            operations.size
        )

        operations.forEach { cookie ->

            manager.setCookie(HOME_URL, cookie) {

                if (remaining.decrementAndGet() == 0) {

                    manager.flush()

                    mainHandler.post {
                        Toast.makeText(
                            context,
                            "OnlyKDrama cookie deletion completed",
                            Toast.LENGTH_SHORT
                        ).show()

                        onDone?.invoke()
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(context: Context) {

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val status = TextView(context).apply {
            text = "Opening OnlyKDrama..."
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
        }

        val webView = WebView(context)

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
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int
            ) {
                progress.progress = newProgress

                progress.visibility =
                    if (newProgress >= 100) View.GONE
                    else View.VISIBLE
            }

            override fun onReceivedTitle(
                view: WebView?,
                title: String?
            ) {
                if (!title.isNullOrBlank()) {
                    status.text = title
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?
            ) {
                status.text = "Loading website..."
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                cookieManager.flush()

                progress.visibility = View.GONE

                status.text =
                    "Page loaded. Complete any verification displayed."
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    status.text =
                        "Page error: ${error?.description ?: "Unknown error"}"
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                response: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    status.text =
                        "Website response: HTTP ${response?.statusCode ?: "error"}"
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
                status.text =
                    "Secure connection failed. Check device date and network."
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

        val back = Button(context).apply {
            text = "BACK"

            setOnClickListener {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
            }
        }

        val refresh = Button(context).apply {
            text = "REFRESH"
            setOnClickListener {
                webView.reload()
            }
        }

        val clear = Button(context).apply {
            text = "CLEAR COOKIES"

            setOnClickListener {
                clearSiteCookies(context) {
                    webView.clearHistory()
                    webView.reload()
                }
            }
        }

        val close = Button(context).apply {
            text = "CLOSE"
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL

            addView(back, LinearLayout.LayoutParams(0, -2, 1f))
            addView(refresh, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clear, LinearLayout.LayoutParams(0, -2, 1f))
            addView(close, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)

            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
            addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, -2))
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("OnlyKDrama Website")
            .setView(content)
            .create()

        var cleaned = false

        fun cleanup() {
            if (cleaned) return

            cleaned = true

            cookieManager.flush()
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }

        close.setOnClickListener {
            cleanup()
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
                    cleanup()
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

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * 0.90f).toInt()
        )

        webView.loadUrl(HOME_URL)
    }
}