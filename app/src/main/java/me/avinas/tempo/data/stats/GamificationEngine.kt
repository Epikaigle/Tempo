package me.avinas.tempo.data.stats

import kotlin.math.floor
import kotlin.math.pow

/**
 * Core engine for the gamification system.
 * 
 * Handles XP calculation, level computation, and badge definitions.
 * All logic is deterministic. XP can always be recomputed from listening history.
 * 
 * Level formula: xpForLevel(n) = floor(130 * n^1.5)
 * This creates an exponential curve with no cap:
 *   Level 10 = 4,110 XP       Level 50 = 45,961 XP
 *   Level 25 = 16,250 XP      Level 100 = 130,000 XP
 */
object GamificationEngine {

    // =====================
    // XP Constants
    // =====================
    
    const val XP_FULL_PLAY = 10L    // ≥80% completion
    const val XP_PARTIAL_PLAY = 3L  // 30-79% completion
    const val XP_SKIPPED = 0L       // <30% completion

    // Anti-gaming: max plays of the *same track* per calendar day that contribute XP.
    // Additional plays are still recorded (stats stay accurate) but earn 0 XP.
    const val MAX_XP_PLAYS_PER_TRACK_PER_DAY = 3

    // Discovery
    const val XP_NEW_ARTIST = 20L
    
    // =====================
    // Level Computation
    // =====================
    
    /**
     * Cumulative XP required to reach a given level.
     * Uses formula: floor(130 * level^1.5)
     */
    fun cumulativeXpForLevel(level: Int): Long {
        if (level <= 0) return 0
        return floor(130.0 * level.toDouble().pow(1.5)).toLong()
    }
    
    /**
     * Compute the current level for a given total XP.
     * Returns level number (0 = haven't reached level 1 yet).
     */
    fun computeLevel(totalXp: Long): Int {
        if (totalXp <= 0) return 0
        var level = 0
        while (cumulativeXpForLevel(level + 1) <= totalXp) {
            level++
        }
        return level
    }
    
    /**
     * Given total XP, compute full level state.
     */
    fun computeLevelState(totalXp: Long): LevelState {
        val level = computeLevel(totalXp)
        val xpForCurrent = cumulativeXpForLevel(level)
        val xpForNext = cumulativeXpForLevel(level + 1)
        return LevelState(
            level = level,
            totalXp = totalXp,
            xpForCurrentLevel = xpForCurrent,
            xpForNextLevel = xpForNext
        )
    }
    
    /**
     * Calculate total XP from play counts and discovery.
     */
    fun calculateXp(fullPlayCount: Int, partialPlayCount: Int, uniqueArtistCount: Int = 0): Long {
        return (fullPlayCount * XP_FULL_PLAY) + 
               (partialPlayCount * XP_PARTIAL_PLAY) +
               (uniqueArtistCount * XP_NEW_ARTIST)
    }
    
    /**
     * Calculate the longest streak from a sorted list of dates (most recent first).
     * Dates should be in YYYY-MM-DD format.
     */
    fun calculateStreak(dates: List<String>): StreakInfo {
        if (dates.isEmpty()) return StreakInfo(0, 0)
        
        val parsedDates = dates.mapNotNull { 
            try { java.time.LocalDate.parse(it) } catch (e: Exception) { null }
        }.sortedDescending()
        
        if (parsedDates.isEmpty()) return StreakInfo(0, 0)
        
        // Current streak (from today backwards)
        val today = java.time.LocalDate.now()
        var currentStreak = 0
        var expectedDate = today
        
        for (date in parsedDates) {
            if (date == expectedDate || date == expectedDate.minusDays(1)) {
                if (date == expectedDate) {
                    currentStreak++
                    expectedDate = date.minusDays(1)
                } else if (date == expectedDate.minusDays(1)) {
                    // Allow 1-day gap if today hasn't been listened yet
                    if (currentStreak == 0) {
                        currentStreak++
                        expectedDate = date.minusDays(1)
                    } else {
                        break
                    }
                }
            } else if (date < expectedDate.minusDays(1)) {
                break
            }
            // Skip duplicate dates
        }
        
        // Longest streak ever
        var longestStreak = 0
        var currentRun = 1
        for (i in 1 until parsedDates.size) {
            val diff = parsedDates[i - 1].toEpochDay() - parsedDates[i].toEpochDay()
            if (diff == 1L) {
                currentRun++
            } else if (diff > 1L) {
                longestStreak = maxOf(longestStreak, currentRun)
                currentRun = 1
            }
            // diff == 0 means same day (duplicate), skip
        }
        longestStreak = maxOf(longestStreak, currentRun)
        
        return StreakInfo(
            currentStreak = currentStreak,
            longestStreak = longestStreak
        )
    }
    
    // =====================
    // Titles
    // =====================
    
    /**
     * Compute User Title based on BOTH level and listening diversity (unique artists).
     * This decouples the title from pure volume/farming.
     */
    fun computeTitle(level: Int, uniqueArtists: Int): String {
        return when {
            level >= 150 && uniqueArtists >= 2000 -> "Sound God"
            level >= 100 && uniqueArtists >= 1000 -> "Audiophile"
            level >= 75 && uniqueArtists >= 750 -> "Music Legend"
            level >= 50 && uniqueArtists >= 500 -> "Music Connoisseur"
            level >= 35 && uniqueArtists >= 250 -> "Dedicated Listener"
            level >= 20 && uniqueArtists >= 100 -> "Music Enthusiast"
            level >= 10 && uniqueArtists >= 50 -> "Music Fan"
            level >= 5 && uniqueArtists >= 10 -> "Casual Listener"
            else -> "Newcomer"
        }
    }

    // =====================
    // Badge Rarity
    // =====================

    /**
     * Rarity tier for a badge. Rarer badges are worth more XP per star and are
     * visually distinguished in the UI (glow, accent, label).
     *
     * @param xpPerStar  XP awarded per earned star (deterministic, recomputed from stars).
     * @param sortWeight Higher = shown first within a category (rarer on top).
     */
    enum class BadgeRarity(val label: String, val xpPerStar: Int, val sortWeight: Int) {
        COMMON("Common", 25, 0),
        RARE("Rare", 75, 1),
        EPIC("Epic", 200, 2),
        LEGENDARY("Legendary", 500, 3),
        MYTHIC("Mythic", 1500, 4)
    }

    /**
     * Static rarity assignment per badge. Rarity is a property of the *definition*,
     * not the earned row, so it lives here (no DB column / migration needed).
     */
    val BADGE_RARITY: Map<String, BadgeRarity> = mapOf(
        // Milestones
        "first_play"     to BadgeRarity.COMMON,
        "plays_100"      to BadgeRarity.COMMON,
        "plays_500"      to BadgeRarity.RARE,
        "plays_1000"     to BadgeRarity.RARE,
        "plays_5000"     to BadgeRarity.EPIC,
        "plays_10000"    to BadgeRarity.LEGENDARY,
        // Time
        "time_1h"        to BadgeRarity.COMMON,
        "time_24h"       to BadgeRarity.COMMON,
        "time_100h"      to BadgeRarity.RARE,
        "time_500h"      to BadgeRarity.EPIC,
        // Streaks
        "streak_7"       to BadgeRarity.COMMON,
        "streak_30"      to BadgeRarity.RARE,
        "streak_100"     to BadgeRarity.EPIC,
        "streak_365"     to BadgeRarity.MYTHIC,
        // Discovery
        "artists_10"     to BadgeRarity.COMMON,
        "artists_50"     to BadgeRarity.RARE,
        "artists_100"    to BadgeRarity.RARE,
        "genres_10"      to BadgeRarity.COMMON,
        "genres_25"      to BadgeRarity.RARE,
        // Engagement
        "night_owl"      to BadgeRarity.RARE,
        "early_bird"     to BadgeRarity.RARE,
        "marathon"       to BadgeRarity.EPIC,
        // Level
        "level_5"        to BadgeRarity.COMMON,
        "level_10"       to BadgeRarity.COMMON,
        "level_25"       to BadgeRarity.RARE,
        "level_50"       to BadgeRarity.EPIC,
        "level_75"       to BadgeRarity.EPIC,
        "level_100"      to BadgeRarity.MYTHIC
    )

    /** Rarity for a badge id (COMMON if unknown). */
    fun getRarity(badgeId: String): BadgeRarity = BADGE_RARITY[badgeId] ?: BadgeRarity.COMMON

    /** Total XP a badge contributes given its current star count. */
    fun getBadgeXpContribution(badgeId: String, stars: Int): Long =
        getRarity(badgeId).xpPerStar.toLong() * stars.coerceAtLeast(0)

    // =====================
    // Badge Definitions
    // =====================

    val ALL_BADGE_DEFINITIONS: List<BadgeDefinition> = listOf(
        // === Milestones ===
        BadgeDefinition("first_play", "First Note", "Your musical journey begins", "music_note", "MILESTONE", 1, BadgeRarity.COMMON),
        BadgeDefinition("plays_100", "Century", "Play 100 songs", "century", "MILESTONE", 100, BadgeRarity.COMMON),
        BadgeDefinition("plays_500", "Sound Pilgrim", "Journey through 500 tracks", "star_half", "MILESTONE", 500, BadgeRarity.RARE),
        BadgeDefinition("plays_1000", "Grand Maestro", "Master 1,000 tracks", "star", "MILESTONE", 1_000, BadgeRarity.RARE),
        BadgeDefinition("plays_5000", "Virtuoso", "Conquer 5,000 tracks", "diamond", "MILESTONE", 5_000, BadgeRarity.EPIC),
        BadgeDefinition("plays_10000", "Legendary", "Transcend 10,000 tracks", "emoji_events", "MILESTONE", 10_000, BadgeRarity.LEGENDARY),

        // === Time ===
        BadgeDefinition("time_1h", "First Hour", "Your first hour of music", "timer", "TIME", 1, BadgeRarity.COMMON),
        BadgeDefinition("time_24h", "Day Tripper", "A full day's worth of music", "schedule", "TIME", 24, BadgeRarity.COMMON),
        BadgeDefinition("time_100h", "Centurion", "100 hours of listening", "hourglass_full", "TIME", 100, BadgeRarity.RARE),
        BadgeDefinition("time_500h", "Sound Sage", "500 hours of listening", "headphones", "TIME", 500, BadgeRarity.EPIC),

        // === Streaks ===
        BadgeDefinition("streak_7", "Week Warrior", "7-day listening streak", "local_fire_department", "STREAK", 7, BadgeRarity.COMMON),
        BadgeDefinition("streak_30", "Monthly Maven", "30-day listening streak", "whatshot", "STREAK", 30, BadgeRarity.RARE),
        BadgeDefinition("streak_100", "Ironclad", "100-day listening streak", "military_tech", "STREAK", 100, BadgeRarity.EPIC),
        BadgeDefinition("streak_365", "Year-Round", "365-day listening streak", "auto_awesome", "STREAK", 365, BadgeRarity.MYTHIC),

        // === Discovery ===
        BadgeDefinition("artists_10", "Explorer", "Discover unique artists", "explore", "DISCOVERY", 10, BadgeRarity.COMMON),
        BadgeDefinition("artists_50", "Curator", "Discover 50 unique artists", "collections", "DISCOVERY", 50, BadgeRarity.RARE),
        BadgeDefinition("artists_100", "Connoisseur", "Discover 100 unique artists", "public", "DISCOVERY", 100, BadgeRarity.RARE),
        BadgeDefinition("genres_10", "Genre Hopper", "Explore different genres", "category", "DISCOVERY", 10, BadgeRarity.COMMON),
        BadgeDefinition("genres_25", "Eclectic", "Explore 25+ genres", "palette", "DISCOVERY", 25, BadgeRarity.RARE),

        // === Engagement ===
        BadgeDefinition("night_owl", "Night Owl", "Late-night plays (12–5 AM)", "nightlight", "ENGAGEMENT", 100, BadgeRarity.RARE),
        BadgeDefinition("early_bird", "Early Bird", "Early morning plays (5–8 AM)", "wb_sunny", "ENGAGEMENT", 100, BadgeRarity.RARE),
        BadgeDefinition("marathon", "Marathon", "Complete marathon listening sessions (3+ hours each)", "directions_run", "ENGAGEMENT", 5, BadgeRarity.EPIC),

        // === Level Milestones ===
        BadgeDefinition("level_5", "Rising Star", "Reach Level 5", "grade", "LEVEL", 5, BadgeRarity.COMMON),
        BadgeDefinition("level_10", "Double Digits", "Reach Level 10", "looks_one", "LEVEL", 10, BadgeRarity.COMMON),
        BadgeDefinition("level_25", "Quarter Century", "Reach Level 25", "military_tech", "LEVEL", 25, BadgeRarity.RARE),
        BadgeDefinition("level_50", "Halfway There", "Reach Level 50", "workspace_premium", "LEVEL", 50, BadgeRarity.EPIC),
        BadgeDefinition("level_75", "Elite Listener", "Reach Level 75", "shield", "LEVEL", 75, BadgeRarity.EPIC),
        BadgeDefinition("level_100", "The Centennial", "Reach Level 100", "emoji_events", "LEVEL", 100, BadgeRarity.MYTHIC)
    )

    data class BadgeDefinition(
        val badgeId: String,
        val name: String,
        val description: String,
        val iconName: String,
        val category: String,
        val threshold: Int,
        val rarity: BadgeRarity = BadgeRarity.COMMON
    )

    // =====================
    // Star Tiers
    // =====================

    /**
     * Multipliers for each star tier (1-5). Threshold for star N = base * STAR_MULTIPLIERS[N-1].
     * Steeper curve ensures stars feel earned:
     *   ★1 = base (unlock), ★2 = 3× (dedicated), ★3 = 8× (committed),
     *   ★4 = 20× (elite), ★5 = 50× (legendary)
     */
    val STAR_MULTIPLIERS = intArrayOf(1, 3, 8, 20, 50)

    /**
     * Beginner / introductory badges that cap at 1 star.
     * These are participation trophies for onboarding — they should not inflate the star total.
     * In the UI they are shown as "Unlocked" rather than with a 5-star display.
     */
    val BEGINNER_BADGES = setOf("first_play", "time_1h")

    const val MAX_STARS = 5

    /**
     * Get the threshold for a specific star tier.
     * @param baseThreshold The badge's base threshold (1-star unlock).
     * @param star Star number 1-5.
     */
    fun getStarThreshold(baseThreshold: Int, star: Int): Int {
        val index = (star - 1).coerceIn(0, STAR_MULTIPLIERS.lastIndex)
        return baseThreshold * STAR_MULTIPLIERS[index]
    }

    /**
     * Compute how many stars a badge has earned given raw progress and base threshold.
     * @return Star count 0-5.
     */
    fun computeStars(rawProgress: Int, baseThreshold: Int): Int {
        var stars = 0
        for (i in STAR_MULTIPLIERS.indices) {
            if (rawProgress >= baseThreshold * STAR_MULTIPLIERS[i]) {
                stars = i + 1
            } else {
                break
            }
        }
        return stars.coerceIn(0, MAX_STARS)
    }

    /**
     * Get the threshold for the next star, or the max star threshold if already maxed.
     */
    fun getNextStarThreshold(baseThreshold: Int, currentStars: Int): Int {
        val nextStar = (currentStars + 1).coerceAtMost(MAX_STARS)
        return getStarThreshold(baseThreshold, nextStar)
    }

    /**
     * Get star-tier description suffix (e.g., "★★★☆☆").
     */
    fun starLabel(stars: Int): String {
        return "★".repeat(stars) + "☆".repeat((MAX_STARS - stars).coerceAtLeast(0))
    }

    data class LevelState(
        val level: Int,
        val totalXp: Long,
        val xpForCurrentLevel: Long,
        val xpForNextLevel: Long
    )
    
    data class StreakInfo(
        val currentStreak: Int,
        val longestStreak: Int
    )
}
