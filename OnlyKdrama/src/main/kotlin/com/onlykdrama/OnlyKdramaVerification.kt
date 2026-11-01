
package com.onlykdrama

import android.app.Activity
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

    @Volatile
    private var opening = false

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
        synchronized(this) {
            val now = System.currentTimeMillis()

            if (opening || now - lastPromptTime < COOLDOWN) return

            opening = true
            lastPromptTime = now
        }

        handler.post {
            val activity = currentActivity.get()

            if (
                activity == null ||
                activity.isFinishing ||
                activity.isDestroyed
            ) {
                synchronized(this) {
                    opening = false
                    lastPromptTime = 0L
                }
                return@post
            }

            try {
                OnlyKdramaWebView.open(activity) {
                    synchronized(this) {
                        opening = false
                        lastPromptTime = 0L
                    }
                }
            } catch (_: Exception) {
                synchronized(this) {
                    opening = false
                    lastPromptTime = 0L
                }
            }
        }
    }

    fun sessionVerified() {
        synchronized(this) {
            lastPromptTime = 0L
            opening = false
        }
    }
}
