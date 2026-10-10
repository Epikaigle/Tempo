package me.avinas.tempo.data.local.dao

import org.junit.Assert.assertEquals
import org.junit.Test

class NearestPlaybackMatchTest {
    private data class Candidate(
        val timestamp: Long,
        val claimedByProducer: Boolean = false,
    )

    @Test
    fun `assigns secondary producer to nearest of two rapid replays`() {
        val candidates = listOf(
            Candidate(1_700_000_000_000L),
            Candidate(1_700_000_008_000L),
        )
        val target = nearestEligiblePlaybackIndex(
            candidates, 1_700_000_007_850L,
            timestampOf = { it.timestamp },
            eligible = { true },
        )
        assertEquals("The later replay, not the first database row, owns the origin", 1, target)
    }

    @Test
    fun `already claimed playback is not eligible even when closer`() {
        val candidates = listOf(
            Candidate(1_700_000_000_000L),
            Candidate(1_700_000_008_000L, claimedByProducer = true),
        )
        assertEquals(0, nearestEligiblePlaybackIndex(
            candidates, 1_700_000_007_850L,
            timestampOf = { it.timestamp },
            eligible = { !it.claimedByProducer },
        ))
    }

    @Test
    fun `no eligible playback results in a new event`() {
        val candidates = listOf(Candidate(100L, claimedByProducer = true))
        assertEquals(-1, nearestEligiblePlaybackIndex(
            candidates, 100L,
            timestampOf = { it.timestamp },
            eligible = { !it.claimedByProducer },
        ))
    }

    @Test
    fun `equidistant candidates deterministically keep first ordering`() {
        assertEquals(0, nearestEligiblePlaybackIndex(
            listOf(Candidate(1_000L), Candidate(3_000L)), 2_000L,
            timestampOf = { it.timestamp },
            eligible = { true },
        ))
    }
}
