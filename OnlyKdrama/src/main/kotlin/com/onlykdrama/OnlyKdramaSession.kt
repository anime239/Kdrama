package com.onlykdrama

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.CookieManager
import java.util.concurrent.atomic.AtomicLong

object OnlyKdramaSession {

    private const val TAG = "OnlyKDramaSession"
    private const val PREFS = "onlykdrama_session"
    private const val COOKIE_KEY = "cookies"
    private const val UA_KEY = "user_agent"
    private const val SITE_URL = "https://onlykdrama.shop/"

    @Volatile
    private var appContext: Context? = null

    private val sessionVersion = AtomicLong(0L)

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun getSessionVersion(): Long = sessionVersion.get()

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    private fun parseCookieMap(cookies: String?): LinkedHashMap<String, String> {
        val map = linkedMapOf<String, String>()
        cookies.orEmpty().split(";").forEach { raw ->
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
        return map
    }

    private fun cookieNames(cookies: String?): List<String> {
        return parseCookieMap(cookies).keys.toList()
    }

    private fun syncWebViewCookiesIfNeeded(
        prefs: SharedPreferences,
        existingCookies: String?
    ): String? {
        val webCookies = runCatching {
            CookieManager.getInstance().getCookie(SITE_URL)
        }.getOrNull()

        if (webCookies.isNullOrBlank()) return existingCookies

        val existingMap = parseCookieMap(existingCookies)
        val webMap = parseCookieMap(webCookies)

        val webClearance = webMap.entries
            .firstOrNull { it.key.equals("cf_clearance", true) }
            ?.value

        if (webClearance.isNullOrBlank()) return existingCookies

        val existingClearance = existingMap.entries
            .firstOrNull { it.key.equals("cf_clearance", true) }
            ?.value

        if (existingClearance == webClearance && existingMap.isNotEmpty()) {
            return existingCookies
        }

        existingMap.putAll(webMap)
        val merged = existingMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

        synchronized(this) {
            prefs.edit()
                .putString(COOKIE_KEY, merged)
                .commit()
        }

        Log.d(TAG, "Synced fresh cookies from WebView: ${existingMap.keys}")
        return merged
    }

    fun save(
        context: Context,
        cookies: String?,
        userAgent: String?
    ): Boolean {
        initialize(context)

        val cleanedMap = parseCookieMap(cookies)
        if (cleanedMap.isEmpty()) return false

        val formatted = cleanedMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val names = cleanedMap.keys.toList()

        val saved = synchronized(this) {
            val editor = preferences(context).edit()
                .putString(COOKIE_KEY, formatted)

            if (!userAgent.isNullOrBlank()) {
                editor.putString(UA_KEY, userAgent)
            }

            val ok = editor.commit()
            if (ok) {
                sessionVersion.incrementAndGet()
            }
            ok
        }

        Log.d(
            TAG,
            "Session save=$saved, version=${sessionVersion.get()}, cookieNames=$names, cf_clearance=${names.any { it.equals("cf_clearance", true) }}"
        )

        return saved
    }

    fun mergeCookies(newCookies: Map<String, String>) {
        val context = appContext ?: return
        if (newCookies.isEmpty()) return

        synchronized(this) {
            val prefs = preferences(context)
            val existing = parseCookieMap(prefs.getString(COOKIE_KEY, null))

            var changed = false
            for ((key, value) in newCookies) {
                val cleanKey = key.trim()
                val cleanValue = value.trim()
                if (cleanKey.isBlank() || cleanValue.isBlank()) continue
                if (existing[cleanKey] != cleanValue) {
                    existing[cleanKey] = cleanValue
                    changed = true
                }
            }

            if (changed) {
                val merged = existing.entries.joinToString("; ") { "${it.key}=${it.value}" }
                prefs.edit()
                    .putString(COOKIE_KEY, merged)
                    .commit()
                Log.d(TAG, "Merged response cookies: ${existing.keys}")
            }
        }
    }

    fun hasSavedSession(context: Context): Boolean {
        return hasCloudflareClearance(context)
    }

    fun hasCloudflareClearance(context: Context): Boolean {
        val cookies = preferences(context)
            .getString(COOKIE_KEY, null)

        return cookieNames(cookies)
            .any { it.equals("cf_clearance", true) }
    }

    fun savedCookieNames(context: Context): List<String> {
        val cookies = preferences(context)
            .getString(COOKIE_KEY, null)

        return cookieNames(cookies)
    }

    fun savedCookieNamesForLog(): List<String> {
        val context = appContext ?: return emptyList()
        return savedCookieNames(context)
    }

    fun hasCloudflareClearanceForLog(): Boolean {
        val context = appContext ?: return false
        return hasCloudflareClearance(context)
    }

    fun headers(
        original: Map<String, String>
    ): Map<String, String> {
        val context = appContext ?: return original
        val prefs = preferences(context)

        val storedCookies = prefs.getString(COOKIE_KEY, null)
        val cookies = syncWebViewCookiesIfNeeded(prefs, storedCookies)
        val userAgent = prefs.getString(UA_KEY, null)

        return original.toMutableMap().apply {
            if (!cookies.isNullOrBlank()) {
                put("Cookie", cookies)
            }

            if (!userAgent.isNullOrBlank()) {
                put("User-Agent", userAgent)
            }
        }
    }

    fun clearIfVersionMatches(expectedVersion: Long) {
        val context = appContext ?: return
        synchronized(this) {
            if (sessionVersion.get() != expectedVersion) {
                Log.d(TAG, "Skip clear: session already updated to version=${sessionVersion.get()}")
                return
            }
            clear(context)
        }
    }

    fun clear() {
        val context = appContext ?: return
        clear(context)
    }

    fun clear(context: Context) {
        synchronized(this) {
            preferences(context).edit()
                .remove(COOKIE_KEY)
                .remove(UA_KEY)
                .commit()
        }

        Log.d(TAG, "Saved session cleared")
    }
}
