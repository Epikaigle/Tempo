package me.avinas.tempo.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_preferences")
data class UserPreferences(
    @PrimaryKey val id: Int = 0,
    val theme: String = "system",
    val notifications: Boolean = true,
    val spotifyLinked: Boolean = false,
    // Extended audio analysis downloads 30s preview audio for mood/energy analysis
    // Default false to save data usage - only ReccoBeats DB lookup is used by default
    val extendedAudioAnalysis: Boolean = false,
    
    // Smart Metadata: Merge "Live", "Remix", etc. with studio versions by default
    // True = Cleaner library (User request), False = Precise separation
    val mergeAlternateVersions: Boolean = true,
    
    // Content Filtering: Filter podcasts from tracking by default
    // True = Only track music, False = Track all audio content
    val filterPodcasts: Boolean = true,
    
    // Content Filtering: Filter audiobooks from tracking by default
    // True = Only track music, False = Track all audio content
    val filterAudiobooks: Boolean = true,
    
    // Onboarding: Track if user has seen the "long press" coach mark in History
    val hasSeenHistoryCoachMark: Boolean = false,
    
    // Walkthrough Flags (Passive Game)
    val hasSeenSpotlightTutorial: Boolean = false,
    val hasSeenStatsSortTutorial: Boolean = false,
    val hasSeenStatsItemClickTutorial: Boolean = false,
    
    // Spotlight Story Reminders: Track when reminders were last shown (YYYY-MM-DD format)
    val lastWeeklyReminderShown: String? = null,  // Last date weekly reminder was shown
    val lastMonthlyReminderShown: String? = null, // Last date monthly reminder was shown
    val lastYearlyReminderShown: String? = null,  // Last date yearly reminder was shown
    val lastAllTimeReminderShown: String? = null,  // Last date all-time reminder was shown (6-month milestone)
    
    // Spotify Import Feature
    val spotifyApiOnlyMode: Boolean = false,
    val spotifyImportCursor: String? = null,
    val lastSpotifyImportTimestamp: Long? = null,
    
    // Last.fm Import Feature
    val lastfmUsername: String? = null,
    val lastfmConnected: Boolean = false,
    val lastfmSyncFrequency: String = "NONE",
    
    // Smart Notification Timing
    val smartChallengeNotifHour: Int? = null,
    val smartChallengeNotifCalcTime: Long? = null,
    
    // Feature Toggles
    
    /**
     * Whether gamification features (levels, badges, XP, challenges) are enabled.
     * When false, these features are hidden from the UI and background processing is stopped.
     */
    val isGamificationEnabled: Boolean = true,
    
    /**
     * Whether music tracking is automatically paused when battery falls below the low threshold
     * (defined by BatteryUtils.CRITICAL_BATTERY_LEVEL, currently 20%).
     *
     * When true (default): tracking pauses on low battery to save power. The foreground
     * notification will clearly indicate the paused state, and tracking resumes automatically
     * once the battery recovers.
     *
     * When false: tracking continues at all battery levels (uses slightly more power on low
     * battery but ensures no listening sessions are missed).
     */
    val pauseTrackingOnLowBattery: Boolean = true,

    /**
     * Period key of the last Spotlight story the user viewed (e.g. "W2026-08-04", "M2026-08", "Y2026").
     * When this matches the current story period key, the home-screen ring shows gray (viewed).
     * When it differs, the ring shows the colored accent (new/unviewed story available).
     */
    val lastSpotlightStoryViewed: String? = null
)

