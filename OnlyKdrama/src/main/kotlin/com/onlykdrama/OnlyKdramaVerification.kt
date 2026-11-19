package com.onlykdrama

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

object OnlyKdramaVerification {

    private const val DISMISS_COOLDOWN = 30_000L
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentActivity = WeakReference<Activity>(null)

    @Volatile
    private var lastDismissedTime = 0L

    @Volatile
    private var registered = false

    @Volatile
    private var pendingVerification: CompletableDeferred<Boolean>? = null

    fun initialize(context: Context) {
        (context as? Activity)?.takeIf { !it.isFinishing && !it.isDestroyed }?.let {
            currentActivity = WeakReference(it)
        }

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

                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            currentActivity = WeakReference(activity)
                        }
                    }

                    override fun onActivityStarted(activity: Activity) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            currentActivity = WeakReference(activity)
                        }
                    }

                    override fun onActivityStopped(activity: Activity) {}
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                }
            )

            registered = true
        }
    }

    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private fun findForegroundActivity(): Activity? {
        currentActivity.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let {
            return it
        }

        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass
                .getMethod("currentActivityThread")
                .invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(activityThread) as? Map<*, *> ?: return null

            var fallbackActivity: Activity? = null

            for (record in activities.values) {
                if (record == null) continue
                val recordClass = record.javaClass
                val activityField = recordClass.getDeclaredField("activity")
                activityField.isAccessible = true
                val activity = activityField.get(record) as? Activity ?: continue

                if (activity.isFinishing || activity.isDestroyed) continue

                val pausedField = recordClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(record)) {
                    currentActivity = WeakReference(activity)
                    return activity
                }

                if (fallbackActivity == null) {
                    fallbackActivity = activity
                }
            }

            fallbackActivity?.also { currentActivity = WeakReference(it) }
        }.getOrNull()
    }

    private fun openPopupWithRetry(attempt: Int = 0) {
        val activity = findForegroundActivity()

        if (activity == null) {
            if (attempt < 15) {
                handler.postDelayed({ openPopupWithRetry(attempt + 1) }, 150L)
            } else {
                completeVerification(verified = false, dismissedByUser = false)
            }
            return
        }

        try {
            OnlyKdramaWebView.open(
                context = activity,
                clearStaleClearance = true
            ) { verified ->
                completeVerification(
                    verified = verified,
                    dismissedByUser = !verified
                )
            }
        } catch (_: Exception) {
            completeVerification(verified = false, dismissedByUser = false)
        }
    }

    suspend fun awaitVerification(timeoutMs: Long = 90_000L): Boolean {
        val deferred: CompletableDeferred<Boolean>
        val shouldOpen: Boolean

        synchronized(this) {
            val existing = pendingVerification
            if (existing != null && !existing.isCompleted) {
                deferred = existing
                shouldOpen = false
            } else {
                val now = System.currentTimeMillis()
                if (now - lastDismissedTime < DISMISS_COOLDOWN) {
                    return false
                }
                deferred = CompletableDeferred()
                pendingVerification = deferred
                shouldOpen = true
            }
        }

        if (shouldOpen) {
            handler.post { openPopupWithRetry(0) }
        }

        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: false
    }

    private fun completeVerification(verified: Boolean, dismissedByUser: Boolean) {
        val toComplete: CompletableDeferred<Boolean>?
        synchronized(this) {
            if (verified) {
                lastDismissedTime = 0L
            } else if (dismissedByUser) {
                lastDismissedTime = System.currentTimeMillis()
            }
            toComplete = pendingVerification
            pendingVerification = null
        }
        toComplete?.complete(verified)
    }

    fun sessionVerified() {
        completeVerification(verified = true, dismissedByUser = false)
    }
}
