package me.avinas.tempo.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MusicShareCardTest {

    @Test
    fun `resolveSongStandingKicker prioritizes peak rank #1`() {
        val kicker = resolveSongStandingKicker(
            peakRank = 1,
            playCount = 500,
            completionRate = 0.95f,
            skipRate = 0.01f
        )
        assertEquals("👑 #1 ALL-TIME TRACK", kicker)
    }

    @Test
    fun `resolveSongStandingKicker handles top 5 and top 20 rankings`() {
        val top3 = resolveSongStandingKicker(peakRank = 3, playCount = 80, completionRate = 0.8f, skipRate = 0.1f)
        assertEquals("🌟 TOP 3 IN LIBRARY", top3)

        val top15 = resolveSongStandingKicker(peakRank = 15, playCount = 30, completionRate = 0.7f, skipRate = 0.15f)
        assertEquals("🔥 TOP 15 ROTATION", top15)
    }

    @Test
    fun `resolveSongStandingKicker recognizes Century Club and Heavy Rotation`() {
        val century = resolveSongStandingKicker(peakRank = null, playCount = 120, completionRate = 0.6f, skipRate = 0.2f)
        assertEquals("⚡ CENTURY CLUB • 100+ SPINS", century)

        val heavy = resolveSongStandingKicker(peakRank = null, playCount = 65, completionRate = 0.6f, skipRate = 0.2f)
        assertEquals("⚡ HEAVY ROTATION", heavy)
    }

    @Test
    fun `resolveSongStandingKicker recognizes zero skips loyalty`() {
        val loyal = resolveSongStandingKicker(peakRank = null, playCount = 25, completionRate = 0.98f, skipRate = 0.02f)
        assertEquals("🎧 ZERO SKIPS • DEEP ROTATION", loyal)
    }

    @Test
    fun `resolveSongStandingKicker falls back gracefully for casual tracks`() {
        val fallback = resolveSongStandingKicker(peakRank = null, playCount = 4, completionRate = 0.5f, skipRate = 0.3f)
        assertEquals("TEMPO • TRACK HIGHLIGHT", fallback)
    }

    @Test
    fun `resolveSongFlexStat prioritizes discovery date over peak rank and completion`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2023, Calendar.OCTOBER, 15)
        }
        val flex = resolveSongFlexStat(
            firstPlayed = cal.timeInMillis,
            peakRank = 2,
            completionRate = 0.95f,
            isFavorite = true
        )
        assertEquals("SINCE", flex.label)
        assertTrue("Expected Oct in since date: ${flex.value}", flex.value.contains("Oct", ignoreCase = true))
    }

    @Test
    fun `resolveSongFlexStat uses peak rank when discovery date is absent`() {
        val flex = resolveSongFlexStat(
            firstPlayed = null,
            peakRank = 4,
            completionRate = 0.9f,
            isFavorite = false
        )
        assertEquals("PEAK", flex.label)
        assertEquals("#4", flex.value)
    }

    @Test
    fun `resolveSongFlexStat uses finish rate when peak rank is absent`() {
        val flex = resolveSongFlexStat(
            firstPlayed = null,
            peakRank = null,
            completionRate = 0.92f,
            isFavorite = false
        )
        assertEquals("FINISH", flex.label)
        assertEquals("92%", flex.value)
    }

    @Test
    fun `resolveSongFlexStat falls back to status`() {
        val fav = resolveSongFlexStat(firstPlayed = null, peakRank = null, completionRate = null, isFavorite = true)
        assertEquals("STATUS", fav.label)
        assertEquals("Fav", fav.value)

        val nonFav = resolveSongFlexStat(firstPlayed = null, peakRank = null, completionRate = null, isFavorite = false)
        assertEquals("STATUS", nonFav.label)
        assertEquals("Spun", nonFav.value)
    }

    @Test
    fun `resolveSongFlexHighlight selects best editorial flex`() {
        // Binge day takes priority
        val binge = resolveSongFlexHighlight(
            peakBingeDay = Pair("2024-02-14", 14),
            habitualHour = "Late Night",
            skipRate = 0.02f,
            playCount = 30,
            mood = "Energetic",
            genre = "Electronic"
        )
        assertEquals("🔥 Binge Record: 14 plays in a day", binge)

        // Habitual hour when no binge
        val routine = resolveSongFlexHighlight(
            peakBingeDay = null,
            habitualHour = "Late Night",
            skipRate = 0.1f,
            playCount = 20,
            mood = "Chill",
            genre = "Lo-Fi"
        )
        assertEquals("🌙 Signature late night soundtrack", routine)

        // Loyalty when no habitual hour
        val loyalty = resolveSongFlexHighlight(
            peakBingeDay = null,
            habitualHour = null,
            skipRate = 0.03f,
            playCount = 25,
            mood = null,
            genre = null
        )
        assertEquals("✨ 100% Loyalty • Never Skipped", loyalty)

        // Mood when loyalty doesn't qualify
        val moodHighlight = resolveSongFlexHighlight(
            peakBingeDay = null,
            habitualHour = null,
            skipRate = 0.2f,
            playCount = 5,
            mood = "Melancholic",
            genre = "Indie"
        )
        assertEquals("✨ Vibe: Melancholic", moodHighlight)

        // Genre fallback
        val genreHighlight = resolveSongFlexHighlight(
            peakBingeDay = null,
            habitualHour = null,
            skipRate = 0.2f,
            playCount = 5,
            mood = null,
            genre = "Synthwave"
        )
        assertEquals("🎧 Synthwave", genreHighlight)

        // Null when nothing available
        val empty = resolveSongFlexHighlight(null, null, null, 1, null, null)
        assertNull(empty)
    }

    @Test
    fun `resolveArtistFlexPill selects streak then discovery date`() {
        val streak = resolveArtistFlexPill(
            listeningStreakDays = 7,
            firstListenedDate = 1672531199000L,
            firstDiscoveryTimestamp = 1609459200000L
        )
        assertEquals("🔥 7-day listening streak", streak)

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2022, Calendar.JUNE, 1)
        }
        val discovery = resolveArtistFlexPill(
            listeningStreakDays = 1,
            firstListenedDate = cal.timeInMillis,
            firstDiscoveryTimestamp = null
        )
        assertNotNull(discovery)
        assertTrue("Expected 'Listening since' in $discovery", discovery!!.startsWith("🎧 Listening since"))

        val genreOnly = resolveArtistFlexPill(0, null, null, topGenre = "Indie Rock")
        assertEquals("🎵 Indie Rock", genreOnly)

        val none = resolveArtistFlexPill(0, null, null, null)
        assertNull(none)
    }

    @Test
    fun `resolveArtistStandingKicker handles percentile and play count tiers`() {
        val number1 = resolveArtistStandingKicker(percentile = 0.0, playCount = 300)
        assertEquals("👑 #1 ALL-TIME ARTIST", number1)

        val top1 = resolveArtistStandingKicker(percentile = 0.8, playCount = 300)
        assertEquals("👑 TOP 1% IN YOUR LIBRARY", top1)

        val top5 = resolveArtistStandingKicker(percentile = 4.2, playCount = 150)
        assertEquals("🌟 TOP 5% IN YOUR LIBRARY", top5)

        val top10 = resolveArtistStandingKicker(percentile = 9.5, playCount = 100)
        assertEquals("🔥 TOP 10% IN YOUR LIBRARY", top10)

        // Volume guard prevents false top 1% claim with only 5 plays
        val lowVolume = resolveArtistStandingKicker(percentile = 0.5, playCount = 5)
        assertEquals("TEMPO • ARTIST SPOTLIGHT", lowVolume)

        val century = resolveArtistStandingKicker(percentile = null, playCount = 650)
        assertEquals("⚡ CENTURY CLUB • 500+ SPINS", century)

        val heavy = resolveArtistStandingKicker(percentile = null, playCount = 280)
        assertEquals("⚡ HEAVY ROTATION • 200+ SPINS", heavy)

        val regular = resolveArtistStandingKicker(percentile = null, playCount = 60)
        assertEquals("🎧 ROTATION REGULAR", regular)

        val spotlight = resolveArtistStandingKicker(percentile = null, playCount = 12)
        assertEquals("TEMPO • ARTIST SPOTLIGHT", spotlight)
    }
    @Test
    fun `ShareTheme GLITCH is 6th theme, uses artwork, and sets GLITCH_MOTION backdrop`() {
        // Must be the 6th theme (index 5)
        assertEquals(ShareTheme.GLITCH, ShareTheme.entries[5])
        assertEquals(6, ShareTheme.entries.size)

        val palette = ShareTheme.GLITCH.palette
        assertTrue("GLITCH theme must use artwork for base album art", palette.usesArtwork)
        assertEquals(ShareBackdropStyle.GLITCH_MOTION, palette.backdrop)
        assertTrue("GLITCH must be a dark theme", palette.isDark)
        assertEquals(3, palette.gradient.size)
        assertEquals(3, palette.overlay.size)
        assertNotNull(palette.accent)
        assertNotNull(palette.glowTop)
        assertNotNull(palette.glowBottom)
    }
}
