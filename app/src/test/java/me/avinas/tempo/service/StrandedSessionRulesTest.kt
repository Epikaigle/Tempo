package me.avinas.tempo.service

import me.avinas.tempo.service.StrandedSessionRules.Reason
import me.avinas.tempo.service.StrandedSessionRules.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the second half of the voice-note bug report: a finished item staying
 * pinned to the permanent notification for hours, even after the source app was force-stopped.
 *
 * The session was never evicted because the notification-removal callback had been skipped, so
 * these rules are the backstop that guarantees eviction happens anyway.
 */
class StrandedSessionRulesTest {

    private val minimumPlayDurationMs = 25_000L
    private val now = 10_000_000L

    private fun snapshot(
        packageName: String = "com.example.player",
        isPlaying: Boolean = false,
        playDurationMs: Long = 0L,
        idleForMs: Long = 0L,
        hasLiveController: Boolean = false,
    ) = Snapshot(
        packageName = packageName,
        isPlaying = isPlaying,
        playDurationMs = playDurationMs,
        lastUpdateAt = now - idleForMs,
        hasLiveController = hasLiveController,
    )

    private fun reasonFor(
        snapshot: Snapshot,
        posted: Set<String>? = emptySet(),
    ) = StrandedSessionRules.reapReason(
        snapshot = snapshot,
        minimumPlayDurationMs = minimumPlayDurationMs,
        now = now,
        postedNotificationPackages = { posted },
    )

    // ---- The reported bug --------------------------------------------------

    @Test
    fun `a short paused voice note with no notification is reaped`() {
        // 12s voice note, below the 25s minimum, app gone, no controller, no notification.
        val reason = reasonFor(snapshot(playDurationMs = 12_000L, idleForMs = 3 * 60 * 1000L))
        assertEquals(Reason.PAUSED_BELOW_MINIMUM, reason)
    }

    @Test
    fun `a session whose app left no controller and no notification is reaped`() {
        // Long enough to be saved, but the app is gone entirely.
        val reason = reasonFor(snapshot(playDurationMs = 200_000L, idleForMs = 5 * 60 * 1000L))
        assertEquals(Reason.SOURCE_GONE, reason)
    }

    @Test
    fun `a session is reaped even while it still claims to be playing`() {
        // The app was killed mid-playback: the session never got paused, so isPlaying stays true.
        val reason = reasonFor(
            snapshot(isPlaying = true, playDurationMs = 200_000L, idleForMs = 5 * 60 * 1000L),
        )
        assertEquals(Reason.SOURCE_GONE, reason)
    }

    @Test
    fun `a stranded session is not reaped before the grace period elapses`() {
        assertNull(
            "a momentary gap while an app rebuilds its notification must not evict the session",
            reasonFor(snapshot(playDurationMs = 200_000L, idleForMs = 30_000L)),
        )
    }

    // ---- Cost guarantees ---------------------------------------------------

    @Test
    fun `an unknown notification list keeps the session instead of evicting on a guess`() {
        // null means the listener is disconnected. Evicting here would drop legitimate plays
        // every time the listener rebinds.
        assertNull(reasonFor(snapshot(playDurationMs = 200_000L, idleForMs = 10 * 60 * 1000L), posted = null))
    }

    @Test
    fun `the notification lookup is not consulted when local state already decides`() {
        // Reading posted notifications is a binder IPC that marshals every notification, so it
        // must not run for a session the pure local rule already settles.
        var lookups = 0
        val reason = StrandedSessionRules.reapReason(
            snapshot = snapshot(playDurationMs = 5_000L, idleForMs = 10 * 60 * 1000L),
            minimumPlayDurationMs = minimumPlayDurationMs,
            now = now,
            postedNotificationPackages = {
                lookups++
                emptySet()
            },
        )

        assertEquals(Reason.PAUSED_BELOW_MINIMUM, reason)
        assertEquals("the IPC-backed lookup must be skipped for sub-minimum paused sessions", 0, lookups)
    }

    @Test
    fun `the notification lookup runs at most once per evaluation`() {
        var lookups = 0
        StrandedSessionRules.reapReason(
            snapshot = snapshot(playDurationMs = 200_000L, idleForMs = 5 * 60 * 1000L),
            minimumPlayDurationMs = minimumPlayDurationMs,
            now = now,
            postedNotificationPackages = {
                lookups++
                emptySet()
            },
        )
        assertEquals(1, lookups)
    }

    // ---- Paused music must survive ----------------------------------------

    @Test
    fun `a paused track with its notification still posted is kept`() {
        assertNull(
            reasonFor(
                snapshot(
                    playDurationMs = 90_000L,
                    idleForMs = 30 * 60 * 1000L,
                    hasLiveController = true,
                ),
                posted = setOf("com.example.player"),
            ),
        )
    }

    @Test
    fun `a paused track past the minimum is kept even without a live controller`() {
        // Plenty of players drop their MediaController while paused but keep the notification.
        assertNull(
            reasonFor(
                snapshot(packageName = "com.example.player", playDurationMs = 90_000L, idleForMs = 30 * 60 * 1000L),
                posted = setOf("com.example.player"),
            ),
        )
    }

    @Test
    fun `another app's notification does not protect a stranded session`() {
        // The posted set is keyed by package: a Gmail notification must not keep a dead
        // player's session alive.
        val reason = reasonFor(
            snapshot(packageName = "com.example.player", playDurationMs = 200_000L, idleForMs = 5 * 60 * 1000L),
            posted = setOf("com.google.android.gm"),
        )
        assertEquals(Reason.SOURCE_GONE, reason)
    }

    @Test
    fun `a short paused session is kept while it is still fresh`() {
        assertNull(reasonFor(snapshot(playDurationMs = 5_000L, idleForMs = 30_000L)))
    }

    @Test
    fun `an actively playing session is never reaped on duration grounds`() {
        assertNull(
            reasonFor(
                snapshot(
                    isPlaying = true,
                    playDurationMs = 2_000L,
                    idleForMs = 10 * 60 * 1000L,
                    hasLiveController = true,
                ),
                posted = setOf("com.example.player"),
            ),
        )
    }

    @Test
    fun `exactly at the minimum play duration is not below it`() {
        // Boundary: the save path uses `< minimum`, so equal must survive here too.
        assertNull(
            reasonFor(
                snapshot(
                    playDurationMs = minimumPlayDurationMs,
                    idleForMs = 10 * 60 * 1000L,
                ),
                posted = setOf("com.example.player"),
            ),
        )
    }

    @Test
    fun `a zero minimum never reaps on duration grounds`() {
        // Users can set the minimum as low as one second; a 0 value must not evict everything.
        assertNull(
            StrandedSessionRules.reapReason(
                snapshot = snapshot(
                    playDurationMs = 0L,
                    idleForMs = 10 * 60 * 1000L,
                ),
                minimumPlayDurationMs = 0L,
                now = now,
                postedNotificationPackages = { setOf("com.example.player") },
            ),
        )
    }
}
