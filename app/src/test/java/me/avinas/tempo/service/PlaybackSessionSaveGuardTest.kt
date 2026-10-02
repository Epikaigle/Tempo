package me.avinas.tempo.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [MusicTrackingService.PlaybackSession.eventSaveClaimed] to ensure
 * a session can only be saved once.
 */
class PlaybackSessionSaveGuardTest {

    private fun session(title: String) =
        MusicTrackingService.PlaybackSession(
            packageName = "player",
            title = title,
            artist = "Artist",
            album = null,
        )

    @Test
    fun `event save claim starts unclaimed`() {
        assertFalse(session("Song").eventSaveClaimed.get())
    }

    @Test
    fun `event save can only be claimed once per session`() {
        val s = session("Song")
        assertTrue("first save must be allowed", s.eventSaveClaimed.compareAndSet(false, true))
        assertFalse(
            "second save of the same session must be blocked",
            s.eventSaveClaimed.compareAndSet(false, true),
        )
    }

    @Test
    fun `distinct sessions have independent save claims`() {
        val a = session("Song A")
        val b = session("Song B")
        assertTrue(a.eventSaveClaimed.compareAndSet(false, true))
        // Subsequent tracks create independent sessions that are not blocked by prior claims.
        assertTrue(b.eventSaveClaimed.compareAndSet(false, true))
    }
}
