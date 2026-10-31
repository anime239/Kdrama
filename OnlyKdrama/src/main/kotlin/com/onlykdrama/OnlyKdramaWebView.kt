
package com.onlykdrama

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.app.AlertDialog

object OnlyKdramaWebView {

    private const val HOME_URL = "https://onlykdrama.shop/"

    @SuppressLint("SetJavaScriptEnabled")
    fun open(context: Context) {

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        val webView = WebView(context)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                super.onPageFinished(view, url)
                cookies.flush()
            }
        }

        webView.webChromeClient = WebChromeClient()

        val progress = ProgressBar(context, null,
            android.R.attr.progressBarStyleHorizontal)

        val status = TextView(context).apply {
            text = "Opening OnlyKDrama..."
            setPadding(12, 8, 12, 8)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                status.text = "Loading website..."
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                progress.visibility = View.GONE
                status.text = "Website loaded"
                cookies.flush()
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    status.text = "Unable to load website"
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
            addView(refresh, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(close, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
        }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(progress)
            addView(webView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(buttons)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("OnlyKDrama Browser")
            .setView(layout)
            .create()

        close.setOnClickListener {
            cookies.flush()
            webView.stopLoading()
            webView.destroy()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            cookies.flush()
            webView.stopLoading()
            webView.destroy()
        }

        dialog.setOnCancelListener {
            cookies.flush()
        }

        dialog.show()

        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (context.resources.displayMetrics.heightPixels * 0.85).toInt()
        )

        webView.loadUrl(HOME_URL)
    }
}
