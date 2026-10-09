package me.avinas.tempo.data.importexport

import me.avinas.tempo.data.local.entities.ListeningEventOrigin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Regression checks for backups restored into a previously synchronized phone. */
class RestoredPlaybackIdentityTest {
    private val desktop = ListeningEventOrigin("a".repeat(64), 12L, "desktop-one")
    private val browser = ListeningEventOrigin("b".repeat(64), 12L, "browser-two")

    @Test
    fun `new listening event remains importable without matching origins`() {
        assertTrue(shouldImportOfflinePlayback(listOf(desktop), emptyMap()))
        assertTrue(shouldImportOfflinePlayback(emptyList(), emptyMap()))
    }

    @Test
    fun `restored metadata drift does not duplicate a known producer event`() {
        assertFalse(shouldImportOfflinePlayback(listOf(desktop), mapOf(desktop.originEventId to 101L)))
    }

    @Test
    fun `two producers representing the same physical play are not reimported`() {
        val known = mapOf(desktop.originEventId to 101L, browser.originEventId to 101L)
        assertFalse(shouldImportOfflinePlayback(listOf(desktop, browser), known))
    }

    @Test
    fun `contradictory origin claims abort rather than silently merge history`() {
        val contradictory = mapOf(desktop.originEventId to 101L, browser.originEventId to 102L)
        try {
            shouldImportOfflinePlayback(listOf(desktop, browser), contradictory)
            fail("Conflicting producer claims should be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("different listening events"))
        }
    }
}
