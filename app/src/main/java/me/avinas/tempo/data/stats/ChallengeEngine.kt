package me.avinas.tempo.data.stats

import me.avinas.tempo.data.local.entities.DailyChallenge
import java.util.Calendar
import kotlin.math.abs

/**
 * Engine for generating smart, personalized daily challenges.
 *
 * Challenges auto-calibrate based on the user's recent listening history
 * and have strict maximum limits to ensure they remain practical.
 */
object ChallengeEngine {
    object Category {
        const val VOLUME = "VOLUME"
        const val DISCOVERY = "DISCOVERY"
        const val EXPLORATION = "EXPLORATION"
        const val TIME = "TIME"
        const val VARIETY = "VARIETY"
    }

    object Difficulty {
        const val EASY = "EASY"
        const val MEDIUM = "MEDIUM"
        const val HARD = "HARD"
    }

    // Smart Limits (Safety Caps)
    // Even if a user listens to 500 songs a day, we won't ask them to listen to 750.
    private const val MAX_SONGS_PER_DAY = 50
    private const val MAX_MINUTES_PER_DAY = 180
    private const val MAX_UNIQUE_ARTISTS = 20
    private const val MAX_NEW_ARTISTS = 5
    private const val MIN_NEW_ARTISTS = 2
    private const val MAX_NEW_GENRES = 3
    private const val MIN_NEW_GENRES = 1
    private const val MAX_EXPLORATION_SONGS = 10

    // Fallback Defaults (New Users)

    private const val DEFAULT_SONGS_EASY = 5
    private const val DEFAULT_SONGS_MEDIUM = 15
    private const val DEFAULT_SONGS_HARD = 25

    private const val DEFAULT_MINS_EASY = 15
    private const val DEFAULT_MINS_MEDIUM = 45
    private const val DEFAULT_MINS_HARD = 90

    // Personalized time-window challenge defaults (used when there's no usable history)
    private const val DEFAULT_WINDOW_START_HOUR = 7
    private const val DEFAULT_WINDOW_END_HOUR = 10

    // Absolute guardrails for any personalized listening window
    private const val EARLIEST_WINDOW_START_HOUR = 4
    private const val LATEST_WINDOW_END_HOUR = 23

    /** Songs required inside a personalized time-window challenge. */
    private const val TIME_WINDOW_TARGET_SONGS = 5

    /**
     * Data class to hold user's recent listening stats for calibration
     */
    data class UserHistoryMetrics(
        val avgSongsPerDay: Int,
        val avgMinutesPerDay: Int,
        val avgUniqueArtistsPerDay: Int,
        val topGenres: List<String>,
        val topArtists: List<String>,
        /**
         * Typical hour-of-day the user *starts* listening (from StatsDao.getTypicalStartHour).
         * Used to personalize the time-window challenge. Null when there is no history.
         */
        val typicalStartHour: Int? = null,
    )

    /**
     * Generate 4 challenges for today (1 EASY, 2 MEDIUM, 1 HARD).
     *
     * @param dateString YYYY-MM-DD
     * @param metrics The user's recent 7-day average metrics (null for new users)
     * @param excludeTypes Challenge-id prefixes used yesterday (e.g. "time", "volume_songs").
     *        The rotation is nudged forward until it lands on a type not in this set, so the
     *        user isn't served the exact same challenge family two days running.
     */
    fun generateChallenges(
        dateString: String,
        metrics: UserHistoryMetrics?,
        excludeTypes: Set<String> = emptySet(),
    ): List<DailyChallenge> {
        val challenges = mutableListOf<DailyChallenge>()

        // Base metrics (use defaults if history is null/empty)
        val baseSongs = if (metrics != null && metrics.avgSongsPerDay > 0) metrics.avgSongsPerDay else DEFAULT_SONGS_MEDIUM
        val baseMins = if (metrics != null && metrics.avgMinutesPerDay > 0) metrics.avgMinutesPerDay else DEFAULT_MINS_MEDIUM
        val baseArtists = if (metrics != null && metrics.avgUniqueArtistsPerDay > 0) metrics.avgUniqueArtistsPerDay else 5

        // Calculate targets natively
        val easySongsTarget = calibrate(baseSongs, 0.8f, MAX_SONGS_PER_DAY).coerceAtLeast(3)
        val medSongsTarget = calibrate(baseSongs, 1.2f, MAX_SONGS_PER_DAY).coerceAtLeast(10)
        val hardSongsTarget = calibrate(baseSongs, 1.5f, MAX_SONGS_PER_DAY).coerceAtLeast(20)

        val easyMinsTarget = calibrate(baseMins, 0.8f, MAX_MINUTES_PER_DAY).coerceAtLeast(10)
        val medMinsTarget = calibrate(baseMins, 1.2f, MAX_MINUTES_PER_DAY).coerceAtLeast(30)
        val hardMinsTarget = calibrate(baseMins, 1.5f, MAX_MINUTES_PER_DAY).coerceAtLeast(60)

        val medArtistsTarget = calibrate(baseArtists, 1.2f, MAX_UNIQUE_ARTISTS).coerceAtLeast(5)

        // Discovery targets scale with the user's demonstrated discovery appetite instead of
        // being pinned at the cap, so a light listener isn't asked for 5 brand-new artists and
        // a heavy explorer is still stretched.
        val newArtistsTarget = calibrate(baseArtists, 0.5f, MAX_NEW_ARTISTS).coerceAtLeast(MIN_NEW_ARTISTS)
        val newGenresTarget = calibrate(baseArtists, 0.3f, MAX_NEW_GENRES).coerceAtLeast(MIN_NEW_GENRES)

        // Generate 1 EASY, 2 MEDIUM, 1 HARD (4 challenges total)

        // EASY Challenge
        // Alternate between songs and minutes based on day of year
        val dayOfYear = getDayOfYear(dateString)
        if (dayOfYear % 2 == 0) {
            challenges.add(createVolumeSongsChallenge(dateString, Difficulty.EASY, easySongsTarget))
        } else {
            challenges.add(createVolumeMinsChallenge(dateString, Difficulty.EASY, easyMinsTarget))
        }

        // MEDIUM Challenge 1 (Time / Variety / Volume)
        // The rotation is nudged past whatever the user was served yesterday so the same
        // challenge family isn't repeated two days in a row.
        when (pickSlot(dayOfYear, 3, excludeTypes, ::med1Type)) {
            0 -> {
                challenges.add(createTimeWindowChallenge(dateString, metrics?.typicalStartHour))
            }

            1 -> {
                challenges.add(createVarietyArtistsChallenge(dateString, Difficulty.MEDIUM, medArtistsTarget))
            }

            else -> {
                // Give the opposite of what Easy got
                if (dayOfYear % 2 == 0) {
                    challenges.add(createVolumeMinsChallenge(dateString, Difficulty.MEDIUM, medMinsTarget))
                } else {
                    challenges.add(createVolumeSongsChallenge(dateString, Difficulty.MEDIUM, medSongsTarget))
                }
            }
        }

        // MEDIUM Challenge 2 (Exploration - Dynamic)
        // Dynamically pick a top artist or genre
        if (metrics != null && metrics.topArtists.isNotEmpty() && dayOfYear % 2 == 0) {
            val idx = dayOfYear % metrics.topArtists.size
            val artist = metrics.topArtists[idx]
            val count = calibrate(baseSongs, 0.5f, MAX_EXPLORATION_SONGS).coerceAtLeast(3)
            challenges.add(createExplorationArtistChallenge(dateString, Difficulty.MEDIUM, count, artist))
        } else if (metrics != null && metrics.topGenres.isNotEmpty()) {
            val idx = (dayOfYear / 2) % metrics.topGenres.size
            val genre = metrics.topGenres[idx]
            val count = calibrate(baseSongs, 0.5f, MAX_EXPLORATION_SONGS).coerceAtLeast(3)
            challenges.add(createExplorationGenreChallenge(dateString, Difficulty.MEDIUM, count, genre))
        } else {
            // Fallback to Discovery
            challenges.add(createDiscoveryGenresChallenge(dateString, Difficulty.MEDIUM, newGenresTarget))
        }

        // HARD Challenge
        when (pickSlot(dayOfYear, 3, excludeTypes, ::hardType)) {
            0 -> challenges.add(createVolumeSongsChallenge(dateString, Difficulty.HARD, hardSongsTarget))
            1 -> challenges.add(createVolumeMinsChallenge(dateString, Difficulty.HARD, hardMinsTarget))
            else -> challenges.add(createDiscoveryArtistsChallenge(dateString, Difficulty.HARD, newArtistsTarget))
        }

        return challenges
    }

    /**
     * Applies a multiplier to a base value, and safely caps it.
     */
    private fun calibrate(
        base: Int,
        multiplier: Float,
        maxCap: Int,
    ): Int = (base * multiplier).toInt().coerceAtMost(maxCap)

    private fun getDayOfYear(dateString: String): Int =
        try {
            val parts = dateString.split("-")
            if (parts.size == 3) {
                val cal = Calendar.getInstance()
                cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                cal.get(Calendar.DAY_OF_YEAR)
            } else {
                1
            }
        } catch (e: Exception) {
            1
        }

    /** The challenge-id prefix each MEDIUM-1 slot maps to. */
    private fun med1Type(slot: Int): String =
        when (slot) {
            0 -> "time"
            1 -> "variety"
            else -> "volume"
        }

    /** The challenge-id prefix each HARD slot maps to. */
    private fun hardType(slot: Int): String =
        when (slot) {
            0 -> "volume_songs"
            1 -> "volume_mins"
            else -> "discovery_artists"
        }

    /**
     * Pick a rotation slot for [dayOfYear], skipping any slot whose challenge-id prefix the
     * user was already served yesterday ([excludeTypes]). Nudges forward from the natural
     * `dayOfYear % slotCount` position so the rotation stays deterministic for a given day
     * but rarely repeats the same family back-to-back.
     */
    private fun pickSlot(
        dayOfYear: Int,
        slotCount: Int,
        excludeTypes: Set<String>,
        typeForSlot: (Int) -> String,
    ): Int {
        if (excludeTypes.isEmpty()) return dayOfYear % slotCount
        for (offset in 0 until slotCount) {
            val slot = (dayOfYear + offset) % slotCount
            if (excludeTypes.none { typeForSlot(slot).startsWith(it) || it.startsWith(typeForSlot(slot)) }) {
                return slot
            }
        }
        return dayOfYear % slotCount
    }

    // Challenge Factories

    /**
     * XP reward per difficulty, chosen deterministically from the date so a challenge that is
     * ever regenerated for the same day keeps the same reward. This keeps total XP (recomputed
     * by summing stored challenge rewards) reproducible.
     */
    private fun getReward(
        difficulty: String,
        dateString: String,
    ): Int {
        val options =
            when (difficulty) {
                Difficulty.EASY -> listOf(15, 20, 25)
                Difficulty.MEDIUM -> listOf(30, 40, 50)
                Difficulty.HARD -> listOf(60, 80, 100)
                else -> return 20
            }
        return options[abs(dateString.hashCode()) % options.size]
    }

    private fun createVolumeSongsChallenge(
        date: String,
        difficulty: String,
        target: Int,
    ): DailyChallenge =
        DailyChallenge(
            challengeId = "volume_songs_$difficulty",
            date = date,
            title = "$target-Song Sprint",
            description = "Listen to $target songs today.",
            xpReward = getReward(difficulty, date),
            targetValue = target,
            category = Category.VOLUME,
            difficulty = difficulty,
        )

    private fun createVolumeMinsChallenge(
        date: String,
        difficulty: String,
        target: Int,
    ): DailyChallenge =
        DailyChallenge(
            challengeId = "volume_mins_$difficulty",
            date = date,
            title = "Audio Immersion",
            description = "Listen for a total of $target minutes today.",
            xpReward = getReward(difficulty, date),
            targetValue = target,
            category = Category.VOLUME,
            difficulty = difficulty,
        )

    private fun createVarietyArtistsChallenge(
        date: String,
        difficulty: String,
        target: Int,
    ): DailyChallenge =
        DailyChallenge(
            challengeId = "variety_artists_$difficulty",
            date = date,
            title = "Broad Horizons",
            description = "Listen to $target different artists today.",
            xpReward = getReward(difficulty, date),
            targetValue = target,
            category = Category.VARIETY,
            difficulty = difficulty,
        )

    private fun createDiscoveryArtistsChallenge(
        date: String,
        difficulty: String,
        target: Int,
    ): DailyChallenge =
        DailyChallenge(
            challengeId = "discovery_artists_$difficulty",
            date = date,
            title = "Talent Scout",
            description = "Discover and listen to $target new artists.",
            xpReward = getReward(difficulty, date),
            targetValue = target,
            category = Category.DISCOVERY,
            difficulty = difficulty,
        )

    private fun createDiscoveryGenresChallenge(
        date: String,
        difficulty: String,
        target: Int,
    ): DailyChallenge =
        DailyChallenge(
            challengeId = "discovery_genres_$difficulty",
            date = date,
            title = "Sound Explorer",
            description = "Explore $target different genres.",
            xpReward = getReward(difficulty, date),
            targetValue = target,
            category = Category.DISCOVERY,
            difficulty = difficulty,
        )

    private fun createExplorationArtistChallenge(
        date: String,
        difficulty: String,
        target: Int,
        artist: String,
    ): DailyChallenge =
        DailyChallenge(
            // Stable, collision-free id (slug, not String.hashCode()) so two different
            // artists can never collide into the same row on the same day.
            challengeId = "explore_artist_${slug(artist)}",
            date = date,
            title = "Deep Dive: $artist",
            description = "Listen to $target songs by $artist.",
            xpReward = getReward(difficulty, date),
            targetValue = target,
            category = Category.EXPLORATION,
            difficulty = difficulty,
            targetMetadata = artist,
        )

    private fun createExplorationGenreChallenge(
        date: String,
        difficulty: String,
        target: Int,
        genre: String,
    ): DailyChallenge {
        val displayGenre = genre.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return DailyChallenge(
            challengeId = "explore_genre_${slug(genre)}",
            date = date,
            title = "Genre Focus: $displayGenre",
            description = "Vibe out to $target $displayGenre songs.",
            xpReward = getReward(difficulty, date),
            targetValue = target,
            category = Category.EXPLORATION,
            difficulty = difficulty,
            targetMetadata = genre,
        )
    }

    /**
     * Personalized time-window challenge. Uses the user's typical first-listen hour to build a
     * 3-hour window they'd actually be listening in, with a title matching the time of day.
     * Falls back to a fixed 7-10 AM "Early Bird" when there's no usable history.
     *
     * The window is stored in targetMetadata as "start,end" so ChallengeRepository can query
     * progress without re-deriving it.
     */
    private fun createTimeWindowChallenge(
        date: String,
        typicalStartHour: Int?,
    ): DailyChallenge {
        val start: Int
        val end: Int
        val id: String
        val title: String

        if (typicalStartHour != null && typicalStartHour in 0..23) {
            start = (typicalStartHour - 1).coerceIn(EARLIEST_WINDOW_START_HOUR, LATEST_WINDOW_END_HOUR - 2)
            end = (start + 3).coerceAtMost(LATEST_WINDOW_END_HOUR)
            when {
                end <= 12 -> {
                    id = "time_early_bird"
                    title = "Early Bird"
                }

                start >= 17 -> {
                    id = "time_night_owl"
                    title = "Night Owl"
                }

                else -> {
                    id = "time_prime_time"
                    title = "Prime Time"
                }
            }
        } else {
            start = DEFAULT_WINDOW_START_HOUR
            end = DEFAULT_WINDOW_END_HOUR
            id = "time_early_bird"
            title = "Early Bird"
        }

        return DailyChallenge(
            challengeId = id,
            date = date,
            title = title,
            description = "Listen to $TIME_WINDOW_TARGET_SONGS songs between ${formatHour(start)} and ${formatHour(end)}.",
            xpReward = getReward(Difficulty.MEDIUM, date),
            targetValue = TIME_WINDOW_TARGET_SONGS,
            category = Category.TIME,
            difficulty = Difficulty.MEDIUM,
            targetMetadata = "$start,$end",
        )
    }

    /** Stable, deterministic slug for challenge IDs - lowercase alphanumeric, no hash collisions. */
    private fun slug(value: String): String =
        value
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "x" }

    private fun formatHour(hour: Int): String {
        val h = hour % 24
        val suffix = if (h < 12) "AM" else "PM"
        val display =
            when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
        return "$display $suffix"
    }
}
