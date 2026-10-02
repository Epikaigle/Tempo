package me.avinas.tempo.data.repository

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.dao.GamificationDao
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.UserLevel
import me.avinas.tempo.data.stats.GamificationEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the gamification system.
 *
 * Manages XP computation, level tracking, and badge evaluation.
 * XP is always deterministic: recomputed from listening history, never incremental.
 */
@Singleton
class GamificationRepository
    @Inject
    constructor(
        private val gamificationDao: GamificationDao,
        private val appDatabase: AppDatabase,
    ) {
        companion object {
            private const val TAG = "GamificationRepo"
        }

        // Level & XP

        fun observeUserLevel(): Flow<UserLevel?> = gamificationDao.observeUserLevel()

        suspend fun getUserLevel(): UserLevel = gamificationDao.getUserLevel() ?: UserLevel()

        /**
         * Permanently delete challenges older than [cutoffDate] (YYYY-MM-DD) while preserving XP.
         *
         * recomputeXpAndLevel() derives total XP by summing xpReward across *all* completed
         * challenge rows, so a naive DELETE would silently subtract that XP on the next recompute.
         * To keep total XP invariant, the XP of the completed rows being pruned is first added to
         * user_level.banked_challenge_xp, which recomputeXpAndLevel adds back every time. The
         * bank + recompute + delete run inside one transaction so XP can never be lost.
         */
        suspend fun pruneChallengesPreservingXp(cutoffDate: String): Int =
            try {
                var deleted = 0
                appDatabase.withTransaction {
                    val banked = gamificationDao.sumCompletedXpBefore(cutoffDate)
                    if (banked > 0) gamificationDao.addBankedChallengeXp(banked)
                    // Recompute AFTER banking so total_xp includes the banked offset before the
                    // source rows are removed.
                    recomputeXpAndLevel()
                    deleted = gamificationDao.deleteChallengesOlderThan(cutoffDate)
                }
                if (deleted > 0) Log.i(TAG, "Pruned $deleted challenges older than $cutoffDate (XP banked & preserved)")
                deleted
            } catch (e: Exception) {
                Log.w(TAG, "pruneChallengesPreservingXp failed", e)
                0
            }

        /**
         * Recompute XP and level from listening history and update the database.
         * Returns the previous level for level-up detection.
         */
        suspend fun recomputeXpAndLevel(): LevelUpResult {
            val previousLevel = gamificationDao.getUserLevel()
            val previousLevelNum = previousLevel?.currentLevel ?: 0

            // Calculate XP deterministically from listening events
            val fullPlays = gamificationDao.getFullPlayCount()
            val partialPlays = gamificationDao.getPartialPlayCount()
            val uniqueArtists = gamificationDao.getUniqueArtistCount()
            var totalXp = GamificationEngine.calculateXp(fullPlays, partialPlays, uniqueArtists)

            // Add bonus XP from completed challenges
            // Since challenges aren't bound to specific play events, we query all completed challenges
            // Note: For a true deterministic calc, we might need a separate table or query, but
            // counting completed challenges in daily_challenges is sufficient.
            val completedChallenges = gamificationDao.getAllCompletedChallenges()
            val challengeBonusXp = completedChallenges.sumOf { it.xpReward.toLong() }
            totalXp += challengeBonusXp

            // Add XP banked from pruned challenges. Pruning deletes old challenge rows, so their
            // XP would otherwise vanish on this recompute; the bank keeps total XP invariant.
            val bankedChallengeXp = previousLevel?.bankedChallengeXp ?: 0L
            totalXp += bankedChallengeXp

            // Add bonus XP from earned badge stars. Badge stars are derived deterministically
            // from listening data (recomputed in evaluateAllBadges), and rarity is a static
            // property of the badge definition — so this contribution stays fully recomputable.
            val badgeBonusXp =
                gamificationDao.getAllBadges().sumOf { badge ->
                    GamificationEngine.getBadgeXpContribution(badge.badgeId, badge.stars)
                }
            totalXp += badgeBonusXp

            // Compute level from XP
            val levelState = GamificationEngine.computeLevelState(totalXp)

            // Compute streak
            val dates = gamificationDao.getDistinctListeningDates()
            val streakInfo = GamificationEngine.calculateStreak(dates)

            val updatedLevel =
                UserLevel(
                    id = 1,
                    totalXp = totalXp,
                    currentLevel = levelState.level,
                    xpForCurrentLevel = levelState.xpForCurrentLevel,
                    xpForNextLevel = levelState.xpForNextLevel,
                    lastXpAwardedAt = System.currentTimeMillis(),
                    currentStreak = streakInfo.currentStreak,
                    longestStreak = maxOf(streakInfo.longestStreak, previousLevel?.longestStreak ?: 0),
                    lastStreakDate = dates.firstOrNull() ?: "",
                    bankedChallengeXp = bankedChallengeXp,
                )

            gamificationDao.upsertUserLevel(updatedLevel)

            Log.d(
                TAG,
                "XP recomputed: $totalXp XP, Level ${levelState.level} " +
                    "(full=$fullPlays, partial=$partialPlays, challenges=$challengeBonusXp, " +
                    "badges=$badgeBonusXp, streak=${streakInfo.currentStreak})",
            )

            return LevelUpResult(
                previousLevel = previousLevelNum,
                newLevel = levelState.level,
                totalXp = totalXp,
                didLevelUp = levelState.level > previousLevelNum,
            )
        }

        // Badges

        fun observeAllBadges(): Flow<List<Badge>> = gamificationDao.observeAllBadges()

        fun observeEarnedBadges(): Flow<List<Badge>> = gamificationDao.observeEarnedBadges()

        fun observeRecentBadges(limit: Int = 3): Flow<List<Badge>> = gamificationDao.observeRecentBadges(limit)

        suspend fun getEarnedBadgeCount(): Int = gamificationDao.getEarnedBadgeCount()

        /** One-shot snapshot of all badges — used by InsightCardGenerator (not a Flow). */
        suspend fun getAllBadgesSnapshot(): List<Badge> = gamificationDao.getAllBadges()

        suspend fun getUniqueArtistCount(): Int = gamificationDao.getUniqueArtistCount()

        fun observeUnacknowledgedBadges(): Flow<List<Badge>> = gamificationDao.observeUnacknowledgedBadges()

        suspend fun markBadgesAsAcknowledged(badgeIds: List<String>) = gamificationDao.markBadgesAsAcknowledged(badgeIds)

        suspend fun getNextEarnableBadge(): Badge? {
            val allBadges = gamificationDao.getAllBadges()
            return allBadges
                .filter { !it.isMaxed } // Include locked badges and earned badges not yet at 5 stars
                .maxByOrNull { it.progressFraction }
        }

        /**
         * Evaluates badge completion criteria across all star tiers (★1 to ★5).
         * Returns newly earned badge IDs.
         */
        suspend fun evaluateAllBadges(): List<String> {
            val userLevel = gamificationDao.getUserLevel() ?: UserLevel()
            val totalPlays = gamificationDao.getTotalPlayCount()
            val totalTimeMs = gamificationDao.getTotalListeningTimeMs()
            val totalTimeHours = (totalTimeMs / 3_600_000).toInt()
            val uniqueArtists = gamificationDao.getUniqueArtistCount()
            val uniqueGenres = gamificationDao.getUniqueGenreCount()
            val nightPlays = gamificationDao.getPlayCountBetweenHours(0, 5)
            val morningPlays = gamificationDao.getPlayCountBetweenHours(5, 8)
            val marathonSessions = gamificationDao.getMarathonSessionCount()

            val existingBadgesMap = gamificationDao.getAllBadges().associateBy { it.badgeId }
            val updatedBadges = ArrayList<Badge>(GamificationEngine.ALL_BADGE_DEFINITIONS.size)
            val newlyEarned = mutableListOf<String>()

            for (def in GamificationEngine.ALL_BADGE_DEFINITIONS) {
                val existing = existingBadgesMap[def.badgeId]

                // Get raw (uncapped) progress for this badge
                val rawProgress =
                    when {
                        def.category == "MILESTONE" -> totalPlays
                        def.category == "TIME" -> totalTimeHours
                        def.category == "STREAK" -> userLevel.longestStreak
                        def.badgeId.startsWith("artists_") -> uniqueArtists
                        def.badgeId.startsWith("genres_") -> uniqueGenres
                        def.badgeId == "night_owl" -> nightPlays
                        def.badgeId == "early_bird" -> morningPlays
                        def.badgeId == "marathon" -> marathonSessions
                        def.category == "LEVEL" -> userLevel.currentLevel
                        else -> 0
                    }

                // Compute star tier
                // Beginner badges cap at 1 star (labeled "Unlocked" in UI);
                // computeStars() enforces that cap internally.
                val currentStars = GamificationEngine.computeStars(rawProgress, def)
                val previousStars = existing?.stars ?: 0
                val isNowEarned = currentStars >= 1
                val wasAlreadyEarned = existing?.isEarned == true

                // Progress toward next star (or show maxed state)
                val nextStarThreshold = GamificationEngine.getNextStarThreshold(def, currentStars)

                // For the progress bar: show progress within current tier toward next star
                val displayProgress: Int
                val displayMaxProgress: Int
                if (currentStars >= GamificationEngine.MAX_STARS) {
                    // Maxed out — show full bar
                    displayProgress = nextStarThreshold
                    displayMaxProgress = nextStarThreshold
                } else {
                    // Show progress toward next star
                    displayProgress = rawProgress.coerceAtMost(nextStarThreshold)
                    displayMaxProgress = nextStarThreshold
                }

                val badge =
                    Badge(
                        id = existing?.id ?: 0,
                        badgeId = def.badgeId,
                        name = def.name,
                        description = def.description,
                        iconName = def.iconName,
                        category = def.category,
                        earnedAt =
                            when {
                                isNowEarned && wasAlreadyEarned -> existing!!.earnedAt
                                isNowEarned -> System.currentTimeMillis()
                                else -> 0
                            },
                        progress = displayProgress,
                        maxProgress = displayMaxProgress,
                        isEarned = isNowEarned,
                        stars = currentStars,
                        isAcknowledged =
                            if ((isNowEarned && !wasAlreadyEarned) ||
                                (currentStars > previousStars)
                            ) {
                                false
                            } else {
                                existing?.isAcknowledged ?: false
                            },
                    )

                updatedBadges.add(badge)

                if (isNowEarned && !wasAlreadyEarned) {
                    newlyEarned.add(def.badgeId)
                    Log.i(TAG, "🏆 Badge earned: ${def.name} (★$currentStars)")
                } else if (currentStars > previousStars && wasAlreadyEarned) {
                    Log.i(TAG, "⭐ Badge upgraded: ${def.name} → ★$currentStars")
                }
            }

            if (updatedBadges.isNotEmpty()) {
                gamificationDao.upsertBadges(updatedBadges)
            }

            Log.d(TAG, "Badge evaluation complete: ${newlyEarned.size} new badges earned")
            return newlyEarned
        }

        /**
         * Full refresh: recompute XP + evaluate all badges.
         */
        suspend fun fullRefresh(): GamificationRefreshResult =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val levelUpResult = recomputeXpAndLevel()
                val newBadges = evaluateAllBadges()
                GamificationRefreshResult(
                    levelUpResult = levelUpResult,
                    newlyEarnedBadgeIds = newBadges,
                )
            }
    }

data class LevelUpResult(
    val previousLevel: Int,
    val newLevel: Int,
    val totalXp: Long,
    val didLevelUp: Boolean,
)

data class GamificationRefreshResult(
    val levelUpResult: LevelUpResult,
    val newlyEarnedBadgeIds: List<String>,
)
