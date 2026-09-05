package me.avinas.tempo.data.preferences

import android.content.Context
import android.util.Base64

/**
 * Lightweight persistent settings for tracking rules that need to be read directly
 * by [me.avinas.tempo.service.MusicTrackingService].
 *
 * These settings intentionally live in SharedPreferences instead of the Room user
 * preferences row so changing a threshold never requires a database migration and
 * the notification-listener service can read an updated value synchronously.
 */
class TrackingRulesPreferences(context: Context) {

    enum class DurationMode {
        GLOBAL,
        NO_LIMIT,
        CUSTOM
    }

    enum class ContentOverrideType {
        MUSIC,
        VIDEO
    }

    data class ContentOverrideRule(
        val title: String,
        val artist: String,
        val type: ContentOverrideType
    )

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /** Minimum accumulated listening time required before a play is stored. */
    var minimumPlayDurationMs: Long
        get() = prefs.getLong(KEY_MIN_PLAY_DURATION_MS, DEFAULT_MIN_PLAY_DURATION_MS)
            .coerceIn(MIN_ALLOWED_PLAY_DURATION_MS, MAX_ALLOWED_PLAY_DURATION_MS)
        set(value) {
            prefs.edit()
                .putLong(
                    KEY_MIN_PLAY_DURATION_MS,
                    value.coerceIn(MIN_ALLOWED_PLAY_DURATION_MS, MAX_ALLOWED_PLAY_DURATION_MS)
                )
                .apply()
        }

    /**
     * Default maximum media duration that is still considered music.
     * `null` means no maximum.
     */
    var defaultMaxMusicDurationMs: Long?
        get() = prefs.getLong(KEY_DEFAULT_MAX_MUSIC_DURATION_MS, DEFAULT_MAX_MUSIC_DURATION_MS)
            .takeIf { it > 0L }
        set(value) {
            prefs.edit()
                .putLong(
                    KEY_DEFAULT_MAX_MUSIC_DURATION_MS,
                    value?.coerceIn(MIN_ALLOWED_MAX_MEDIA_DURATION_MS, MAX_ALLOWED_MAX_MEDIA_DURATION_MS)
                        ?: NO_LIMIT_VALUE
                )
                .apply()
        }

    /** Effective maximum duration for one app, after applying its override. */
    fun getMaxMusicDurationMs(packageName: String): Long? {
        val key = appDurationKey(packageName)
        if (!prefs.contains(key)) return defaultMaxMusicDurationMs
        return prefs.getLong(key, NO_LIMIT_VALUE).takeIf { it > 0L }
    }

    fun getAppDurationMode(packageName: String): DurationMode {
        val key = appDurationKey(packageName)
        if (!prefs.contains(key)) return DurationMode.GLOBAL
        return if (prefs.getLong(key, NO_LIMIT_VALUE) > 0L) {
            DurationMode.CUSTOM
        } else {
            DurationMode.NO_LIMIT
        }
    }

    fun getAppCustomMaxMusicDurationMs(packageName: String): Long? {
        if (getAppDurationMode(packageName) != DurationMode.CUSTOM) return null
        return prefs.getLong(appDurationKey(packageName), NO_LIMIT_VALUE).takeIf { it > 0L }
    }

    fun setAppUseGlobal(packageName: String) {
        prefs.edit().remove(appDurationKey(packageName)).apply()
    }

    fun setAppNoLimit(packageName: String) {
        prefs.edit().putLong(appDurationKey(packageName), NO_LIMIT_VALUE).apply()
    }

    fun setAppCustomMaxMusicDurationMs(packageName: String, durationMs: Long) {
        prefs.edit()
            .putLong(
                appDurationKey(packageName),
                durationMs.coerceIn(MIN_ALLOWED_MAX_MEDIA_DURATION_MS, MAX_ALLOWED_MAX_MEDIA_DURATION_MS)
            )
            .apply()
    }

    fun getContentOverrides(): List<ContentOverrideRule> {
        return prefs.getStringSet(KEY_CONTENT_OVERRIDES, emptySet())
            .orEmpty()
            .mapNotNull(::decodeRule)
            .sortedWith(
                compareBy<ContentOverrideRule> { it.type.name }
                    .thenBy { it.artist.lowercase() }
                    .thenBy { it.title.lowercase() }
            )
    }

    /**
     * Adds or replaces a manual override. Empty title means "all titles" and empty
     * artist means "all artists"; both cannot be empty at the same time.
     */
    fun putContentOverride(
        title: String,
        artist: String,
        type: ContentOverrideType
    ): Boolean {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() && cleanArtist.isBlank()) return false

        val existing = getContentOverrides().filterNot {
            normalized(it.title) == normalized(cleanTitle) &&
                normalized(it.artist) == normalized(cleanArtist)
        }
        val updated = existing + ContentOverrideRule(cleanTitle, cleanArtist, type)
        prefs.edit()
            .putStringSet(KEY_CONTENT_OVERRIDES, updated.map(::encodeRule).toSet())
            .apply()
        return true
    }

    fun removeContentOverride(rule: ContentOverrideRule) {
        val updated = getContentOverrides().filterNot {
            normalized(it.title) == normalized(rule.title) &&
                normalized(it.artist) == normalized(rule.artist) &&
                it.type == rule.type
        }
        prefs.edit()
            .putStringSet(KEY_CONTENT_OVERRIDES, updated.map(::encodeRule).toSet())
            .apply()
    }

    /**
     * Resolves the best manual override for a media item.
     * Priority: exact title+artist, title-only, then artist-only.
     */
    fun findContentOverride(title: String, artist: String): ContentOverrideType? {
        val titleNorm = normalized(title)
        val artistNorm = normalized(artist)
        if (titleNorm.isBlank() && artistNorm.isBlank()) return null

        var best: Pair<Int, ContentOverrideType>? = null
        for (rule in getContentOverrides()) {
            val ruleTitle = normalized(rule.title)
            val ruleArtist = normalized(rule.artist)

            val titleMatches = ruleTitle.isBlank() || ruleTitle == titleNorm
            val artistMatches = ruleArtist.isBlank() || ruleArtist == artistNorm
            if (!titleMatches || !artistMatches) continue

            val score = when {
                ruleTitle.isNotBlank() && ruleArtist.isNotBlank() -> 3
                ruleTitle.isNotBlank() -> 2
                ruleArtist.isNotBlank() -> 1
                else -> 0
            }
            if (best == null || score > best.first) {
                best = score to rule.type
            }
        }
        return best?.second
    }

    private fun appDurationKey(packageName: String): String =
        KEY_APP_MAX_DURATION_PREFIX + packageName.trim()

    private fun encodeRule(rule: ContentOverrideRule): String = buildString {
        append(rule.type.name)
        append('|')
        append(encode(rule.title))
        append('|')
        append(encode(rule.artist))
    }

    private fun decodeRule(raw: String): ContentOverrideRule? {
        val parts = raw.split('|', limit = 3)
        if (parts.size != 3) return null
        val type = runCatching { ContentOverrideType.valueOf(parts[0]) }.getOrNull() ?: return null
        val title = decode(parts[1]) ?: return null
        val artist = decode(parts[2]) ?: return null
        if (title.isBlank() && artist.isBlank()) return null
        return ContentOverrideRule(title, artist, type)
    }

    private fun encode(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP or Base64.URL_SAFE
    )

    private fun decode(value: String): String? = runCatching {
        String(
            Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE),
            Charsets.UTF_8
        )
    }.getOrNull()

    private fun normalized(value: String): String = value.trim().lowercase()

    companion object {
        const val DEFAULT_MIN_PLAY_DURATION_MS = 25_000L
        const val DEFAULT_MAX_MUSIC_DURATION_MS = 20 * 60 * 1000L

        private const val PREFS_NAME = "tracking_rules_preferences"
        private const val KEY_MIN_PLAY_DURATION_MS = "minimum_play_duration_ms"
        private const val KEY_DEFAULT_MAX_MUSIC_DURATION_MS = "default_max_music_duration_ms"
        private const val KEY_APP_MAX_DURATION_PREFIX = "app_max_music_duration_ms:"
        private const val KEY_CONTENT_OVERRIDES = "content_overrides"
        private const val NO_LIMIT_VALUE = 0L

        private const val MIN_ALLOWED_PLAY_DURATION_MS = 1_000L
        private const val MAX_ALLOWED_PLAY_DURATION_MS = 10 * 60 * 1000L
        private const val MIN_ALLOWED_MAX_MEDIA_DURATION_MS = 1_000L
        private const val MAX_ALLOWED_MAX_MEDIA_DURATION_MS = 24 * 60 * 60 * 1000L
    }
}
