package me.avinas.tempo.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import me.avinas.tempo.data.local.entities.Badge
import me.avinas.tempo.data.local.entities.DailyChallenge
import me.avinas.tempo.data.local.entities.UserLevel

@Dao
interface GamificationDao {
    // UserLevel
    @Query("SELECT * FROM user_level WHERE id = 1")
    suspend fun getUserLevel(): UserLevel?

    @Query("SELECT * FROM user_level WHERE id = 1")
    fun observeUserLevel(): Flow<UserLevel?>

    @Upsert
    suspend fun upsertUserLevel(userLevel: UserLevel)

    // Badges
    @Query("SELECT * FROM badges ORDER BY earned_at DESC")
    suspend fun getAllBadges(): List<Badge>

    @Query("SELECT * FROM badges WHERE is_earned = 1 ORDER BY earned_at DESC")
    suspend fun getEarnedBadges(): List<Badge>

    @Query("SELECT * FROM badges ORDER BY is_earned DESC, stars DESC, category ASC, max_progress ASC")
    fun observeAllBadges(): Flow<List<Badge>>

    @Query("SELECT * FROM badges WHERE is_earned = 1 ORDER BY earned_at DESC")
    fun observeEarnedBadges(): Flow<List<Badge>>

    @Query("SELECT * FROM badges WHERE is_earned = 1 AND is_acknowledged = 0 ORDER BY earned_at DESC")
    fun observeUnacknowledgedBadges(): Flow<List<Badge>>

    @Query("UPDATE badges SET is_acknowledged = 1 WHERE badge_id IN (:badgeIds)")
    suspend fun markBadgesAsAcknowledged(badgeIds: List<String>)

    @Query("SELECT * FROM badges WHERE is_earned = 1 ORDER BY earned_at DESC LIMIT :limit")
    fun observeRecentBadges(limit: Int = 3): Flow<List<Badge>>

    @Query("SELECT * FROM badges WHERE badge_id = :badgeId")
    suspend fun getBadgeById(badgeId: String): Badge?

    @Upsert
    suspend fun upsertBadge(badge: Badge)

    @Upsert
    suspend fun upsertBadges(badges: List<Badge>)

    @Query("SELECT COUNT(*) FROM badges WHERE is_earned = 1")
    suspend fun getEarnedBadgeCount(): Int

    @Query("SELECT COUNT(*) FROM badges")
    suspend fun getTotalBadgeCount(): Int

    // XP Calculation helpers (queries against listening_events)

    /**
     * Count XP-eligible full plays (≥80% completion).
     *
     * Anti-gaming rules applied:
     *  1. Muted plays (volume_level = 0) are excluded — NULL is treated as audible (legacy rows).
     *  2. The same track is capped at MAX_XP_PLAYS_PER_TRACK_PER_DAY full plays per calendar day.
     *     Additional plays of the same song are still recorded but contribute 0 XP.
     */
    @Query(
        """
        SELECT COALESCE(SUM(capped), 0) FROM (
            SELECT MIN(COUNT(*), 3) AS capped
            FROM listening_events
            WHERE completionPercentage >= 80
              AND (volume_level IS NULL OR volume_level > 0)
            GROUP BY track_id, date(timestamp / 1000, 'unixepoch', 'localtime')
        )
    """,
    )
    suspend fun getFullPlayCount(): Int

    /**
     * Count XP-eligible partial plays (30–79% completion).
     *
     * Anti-gaming rules applied:
     *  1. Muted plays (volume_level = 0) are excluded.
     *  2. Same track capped at 3 partial plays per calendar day for XP purposes.
     */
    @Query(
        """
        SELECT COALESCE(SUM(capped), 0) FROM (
            SELECT MIN(COUNT(*), 3) AS capped
            FROM listening_events
            WHERE completionPercentage >= 30 AND completionPercentage < 80
              AND (volume_level IS NULL OR volume_level > 0)
            GROUP BY track_id, date(timestamp / 1000, 'unixepoch', 'localtime')
        )
    """,
    )
    suspend fun getPartialPlayCount(): Int

    /** Total listening time in milliseconds */
    @Query("SELECT COALESCE(SUM(playDuration), 0) FROM listening_events")
    suspend fun getTotalListeningTimeMs(): Long

    /** Total play count */
    @Query("SELECT COUNT(*) FROM listening_events")
    suspend fun getTotalPlayCount(): Int

    /**
     * Count unique artists across all listening history.
     *
     * Attribution goes through the track_artists junction, matching
     * StatsDao.getTopArtistsByPlayCount. Counting the raw tracks.artist display string
     * instead treated a duet ("A, B") as one extra, distinct artist and never credited
     * the individual collaborators.
     *
     * Feeds XP, level, badges and the profile's artist total, so correcting it re-bases
     * those numbers for existing users on the next recompute.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT ta.artist_id)
        FROM listening_events le
        INNER JOIN track_artists ta ON ta.track_id = le.track_id
        WHERE (le.volume_level IS NULL OR le.volume_level > 0)
    """,
    )
    suspend fun getUniqueArtistCount(): Int

    /** Count unique genres listened to */
    @Query(
        """
        SELECT COUNT(DISTINCT em.genres) 
        FROM listening_events le 
        JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON em.track_id = t.id
        WHERE em.genres IS NOT NULL AND em.genres != ''
    """,
    )
    suspend fun getUniqueGenreCount(): Int

    /** Count plays between specific hours (for Night Owl / Early Bird) using local time */
    @Query(
        """
        SELECT COUNT(*) FROM listening_events 
        WHERE CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) >= :startHour 
        AND CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) < :endHour
    """,
    )
    suspend fun getPlayCountBetweenHours(
        startHour: Int,
        endHour: Int,
    ): Int

    /** Get all distinct listening dates for streak calculation */
    @Query(
        """
        SELECT DISTINCT date(timestamp / 1000, 'unixepoch', 'localtime') as listen_date 
        FROM listening_events 
        ORDER BY listen_date DESC
    """,
    )
    suspend fun getDistinctListeningDates(): List<String>

    /** Get longest listening session duration in milliseconds */
    @Query(
        """
        SELECT COALESCE(MAX(session_total), 0) FROM (
            SELECT session_id, SUM(playDuration) as session_total 
            FROM listening_events 
            WHERE session_id IS NOT NULL 
            GROUP BY session_id
        )
    """,
    )
    suspend fun getLongestSessionMs(): Long

    /**
     * Count distinct listening sessions that lasted 3+ hours (a "marathon").
     * A session is grouped by session_id; its total play duration must reach
     * 10,800,000 ms (3 hours). Used by the Marathon badge.
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT session_id
            FROM listening_events
            WHERE session_id IS NOT NULL
            GROUP BY session_id
            HAVING SUM(playDuration) >= 10800000
        )
    """,
    )
    suspend fun getMarathonSessionCount(): Int

    // Daily Challenges
    @Query("SELECT * FROM daily_challenges WHERE date = :date ORDER BY difficulty ASC")
    fun observeChallengesForDate(date: String): Flow<List<DailyChallenge>>

    @Query("SELECT * FROM daily_challenges WHERE date = :date ORDER BY difficulty ASC")
    suspend fun getChallengesForDate(date: String): List<DailyChallenge>

    @Upsert
    suspend fun upsertChallenge(challenge: DailyChallenge)

    @Upsert
    suspend fun upsertChallenges(challenges: List<DailyChallenge>)

    @Query("SELECT COUNT(*) FROM daily_challenges WHERE date = :date AND is_completed = 1")
    suspend fun getCompletedChallengeCount(date: String): Int

    @Query("SELECT * FROM daily_challenges WHERE is_completed = 1")
    suspend fun getAllCompletedChallenges(): List<DailyChallenge>

    // Challenge Progress Trackers (since start of day)

    @Query(
        """
                SELECT COUNT(*) FROM listening_events
                WHERE timestamp >= :startOfDayMs
                    AND (volume_level IS NULL OR volume_level > 0)
        """,
    )
    suspend fun getTodayPlayCount(startOfDayMs: Long): Int

    /**
     * Count distinct artists heard today ("Broad Horizons" / variety_artists challenge).
     *
     * Attribution goes through the canonical track_artists junction, so a collaboration
     * (e.g. "Arijit Singh, Shreya Ghoshal") counts as two artists rather than one raw string.
     * All roles count, matching how StatsDao.getTopArtistsByPlayCount ranks artists.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT ta.artist_id)
        FROM listening_events le
        INNER JOIN track_artists ta ON ta.track_id = le.track_id
        WHERE le.timestamp >= :startOfDayMs
          AND (le.volume_level IS NULL OR le.volume_level > 0)
    """,
    )
    suspend fun getTodayUniqueArtists(startOfDayMs: Long): Int

    @Query(
        """
        SELECT COALESCE(SUM(playDuration), 0) FROM listening_events
        WHERE timestamp >= :startOfDayMs
          AND (volume_level IS NULL OR volume_level > 0)
    """,
    )
    suspend fun getTodayListeningTimeMs(startOfDayMs: Long): Long

    /**
     * Count distinct individual genres today.
     * Genres are stored as '|||'-delimited strings (e.g. "rock|||pop|||indie").
     * This extracts the primary genre (first segment) for each track and counts distinct values,
     * giving a meaningful "different genres heard" count rather than counting full combo strings.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT LOWER(TRIM(
            CASE WHEN instr(em.genres, '|||') > 0
                 THEN substr(em.genres, 1, instr(em.genres, '|||') - 1)
                 ELSE em.genres
            END)))
        FROM listening_events le
        JOIN tracks t ON le.track_id = t.id
        LEFT JOIN enriched_metadata em ON em.track_id = t.id
        WHERE le.timestamp >= :startOfDayMs
          AND (le.volume_level IS NULL OR le.volume_level > 0)
          AND em.genres IS NOT NULL AND em.genres != ''
    """,
    )
    suspend fun getTodayUniqueGenres(startOfDayMs: Long): Int

    /**
     * Count artists heard today for the first time ever (never heard before today).
     * Used by the "Talent Scout" / discovery_artists challenge.
     *
     * Attribution goes through track_artists, so an artist who only appears on a
     * collaboration today still counts as newly discovered.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT ta.artist_id)
        FROM listening_events le
        INNER JOIN track_artists ta ON ta.track_id = le.track_id
        WHERE le.timestamp >= :startOfDayMs
          AND (le.volume_level IS NULL OR le.volume_level > 0)
          AND ta.artist_id NOT IN (
              SELECT DISTINCT ta2.artist_id
              FROM listening_events le2
              INNER JOIN track_artists ta2 ON ta2.track_id = le2.track_id
              WHERE le2.timestamp < :startOfDayMs
          )
    """,
    )
    suspend fun getTodayNewArtists(startOfDayMs: Long): Int

    /**
     * Count today's plays of tracks crediting [artistName] ("Deep Dive" / explore_artist challenge).
     *
     * [artistName] is the challenge target, sourced from StatsDao.getTopArtistsByPlayCount,
     * which resolves individual artists through the track_artists junction. This query must use
     * the same junction: matching the raw tracks.artist display string meant a duet such as
     * "Arijit Singh, Shreya Ghoshal" counted for neither artist, making the challenge
     * unachievable for anyone who listens mostly to collaborations.
     * All roles count, matching getTopArtistsByPlayCount.
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM listening_events le
        INNER JOIN track_artists ta ON ta.track_id = le.track_id
        INNER JOIN artists a ON a.id = ta.artist_id
        WHERE le.timestamp >= :startOfDayMs
          AND (le.volume_level IS NULL OR le.volume_level > 0)
          AND LOWER(TRIM(a.name)) = LOWER(TRIM(:artistName))
    """,
    )
    suspend fun getTodayPlayCountForArtist(
        startOfDayMs: Long,
        artistName: String,
    ): Int

    /**
     * Count today's plays of tracks tagged with [genre] ("Genre Focus" / explore_genre challenge).
     *
     * [genre] is the challenge target, sourced from StatsDao.getTopGenresRaw, whose `genre`
     * column is a whole '|||'-delimited combo (e.g. "Pop|||dance pop"). Matching that combo
     * verbatim against em.genres found nothing, and the target rendered as
     * "Genre Focus: Pop|||dance pop", so the challenge was unachievable. [genre] is now a
     * single genre (see ChallengeRepository), matched as one whole '|||'-delimited segment:
     * padding both sides with the delimiter and using LIKE stops "pop" matching "k-pop"
     * or "dance pop".
     *
     * The COALESCE mirrors getTopGenresRaw exactly, so a genre it sourced from tags stays
     * countable, and the two REPLACEs tolerate the legacy " jazz ||| pop " padding that
     * Converters.repairListColumnValue strips on the Kotlin side.
     */
    @Query(
        """
        SELECT COUNT(*)
        FROM listening_events le
        LEFT JOIN enriched_metadata em ON em.track_id = le.track_id
        WHERE le.timestamp >= :startOfDayMs
          AND (le.volume_level IS NULL OR le.volume_level > 0)
          AND REPLACE(
                  REPLACE(
                      '|||' || LOWER(COALESCE(NULLIF(em.genres, ''), NULLIF(em.tags, ''))) || '|||',
                      ' |||', '|||'
                  ),
                  '||| ', '|||'
              ) LIKE '%|||' || LOWER(TRIM(:genre)) || '|||%'
    """,
    )
    suspend fun getTodayPlayCountForGenre(
        startOfDayMs: Long,
        genre: String,
    ): Int

    /** Count plays today between specific hours (for Early Bird / Night Owl challenges) */
    @Query(
        """
        SELECT COUNT(*) FROM listening_events 
        WHERE timestamp >= :startOfDayMs
        AND (volume_level IS NULL OR volume_level > 0)
        AND CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) >= :startHour 
        AND CAST(strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) < :endHour
    """,
    )
    suspend fun getTodayPlayCountBetweenHours(
        startOfDayMs: Long,
        startHour: Int,
        endHour: Int,
    ): Int
}
