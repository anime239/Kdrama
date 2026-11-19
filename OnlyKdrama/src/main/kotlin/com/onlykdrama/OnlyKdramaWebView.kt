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
import android.view.WindowManager
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
import java.lang.ref.WeakReference

object OnlyKdramaWebView {

    const val HOME_URL = "https://onlykdrama.shop/"
    private const val BASE_DOMAIN_URL = "https://onlykdrama.shop"

    private const val SAKURA_PINK = 0xFFF48FB1.toInt()
    private const val BLUSH_PINK = 0xFFFFCAD4.toInt()
    private const val DARK = 0xFF0D070B.toInt()
    private const val PANEL = 0xFF1C1118.toInt()
    private const val ROSE_BORDER = 0xFF4A2536.toInt()
    private const val TEXT = 0xFFFFF5F8.toInt()
    private const val MUTED = 0xFFC7A8B5.toInt()

    private const val WATCH_INTERVAL = 700L

    @Volatile
    private var activeDialog: WeakReference<AlertDialog>? = null

    private fun extractCookieValue(cookies: String?, name: String): String? {
        return cookies.orEmpty()
            .split(";")
            .map { it.trim() }
            .firstOrNull { it.substringBefore("=").equals(name, true) }
            ?.substringAfter("=", "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun mergeCookies(vararg sources: String?): String {
        val map = linkedMapOf<String, String>()
        for (source in sources) {
            source.orEmpty().split(";").forEach { raw ->
                val trimmed = raw.trim()
                val idx = trimmed.indexOf('=')
                if (idx > 0) {
                    val key = trimmed.substring(0, idx).trim()
                    val value = trimmed.substring(idx + 1).trim()
                    if (key.isNotBlank() && value.isNotBlank()) {
                        map[key] = value
                    }
                }
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun hasSiteCookies(): Boolean {
        val manager = CookieManager.getInstance()
        val cookies = mergeCookies(
            manager.getCookie(HOME_URL),
            manager.getCookie(BASE_DOMAIN_URL)
        )
        return extractCookieValue(cookies, "cf_clearance") != null
    }

    private fun clearClearanceCookie(
        handler: Handler,
        onDone: () -> Unit
    ) {
        val manager = CookieManager.getInstance()
        val expired = "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
        val targets = listOf(
            HOME_URL to "cf_clearance=; $expired; path=/",
            HOME_URL to "cf_clearance=; $expired; domain=.onlykdrama.shop; path=/",
            BASE_DOMAIN_URL to "cf_clearance=; $expired; path=/",
            "https://www.onlykdrama.shop/" to "cf_clearance=; $expired; path=/"
        )

        var remaining = targets.size
        for ((url, cookie) in targets) {
            manager.setCookie(url, cookie) {
                handler.post {
                    remaining -= 1
                    if (remaining <= 0) {
                        manager.flush()
                        onDone()
                    }
                }
            }
        }
    }

    fun clearSiteCookies(
        context: Context,
        onDone: (() -> Unit)? = null
    ) {
        val manager = CookieManager.getInstance()
        val expired = "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
        val cookies = mergeCookies(
            manager.getCookie(HOME_URL),
            manager.getCookie(BASE_DOMAIN_URL)
        )

        cookies.split(";")
            .map { it.substringBefore("=").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { name ->
                manager.setCookie(HOME_URL, "$name=; $expired; path=/")
                manager.setCookie(BASE_DOMAIN_URL, "$name=; $expired; path=/")
                manager.setCookie(HOME_URL, "$name=; $expired; domain=.onlykdrama.shop; path=/")
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
        clearStaleClearance: Boolean = false,
        onClosed: ((verified: Boolean) -> Unit)? = null
    ) {
        activeDialog?.get()?.let { existing ->
            if (existing.isShowing) {
                runCatching { existing.dismiss() }
            }
        }

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
        ).coerceAtLeast(dp(420))

        val webViewHeight = (
            windowHeight - dp(325)
        ).coerceAtLeast(dp(150))

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val initialClearance = extractCookieValue(
            mergeCookies(
                cookieManager.getCookie(HOME_URL),
                cookieManager.getCookie(BASE_DOMAIN_URL)
            ),
            "cf_clearance"
        )

        val handler = Handler(Looper.getMainLooper())

        var cleaned = false
        var saved = false
        var warnedNoClearance = false

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
            setTextColor(0xFF230B16.toInt())
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(
                BLUSH_PINK,
                dp(13).toFloat()
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
            makeText("OnlyKDrama", 18f, BLUSH_PINK, true)
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
            text = "● PINK"
            textSize = 11f
            setTextColor(BLUSH_PINK)
            setPadding(
                dp(11),
                dp(9),
                dp(11),
                dp(9)
            )
            background = roundedBackground(
                0xFF331825.toInt(),
                dp(24).toFloat()
            )
        }

        header.addView(secureBadge)

        // Status panel

        val statusPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(15),
                dp(12),
                dp(15),
                dp(12)
            )
            background = roundedBackground(
                PANEL,
                dp(15).toFloat(),
                ROSE_BORDER
            )
        }

        statusPanel.addView(
            makeText(
                "Website verification",
                15f,
                BLUSH_PINK,
                true
            )
        )

        val status = makeText(
            "Complete verification below — saves & closes automatically.",
            12f,
            MUTED
        ).apply {
            setPadding(0, dp(5), 0, 0)
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
                ColorStateList.valueOf(SAKURA_PINK)
        }

        // WebView

        val webView = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.VISIBLE
            isFocusable = true
            isFocusableInTouchMode = true
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

        fun isExpectedHost(url: String?): Boolean {
            val host = runCatching {
                Uri.parse(url.orEmpty()).host.orEmpty()
            }.getOrDefault("")
            val targetHost = Uri.parse(HOME_URL).host.orEmpty()
            return host.equals(targetHost, true) ||
                host.endsWith(".$targetHost", true)
        }

        fun isChallengeTitle(title: String?): Boolean {
            val lower = title.orEmpty().trim().lowercase()
            if (lower.isBlank()) return true
            if (lower.startsWith("http://") || lower.startsWith("https://")) return true
            if (lower == "onlykdrama.shop" || lower == "onlykdrama.shop/") return true
            return lower.contains("just a moment") ||
                lower.contains("attention required") ||
                lower.contains("verify") ||
                lower.contains("cloudflare") ||
                lower.contains("checking your browser") ||
                lower.contains("security") ||
                lower.contains("access denied") ||
                lower.contains("error 1015")
        }

        fun getAllSiteCookies(currentUrl: String?): String {
            return mergeCookies(
                cookieManager.getCookie(HOME_URL),
                cookieManager.getCookie(BASE_DOMAIN_URL),
                currentUrl?.takeIf { isExpectedHost(it) }?.let {
                    cookieManager.getCookie(it)
                }
            )
        }

        fun completeSaveAndClose(cookies: String) {
            cookieManager.flush()

            val result = OnlyKdramaSession.save(
                context,
                cookies,
                webView.settings.userAgentString
            )

            if (!result) {
                status.text = "Failed to save session. Tap Save & Close to retry."
                return
            }

            saved = true

            OnlyKdramaVerification.sessionVerified()

            status.text = "Verified! Session saved — closing..."
            secureBadge.text = "✓ VERIFIED"
            secureBadge.setTextColor(BLUSH_PINK)

            handler.postDelayed({
                if (!cleaned && dialog.isShowing) {
                    dialog.dismiss()
                }
            }, 300L)
        }

        fun tryAutoSave(): Boolean {
            if (saved) return true
            if (cleaned || !dialog.isShowing) return false

            val currentUrl = webView.url
            if (!isExpectedHost(currentUrl)) return false

            cookieManager.flush()
            val cookies = getAllSiteCookies(currentUrl)
            val clearance = extractCookieValue(cookies, "cf_clearance") ?: return false

            if (clearStaleClearance && initialClearance != null && clearance == initialClearance) {
                return false
            }

            if (isChallengeTitle(webView.title)) {
                status.text = "Verification accepted! Loading site..."
                return false
            }

            completeSaveAndClose(cookies)
            return true
        }

        val cookieWatcher = object : Runnable {
            override fun run() {
                if (cleaned || saved || !dialog.isShowing) return
                if (!tryAutoSave()) {
                    handler.postDelayed(this, WATCH_INTERVAL)
                }
            }
        }

        fun saveSessionManually() {
            if (saved || cleaned) return

            cookieManager.flush()
            val cookies = getAllSiteCookies(webView.url)

            if (cookies.isBlank()) {
                status.text = "No site cookies found yet. Wait for the page to load."
                return
            }

            val hasClearance = extractCookieValue(cookies, "cf_clearance") != null
            if (!hasClearance && !warnedNoClearance) {
                warnedNoClearance = true
                status.text =
                    "No cf_clearance cookie yet. Complete the check, or tap Save & Close again."
                return
            }

            completeSaveAndClose(cookies)
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

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!saved && !cleaned) {
                    handler.postDelayed({ tryAutoSave() }, 200L)
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
                warnedNoClearance = false
                if (!saved) {
                    status.text = "Loading OnlyKDrama..."
                }
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                cookieManager.flush()
                progress.visibility = View.GONE

                if (!saved) {
                    handler.postDelayed({
                        if (!saved && !cleaned && !tryAutoSave()) {
                            status.text =
                                "Complete verification below — saves & closes automatically."
                        }
                    }, 250L)
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

                minHeight = dp(44)
                minimumHeight = dp(44)

                background = roundedBackground(
                    color,
                    dp(12).toFloat()
                )

                stateListAnimator = null
                elevation = 0f
            }
        }

        val saveButton = makeButton(
            "Save & Close",
            SAKURA_PINK,
            0xFF230B16.toInt()
        )

        val back = makeButton(
            "‹  Back",
            0xFF24131C.toInt(),
            TEXT
        )

        val refresh = makeButton(
            "↻  Refresh",
            0xFF24131C.toInt(),
            TEXT
        )

        val close = makeButton(
            "Close",
            0xFF2D1620.toInt(),
            0xFFFF9EB5.toInt()
        )

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(5))
            background = roundedBackground(
                DARK,
                dp(14).toFloat()
            )
        }

        controls.addView(
            saveButton,
            LinearLayout.LayoutParams(-1, dp(46))
        )

        val secondaryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        secondaryRow.addView(
            back,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(5)
            }
        )

        secondaryRow.addView(
            refresh,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(5)
            }
        )

        secondaryRow.addView(
            close,
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        controls.addView(
            secondaryRow,
            LinearLayout.LayoutParams(-1, dp(44)).apply {
                topMargin = dp(6)
            }
        )

        // Footer

        val footer = makeText(
            "Auto-saves & closes once verification completes",
            10f,
            MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
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
                ROSE_BORDER
            )

            addView(
                header,
                LinearLayout.LayoutParams(
                    -1,
                    dp(56)
                )
            )

            addView(
                statusPanel,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                ).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(7)
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
                    bottomMargin = dp(7)
                }
            )

            addView(
                controls,
                LinearLayout.LayoutParams(
                    -1,
                    -2
                )
            )

            addView(
                footer,
                LinearLayout.LayoutParams(
                    -1,
                    dp(22)
                )
            )
        }

        // Dialog

        dialog = AlertDialog.Builder(context)
            .setView(content)
            .create()

        activeDialog = WeakReference(dialog)

        fun cleanup() {
            if (cleaned) return

            cleaned = true

            if (activeDialog?.get() === dialog) {
                activeDialog = null
            }

            handler.removeCallbacksAndMessages(null)
            cookieManager.flush()

            webView.stopLoading()
            webView.onPause()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()

            onClosed?.invoke(saved)
        }

        // Button actions

        saveButton.setOnClickListener {
            saveSessionManually()
        }

        back.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        refresh.setOnClickListener {
            if (saved || cleaned) return@setOnClickListener

            warnedNoClearance = false
            secureBadge.text = "● PINK"
            secureBadge.setTextColor(BLUSH_PINK)
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

        // Show dialog

        dialog.show()

        dialog.window?.apply {
            clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )

            setBackgroundDrawableResource(
                android.R.color.transparent
            )

            setGravity(Gravity.CENTER)

            setLayout(
                windowWidth,
                windowHeight
            )
        }

        fun startLoading() {
            if (cleaned || !dialog.isShowing) return

            content.requestLayout()
            webView.requestLayout()
            webView.invalidate()

            webView.onResume()
            webView.requestFocus()
            webView.loadUrl(HOME_URL)

            handler.postDelayed(cookieWatcher, WATCH_INTERVAL)
        }

        content.post {
            if (cleaned || !dialog.isShowing) {
                return@post
            }

            if (clearStaleClearance) {
                OnlyKdramaSession.clear(context)
                clearClearanceCookie(handler) {
                    startLoading()
                }
            } else {
                startLoading()
            }
        }
    }
}
