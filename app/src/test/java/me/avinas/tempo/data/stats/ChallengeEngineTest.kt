package me.avinas.tempo.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChallengeEngineTest {
    @Test
    fun `generateChallenges produces 4 challenges on active day`() {
        val dateString = "2023-10-27"
        val metrics =
            ChallengeEngine.UserHistoryMetrics(
                avgSongsPerDay = 20,
                avgMinutesPerDay = 65,
                avgUniqueArtistsPerDay = 10,
                topArtists = listOf("Artist A", "Artist B"),
                topGenres = listOf("Rock", "Pop"),
            )

        val challenges = ChallengeEngine.generateChallenges(dateString, metrics)

        assertEquals(4, challenges.size)
        // All should belong to the same date
        assertTrue(challenges.all { it.date == dateString })
        // Difficulties should be varied (usually Easy, Medium, Hard)
        assertTrue(challenges.any { it.difficulty == ChallengeEngine.Difficulty.EASY })
        assertTrue(challenges.any { it.difficulty == ChallengeEngine.Difficulty.MEDIUM })
        assertTrue(challenges.any { it.difficulty == ChallengeEngine.Difficulty.HARD })
    }

    @Test
    fun `generateChallenges produces 4 challenges with fallback metrics`() {
        val dateString = "2023-10-27"
        // Null metrics also works with defaults
        val challenges = ChallengeEngine.generateChallenges(dateString, null)

        assertEquals(4, challenges.size)
        // Fallback targets should be reasonable
        val volumeChallenge = challenges.find { it.category == ChallengeEngine.Category.VOLUME }
        // Default easy volume without metadata is 5-20 songs
        assertTrue((volumeChallenge?.targetValue ?: 0) >= 5)
    }

    @Test
    fun `generated challenge titles carry no emoji`() {
        val metrics =
            ChallengeEngine.UserHistoryMetrics(
                avgSongsPerDay = 20,
                avgMinutesPerDay = 65,
                avgUniqueArtistsPerDay = 10,
                topArtists = listOf("Artist A", "Artist B"),
                topGenres = listOf("Rock", "Pop"),
            )

        // Walk enough dates to reach every branch (day-of-year parity picks the type), plus the
        // no-history path, so all eight challenge types are generated.
        val challenges =
            (1..24).flatMap { day ->
                ChallengeEngine.generateChallenges("2023-01-%02d".format(day), metrics)
            } + ChallengeEngine.generateChallenges("2023-01-01", null)

        assertTrue(challenges.isNotEmpty())

        // Keep the coverage claim above honest — otherwise this test can silently stop covering
        // the types it exists to guard. A new type here means the icon set in
        // res/drawable/ic_challenge_* needs an entry too.
        val expectedTypes =
            setOf(
                "volume_songs",
                "volume_mins",
                "variety_artists",
                "discovery_artists",
                "discovery_genres",
                "explore_artist",
                "explore_genre",
                "time_early_bird",
            )
        val missing = expectedTypes.filterNot { type -> challenges.any { it.challengeId.startsWith(type) } }
        assertTrue("Challenge types not exercised: $missing", missing.isEmpty())

        challenges.forEach { challenge ->
            assertTrue(
                "Title '${challenge.title}' still contains an emoji",
                challenge.title.none { Character.getType(it).toInt() == Character.OTHER_SYMBOL.toInt() },
            )
            // The challenge card strips leading non-letter/digit characters from persisted titles
            // (legacy rows predate the icon set), so a fresh title must start with one.
            assertTrue(
                "Title '${challenge.title}' does not start with a letter or digit",
                challenge.title.first().isLetterOrDigit(),
            )
        }
    }
}
