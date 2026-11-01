
package com.onlykdrama

import android.content.Context
import android.util.Log

object OnlyKdramaSession {

    private const val TAG = "OnlyKDramaSession"
    private const val PREFS = "onlykdrama_session"
    private const val COOKIE_KEY = "cookies"
    private const val UA_KEY = "user_agent"

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    private fun cookieNames(cookies: String?): List<String> {
        return cookies.orEmpty()
            .split(";")
            .map { it.substringBefore("=").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun save(
        context: Context,
        cookies: String?,
        userAgent: String?
    ): Boolean {
        initialize(context)

        if (cookies.isNullOrBlank()) return false

        val names = cookieNames(cookies)

        val saved = preferences(context).edit()
            .putString(COOKIE_KEY, cookies)
            .putString(UA_KEY, userAgent.orEmpty())
            .commit()

        Log.d(
            TAG,
            "Session save=$saved, cookieNames=$names, cf_clearance=${names.any { it.equals("cf_clearance", true) }}"
        )

        return saved
    }

    fun hasSavedSession(context: Context): Boolean {
        return preferences(context)
            .getString(COOKIE_KEY, null)
            .orEmpty()
            .isNotBlank()
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

    fun headers(
        original: Map<String, String>
    ): Map<String, String> {
        val context = appContext ?: return original
        val prefs = preferences(context)

        val cookies = prefs.getString(COOKIE_KEY, null)
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

    fun clear(context: Context) {
        preferences(context).edit()
            .remove(COOKIE_KEY)
            .remove(UA_KEY)
            .commit()

        Log.d(TAG, "Saved session cleared")
    }
}
