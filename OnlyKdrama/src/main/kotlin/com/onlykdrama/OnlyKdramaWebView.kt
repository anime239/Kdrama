
package com.onlykdrama

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.webkit.SslErrorHandler

object OnlyKdramaWebView {

    private const val HOME_URL = "https://onlykdrama.shop/"

    @SuppressLint("SetJavaScriptEnabled")
    fun open(context: Context) {

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

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

            builtInZoomControls = true
            displayZoomControls = false

            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)

            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            allowFileAccess = false
            allowContentAccess = true
        }

        val status = TextView(context).apply {
            text = "Opening OnlyKDrama..."
            textSize = 13f
            setPadding(12, 8, 12, 8)
        }

        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int
            ) {
                progress.progress = newProgress

                if (newProgress >= 100) {
                    progress.visibility = View.GONE
                } else {
                    progress.visibility = View.VISIBLE
                }
            }

            override fun onReceivedTitle(
                view: WebView?,
                title: String?
            ) {
                status.text = title ?: "OnlyKDrama"
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
                progress.progress = 0
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                cookieManager.flush()
                progress.progress = 100
                progress.visibility = View.GONE

                status.text = url ?: "Website loaded"
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    status.text =
                        "Page error: ${error?.description ?: "Unable to load"}"
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

            addView(
                back,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            addView(
                refresh,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            addView(
                close,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL

            addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                progress,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    4
                )
            )

            addView(
                webView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

            addView(buttons)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("OnlyKDrama Browser")
            .setView(layout)
            .create()

        var destroyed = false

        fun closeBrowser() {
            if (destroyed) return

            destroyed = true

            cookieManager.flush()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()

            dialog.dismiss()
        }

        close.setOnClickListener {
            closeBrowser()
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (
                keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP
            ) {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    closeBrowser()
                }
                true
            } else {
                false
            }
        }

        dialog.setOnDismissListener {
            if (!destroyed) {
                destroyed = true
                cookieManager.flush()
                webView.stopLoading()
                webView.destroy()
            }
        }

        dialog.show()

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * 0.90).toInt()
        )

        webView.loadUrl(HOME_URL)
    }
}
