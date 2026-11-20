package com.onlykdrama

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.lang.ref.WeakReference

object OnlyKdramaWebView {

    const val HOME_URL = "https://onlykdrama.shop/"
    private const val BASE_DOMAIN_URL = "https://onlykdrama.shop"

    // Creamy Strawberry Rom-Com Palette
    private const val CREAM_CANVAS = 0xFFFFF7F9.toInt()
    private const val CREAM_BORDER = 0xFFFFD1DF.toInt()
    private const val ROSE_BANNER = 0xFFFF7597.toInt()
    private const val BLUSH_TRACK = 0xFFFFE3EC.toInt()
    private const val ESPRESSO_INK = 0xFF1E0F16.toInt()
    private const val DARK_DOCK = 0xFF1A0E14.toInt()
    private const val DARK_PILL_BTN = 0xFF331B26.toInt()
    private const val SOFT_ROSE_TEXT = 0xFFFFB3C6.toInt()

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
        if (cookies.isNotBlank()) return true
        return OnlyKdramaSession.savedCookieNamesForLog().isNotEmpty()
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
        strokeColor: Int? = null,
        strokeWidthPx: Int = 2
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            strokeColor?.let {
                setStroke(strokeWidthPx, it)
            }
        }
    }

    private fun loveHeaderDrawable(radiusPx: Float, density: Float): Drawable = object : Drawable() {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ROSE_BANNER
            style = Paint.Style.FILL
        }
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x26FFFFFF
            style = Paint.Style.FILL
        }
        private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x52FFF7F9
            style = Paint.Style.FILL
        }
        private val clipPath = Path()
        private val rect = RectF()

        private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, size: Float) {
            val path = Path()
            val topCurve = size * 0.3f
            path.moveTo(cx, cy + topCurve)
            path.cubicTo(cx, cy, cx - size / 2f, cy, cx - size / 2f, cy + topCurve)
            path.cubicTo(
                cx - size / 2f,
                cy + (size + topCurve) / 2f,
                cx,
                cy + (size + topCurve) / 1.4f,
                cx,
                cy + size
            )
            path.cubicTo(
                cx,
                cy + (size + topCurve) / 1.4f,
                cx + size / 2f,
                cy + (size + topCurve) / 2f,
                cx + size / 2f,
                cy + topCurve
            )
            path.cubicTo(cx + size / 2f, cy, cx, cy, cx, cy + topCurve)
            path.close()
            canvas.drawPath(path, artPaint)
        }

        private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val path = Path().apply {
                moveTo(cx, cy - r)
                quadTo(cx, cy, cx + r, cy)
                quadTo(cx, cy, cx, cy + r)
                quadTo(cx, cy, cx - r, cy)
                quadTo(cx, cy, cx, cy - r)
                close()
            }
            canvas.drawPath(path, artPaint)
        }

        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return

            rect.set(0f, 0f, w, h)
            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
            canvas.clipPath(clipPath)

            canvas.drawRect(rect, bgPaint)
            canvas.drawCircle(w * 0.86f, h * 0.25f, h * 0.65f, circlePaint)
            canvas.drawCircle(w * 0.68f, h * 0.95f, h * 0.45f, circlePaint)

            drawHeart(canvas, w * 0.62f, h * 0.24f, 12f * density)
            drawSparkle(canvas, w * 0.55f, h * 0.72f, 7f * density)
            drawSparkle(canvas, w * 0.75f, h * 0.28f, 5.5f * density)

            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            bgPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
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

        fun dpF(value: Float): Float =
            value * density

        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        val windowWidth = minOf(
            dp(435),
            screenWidth - dp(28)
        ).coerceAtLeast(dp(280))

        val windowHeight = minOf(
            dp(760),
            screenHeight - dp(56)
        ).coerceAtLeast(dp(420))

        val webViewHeight = (
            windowHeight - dp(190)
        ).coerceAtLeast(dp(200))

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
        var pageLoadedOnce = false

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
                    typeface = Typeface.DEFAULT_BOLD
                }
            }
        }

        // Top Sweetheart Rose Banner with Merged Live Status
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
            background = loveHeaderDrawable(dpF(22f), density)
        }

        val titleArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleArea.addView(
            makeText("OnlyKDrama ♡", 18f, CREAM_CANVAS, bold = true)
        )

        val status = makeText(
            "Complete verification below — auto-saves ♡",
            11.5f,
            0xFFFFE8EF.toInt()
        ).apply {
            setPadding(0, dp(2), dp(8), 0)
            maxLines = 2
        }

        titleArea.addView(status)

        header.addView(
            titleArea,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        val secureBadge = TextView(context).apply {
            text = "● CHECK"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(SOFT_ROSE_TEXT)
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = roundedBackground(ESPRESSO_INK, dpF(20f))
        }

        header.addView(secureBadge)

        // Slim Fused Rose Progress Bar
        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(ROSE_BANNER)
            progressBackgroundTintList = ColorStateList.valueOf(BLUSH_TRACK)
        }

        // Rounded WebView Container
        val webViewRadius = dpF(20f)
        val webViewFrame = FrameLayout(context).apply {
            background = roundedBackground(
                color = Color.BLACK,
                radius = webViewRadius,
                strokeColor = CREAM_BORDER,
                strokeWidthPx = dpF(1.5f).toInt().coerceAtLeast(1)
            )
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, webViewRadius)
                }
            }
            clipToOutline = true
        }

        val webView = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            overScrollMode = View.OVER_SCROLL_NEVER
            visibility = View.VISIBLE
            isFocusable = true
            isFocusableInTouchMode = true
        }

        webViewFrame.addView(
            webView,
            FrameLayout.LayoutParams(-1, -1)
        )

        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

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
                status.text = "Save failed — tap Save & Close to retry"
                return
            }

            saved = true

            OnlyKdramaVerification.sessionVerified()

            status.text = "Verified! Closing automatically ♡"
            secureBadge.text = "✓ SAVED"
            secureBadge.setTextColor(0xFF9BE8B1.toInt())

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
            val clearance = extractCookieValue(cookies, "cf_clearance")

            if (isChallengeTitle(webView.title)) {
                if (clearance != null && (!clearStaleClearance || clearance != initialClearance)) {
                    status.text = "Accepted! Loading site..."
                }
                return false
            }

            // Real site loaded (either with fresh cf_clearance OR without needing a challenge)
            if (clearance != null) {
                if (clearStaleClearance && initialClearance != null && clearance == initialClearance) {
                    return false
                }
                completeSaveAndClose(cookies)
                return true
            }

            if (pageLoadedOnce) {
                completeSaveAndClose(cookies)
                return true
            }

            return false
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
            completeSaveAndClose(cookies)
        }

        // WebChromeClient (also blocks ad JS popups like fake bot alerts)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!saved && !cleaned) {
                    handler.postDelayed({ tryAutoSave() }, 200L)
                }
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                result?.cancel()
                return true
            }
        }

        // WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                if (!saved) {
                    status.text = "Loading OnlyKDrama..."
                }
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                cookieManager.flush()
                progress.visibility = View.GONE
                pageLoadedOnce = true

                if (!saved) {
                    handler.postDelayed({
                        if (!saved && !cleaned && !tryAutoSave()) {
                            status.text = "Tap the checkbox below — auto-saves ♡"
                        }
                    }, 300L)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    status.text = "Connection error: ${error?.description}"
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
                val host = uri.host.orEmpty()
                // Block external ad redirects; allow onlykdrama.shop and Cloudflare challenge domains
                if (uri.scheme != "https" && uri.scheme != "http") return true
                val allowed = host.endsWith("onlykdrama.shop", true) ||
                    host.endsWith("cloudflare.com", true) ||
                    host.endsWith("challenges.cloudflare.com", true)
                return !allowed
            }
        }

        // Floating Dark Espresso Pill Dock (Single Compact Row)
        fun makeCircleIconBtn(symbol: String): Button = Button(context).apply {
            text = symbol
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(BLUSH_TRACK)
            setPadding(0, 0, 0, 0)
            minWidth = dp(44)
            minimumWidth = dp(44)
            minHeight = dp(44)
            minimumHeight = dp(44)
            background = roundedBackground(DARK_PILL_BTN, dpF(22f))
            stateListAnimator = null
            elevation = 0f
        }

        val back = makeCircleIconBtn("‹")
        val refresh = makeCircleIconBtn("↻")
        val close = makeCircleIconBtn("✕")

        val saveButton = Button(context).apply {
            text = "Save & Close ♡"
            isAllCaps = false
            maxLines = 1
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CREAM_CANVAS)
            setPadding(dp(12), 0, dp(12), 0)
            minHeight = dp(44)
            minimumHeight = dp(44)
            background = roundedBackground(ROSE_BANNER, dpF(22f))
            stateListAnimator = null
            elevation = 0f
        }

        val floatingDock = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedBackground(DARK_DOCK, dpF(28f))
        }

        floatingDock.addView(
            back,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(6)
            }
        )

        floatingDock.addView(
            refresh,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(8)
            }
        )

        floatingDock.addView(
            saveButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(8)
            }
        )

        floatingDock.addView(
            close,
            LinearLayout.LayoutParams(dp(44), dp(44))
        )

        // Main Creamy Card Shell
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(
                color = CREAM_CANVAS,
                radius = dpF(28f),
                strokeColor = CREAM_BORDER,
                strokeWidthPx = dpF(1.5f).toInt().coerceAtLeast(1)
            )

            addView(
                header,
                LinearLayout.LayoutParams(-1, -2)
            )

            addView(
                progress,
                LinearLayout.LayoutParams(-1, dp(3)).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
            )

            addView(
                webViewFrame,
                LinearLayout.LayoutParams(-1, webViewHeight).apply {
                    bottomMargin = dp(10)
                }
            )

            addView(
                floatingDock,
                LinearLayout.LayoutParams(-1, -2)
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

            pageLoadedOnce = false
            secureBadge.text = "● CHECK"
            secureBadge.setTextColor(SOFT_ROSE_TEXT)
            status.text = "Refreshing website..."

            webView.reload()
        }

        close.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
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
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            setLayout(windowWidth, windowHeight)
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
            if (cleaned || !dialog.isShowing) return@post

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
