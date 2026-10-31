
package com.onlykdrama

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
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

object OnlyKdramaWebView {

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
        fun dp(value: Int): Int = (value * density).toInt()

        val status = TextView(context).apply {
            text = "Opening OnlyKDrama..."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
        }

        val webView = WebView(context).apply {
            setBackgroundColor(Color.WHITE)
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
                    "Complete any website verification shown below."
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
                        "Website HTTP error: ${response?.statusCode ?: "Unknown"}"
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
                status.text =
                    "Secure connection failed. Check your network and device date."
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

        val close = Button(context).apply {
            text = "CLOSE"
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL

            addView(
                back,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            addView(
                refresh,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            addView(
                close,
                LinearLayout.LayoutParams(0, -2, 1f)
            )
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)

            addView(
                status,
                LinearLayout.LayoutParams(-1, -2)
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
                LinearLayout.LayoutParams(-1, -2)
            )
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("OnlyKDrama · Cloudflare Verification")
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
