package me.avinas.tempo.utils

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies [ForegroundNotificationGuard.rejectionReason] reproduces the two conditions the platform
 * kills the process for in `ServiceRecord.postNotification()`.
 *
 * Those kills are asynchronous: the framework copies the notification out of `startForeground()`,
 * posts it later on the ActivityManager handler thread, and calls
 * `killMisbehavingService(..., CannotPostForegroundServiceNotificationException.TYPE_ID)` when the
 * post is rejected — the user-visible `"Bad notification for startForeground"`. Nothing around
 * `startForeground()` can catch that, so this pre-flight check is the only defence and must not
 * drift from the framework's rules.
 *
 * The notification's channel id and icon are passed in rather than read from a `Notification`
 * because the public SDK exposes only getters for them, which makes them unsettable in a plain JVM
 * test. [ForegroundNotificationGuard.rejectionReason] reads them on device.
 */
class ForegroundNotificationGuardTest {
    private val sdkO = Build.VERSION_CODES.O

    @Test
    fun `accepts a notification with an existing channel and a small icon`() {
        val reason =
            reject(
                channelId = "tempo_tracking_channel",
                hasSmallIcon = true,
                channelExists = { it == "tempo_tracking_channel" },
            )

        assertNull(reason)
    }

    /** AOSP: `invalid channel for service notification: <notification>`. */
    @Test
    fun `rejects a notification whose channel was deleted`() {
        val reason =
            reject(
                channelId = "tempo_tracking_channel",
                hasSmallIcon = true,
                channelExists = { false },
            )

        assertEquals("invalid channel for service notification: tempo_tracking_channel", reason)
    }

    /** AOSP: `invalid service notification: <notification>`. */
    @Test
    fun `rejects a notification with no small icon`() {
        val reason = reject(channelId = "tempo_tracking_channel", hasSmallIcon = false)

        assertEquals("invalid service notification: no small icon", reason)
    }

    @Test
    fun `rejects a notification with no channel on Android O and later`() {
        val reason = reject(channelId = null, hasSmallIcon = true)

        assertEquals("notification has no channel id", reason)
    }

    @Test
    fun `rejects an empty channel id on Android O and later`() {
        val reason = reject(channelId = "", hasSmallIcon = true)

        assertEquals("notification has no channel id", reason)
    }

    @Test
    fun `refuses to promote when the notification service cannot be reached`() {
        val reason =
            reject(
                channelId = "tempo_tracking_channel",
                hasSmallIcon = true,
                managerAvailable = false,
            )

        assertEquals("NotificationManager unavailable", reason)
    }

    /**
     * Guards the pre-O behaviour: channels do not exist before Android 8, so the framework does not
     * check them there and neither should the guard.
     */
    @Test
    fun `ignores channel checks below Android O`() {
        val reason = reject(channelId = null, hasSmallIcon = true, sdkInt = Build.VERSION_CODES.N)

        assertNull(reason)
    }

    /** The icon rule is not version-gated, so it still applies below Android O. */
    @Test
    fun `still rejects a missing icon below Android O`() {
        val reason =
            reject(channelId = null, hasSmallIcon = false, sdkInt = Build.VERSION_CODES.N)

        assertEquals("invalid service notification: no small icon", reason)
    }

    /** A deleted channel outranks a missing icon, matching the framework's check order. */
    @Test
    fun `reports the channel failure before the icon failure`() {
        val reason =
            reject(
                channelId = "tempo_tracking_channel",
                hasSmallIcon = false,
                channelExists = { false },
            )

        assertEquals("invalid channel for service notification: tempo_tracking_channel", reason)
    }

    private fun reject(
        channelId: String?,
        hasSmallIcon: Boolean,
        channelExists: (String) -> Boolean = { true },
        managerAvailable: Boolean = true,
        sdkInt: Int = sdkO,
    ): String? =
        ForegroundNotificationGuard.rejectionReason(
            channelId = channelId,
            hasSmallIcon = hasSmallIcon,
            channelExists = channelExists,
            managerAvailable = managerAvailable,
            sdkInt = sdkInt,
        )
}
