
package com.onlykdrama

import android.content.Context

object OnlyKdramaSession {

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

    fun save(
        context: Context,
        cookies: String?,
        userAgent: String?
    ) {
        initialize(context)

        if (cookies.isNullOrBlank()) return

        preferences(context).edit()
            .putString(COOKIE_KEY, cookies)
            .putString(UA_KEY, userAgent.orEmpty())
            .apply()
    }

    fun hasSavedSession(context: Context): Boolean {
        return preferences(context)
            .getString(COOKIE_KEY, null)
            .orEmpty()
            .isNotBlank()
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
            .apply()
    }
}
