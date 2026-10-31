
package com.onlykdrama

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.webkit.*
import android.widget.*

object OnlyKdramaWebView {

    private const val HOME_URL = "https://onlykdrama.shop/"

    @SuppressLint("SetJavaScriptEnabled")
    fun open(context: Context) {

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val webView = WebView(context).apply {
            setBackgroundColor(Color.WHITE)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
            setSupportMultipleWindows(true)
            mixedContentMode =
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true
        }

        val status = TextView(context).apply {
            text = "Connecting to OnlyKDrama..."
            textSize = 13f
            setPadding(12, 10, 12, 10)
        }

        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
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

            override fun onReceivedTitle(
                view: WebView?,
                title: String?
            ) {
                if (!title.isNullOrBlank()) {
                    status.text = title
                }
            }

            override fun onConsoleMessage(
                consoleMessage: ConsoleMessage
            ): Boolean {
                if (consoleMessage.messageLevel() ==
                    ConsoleMessage.MessageLevel.ERROR
                ) {
                    status.text =
                        "Website script error: ${consoleMessage.message()}"
                }
                return true
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

                status.text = "Loaded: ${url ?: "Unknown URL"}"
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    status.text =
                        "Page error: ${error?.description}"
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    status.text =
                        "HTTP error: ${errorResponse?.statusCode}"
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
                status.text = "SSL certificate error"
            }
        }

        val back = Button(context).apply {
            text = "Back"
            setOnClickListener {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
            }
        }

        val refresh = Button(context).apply {
            text = "Refresh"
            setOnClickListener {
                webView.reload()
            }
        }

        val close = Button(context).apply {
            text = "Close"
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL

            addView(back, LinearLayout.LayoutParams(0, -2, 1f))
            addView(refresh, LinearLayout.LayoutParams(0, -2, 1f))
            addView(close, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)

            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(progress, LinearLayout.LayoutParams(-1, 4))
            addView(
                webView,
                LinearLayout.LayoutParams(-1, 0, 1f)
            )
            addView(buttons, LinearLayout.LayoutParams(-1, -2))
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("OnlyKDrama Browser")
            .setView(layout)
            .create()

        var destroyed = false

        fun cleanup() {
            if (destroyed) return
            destroyed = true

            cookieManager.flush()
            webView.stopLoading()
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
            (context.resources.displayMetrics.heightPixels * 0.90).toInt()
        )

        webView.loadUrl(HOME_URL)
    }
}
