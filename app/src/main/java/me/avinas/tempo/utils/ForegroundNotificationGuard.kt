package me.avinas.tempo.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import me.avinas.tempo.R

/**
 * Shared pre-flight validation for foreground-service notifications.
 *
 * The system does not validate a foreground notification when `startForeground()` is called. It
 * copies the notification into the service record and posts it later, on the ActivityManager
 * handler thread, in `ServiceRecord.postNotification()`. If that post is rejected, the framework
 * stops the service and kills the process with
 * `RemoteServiceException$CannotPostForegroundServiceNotificationException: Bad notification for
 * startForeground`. Because the kill happens asynchronously on a system thread, no `try/catch`
 * around `startForeground()` can observe it — the only defence is to never hand the system a
 * notification that it will reject.
 *
 * `ServiceRecord.postNotification()` kills the process for exactly two reasons:
 *
 * 1. The notification names a channel that does not exist. On apps targeting Android 8.1+ this
 *    throws `"invalid channel for service notification"`. A channel can be missing even though the
 *    app created it earlier: the user can delete it in system settings, and clearing app data
 *    deletes it for real.
 * 2. The notification has no small icon, which throws `"invalid service notification"`.
 *
 * [isPostable] mirrors both checks so callers can verify a built notification before promoting.
 */
object ForegroundNotificationGuard {
    private const val TAG = "FgNotificationGuard"

    /** Default importance used when a foreground channel has to be recreated. */
    private const val RECREATED_CHANNEL_IMPORTANCE = NotificationManager.IMPORTANCE_LOW

    /**
     * Guard-owned channel used when a worker's own channel is unusable.
     *
     * This is the one channel a foreground notification can always fall back to, so it is created
     * on demand by [ensureChannelsExist] and shared by every worker.
     */
    const val FALLBACK_CHANNEL_ID = "tempo_foreground_work"

    /**
     * Creates [channelIds] when they are missing and reports whether every one of them exists
     * afterwards.
     *
     * This is safe to call before every promotion: creating an existing channel is a no-op and
     * never resets user-customised importance.
     */
    fun ensureChannelsExist(
        context: Context,
        channelIds: List<String>,
        logTag: String = TAG,
    ): Boolean {
        if (channelIds.isEmpty()) return true

        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null) {
            Log.e(logTag, "NotificationManager unavailable; cannot verify ${channelIds.size} channel(s)")
            return false
        }

        val missing = channelIds.filter { manager.getNotificationChannel(it) == null }
        if (missing.isEmpty()) return true

        Log.w(logTag, "Notification channel(s) missing ($missing) — recreating before promoting to foreground")

        val name = context.applicationInfo.loadLabel(context.packageManager)?.toString()
        missing.forEach { id ->
            try {
                manager.createNotificationChannel(
                    NotificationChannel(id, name ?: "Tempo", RECREATED_CHANNEL_IMPORTANCE).apply {
                        description = "Tempo background work"
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_SECRET
                    },
                )
            } catch (e: Exception) {
                Log.e(logTag, "Failed to recreate notification channel '$id'", e)
            }
        }

        // Notification channels can only be created while the app is allowed to post
        // notifications; if that is denied the recreate above is a silent no-op.
        val stillMissing = channelIds.filter { manager.getNotificationChannel(it) == null }
        if (stillMissing.isNotEmpty()) {
            Log.e(logTag, "Notification channel(s) still missing after recreate: $stillMissing")
            return false
        }

        Log.i(logTag, "Recreated notification channel(s): $missing")
        return true
    }

    /**
     * Mirrors `ServiceRecord.postNotification()`'s rejection checks, returning the message the
     * framework would kill the process with — or `null` when the notification is safe to post.
     */
    fun rejectionReason(context: Context, notification: Notification?): String? {
        if (notification == null) return "notification is null"

        val manager = context.getSystemService(NotificationManager::class.java)
        return rejectionReason(
            channelId = notification.channelId,
            hasSmallIcon = notification.smallIcon != null,
            channelExists = { id -> manager?.getNotificationChannel(id) != null },
            managerAvailable = manager != null,
        )
    }

    /**
     * The check above, with the notification's platform facts passed in instead of read off a
     * `Notification`.
     *
     * `Notification.getChannelId()` and `getSmallIcon()` are readable through the public SDK, but
     * the fields behind them are not writable, so a plain JVM test cannot build a `Notification`
     * carrying a chosen channel or a cleared icon. Taking the two facts as parameters keeps the
     * rule itself testable; the public overload supplies them on device.
     *
     * [managerAvailable] is `false` when the notification service could not be reached, which is
     * treated as fatal because the channel cannot then be verified.
     */
    internal fun rejectionReason(
        channelId: String?,
        hasSmallIcon: Boolean,
        channelExists: (String) -> Boolean,
        managerAvailable: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): String? {
        if (sdkInt >= Build.VERSION_CODES.O && channelId.isNullOrEmpty()) {
            return "notification has no channel id"
        }

        if (!channelId.isNullOrEmpty()) {
            if (!managerAvailable) return "NotificationManager unavailable"
            if (!channelExists(channelId)) {
                return "invalid channel for service notification: $channelId"
            }
        }

        if (!hasSmallIcon) {
            return "invalid service notification: no small icon"
        }

        return null
    }

    /** True when [notification] would survive `ServiceRecord.postNotification()`'s validation. */
    fun isPostable(context: Context, notification: Notification?): Boolean =
        rejectionReason(context, notification) == null

    /**
     * Builds a foreground notification for [channelIds] and returns only a notification the system
     * will accept.
     *
     * Every channel in [channelIds] is verified before building, because the built notification may
     * legitimately name any of them. [build] is retried once after missing channels are recreated.
     * Returns `null` when no acceptable notification can be produced, so callers never post
     * something the framework would kill the process over. Callers that hold a
     * `startForegroundService()` obligation must use [buildPromotableOrFallback] instead: for them
     * "refuse to promote" is not a safe outcome.
     */
    fun buildPromotable(
        context: Context,
        channelIds: List<String>,
        logTag: String = TAG,
        build: () -> Notification?,
    ): Notification? {
        if (!ensureChannelsExist(context, channelIds, logTag)) {
            Log.e(logTag, "Refusing to promote: not every notification channel exists ($channelIds)")
            return null
        }

        var attempt = assess(context, logTag, build)
        if (attempt.second != null) {
            Log.w(logTag, "Foreground notification rejected in pre-flight (${attempt.second}); rebuilding once")
            ensureChannelsExist(context, channelIds, logTag)
            attempt = assess(context, logTag, build)
        }

        val (notification, reason) = attempt
        if (notification == null || reason != null) {
            Log.e(logTag, "Foreground notification still invalid (${reason ?: "not built"}); refusing to promote")
            return null
        }
        return notification
    }

    /**
     * [buildPromotable], but it does not decline while a channel can still be created: when [build]
     * cannot produce an acceptable notification it returns a plain one on [FALLBACK_CHANNEL_ID].
     *
     * Callers that hold a `startForegroundService()` obligation must use this rather than
     * [buildPromotable]. Declining to call `startForeground()` does not retire that obligation:
     * `ServiceRecord.fgRequired` is only cleared inside the system server once `startForeground()`
     * arrives (`ActiveServices.setServiceForegroundInnerLocked`), and a service brought down while
     * it is still set is reported to the app as a `RemoteServiceException` crash
     * (`ActiveServices.bringDownServiceLocked` posts `SERVICE_FOREGROUND_CRASH_MSG`). Posting a
     * notification on a channel the guard owns and has just verified keeps the obligation satisfied
     * without risking the framework's own kill for an unpostable notification.
     *
     * Returns `null` only when no notification can be posted at all, which means neither
     * [channelIds] nor [FALLBACK_CHANNEL_ID] could be created.
     */
    fun buildPromotableOrFallback(
        context: Context,
        channelIds: List<String>,
        logTag: String = TAG,
        build: () -> Notification?,
    ): Notification? {
        buildPromotable(context, channelIds, logTag, build)?.let { return it }

        if (!ensureChannelsExist(context, listOf(FALLBACK_CHANNEL_ID), logTag)) {
            Log.e(logTag, "Refusing to promote: '$FALLBACK_CHANNEL_ID' is unavailable too")
            return null
        }

        val fallback = minimalFallback(context)
        if (!isPostable(context, fallback)) {
            Log.e(logTag, "Refusing to promote: fallback notification is not postable")
            return null
        }

        Log.w(logTag, "Promoting with the fallback notification on '$FALLBACK_CHANNEL_ID'")
        return fallback
    }

    /** Builds once and reports the notification alongside the reason it would be rejected, if any. */
    private fun assess(
        context: Context,
        logTag: String,
        build: () -> Notification?,
    ): Pair<Notification?, String?> {
        val notification = runCatchingNotification(logTag, build)
        return notification to notification?.let { rejectionReason(context, it) }
    }

    /**
     * Wraps [notification] in a [ForegroundInfo] the system will accept, falling back to
     * [minimalFallback] on [FALLBACK_CHANNEL_ID] when the notification is unusable.
     *
     * WorkManager's `getForegroundInfo()` must return a non-null [ForegroundInfo], so unlike
     * [buildPromotable] this cannot decline to promote. A plain notification on a channel the guard
     * controls keeps the promotion valid; only when even that channel cannot be created does it
     * throw, failing the work with a normal error instead of letting the framework kill the process.
     */
    fun postableForegroundInfo(
        context: Context,
        notificationId: Int,
        requestedChannelId: String,
        notification: Notification,
        logTag: String = TAG,
    ): ForegroundInfo {
        if (isPostable(context, notification)) {
            return foregroundInfo(notificationId, notification)
        }

        Log.w(
            logTag,
            "Foreground notification unusable on '$requestedChannelId'; retrying on '$FALLBACK_CHANNEL_ID'",
        )

        if (ensureChannelsExist(context, listOf(FALLBACK_CHANNEL_ID), logTag)) {
            val onFallback = minimalFallback(context)
            if (isPostable(context, onFallback)) return foregroundInfo(notificationId, onFallback)
        }

        throw IllegalStateException(
            "No usable notification channel for foreground work: '$requestedChannelId' and " +
                "'$FALLBACK_CHANNEL_ID' are both unavailable",
        )
    }

    /**
     * A notification the framework cannot reject: a known channel plus the app's notification icon.
     *
     * The framework validates only the channel and the small icon, so a plain notification is
     * enough to keep the promotion valid when a worker's richer notification cannot be used.
     */
    private fun minimalFallback(context: Context): Notification =
        NotificationCompat
            .Builder(context, FALLBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tempo")
            .setContentText("Working in the background...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun foregroundInfo(notificationId: Int, notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }

    private fun runCatchingNotification(
        logTag: String,
        build: () -> Notification?,
    ): Notification? =
        try {
            build()
        } catch (e: Exception) {
            Log.e(logTag, "Failed to build foreground notification", e)
            null
        }
}
