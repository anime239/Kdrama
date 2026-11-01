package com.onlykdrama

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

object OnlyKdramaVerification {

    private const val COOLDOWN = 120_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentActivity = WeakReference<Activity>(null)

    @Volatile
    private var lastPromptTime = 0L

    @Volatile
    private var registered = false

    fun initialize(context: Context) {
        if (registered) return

        val application = context.applicationContext as? Application ?: return

        synchronized(this) {
            if (registered) return

            application.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {

                    override fun onActivityResumed(activity: Activity) {
                        currentActivity = WeakReference(activity)
                    }

                    override fun onActivityPaused(activity: Activity) {
                        if (currentActivity.get() === activity) {
                            currentActivity = WeakReference(null)
                        }
                    }

                    override fun onActivityCreated(
                        activity: Activity,
                        savedInstanceState: Bundle?
                    ) {}

                    override fun onActivityStarted(activity: Activity) {}
                    override fun onActivityStopped(activity: Activity) {}
                    override fun onActivitySaveInstanceState(
                        activity: Activity,
                        outState: Bundle
                    ) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                }
            )

            registered = true
        }
    }

    fun challengeDetected() {
        val now = System.currentTimeMillis()

        synchronized(this) {
            if (now - lastPromptTime < COOLDOWN) return
            lastPromptTime = now
        }

        handler.post {
            val activity = currentActivity.get() ?: return@post

            if (activity.isFinishing || activity.isDestroyed) return@post

            AlertDialog.Builder(activity)
                .setTitle("OnlyKDrama Verification")
                .setMessage(
                    "Cloudflare verification is required to access OnlyKDrama. " +
                        "Open the verification browser and complete the check."
                )
                .setPositiveButton("VERIFY") { _, _ ->
                    try {
                        OnlyKdramaWebView.open(activity)
                    } catch (_: Exception) {
                        lastPromptTime = 0L
                    }
                }
                .setNegativeButton("LATER", null)
                .show()
        }
    }

    fun sessionVerified() {
        synchronized(this) {
            lastPromptTime = 0L
        }
    }
}