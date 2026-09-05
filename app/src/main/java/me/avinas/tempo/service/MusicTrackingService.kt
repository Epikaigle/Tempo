package me.avinas.tempo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import me.avinas.tempo.MainActivity
import me.avinas.tempo.R
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.preferences.TrackingRulesPreferences
import me.avinas.tempo.data.preferences.TrackingRulesPreferences.ContentOverrideType
import me.avinas.tempo.data.repository.ArtistLinkingService
import me.avinas.tempo.data.repository.EnrichedMetadataRepository
import me.avinas.tempo.data.repository.ListeningRepository
import me.avinas.tempo.data.repository.RoomStatsRepository
import me.avinas.tempo.data.repository.StatsRepository
import me.avinas.tempo.data.repository.TrackRepository
import me.avinas.tempo.data.repository.TrackAliasRepository
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.data.local.dao.ManualContentMarkDao
import me.avinas.tempo.data.repository.RefreshCoordinator
import me.avinas.tempo.utils.TrackMatcher
import me.avinas.tempo.utils.TrackCandidate
import me.avinas.tempo.worker.EnrichmentWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import me.avinas.tempo.receiver.BatteryStateReceiver
import me.avinas.tempo.utils.BatteryUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * MusicTrackingService captures listening events from music apps via:
 * 1. NotificationListenerService - detects music notifications (primary)
 * 2. MediaSessionManager - tracks playback state changes (fallback/supplement)
 *
 * Handles play, pause, skip, and resume to calculate accurate listening duration.
 *
 * Note: Uses manual Hilt injection via EntryPoint because NotificationListenerService
 * is managed by the system lifecycle.
 */
class MusicTrackingService : NotificationListenerService() {

    /**
     * Hilt EntryPoint for manual dependency injection in NotificationListenerService.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MusicTrackingServiceEntryPoint {
        fun trackRepository(): TrackRepository
        fun listeningRepository(): ListeningRepository
        fun enrichedMetadataRepository(): EnrichedMetadataRepository
        fun statsRepository(): StatsRepository
        fun artistLinkingService(): ArtistLinkingService
        fun trackAliasRepository(): TrackAliasRepository
        fun userPreferencesDao(): UserPreferencesDao
        fun manualContentMarkDao(): ManualContentMarkDao
        fun appPreferenceDao(): me.avinas.tempo.data.local.dao.AppPreferenceDao
        fun refreshCoordinator(): RefreshCoordinator
    }

    companion object {
        private const val TAG = "MusicTrackingService"
        private const val CHANNEL_ID = "tempo_tracking_channel"
        private const val NOTIFICATION_ID = 1001

        /** Sent by [BatteryStateReceiver] to trigger a re-scan of active media sessions. */
        const val ACTION_BATTERY_RECOVERED = "me.avinas.tempo.ACTION_BATTERY_RECOVERED"

        /**
         * Self-heal the listener component on any process start.
         *
         * [me.avinas.tempo.worker.ServiceHealthWorker] restarts a dead listener by
         * toggling this component DISABLED → ENABLED. If the process dies in that
         * window (or the re-enable throws), the component is left DISABLED and
         * tracking stays dead until the user reinstalls — the exact failure users
         * report as "it stopped tracking and never came back".
         *
         * This runs on every app process start: if the component is stuck disabled,
         * re-enable it and ask the system to rebind the listener. Cheap (two
         * PackageManager calls), no battery impact, and it makes the kill window
         * self-recovering instead of permanent.
         */
        fun ensureComponentEnabled(context: Context) {
            try {
                val componentName = ComponentName(context, MusicTrackingService::class.java)
                val state = context.packageManager.getComponentEnabledSetting(componentName)
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    Log.w(TAG, "Listener component found DISABLED — re-enabling (self-heal)")
                    context.packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    try {
                        requestRebind(componentName)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestRebind after self-heal failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ensureComponentEnabled failed", e)
            }
        }

        // Music app package names to monitor (ONLY music apps, no video)
        private val MUSIC_APPS = setOf(
            "com.google.android.apps.youtube.music",
            "com.spotify.music",
            "com.apple.android.music",
            "com.amazon.mp3",
            "com.soundcloud.android",
            "deezer.android.app",
            "com.pandora.android",
            "com.jio.media.jiobeats",
            "com.gaana",
            "com.bsbportal.music",
            "com.hungama.myplay.activity",
            "com.samsung.android.app.music",
            "com.miui.player",
            "com.sec.android.app.music",
            "in.startv.hotstar.music",
            "com.tidal.android",
            "com.aspiro.tidal",
            "com.qobuz.music",
            "app.revanced.android.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "com.vanced.android.youtube.music",
            "com.moonvideo.android.resso",
            "com.audiomack",
            "com.mmm.trebelmusic",
            "com.maxmpz.audioplayer",
            "in.krosbits.musicolet",
            "com.kodarkooperativet.blackplayerfree",
            "com.kodarkooperativet.blackplayerex",
            "nugs.net",
            "net.nugs.multiband",
            "com.dd3boh.outertune",
            "com.zionhuang.music",
            "it.vfsfitvnm.vimusic",
            "oss.krtirtho.spotube",
            "com.shadow.blackhole",
            "com.anandnet.harmonymusic",
            "it.fast4x.rimusic",
            "com.msob7y.namida",
            "com.metrolist.music",
            "com.gokadzev.musify",
            "com.gokadzev.musify.fdroid",
            "ls.bloomee.musicplayer",
            "com.maxrave.simpmusic",
            "it.ncaferra.pixelplayerfree",
            "com.theveloper.pixelplay",
            "com.singularity.gramophone",
            "player.phonograph.plus",
            "org.oxycblt.auxio",
            "com.maloy.muzza",
            "uk.co.projectneon.echo",
            "com.shabinder.spotiflyer",
            "com.kapp.youtube.final",
            "org.schabi.newpipe",
            "org.polymorphicshade.newpipe",
            "com.rhmsoft.pulsar",
            "com.neutroncode.mp",
            "gonemad.gmmp",
            "code.name.monkey.retromusic",
            "com.piyush.music",
            "com.simplecity.amp_pro",
            "ru.stellio.player",
            "io.stellio.music",
            "com.frolo.musp",
            "com.rhmsoft.omnia",
            "ru.yandex.music",
            "com.vivi.vivimusic"
        )

        private val PODCAST_APPS = setOf(
            "com.google.android.apps.podcasts",
            "com.google.android.apps.magazines",
            "fm.player",
            "au.com.shiftyjelly.pocketcasts",
            "com.bambuna.podcastaddict",
            "com.clearchannel.iheartradio.controller",
            "app.tunein.player",
            "com.stitcher.app",
            "com.castbox.player",
            "com.overcast.app",
            "com.apple.android.podcasts",
            "com.podcastone.mobile",
            "com.wondery.wondery",
            "com.podcasts.android",
            "fm.castbox.audiobook.radio.podcast"
        )

        private val AUDIOBOOK_APPS = setOf(
            "com.audible.application",
            "com.google.android.apps.books",
            "com.audiobooks.android.audiobooks",
            "com.scribd.app.reader0",
            "com.storytel",
            "fm.libro",
            "com.libro.app",
            "com.kobo.books.ereader",
            "com.nook.app",
            "com.audiobooks.androidapp"
        )

        private val BLOCKED_APPS = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube",
            "app.revanced.android.youtube",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus",
            "in.startv.hotstar",
            "com.hotstar.android",
            "tv.twitch.android.app",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.instagram.android",
            "com.facebook.katana",
            "com.snapchat.android",
            "com.vimeo.android.videoapp",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "org.videolan.vlc",
            "com.google.android.apps.photos",
            "com.whatsapp",
            "org.telegram.messenger",
            "com.google.android.gm",
            "com.playit.videoplayer",
            "video.player.videoplayer",
            "com.inshot.videoplayer",
            "hd.videoplayer.allformat",
            "video.player.allformat.hd.player",
            "videoplayer.video.player.hd",
            "video.player.hd.videoplayer",
            "uplayer.video.player",
            "com.kmplayer",
            "com.bsplayer.bspandroid.free",
            "com.bsplayer.bspandroid",
            "com.archos.mediacenter.video",
            "com.archos.arcmedia",
            "com.samsung.android.video",
            "com.sec.android.gallery3d",
            "com.samsung.android.service.peoplestripe",
            "com.miui.videoplayer",
            "com.miui.gallery",
            "com.coloros.video",
            "com.oppo.video",
            "com.realme.video",
            "com.android.VideoPlayer",
            "com.videoplayer.vivoplayer",
            "com.oneplus.gallery",
            "com.motorola.MotGallery2",
            "com.motorola.cn.gallery",
            "com.huawei.himovie",
            "com.huawei.himovie.overseas",
            "com.sonyericsson.album",
            "com.lge.video",
            "com.lge.gallery",
            "com.htc.album",
            "com.asus.gallery",
            "com.lenovo.videoplayer",
            "com.google.android.videos",
            "com.google.android.apps.youtube.media",
            "com.google.android.apps.youtube.kids",
            "com.tcl.video",
            "com.tcl.gallery",
            "com.zte.video",
            "com.tecno.video",
            "com.infinix.video",
            "com.transsion.video",
            "com.nokia.gallery",
            "com.hmdglobal.gallery",
            "com.alcatel.video",
            "com.panasonic.video",
            "com.sharp.video",
            "com.hisense.video",
            "com.vlc.remote",
            "com.vidma.videoplayer",
            "com.litterpeng.videoplayer",
            "com.rhmsoft.playerpro",
            "com.videoplayer.hdplayer2020",
            "videoplayer.videoplayer",
            "com.amplayer.video",
            "com.kmvideoplayer.hd",
            "allformat.player.videoplayer",
            "com.xplayer.hd",
            "videoplayer.musicplayer.mp4",
            "com.ufyl.videoplayer",
            "com.lemon.videoplayer",
            "com.hd.videoplayer.master",
            "com.mytech.video.player",
            "com.mp4.hd.videoplayer",
            "com.hdvideoplayer.allformat",
            "com.easytech.videoplayer",
            "com.powerful.videoplayer",
            "com.mediaplayer.fullhd",
            "com.fullhd.video.player",
            "com.max.video.hd",
            "com.super.video.hd",
            "com.player.mediaplayer.hd",
            "com.media.masterplayer",
            "com.playtube.videoplayer",
            "com.flashplayer.videoplayer",
            "com.svplayer.hd",
            "com.evoplayer.hd",
            "com.nova.videoplayer",
            "com.jplayer.hd",
            "com.videoplayer.masterpro",
            "com.vidx.player",
            "com.stream.videoplayer",
            "com.prime.hdplayer",
            "com.alpha.videoplayer",
            "com.boom.hdplayer",
            "com.edge.videoplayer",
            "com.spark.videoplayer",
            "com.ultra.hdplayer",
            "com.pixel.videoplayer",
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.opera.browser",
            "com.opera.browser.beta",
            "com.UCMobile.intl",
            "com.brave.browser",
            "com.microsoft.edge",
            "org.torproject.torbrowser",
            "org.dolphin.browser",
            "com.sec.android.app.sbrowser",
            "com.ksmobile.cb",
            "com.qwant.browser",
            "org.chromium.webview_shell"
        )

        // Typical music duration range used only for unknown-app heuristics.
        private const val MIN_MUSIC_DURATION_MS = 30_000L
        private const val TYPICAL_SONG_MIN_MS = 60_000L
        private const val TYPICAL_SONG_MAX_MS = 10 * 60 * 1000L

        private const val EXTRA_TITLE = Notification.EXTRA_TITLE
        private const val EXTRA_TEXT = Notification.EXTRA_TEXT
        private const val EXTRA_SUB_TEXT = Notification.EXTRA_SUB_TEXT
        private const val EXTRA_INFO_TEXT = Notification.EXTRA_INFO_TEXT
        private const val EXTRA_BIG_TEXT = Notification.EXTRA_BIG_TEXT
        private const val ALBUM_ART_DIR = "album_art"
        private const val REPLAY_THRESHOLD_MS = 5 * 60 * 1000L
        private const val POLL_INTERVAL_SONG_START_MS = 3_000L
        private const val POLL_INTERVAL_SONG_MIDDLE_MS = 8_000L
        private const val POLL_INTERVAL_SONG_END_MS = 4_000L
        private const val POLL_INTERVAL_SHORT_TRACK_MS = 5_000L
        private const val POLL_INTERVAL_UNKNOWN_DURATION_MS = 6_000L
        private const val SONG_START_PHASE_MS = 30_000L
        private const val SONG_END_PHASE_MS = 30_000L
        private const val SHORT_TRACK_THRESHOLD_MS = 90_000L
        private const val SKIP_COMPLETION_THRESHOLD = 30
        private const val FULL_PLAY_COMPLETION_THRESHOLD = 80
        private const val SESSION_AUTOSAVE_INTERVAL_MS = 30_000L
        private const val SHUTDOWN_FLUSH_TIMEOUT_MS = 5_000L
        private const val TRACK_MATCH_THRESHOLD = 0.85
        private const val MAX_POSITION_JUMP_MS = 30_000L
        private const val MAX_PLAY_DURATION_MS = 3_600_000L
        private const val MAX_REASONABLE_DELTA_MS = 5 * 60 * 1000L
        private val TITLE_ARTIST_PATTERNS = listOf(
            Regex("""^(.+?)\s*[-–—]\s*(.+)$"""),
            Regex("""^(.+?)\s*\|\s*(.+)$"""),
            Regex("""^(.+?)\s+by\s+(.+)$""", RegexOption.IGNORE_CASE),
        )
        private val NUMERIC_ONLY_PATTERN = Regex("^\\d+$")
        private val YEAR_PATTERN = Regex(".*\\(\\d{4}\\).*")
        private val REMASTER_PATTERN = Regex(".*\\d{4}.*remaster.*")
    }

    private fun extractArtistFromMetadata(metadata: MediaMetadata): String {
        metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) && isLikelyArtistName(it) }
            ?.let { return it.trim() }
        metadata.getString(MediaMetadata.METADATA_KEY_WRITER)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        metadata.getString(MediaMetadata.METADATA_KEY_COMPOSER)
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title != null) {
            extractArtistFromTitle(title)?.let { return it }
        }
        val logKey = title?.takeIf { it.isNotBlank() }
        if (logKey != null && !loggedArtistExtractionFailures.containsKey(logKey)) {
            loggedArtistExtractionFailures[logKey] = true
            Log.d(TAG, "Artist extraction failed for MediaMetadata. Available keys: " +
                "ARTIST='${metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)}', " +
                "ALBUM_ARTIST='${metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)}', " +
                "DISPLAY_SUBTITLE='${metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)}', " +
                "TITLE='$title'")
        }
        return "Unknown Artist"
    }

    private fun extractArtistFromNotification(extras: android.os.Bundle, title: String): String {
        extras.getCharSequence(EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        extras.getCharSequence(EXTRA_SUB_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { if (!looksLikeAlbum(it)) return it.trim() }
        extras.getCharSequence(EXTRA_INFO_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() && !isPlaceholderArtist(it) }
            ?.let { return it.trim() }
        extras.getCharSequence(EXTRA_BIG_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { bigText ->
                val lines = bigText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                if (lines.size >= 2 && lines[0] == title) {
                    return lines[1].takeIf { !isPlaceholderArtist(it) } ?: "Unknown Artist"
                }
            }
        extractArtistFromTitle(title)?.let { return it }
        if (!loggedArtistExtractionFailures.containsKey(title)) {
            loggedArtistExtractionFailures[title] = true
            Log.d(TAG, "Artist extraction failed for notification. Available: " +
                "TEXT='${extras.getCharSequence(EXTRA_TEXT)}', " +
                "SUB_TEXT='${extras.getCharSequence(EXTRA_SUB_TEXT)}', " +
                "INFO_TEXT='${extras.getCharSequence(EXTRA_INFO_TEXT)}', " +
                "TITLE='$title'")
        }
        return "Unknown Artist"
    }

    private fun extractArtistFromTitle(title: String): String? {
        for (pattern in TITLE_ARTIST_PATTERNS) {
            val match = pattern.find(title)
            if (match != null) {
                val potentialArtist = match.groupValues[2].trim()
                if (potentialArtist.length in 1..50 &&
                    !potentialArtist.all { it.isDigit() } &&
                    !isPlaceholderArtist(potentialArtist)) {
                    return potentialArtist
                }
            }
        }
        return null
    }

    private fun cleanTitleIfNeeded(title: String): String {
        for (pattern in TITLE_ARTIST_PATTERNS) {
            val match = pattern.find(title)
            if (match != null) {
                val cleanTitle = match.groupValues[1].trim()
                if (cleanTitle.isNotBlank()) return cleanTitle
            }
        }
        return title
    }

    private fun isPlaceholderArtist(artist: String): Boolean {
        val lower = artist.lowercase()
        return lower in listOf(
            "unknown", "unknown artist", "<unknown>", "various artists",
            "various", "n/a", "na", "none", "null", "", " ",
            "artist", "track", "music", "audio", "media"
        ) || lower.startsWith("track ") || lower.matches(NUMERIC_ONLY_PATTERN)
    }

    private fun looksLikeAlbum(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("album") ||
            lower.contains("soundtrack") ||
            lower.contains("ost") ||
            lower.contains("compilation") ||
            lower.contains("collection") ||
            lower.contains("vol.") ||
            lower.contains("volume") ||
            lower.matches(YEAR_PATTERN) ||
            lower.matches(REMASTER_PATTERN)
    }

    private fun isLikelyArtistName(text: String): Boolean {
        if (text.length > 60) return false
        val lowerText = text.lowercase()
        val qualityPatterns = listOf("1080p", "720p", "480p", "4k", "hd video", "full hd")
        if (qualityPatterns.any { lowerText.contains("($it") || lowerText.contains("[$it") }) return false
        val videoPatterns = listOf(
            "official video", "official audio", "official music video",
            "lyric video", "lyrics video", "music video", "full video",
            "visualizer", "full album", "audio only"
        )
        if (videoPatterns.any { lowerText.contains(it) }) return false
        val descriptionSuffixes = listOf(
            "(official)", "(audio)", "(video)", "(lyrics)", "(visualizer)",
            "(official video)", "(official audio)", "(lyric video)",
            "(theme song)", "(full video)", "(music video)", "(trailer)"
        )
        if (descriptionSuffixes.any { lowerText.endsWith(it) }) return false
        if (lowerText.contains("anthem)") || lowerText.contains("theme)")) return false
        if (text.length > 50 && lowerText.contains(" - ") && lowerText.contains("(")) return false
        return true
    }

    private fun isLikelyAdvertisementFromNotification(
        title: String,
        artist: String,
        album: String?,
        packageName: String
    ): Boolean {
        val lowerTitle = title.lowercase()
        val lowerArtist = artist.lowercase()
        val adTitlePatterns = listOf(
            "advertisement", "sponsored", "ad break",
            "premium", "upgrade", "subscribe",
            "commercial", "promo", "promotion"
        )
        if (adTitlePatterns.any { lowerTitle.contains(it) }) return true
        if (packageName == "com.spotify.music") {
            if (lowerArtist == "spotify" || lowerArtist.contains("spotify")) return true
            if (album?.lowercase()?.contains("spotify") == true) return true
        }
        if (packageName == "com.google.android.apps.youtube.music") {
            if (lowerArtist == "youtube" || lowerArtist.contains("youtube music")) return true
        }
        val adArtists = listOf(
            "advertisement", "ad", "spotify", "youtube",
            "google", "amazon", "apple", "commercial"
        )
        return adArtists.any { lowerArtist == it || lowerArtist.startsWith("$it ") }
    }

    private lateinit var trackRepository: TrackRepository
    private lateinit var listeningRepository: ListeningRepository
    private lateinit var enrichedMetadataRepository: EnrichedMetadataRepository
    private lateinit var statsRepository: StatsRepository
    private lateinit var artistLinkingService: ArtistLinkingService
    private lateinit var userPreferencesDao: UserPreferencesDao
    private lateinit var trackAliasRepository: TrackAliasRepository
    private lateinit var manualContentMarkDao: ManualContentMarkDao
    private lateinit var appPreferenceDao: me.avinas.tempo.data.local.dao.AppPreferenceDao
    private lateinit var refreshCoordinator: RefreshCoordinator
    private lateinit var trackingRules: TrackingRulesPreferences

    @Volatile private var cachedEnabledApps: Set<String> = emptySet()
    @Volatile private var cachedBlockedApps: Set<String> = emptySet()
    @Volatile private var cachedAllKnownPackages: Set<String> = emptySet()
    @Volatile private var isAppPreferenceCacheInitialized: Boolean = false
    private var lastAppPreferenceFetch: Long = 0
    private val APP_PREFERENCE_CACHE_TTL_MS = 30_000L

    private fun minimumPlayDurationMs(): Long =
        if (::trackingRules.isInitialized) trackingRules.minimumPlayDurationMs
        else TrackingRulesPreferences.DEFAULT_MIN_PLAY_DURATION_MS

    private fun manualContentOverride(title: String, artist: String): ContentOverrideType? =
        if (::trackingRules.isInitialized) trackingRules.findContentOverride(title, artist) else null

    private fun shouldRejectByTrackingRules(
        packageName: String,
        title: String,
        artist: String,
        durationMs: Long
    ): Boolean {
        when (manualContentOverride(title, artist)) {
            ContentOverrideType.VIDEO -> {
                Log.d(TAG, "Rejecting manual video/non-music override: '$title' by '$artist'")
                return true
            }
            ContentOverrideType.MUSIC -> return false
            null -> Unit
        }

        if (durationMs > 0L && ::trackingRules.isInitialized) {
            val maxDuration = trackingRules.getMaxMusicDurationMs(packageName)
            if (maxDuration != null && durationMs > maxDuration) {
                Log.d(
                    TAG,
                    "Rejecting content over configured music limit: '$title' by '$artist' " +
                        "(${durationMs / 1000}s > ${maxDuration / 1000}s) from $packageName"
                )
                return true
            }
        }
        return false
    }

    private fun removeRejectedSession(packageName: String, title: String, artist: String) {
        val previous = playbackStates.remove(packageName) ?: return
        val sameRejectedMedia = previous.title.equals(title, ignoreCase = true) &&
            (previous.artist.equals(artist, ignoreCase = true) ||
                me.avinas.tempo.utils.ArtistParser.isUnknownArtist(previous.artist) ||
                me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist))
        previous.pause()
        if (!sameRejectedMedia) {
            saveListeningEvent(previous)
        }
    }

    private suspend fun shouldFilterContent(
        packageName: String,
        metadata: LocalMediaMetadata?,
        title: String,
        artist: String
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPreferencesFetch > PREFERENCES_CACHE_TTL_MS) {
            try {
                val prefs = userPreferencesDao.getSync()
                cachedFilterPodcasts = prefs?.filterPodcasts ?: true
                cachedFilterAudiobooks = prefs?.filterAudiobooks ?: true
                cachedSpotifyApiOnlyMode = prefs?.spotifyApiOnlyMode ?: false
                cachedMergeAlternateVersions = prefs?.mergeAlternateVersions ?: true
                lastPreferencesFetch = now
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh preferences in shouldFilterContent, using cached", e)
            }
        }

        val filterPodcasts = cachedFilterPodcasts
        val filterAudiobooks = cachedFilterAudiobooks

        if (cachedSpotifyApiOnlyMode && packageName == "com.spotify.music") {
            Log.d(TAG, "Filtering Spotify notification (Spotify-API-Only mode enabled)")
            return true
        }

        when (manualContentOverride(title, artist)) {
            ContentOverrideType.VIDEO -> {
                Log.d(TAG, "Filtering manual video/non-music override: '$title' by '$artist'")
                return true
            }
            ContentOverrideType.MUSIC -> {
                Log.d(TAG, "Always-music override bypassed content filtering: '$title' by '$artist'")
                return false
            }
            null -> Unit
        }

        if (filterPodcasts && packageName in PODCAST_APPS) return true
        if (filterAudiobooks && packageName in AUDIOBOOK_APPS) return true
        if (metadata != null) {
            if (filterPodcasts && metadata.isPodcast()) return true
            if (filterAudiobooks && metadata.isAudiobook()) return true
        }
        val manualMark = manualContentMarkDao.findMatchingMark(title, artist)
        if (manualMark != null) {
            val shouldFilter = when (manualMark.contentType) {
                "PODCAST" -> filterPodcasts
                "AUDIOBOOK" -> filterAudiobooks
                else -> false
            }
            if (shouldFilter) return true
        }
        return false
    }

    private lateinit var trackingManager: MusicTrackingManager
    private lateinit var sessionPersistence: SessionPersistence
    private lateinit var durationEstimator: SmartDurationEstimator
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaSessionManager: MediaSessionManager? = null
    private val activeControllers = java.util.concurrent.ConcurrentHashMap<String, MediaController>()
    private val playbackStates = java.util.concurrent.ConcurrentHashMap<String, PlaybackSession>()
    private val sessionOperationLock = Any()
    @Volatile private var isListenerConnected = false
    private val connectionLock = Any()
    private var autoSaveJob: Job? = null
    private var heartbeatJob: Job? = null
    private val serviceStartTime = AtomicLong(0)
    private val recentPlaysCache = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val durationEstimateCache = android.util.LruCache<String, Long>(512)
    @Volatile private var cachedMergeAlternateVersions: Boolean = true
    @Volatile private var cachedFilterPodcasts: Boolean = true
    @Volatile private var cachedFilterAudiobooks: Boolean = true
    @Volatile private var cachedSpotifyApiOnlyMode: Boolean = false
    private var lastPreferencesFetch: Long = 0
    private val PREFERENCES_CACHE_TTL_MS = 60_000L
    private var lastNotificationUpdate = 0L
    private var lastNotificationContent = ""
    private val localMetadataCache = android.util.LruCache<Long, LocalMediaMetadata>(64)
    private val loggedArtistExtractionFailures = object : LinkedHashMap<String, Boolean>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 100
    }
    private val loggedAdvertisementSkips = object : LinkedHashMap<String, Boolean>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > 50
    }
    private var lastTrackRestartLog: Pair<String, Long>? = null
    private val TRACK_RESTART_LOG_DEBOUNCE_MS = 5_000L
    private var lastSessionChangeLog: Pair<Int, Long>? = null
    private val SESSION_CHANGE_LOG_DEBOUNCE_MS = 2_000L

    data class PlaybackSession(
        val packageName: String,
        var title: String,
        var artist: String,
        var album: String?,
        var trackId: Long? = null,
        var startTimestamp: Long = System.currentTimeMillis(),
        var accumulatedPositionMs: Long = 0,
        var lastRecordedPosition: Long = 0,
        var lastPositionUpdateTime: Long = System.currentTimeMillis(),
        var isPlaying: Boolean = false,
        var estimatedDurationMs: Long? = null,
        var pauseCount: Int = 0,
        var lastPauseTimestamp: Long? = null,
        var totalPauseDurationMs: Long = 0,
        var seekCount: Int = 0,
        var lastSeekTimestamp: Long? = null,
        var positionUpdatesCount: Int = 0,
        val sessionId: String = java.util.UUID.randomUUID().toString().take(8),
        var playbackStartPosition: Long = 0,
        var lastKnownPosition: Long = 0,
        var delayedMetadataRetryScheduled: Boolean = false,
        var isLikelyMusic: Boolean = true,
        var wasInterrupted: Boolean = false,
        var hasReceivedInitialPosition: Boolean = false
    ) {
        fun calculateCurrentPlayDuration(): Long = accumulatedPositionMs.coerceAtMost(MAX_PLAY_DURATION_MS)

        fun updatePosition(newPosition: Long, isCurrentlyPlaying: Boolean) {
            val now = System.currentTimeMillis()
            positionUpdatesCount++
            if (newPosition < 0) return
            if (!hasReceivedInitialPosition) {
                lastRecordedPosition = newPosition
                lastKnownPosition = newPosition
                lastPositionUpdateTime = now
                isPlaying = isCurrentlyPlaying
                hasReceivedInitialPosition = true
                return
            }
            estimatedDurationMs?.let { estimated ->
                if (newPosition > estimated * 1.5) {
                    Log.w("PlaybackSession", "Position $newPosition exceeds duration $estimated by 50%, capping")
                }
            }
            if (isCurrentlyPlaying && newPosition > lastRecordedPosition) {
                val delta = newPosition - lastRecordedPosition
                val timeElapsed = now - lastPositionUpdateTime
                val maxReasonableDelta = timeElapsed + 2000
                if (delta <= maxReasonableDelta) {
                    val safeDelta = delta.coerceAtMost(MAX_REASONABLE_DELTA_MS)
                    accumulatedPositionMs += safeDelta
                    estimatedDurationMs?.let { estimated ->
                        val maxAccumulation = estimated * 3
                        if (accumulatedPositionMs > maxAccumulation && estimated > 60_000) {
                            accumulatedPositionMs = maxAccumulation
                        }
                    }
                } else if (delta < MAX_POSITION_JUMP_MS) {
                    seekCount++
                    lastSeekTimestamp = now
                }
            } else if (newPosition < lastRecordedPosition) {
                val delta = lastRecordedPosition - newPosition
                if (newPosition >= 5000 && delta > 100) {
                    seekCount++
                    lastSeekTimestamp = now
                }
            }
            lastRecordedPosition = newPosition
            lastKnownPosition = newPosition
            lastPositionUpdateTime = now
            isPlaying = isCurrentlyPlaying
        }

        fun pause() {
            if (isPlaying) {
                isPlaying = false
                pauseCount++
                lastPauseTimestamp = System.currentTimeMillis()
            }
        }

        fun resume() {
            if (!isPlaying) {
                val now = System.currentTimeMillis()
                lastPauseTimestamp?.let { totalPauseDurationMs += now - it }
                isPlaying = true
                lastPositionUpdateTime = now
                lastPauseTimestamp = null
            }
        }

        fun wasSkipped(): Boolean {
            val duration = calculateCurrentPlayDuration()
            if (duration < 30_000) return true
            estimatedDurationMs?.let { estimated ->
                if (estimated > 0 && (duration.toFloat() / estimated) < 0.3f) return true
            }
            return false
        }

        fun calculateCompletionPercent(): Int {
            val duration = calculateCurrentPlayDuration()
            estimatedDurationMs?.let { estimated ->
                if (estimated > 0) return ((duration.toDouble() / estimated) * 100).toInt().coerceIn(0, 100)
            }
            val typicalDurationMs = 210_000L
            val estimatedCompletion = ((duration.toDouble() / typicalDurationMs) * 100).toInt()
            return when {
                duration >= 240_000 -> 100
                duration >= 180_000 -> 90
                duration >= 120_000 -> 70
                duration >= 60_000 -> 50
                else -> estimatedCompletion.coerceIn(0, 40)
            }
        }

        fun validatePlayDuration(): Boolean {
            val duration = calculateCurrentPlayDuration()
            if (duration > MAX_PLAY_DURATION_MS) return false
            estimatedDurationMs?.let { estimated ->
                if (estimated > 0 && duration > estimated * 3) return false
            }
            return true
        }

        fun validateCompletionPercentage(): Boolean = calculateCompletionPercent() in 0..100

        fun detectAnomalies(): List<String> {
            val anomalies = mutableListOf<String>()
            val duration = calculateCurrentPlayDuration()
            if (pauseCount > 0 && duration > 0 && duration / pauseCount < 5000) {
                anomalies.add("High pause frequency: $pauseCount pauses in ${duration}ms")
            }
            if (totalPauseDurationMs > duration * 5) anomalies.add("Pause time (${totalPauseDurationMs}ms) >> play time (${duration}ms)")
            if (seekCount > 10 && duration < 60000) anomalies.add("Excessive seeking: $seekCount seeks in ${duration}ms")
            if (duration > 60000 && positionUpdatesCount < 10) anomalies.add("Few position updates: $positionUpdatesCount updates for ${duration}ms")
            estimatedDurationMs?.let { estimated ->
                if (estimated > 0 && duration > estimated * 2) anomalies.add("Duration ${duration}ms is 2x+ estimated ${estimated}ms")
            }
            return anomalies
        }
    }

    private var batteryStateReceiver: BatteryStateReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MusicTrackingService created")
        serviceStartTime.set(System.currentTimeMillis())
        TrackingServiceHeartbeat.markServiceCreated(this)
        startHeartbeat()
        trackingRules = TrackingRulesPreferences(applicationContext)
        initializeDependencies()
        initializeTrackingComponents()
        recoverPersistedSessions()
        createNotificationChannel()
        startForegroundServiceWithNotification()
        initializeMediaSessionManager()
        val receiver = BatteryStateReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        batteryStateReceiver = receiver
        receiver.markReady()
        updateServiceLifecycle()
    }

    private fun initializeDependencies() {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                MusicTrackingServiceEntryPoint::class.java
            )
            trackRepository = entryPoint.trackRepository()
            listeningRepository = entryPoint.listeningRepository()
            enrichedMetadataRepository = entryPoint.enrichedMetadataRepository()
            statsRepository = entryPoint.statsRepository()
            artistLinkingService = entryPoint.artistLinkingService()
            userPreferencesDao = entryPoint.userPreferencesDao()
            trackAliasRepository = entryPoint.trackAliasRepository()
            manualContentMarkDao = entryPoint.manualContentMarkDao()
            appPreferenceDao = entryPoint.appPreferenceDao()
            refreshCoordinator = entryPoint.refreshCoordinator()
            serviceScope.launch {
                try {
                    val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
                    cachedMergeAlternateVersions = prefs.mergeAlternateVersions
                    lastPreferencesFetch = System.currentTimeMillis()
                    refreshAppPreferenceCache()
                    cachedAllKnownPackages = appPreferenceDao.getAllApps().first().map { it.packageName }.toSet()
                    isAppPreferenceCacheInitialized = true
                    withContext(Dispatchers.Main) { rescanActiveMediaSessions() }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load initial preferences, using defaults", e)
                }
            }
            watchBatteryPreference()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize dependencies", e)
            throw e
        }
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                TrackingServiceHeartbeat.markServiceAlive(applicationContext)
                delay(TrackingServiceHeartbeat.HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshAppPreferenceCache() {
        try {
            cachedEnabledApps = appPreferenceDao.getEnabledPackageNames().toSet()
            cachedBlockedApps = appPreferenceDao.getBlockedPackageNames().toSet()
            lastAppPreferenceFetch = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh app preferences, using cached values", e)
        }
    }

    private suspend fun ensureAppPreferenceCacheValid() {
        if (System.currentTimeMillis() - lastAppPreferenceFetch > APP_PREFERENCE_CACHE_TTL_MS) {
            refreshAppPreferenceCache()
        }
    }

    private fun isInEnabledApps(packageName: String): Boolean {
        if (!isAppPreferenceCacheInitialized) return packageName in MUSIC_APPS
        return packageName in cachedEnabledApps
    }

    private fun isInBlockedApps(packageName: String): Boolean {
        if (!isAppPreferenceCacheInitialized) return packageName in BLOCKED_APPS
        if (packageName in cachedBlockedApps) return true
        if (packageName in cachedEnabledApps) return false
        return packageName in BLOCKED_APPS
    }

    private fun cleanupSessionForPackage(packageName: String) {
        val session = playbackStates[packageName] ?: return
        if (session.calculateCurrentPlayDuration() >= minimumPlayDurationMs()) {
            saveListeningEvent(session)
        }
        playbackStates.remove(packageName)
        activeControllers[packageName]?.let { controller ->
            val callback = packageSpecificCallbacks[packageName] ?: sharedCallback
            try { controller.unregisterCallback(callback) } catch (_: Exception) { }
        }
        activeControllers.remove(packageName)
        packageSpecificCallbacks.remove(packageName)
        updateServiceLifecycle()
    }

    private fun initializeTrackingComponents() {
        val offlineStore = try { OfflineEventStore(applicationContext) } catch (_: Exception) { null }
        trackingManager = MusicTrackingManager(listeningRepository, serviceScope, offlineStore)
        sessionPersistence = SessionPersistence(applicationContext)
        sessionPersistence.markServiceActive()
        durationEstimator = SmartDurationEstimator()
    }

    private fun recoverPersistedSessions() {
        serviceScope.launch {
            try {
                if (sessionPersistence.wasUncleanShutdown()) {
                    val recoveredSessions = sessionPersistence.loadSessions()
                    for (state in recoveredSessions) {
                        val alreadySaved = try {
                            listeningRepository.getEventsBySessionId(state.sessionId).isNotEmpty()
                        } catch (_: Exception) { false }
                        if (alreadySaved) continue
                        val estimatedPlayTime = state.totalPlayedMs
                        val cappedPlayTime = estimatedPlayTime.coerceAtMost(MAX_PLAY_DURATION_MS)
                        if (cappedPlayTime < minimumPlayDurationMs()) continue
                        val trackId = state.trackId ?: try {
                            getOrInsertTrack(state.trackTitle, state.trackArtist, state.trackAlbum).id
                        } catch (_: Exception) { null }
                        if (trackId == null) continue
                        val finalPlayTime = if (state.estimatedDurationMs != null && state.estimatedDurationMs > 0) {
                            cappedPlayTime.coerceAtMost(state.estimatedDurationMs * 3)
                        } else cappedPlayTime
                        val event = ListeningEvent(
                            track_id = trackId,
                            timestamp = state.startTimestamp,
                            playDuration = finalPlayTime,
                            completionPercentage = durationEstimator.calculateCompletionPercent(finalPlayTime, state.estimatedDurationMs, false),
                            source = state.packageName,
                            wasSkipped = false,
                            isReplay = false,
                            estimatedDurationMs = state.estimatedDurationMs,
                            pauseCount = state.pauseCount,
                            sessionId = state.sessionId,
                            endTimestamp = System.currentTimeMillis(),
                            wasInterrupted = true
                        )
                        trackingManager.queueEvent(event, state.sessionId)
                    }
                }
                sessionPersistence.clearSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover persisted sessions", e)
            }
        }
    }

    private fun startSessionAutoSave() {
        if (autoSaveJob?.isActive == true) return
        autoSaveJob = serviceScope.launch {
            while (isActive) {
                delay(SESSION_AUTOSAVE_INTERVAL_MS)
                if (!hasActivePlayback()) break
                saveSessionsToPersistence()
            }
        }
    }

    private fun hasActivePlayback(): Boolean = playbackStates.values.any { it.isPlaying && it.isLikelyMusic }
    private var positionPollingJob: Job? = null

    private fun startPositionPolling() {
        if (positionPollingJob?.isActive == true) return
        positionPollingJob = serviceScope.launch {
            while (isActive) {
                if (!hasActivePlayback()) break
                pollMediaSessionPositions()
                delay(calculateSmartPollingInterval())
            }
        }
    }

    private fun calculateSmartPollingInterval(): Long {
        val activeSession = playbackStates.values.firstOrNull { it.isPlaying && it.isLikelyMusic }
            ?: return POLL_INTERVAL_UNKNOWN_DURATION_MS
        val estimatedDuration = activeSession.estimatedDurationMs
        val currentPosition = activeSession.lastRecordedPosition
        if (estimatedDuration == null || estimatedDuration <= 0) return POLL_INTERVAL_UNKNOWN_DURATION_MS
        if (estimatedDuration < SHORT_TRACK_THRESHOLD_MS) return POLL_INTERVAL_SHORT_TRACK_MS
        val remainingTime = estimatedDuration - currentPosition
        return when {
            currentPosition < SONG_START_PHASE_MS -> POLL_INTERVAL_SONG_START_MS
            remainingTime > 0 && remainingTime < SONG_END_PHASE_MS -> POLL_INTERVAL_SONG_END_MS
            else -> POLL_INTERVAL_SONG_MIDDLE_MS
        }
    }

    private fun updateServiceLifecycle() {
        if (hasActivePlayback()) {
            startPositionPolling()
            startSessionAutoSave()
        } else {
            positionPollingJob?.cancel(); positionPollingJob = null
            autoSaveJob?.cancel(); autoSaveJob = null
            val currentSession = playbackStates.values.firstOrNull()
            if (currentSession != null) updateTrackingNotification(currentSession.title, currentSession.artist)
            else updateTrackingNotification(null, null)
        }
    }

    private fun pollMediaSessionPositions() {
        val controllerSnapshot = synchronized(activeControllers) { activeControllers.toList() }
        controllerSnapshot.forEach { (packageName, controller) ->
            try {
                val playbackState = controller.playbackState ?: return@forEach
                val session = playbackStates[packageName] ?: return@forEach
                session.updatePosition(
                    playbackState.position,
                    playbackState.state == PlaybackState.STATE_PLAYING
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error polling position for $packageName", e)
            }
        }
    }

    private suspend fun saveSessionsToPersistence() {
        try {
            val currentSessions = playbackStates.toMap().map { (_, session) ->
                SessionState(
                    sessionId = session.sessionId,
                    packageName = session.packageName,
                    trackId = session.trackId,
                    trackTitle = session.title,
                    trackArtist = session.artist,
                    trackAlbum = session.album,
                    startTimestamp = session.startTimestamp,
                    lastResumeTimestamp = session.lastPositionUpdateTime,
                    totalPlayedMs = session.accumulatedPositionMs,
                    isPlaying = session.isPlaying,
                    pauseCount = session.pauseCount,
                    estimatedDurationMs = session.estimatedDurationMs
                )
            }.associateBy { it.sessionId }
            if (currentSessions.isNotEmpty()) sessionPersistence.saveSessions(currentSessions)
            else sessionPersistence.clearSessions()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-save sessions", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceWithNotification()
        if (intent?.action == ACTION_BATTERY_RECOVERED) {
            BatteryUtils.invalidateCache()
            serviceScope.launch {
                withContext(Dispatchers.Main) {
                    try {
                        activeNotifications?.forEach { sbn -> if (isMusicNotification(sbn)) processNotificationPosted(sbn) }
                    } catch (_: Exception) { }
                    rescanActiveMediaSessions()
                    updateServiceLifecycle()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onListenerConnected() {
        super.onListenerConnected()
        synchronized(connectionLock) {
            if (isListenerConnected) return
            isListenerConnected = true
        }
        TrackingServiceHeartbeat.markListenerConnected(this)
        serviceScope.launch {
            delay(500)
            withContext(Dispatchers.Main) {
                try {
                    activeNotifications?.forEach { sbn -> if (isMusicNotification(sbn)) processNotificationPosted(sbn) }
                } catch (_: Exception) { }
                rescanActiveMediaSessions()
                updateServiceLifecycle()
            }
        }
    }

    override fun onListenerDisconnected() {
        synchronized(connectionLock) { isListenerConnected = false }
        TrackingServiceHeartbeat.markListenerDisconnected(this)
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, MusicTrackingService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isMusicNotification(sbn)) return
        TrackingServiceHeartbeat.markNotificationCallback(this)
        processNotificationPosted(sbn)
        updateServiceLifecycle()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isMusicNotification(sbn)) return
        TrackingServiceHeartbeat.markNotificationCallback(this)
        processNotificationRemoved(sbn)
        updateServiceLifecycle()
    }

    private fun isMusicNotification(sbn: StatusBarNotification): Boolean {
        val packageName = sbn.packageName
        if (isInBlockedApps(packageName)) return false
        if (isInEnabledApps(packageName)) return true
        val notification = sbn.notification
        val category = notification.category
        if (category == Notification.CATEGORY_TRANSPORT || category == Notification.CATEGORY_SERVICE) {
            val extras = notification.extras
            if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) {
                val lowerPkg = packageName.lowercase()
                if (lowerPkg.contains("video") || lowerPkg.contains("movie") || lowerPkg.contains("tv") || lowerPkg.contains("stream")) return false
                return true
            }
        }
        return false
    }

    private fun processNotificationPosted(sbn: StatusBarNotification) {
        if (shouldPauseForBattery()) {
            val session = playbackStates.remove(sbn.packageName)
            if (session != null) { session.pause(); saveListeningEvent(session) }
            updateTrackingNotification(null, null)
            return
        }
        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras
        val rawTitle = extras.getCharSequence(EXTRA_TITLE)?.toString()?.trim()
        if (rawTitle.isNullOrBlank()) return
        val artist = extractArtistFromNotification(extras, rawTitle)
        val title = if (artist != "Unknown Artist" && artist != extractArtistFromTitle(rawTitle)) {
            rawTitle
        } else if (extractArtistFromTitle(rawTitle) != null) {
            cleanTitleIfNeeded(rawTitle)
        } else rawTitle
        val album = extras.getCharSequence(EXTRA_SUB_TEXT)?.toString()?.trim()
            ?.takeIf { !it.equals(artist, ignoreCase = true) }
            ?: extras.getCharSequence(EXTRA_INFO_TEXT)?.toString()?.trim()

        if (manualContentOverride(title, artist) == ContentOverrideType.VIDEO) {
            removeRejectedSession(packageName, title, artist)
            updateTrackingNotification(null, null)
            updateServiceLifecycle()
            return
        }

        val albumArtBitmap = extractAlbumArtFromNotification(notification)
        val (sessionAction, existingSession) = synchronized(sessionOperationLock) {
            val existing = playbackStates[packageName]
            val isSameTrackInfo = existing != null && existing.title == title && (
                existing.artist == artist ||
                    (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(existing.artist) &&
                        !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist))
            )
            val isReplayDetected = isSameTrackInfo && run {
                val playedMs = existing!!.calculateCurrentPlayDuration()
                val estimatedDuration = existing.estimatedDurationMs
                estimatedDuration != null && estimatedDuration > 0 && playedMs > estimatedDuration + 10_000L
            }
            if (isSameTrackInfo && !isReplayDetected) Pair("UPDATE", existing) else Pair("NEW", existing)
        }

        when (sessionAction) {
            "UPDATE" -> {
                val session = existingSession!!
                if (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(session.artist) &&
                    !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist)) {
                    session.artist = artist
                    session.trackId?.let { trackId ->
                        serviceScope.launch {
                            try {
                                val track = trackRepository.getById(trackId).first()
                                if (track != null && me.avinas.tempo.utils.ArtistParser.isUnknownArtist(track.artist)) {
                                    val updatedTrack = track.copy(artist = artist)
                                    trackRepository.update(updatedTrack)
                                    artistLinkingService.linkArtistsForTrack(updatedTrack)
                                    EnrichmentWorker.enqueueImmediate(applicationContext, trackId)
                                }
                            } catch (_: Exception) { }
                        }
                    }
                    updateTrackingNotification(title, artist)
                }
                if (!session.isPlaying) session.resume()
            }
            "NEW" -> {
                if (artist.isNotBlank() &&
                    !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist) &&
                    isLikelyAdvertisementFromNotification(title, artist, album, packageName)) return
                if (existingSession != null) {
                    existingSession.pause()
                    saveListeningEvent(existingSession)
                }
                val cachedDurationKey = generateHash("$title|$artist")
                val cachedDuration = durationEstimateCache.get(cachedDurationKey)
                if (cachedDuration != null && shouldRejectByTrackingRules(packageName, title, artist, cachedDuration)) {
                    removeRejectedSession(packageName, title, artist)
                    updateTrackingNotification(null, null)
                    return
                }
                loggedArtistExtractionFailures.clear()
                loggedAdvertisementSkips.clear()
                val newSession = PlaybackSession(
                    packageName = packageName,
                    title = title,
                    artist = artist,
                    album = album,
                    isPlaying = true,
                    estimatedDurationMs = cachedDuration,
                    accumulatedPositionMs = 0,
                    lastRecordedPosition = 0,
                    isLikelyMusic = true
                )
                playbackStates[packageName] = newSession
                updateTrackingNotification(title, artist)
                val localArtUrl = albumArtBitmap?.let { saveAlbumArtToStorage(it, title, artist) }
                serviceScope.launch {
                    try {
                        val track = getOrInsertTrack(title, artist, album)
                        newSession.trackId = track.id
                        val duration = track.duration ?: getTrackDurationFromMetadata(track.id)
                        if (duration != null && duration > 0) {
                            if (shouldRejectByTrackingRules(packageName, title, artist, duration)) {
                                removeRejectedSession(packageName, title, artist)
                                updateTrackingNotification(null, null)
                                updateServiceLifecycle()
                                return@launch
                            }
                            newSession.estimatedDurationMs = duration
                            durationEstimateCache.put(cachedDurationKey, duration)
                        }
                        if (localArtUrl != null) {
                            delay(10_000)
                            updateTrackAlbumArtIfNeeded(track.id, localArtUrl)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error inserting track", e)
                    }
                }
            }
        }
    }

    private suspend fun getTrackDurationFromMetadata(trackId: Long): Long? = try {
        enrichedMetadataRepository.forTrackSync(trackId)?.trackDurationMs
    } catch (_: Exception) { null }

    private fun processNotificationRemoved(sbn: StatusBarNotification) {
        val session = playbackStates.remove(sbn.packageName) ?: return
        session.pause()
        saveListeningEvent(session)
        if (playbackStates.isEmpty()) updateTrackingNotification(null, null)
        else playbackStates.values.firstOrNull()?.let { updateTrackingNotification(it.title, it.artist) }
    }

    private suspend fun getOrInsertTrack(
        title: String,
        artist: String,
        album: String?,
        metadata: LocalMediaMetadata? = null
    ): Track {
        val cleanTitle = me.avinas.tempo.utils.ArtistParser.cleanTrackTitle(title)
        val exactMatch = trackRepository.findByTitleAndArtist(title, artist)
        if (exactMatch != null) return handleExistingTrack(exactMatch, artist, album)
        val alias = trackAliasRepository.findAlias(title, artist)
        if (alias != null) {
            val targetTrack = trackRepository.getById(alias.targetTrackId).first()
            if (targetTrack != null) return handleExistingTrack(targetTrack, artist, album)
        }
        val fuzzyArtistMatch = trackRepository.findByTitleAndArtistFuzzy(title, artist)
        if (fuzzyArtistMatch != null) return handleExistingTrack(fuzzyArtistMatch, artist, album)
        val titleCandidates = trackRepository.findCandidatesByTitle(title)
        val cleanTitleMatch = titleCandidates.find { track ->
            track.title.equals(cleanTitle, ignoreCase = true) &&
                me.avinas.tempo.utils.ArtistParser.hasAnyMatchingArtist(track.artist, artist)
        }
        if (cleanTitleMatch != null) return handleExistingTrack(cleanTitleMatch, artist, album)
        val now = System.currentTimeMillis()
        if (now - lastPreferencesFetch > PREFERENCES_CACHE_TTL_MS) {
            try {
                val prefs = userPreferencesDao.getSync() ?: me.avinas.tempo.data.local.entities.UserPreferences()
                cachedMergeAlternateVersions = prefs.mergeAlternateVersions
                lastPreferencesFetch = now
            } catch (_: Exception) { }
        }
        val strictMatching = !cachedMergeAlternateVersions
        val candidates = trackRepository.findFuzzyCandidates(title, artist).map { track ->
            TrackCandidate(track.id, track.title, track.artist, track.album)
        }
        val matchResult = TrackMatcher.findBestMatch(title, artist, candidates, strictMatching)
        if (matchResult != null && matchResult.second.overallScore >= TRACK_MATCH_THRESHOLD) {
            val existingTrack = trackRepository.getById(matchResult.first.id).first()
            if (existingTrack != null) return handleExistingTrack(existingTrack, artist, album)
        }
        return insertNewTrack(title, artist, album, metadata)
    }

    private suspend fun handleExistingTrack(existingTrack: Track, newArtist: String, newAlbum: String?): Track {
        if (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(existingTrack.artist) &&
            !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(newArtist)) {
            val updatedTrack = existingTrack.copy(artist = newArtist, album = newAlbum ?: existingTrack.album)
            trackRepository.update(updatedTrack)
            artistLinkingService.linkArtistsForTrack(updatedTrack)
            EnrichmentWorker.enqueueImmediate(applicationContext, existingTrack.id)
            return updatedTrack
        }
        if (existingTrack.primaryArtistId == null && !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(existingTrack.artist)) {
            serviceScope.launch { try { artistLinkingService.linkArtistsForTrack(existingTrack) } catch (_: Exception) { } }
        }
        return existingTrack
    }

    private suspend fun insertNewTrack(
        title: String,
        artist: String,
        album: String?,
        metadata: LocalMediaMetadata? = null
    ): Track {
        val contentType = metadata?.getContentType()?.toString() ?: "MUSIC"
        val newTrack = Track(
            title = title,
            artist = artist,
            album = album,
            duration = null,
            albumArtUrl = null,
            spotifyId = null,
            musicbrainzId = null,
            contentType = contentType
        )
        val id = trackRepository.insert(newTrack)
        val insertedTrack = newTrack.copy(id = id)
        if (!me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist)) {
            try {
                val linkedTrack = artistLinkingService.linkArtistsForTrack(insertedTrack)
                enrichedMetadataRepository.createPendingIfNotExists(id)
                EnrichmentWorker.enqueueImmediate(applicationContext, id)
                return linkedTrack
            } catch (_: Exception) { }
        } else {
            enrichedMetadataRepository.createPendingIfNotExists(id)
            return insertedTrack
        }
        enrichedMetadataRepository.createPendingIfNotExists(id)
        return insertedTrack
    }

    private val loggedEnrichedTracks = object : LinkedHashMap<Long, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>): Boolean = size > 256
    }

    private suspend fun saveLocalMetadataFallback(trackId: Long, localMetadata: LocalMediaMetadata) {
        try {
            val cachedMetadata = localMetadataCache.get(trackId)
            val updatedLocalMetadata = if (cachedMetadata != null) {
                cachedMetadata.copy(
                    genre = localMetadata.genre ?: cachedMetadata.genre,
                    album = localMetadata.album ?: cachedMetadata.album,
                    year = localMetadata.year ?: cachedMetadata.year,
                    durationMs = localMetadata.durationMs ?: cachedMetadata.durationMs,
                    albumArtBitmap = localMetadata.albumArtBitmap ?: cachedMetadata.albumArtBitmap,
                    albumArtUri = localMetadata.albumArtUri ?: cachedMetadata.albumArtUri
                )
            } else localMetadata
            localMetadataCache.put(trackId, updatedLocalMetadata)
            val savedLocalArtUrl = updatedLocalMetadata.albumArtBitmap?.let {
                saveAlbumArtToStorage(it, updatedLocalMetadata.title, updatedLocalMetadata.artist)
            }
            val existingMetadata = enrichedMetadataRepository.forTrackSync(trackId)
            val enrichedArtUrl = existingMetadata?.albumArtUrl
            val enrichedArtIsRemoteUrl = enrichedArtUrl?.startsWith("http") == true
            val hasLocalArt = savedLocalArtUrl != null || updatedLocalMetadata.albumArtUri != null
            val needsAlbumArt = enrichedArtUrl.isNullOrBlank() && hasLocalArt
            val needsGenre = existingMetadata?.genres.isNullOrEmpty() && updatedLocalMetadata.genre != null
            val needsAlbum = existingMetadata?.albumTitle.isNullOrBlank() && updatedLocalMetadata.album != null
            val needsYear = existingMetadata?.releaseYear == null && updatedLocalMetadata.getReleaseYear() != null
            val needsDuration = existingMetadata?.trackDurationMs == null && updatedLocalMetadata.durationMs != null
            val shouldUpdate = existingMetadata == null || needsAlbumArt || needsGenre || needsAlbum || needsYear || needsDuration
            if (!shouldUpdate) {
                val track = trackRepository.getById(trackId).first()
                if (track != null && track.albumArtUrl.isNullOrBlank() && savedLocalArtUrl != null) {
                    trackRepository.update(track.copy(albumArtUrl = savedLocalArtUrl))
                } else if (track != null && enrichedArtIsRemoteUrl && savedLocalArtUrl != null) {
                    Log.d(TAG, "Track $trackId has enriched and local art")
                }
                synchronized(loggedEnrichedTracks) { loggedEnrichedTracks[trackId] = true }
                return
            }
            val localArtUrl = savedLocalArtUrl ?: updatedLocalMetadata.getBestAlbumArtSource()
            val updatedMetadata = (existingMetadata ?: me.avinas.tempo.data.local.entities.EnrichedMetadata(trackId = trackId)).copy(
                albumTitle = existingMetadata?.albumTitle ?: updatedLocalMetadata.album,
                albumArtUrl = existingMetadata?.albumArtUrl ?: localArtUrl,
                albumArtSource = if (needsAlbumArt && localArtUrl != null) {
                    me.avinas.tempo.data.local.entities.AlbumArtSource.LOCAL
                } else existingMetadata?.albumArtSource ?: me.avinas.tempo.data.local.entities.AlbumArtSource.NONE,
                releaseYear = existingMetadata?.releaseYear ?: updatedLocalMetadata.getReleaseYear(),
                trackDurationMs = existingMetadata?.trackDurationMs ?: updatedLocalMetadata.durationMs,
                genres = if (existingMetadata?.genres.isNullOrEmpty() && updatedLocalMetadata.genre != null) {
                    listOf(updatedLocalMetadata.genre)
                } else existingMetadata?.genres ?: emptyList(),
                cacheTimestamp = System.currentTimeMillis()
            )
            enrichedMetadataRepository.upsert(updatedMetadata)
            if (localArtUrl != null) {
                val track = trackRepository.getById(trackId).first()
                if (track != null && track.albumArtUrl.isNullOrBlank()) {
                    trackRepository.update(track.copy(
                        albumArtUrl = localArtUrl,
                        album = track.album.takeUnless { it.isNullOrBlank() } ?: updatedLocalMetadata.album,
                        duration = track.duration ?: updatedLocalMetadata.durationMs
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving local metadata fallback for track $trackId", e)
        }
    }

    private suspend fun checkAndBackfillTrackArt(
        trackId: Long,
        enrichedArtUrl: String?,
        localMetadata: LocalMediaMetadata,
        savedLocalArtUrl: String? = null
    ) {
        try {
            val track = trackRepository.getById(trackId).first()
            if (track != null && track.albumArtUrl.isNullOrBlank()) {
                val fixedEnrichedUrl = me.avinas.tempo.data.enrichment.MusicBrainzEnrichmentService.fixHttpUrl(enrichedArtUrl)
                if (!fixedEnrichedUrl.isNullOrBlank()) {
                    trackRepository.update(track.copy(albumArtUrl = fixedEnrichedUrl))
                } else if (savedLocalArtUrl != null) {
                    trackRepository.update(track.copy(albumArtUrl = savedLocalArtUrl))
                } else {
                    val localArtUrl = localMetadata.albumArtBitmap?.let {
                        saveAlbumArtToStorage(it, localMetadata.title, localMetadata.artist)
                    } ?: localMetadata.getBestAlbumArtSource()
                    if (localArtUrl != null) trackRepository.update(track.copy(albumArtUrl = localArtUrl))
                }
            }
        } catch (_: Exception) { }
    }

    private fun extractAlbumArtFromNotification(notification: Notification): Bitmap? {
        try {
            val extras = notification.extras
            @Suppress("DEPRECATION")
            extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)?.let { return it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notification.getLargeIcon()?.let { icon ->
                    try { icon.loadDrawable(applicationContext)?.let { return it.toBitmap() } } catch (_: Exception) { }
                }
            }
            @Suppress("DEPRECATION")
            notification.largeIcon?.let { return it }
        } catch (_: Exception) { }
        return null
    }

    private fun extractAlbumArtFromMetadata(metadata: MediaMetadata): Bitmap? {
        return try {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (_: Exception) { null }
    }

    private fun saveAlbumArtToStorage(bitmap: Bitmap, title: String, artist: String): String? {
        return try {
            val albumArtDir = File(applicationContext.filesDir, ALBUM_ART_DIR)
            if (!albumArtDir.exists()) albumArtDir.mkdirs()
            val file = File(albumArtDir, "art_${generateHash("$title|$artist")}.jpg")
            if (file.exists()) return "file://${file.absolutePath}"
            val toSave = downsampleBitmap(bitmap, 512)
            FileOutputStream(file).use { out -> toSave.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            if (toSave !== bitmap) toSave.recycle()
            "file://${file.absolutePath}"
        } catch (e: Exception) {
            Log.e(TAG, "Error saving album art", e)
            null
        }
    }

    private fun downsampleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        if (w <= maxDimension && h <= maxDimension) return bitmap
        val ratio = (maxDimension / maxOf(w, h)).coerceAtMost(1f)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    private fun generateHash(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.lowercase().toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private suspend fun updateTrackAlbumArtIfNeeded(trackId: Long, localArtUrl: String) {
        try {
            val track = trackRepository.getById(trackId).first() ?: return
            if (track.albumArtUrl.isNullOrBlank()) trackRepository.update(track.copy(albumArtUrl = localArtUrl))
        } catch (_: Exception) { }
    }

    private fun saveListeningEvent(session: PlaybackSession) {
        serviceScope.launch { saveListeningEventSuspend(session) }
    }

    private suspend fun saveListeningEventSuspend(session: PlaybackSession) {
        val playDuration = session.calculateCurrentPlayDuration()
        if (playDuration < minimumPlayDurationMs()) {
            Log.d(TAG, "Skipping short play: ${playDuration}ms for '${session.title}'")
            return
        }
        var trackId = session.trackId
        if (trackId == null) {
            var retries = 0
            while (session.trackId == null && retries < 5) { delay(500); retries++ }
            trackId = session.trackId
            if (trackId == null) {
                try { trackId = getOrInsertTrack(session.title, session.artist, session.album).id }
                catch (_: Exception) { return }
            }
        }
        insertListeningEvent(trackId, session)
    }

    private suspend fun insertListeningEvent(trackId: Long, session: PlaybackSession) {
        var playDuration = session.calculateCurrentPlayDuration()
        val currentTime = System.currentTimeMillis()
        if (playDuration < minimumPlayDurationMs()) return
        if (playDuration > MAX_PLAY_DURATION_MS) playDuration = MAX_PLAY_DURATION_MS
        session.estimatedDurationMs?.let { estimated ->
            if (estimated > 0 && playDuration > estimated * 3) playDuration = estimated * 3
        }
        val estimatedDuration = session.estimatedDurationMs ?: durationEstimator
            .estimateDuration(session.title, session.artist, session.album).durationMs
        val completionPct = durationEstimator.calculateCompletionPercent(playDuration, estimatedDuration, false)
        val wasSkipped = session.wasSkipped() || completionPct < SKIP_COMPLETION_THRESHOLD
        val isReplay = recentPlaysCache[trackId]?.let { currentTime - it < REPLAY_THRESHOLD_MS } ?: false
        recentPlaysCache[trackId] = currentTime
        val oldThreshold = currentTime - REPLAY_THRESHOLD_MS
        recentPlaysCache.entries.removeIf { it.value < oldThreshold }
        val isFullPlay = completionPct >= FULL_PLAY_COMPLETION_THRESHOLD
        if (isFullPlay && session.estimatedDurationMs != null && session.estimatedDurationMs!! > 0) {
            durationEstimator.recordObservedDuration(
                session.title, session.artist, session.estimatedDurationMs!!, true, session.album
            )
        }
        session.estimatedDurationMs?.takeIf { it > 0 }?.let {
            durationEstimateCache.put(generateHash("${session.title}|${session.artist}"), it)
        }
        val event = ListeningEvent(
            track_id = trackId,
            timestamp = session.startTimestamp,
            playDuration = playDuration,
            completionPercentage = completionPct,
            source = session.packageName,
            wasSkipped = wasSkipped,
            isReplay = isReplay,
            estimatedDurationMs = estimatedDuration,
            pauseCount = session.pauseCount,
            sessionId = session.sessionId,
            endTimestamp = currentTime,
            totalPauseDurationMs = session.totalPauseDurationMs,
            seekCount = session.seekCount,
            positionUpdatesCount = session.positionUpdatesCount,
            wasInterrupted = session.wasInterrupted,
            volumeLevel = (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
                .getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        )
        val anomalies = session.detectAnomalies()
        if (anomalies.isNotEmpty()) Log.i(TAG, "Anomalies detected for '${session.title}': ${anomalies.joinToString("; ")}")
        try {
            if (trackingManager.queueEvent(event, session.sessionId)) {
                (statsRepository as? RoomStatsRepository)?.onNewListeningEvent(event.timestamp)
                refreshCoordinator.notifyNewTrackRecorded()
            }
        } catch (_: Exception) {
            listeningRepository.insert(event)
            (statsRepository as? RoomStatsRepository)?.onNewListeningEvent(event.timestamp)
            refreshCoordinator.notifyNewTrackRecorded()
        }
    }

    @Volatile private var isMediaSessionManagerInitialized = false

    private fun initializeMediaSessionManager() {
        if (isMediaSessionManagerInitialized) return
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, MusicTrackingService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                { controllers -> onActiveSessionsChanged(controllers) }, componentName
            )
            isMediaSessionManagerInitialized = true
            onActiveSessionsChanged(mediaSessionManager?.getActiveSessions(componentName))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize MediaSessionManager", e)
        }
    }

    private fun rescanActiveMediaSessions() {
        if (!isMediaSessionManagerInitialized) return
        try {
            val componentName = ComponentName(this, MusicTrackingService::class.java)
            onActiveSessionsChanged(mediaSessionManager?.getActiveSessions(componentName))
        } catch (_: Exception) { }
    }

    private fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        val currentControllers = controllers ?: emptyList()
        val now = System.currentTimeMillis()
        val (lastCount, lastTime) = lastSessionChangeLog ?: (-1 to 0L)
        if (currentControllers.size != lastCount || now - lastTime > SESSION_CHANGE_LOG_DEBOUNCE_MS) {
            lastSessionChangeLog = currentControllers.size to now
        }
        val newPackageNames = currentControllers.map { it.packageName }.toSet()
        val removedPackages = synchronized(activeControllers) { activeControllers.keys.toSet() - newPackageNames }
        removedPackages.forEach { packageName ->
            activeControllers[packageName]?.unregisterCallback(packageSpecificCallbacks[packageName] ?: sharedCallback)
            activeControllers.remove(packageName)
            packageSpecificCallbacks.remove(packageName)
            playbackStates.remove(packageName)?.let { it.pause(); saveListeningEvent(it) }
        }
        currentControllers.forEach { controller ->
            val packageName = controller.packageName
            if (!activeControllers.containsKey(packageName)) {
                val isEnabled = isInEnabledApps(packageName)
                val isBlocked = isInBlockedApps(packageName)
                val isKnownApp = packageName in cachedAllKnownPackages
                val isExplicitlyDisabled = isKnownApp && !isEnabled && !isBlocked
                val shouldTrack = isEnabled || (isMusicSession(controller) && !isExplicitlyDisabled)
                if (shouldTrack && !isBlocked) {
                    val callback = PackageSpecificCallback(packageName)
                    controller.registerCallback(callback)
                    activeControllers[packageName] = controller
                    packageSpecificCallbacks[packageName] = callback
                    processMediaControllerState(controller)
                }
            }
        }
        if (playbackStates.isEmpty()) updateTrackingNotification(null, null)
        updateServiceLifecycle()
    }

    private val packageSpecificCallbacks = mutableMapOf<String, MediaController.Callback>()
    private val sharedCallback = PackageSpecificCallback("shared_fallback")

    private fun isMusicSession(controller: MediaController): Boolean {
        val packageName = controller.packageName
        if (isInBlockedApps(packageName)) return false
        val isEnabledApp = isInEnabledApps(packageName)
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) return isEnabledApp
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: "Unknown Artist"
        when (manualContentOverride(title, artist)) {
            ContentOverrideType.VIDEO -> return false
            ContentOverrideType.MUSIC -> return true
            null -> Unit
        }
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (shouldRejectByTrackingRules(packageName, title, artist, duration)) return false
        if (isEnabledApp) return true
        if (duration > 0 && duration < MIN_MUSIC_DURATION_MS) return false
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val hasMusicMetadata = !artist.isBlank() && !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist) || !album.isNullOrBlank()
        val lowerPkg = packageName.lowercase()
        val looksLikeVideoApp = lowerPkg.contains("video") || lowerPkg.contains("movie") ||
            lowerPkg.contains("tv") || lowerPkg.contains("stream") ||
            (lowerPkg.contains("player") && !lowerPkg.contains("music"))
        if (looksLikeVideoApp && !hasMusicMetadata) return false
        return playbackState?.state == PlaybackState.STATE_PLAYING || hasMusicMetadata
    }

    private fun processMediaControllerState(controller: MediaController) {
        val packageName = controller.packageName
        if (shouldPauseForBattery()) {
            playbackStates.remove(packageName)?.let { it.pause(); saveListeningEvent(it) }
            updateTrackingNotification(null, null)
            return
        }
        val metadata = controller.metadata
        val playbackState = controller.playbackState
        val state = playbackState?.state
        if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE) {
            playbackStates.remove(packageName)?.let { it.pause(); saveListeningEvent(it) }
            if (playbackStates.isEmpty()) updateTrackingNotification(null, null)
            return
        }
        if (metadata == null) return
        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (rawTitle.isNullOrBlank()) return
        val artist = extractArtistFromMetadata(metadata)
        val title = if (artist != "Unknown Artist" && extractArtistFromTitle(rawTitle) == artist) cleanTitleIfNeeded(rawTitle) else rawTitle
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (shouldRejectByTrackingRules(packageName, title, artist, duration)) {
            removeRejectedSession(packageName, title, artist)
            updateTrackingNotification(null, null)
            updateServiceLifecycle()
            return
        }

        val currentPosition = playbackState?.position ?: 0L
        val isPlaying = state == PlaybackState.STATE_PLAYING
        val (sessionAction, session) = synchronized(sessionOperationLock) {
            val existing = playbackStates[packageName]
            val isSameTrackInfo = existing != null && existing.title == title && (
                existing.artist == artist ||
                    (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(existing.artist) && !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist)) ||
                    (!me.avinas.tempo.utils.ArtistParser.isUnknownArtist(existing.artist) && me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist))
            )
            val isReplayDetected = isSameTrackInfo && isPlaying && run {
                val lastPosition = existing!!.lastKnownPosition
                val playedMs = existing.calculateCurrentPlayDuration()
                val estimatedDuration = existing.estimatedDurationMs ?: duration.takeIf { it > 0 }
                val positionWentBack = lastPosition > 5_000L && currentPosition < 5_000L
                val completion = if (estimatedDuration != null && estimatedDuration > 0) playedMs.toFloat() / estimatedDuration else 0f
                (positionWentBack && playedMs >= 30_000L && completion >= 0.5f) ||
                    (estimatedDuration != null && estimatedDuration > 0 && playedMs > estimatedDuration + 10_000L)
            }
            existing?.lastKnownPosition = currentPosition
            when {
                isSameTrackInfo && !isReplayDetected -> Pair("UPDATE", existing)
                isPlaying -> Pair("NEW", existing)
                else -> Pair("IGNORE", existing)
            }
        }

        when (sessionAction) {
            "UPDATE" -> {
                val currentSession = session!!
                val localMetadata = LocalMediaMetadata.fromMediaMetadata(metadata, packageName)
                if (localMetadata != null && currentSession.trackId != null) {
                    val trackId = currentSession.trackId!!
                    serviceScope.launch {
                        val cached = localMetadataCache.get(trackId)
                        localMetadataCache.put(trackId, if (cached != null) cached.copy(
                            genre = localMetadata.genre ?: cached.genre,
                            album = localMetadata.album ?: cached.album,
                            year = localMetadata.year ?: cached.year,
                            durationMs = localMetadata.durationMs ?: cached.durationMs,
                            albumArtBitmap = localMetadata.albumArtBitmap ?: cached.albumArtBitmap,
                            albumArtUri = localMetadata.albumArtUri ?: cached.albumArtUri
                        ) else localMetadata)
                    }
                    if (!currentSession.delayedMetadataRetryScheduled) {
                        currentSession.delayedMetadataRetryScheduled = true
                        serviceScope.launch {
                            delay(60_000)
                            val stillSame = playbackStates[packageName]?.let {
                                it.trackId == trackId && it.sessionId == currentSession.sessionId
                            } ?: false
                            if (stillSame) localMetadataCache.get(trackId)?.let { saveLocalMetadataFallback(trackId, it) }
                        }
                    }
                }
                if (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(currentSession.artist) &&
                    !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist)) {
                    currentSession.artist = artist
                    currentSession.trackId?.let { trackId ->
                        serviceScope.launch {
                            val track = trackRepository.getById(trackId).first()
                            if (track != null && me.avinas.tempo.utils.ArtistParser.isUnknownArtist(track.artist)) {
                                val updated = track.copy(artist = artist)
                                trackRepository.update(updated)
                                artistLinkingService.linkArtistsForTrack(updated)
                                EnrichmentWorker.enqueueImmediate(applicationContext, trackId)
                            }
                        }
                    }
                    updateTrackingNotification(title, artist)
                }
                currentSession.updatePosition(currentPosition, isPlaying)
                if (isPlaying && !currentSession.isPlaying) currentSession.resume()
                else if (!isPlaying && currentSession.isPlaying) currentSession.pause()
                if (duration > 0) currentSession.estimatedDurationMs = duration
            }
            "NEW" -> {
                session?.let { it.pause(); saveListeningEvent(it) }
                val localMetadata = LocalMediaMetadata.fromMediaMetadata(metadata, packageName)
                if (artist.isNotBlank() && !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artist) && localMetadata?.isLikelyAdvertisement() == true) return
                val albumArtBitmap = localMetadata?.albumArtBitmap ?: extractAlbumArtFromMetadata(metadata)
                val localArtUrl = albumArtBitmap?.let { saveAlbumArtToStorage(it, title, artist) }
                loggedArtistExtractionFailures.clear(); loggedAdvertisementSkips.clear()
                val newSession = PlaybackSession(
                    packageName = packageName,
                    title = title,
                    artist = artist,
                    album = album,
                    isPlaying = true,
                    estimatedDurationMs = if (duration > 0) duration else null,
                    playbackStartPosition = currentPosition,
                    lastKnownPosition = currentPosition,
                    lastRecordedPosition = currentPosition,
                    accumulatedPositionMs = 0,
                    isLikelyMusic = true
                )
                playbackStates[packageName] = newSession
                updateTrackingNotification(title, artist)
                serviceScope.launch {
                    try {
                        if (shouldFilterContent(packageName, localMetadata, title, artist)) {
                            playbackStates.remove(packageName)
                            updateTrackingNotification(null, null)
                            return@launch
                        }
                        val track = getOrInsertTrack(title, artist, album, localMetadata)
                        newSession.trackId = track.id
                        if (localArtUrl != null) {
                            val currentCache = localMetadataCache.get(track.id) ?: localMetadata
                            if (currentCache != null) localMetadataCache.put(track.id, currentCache.copy(albumArtUri = localArtUrl))
                            else localMetadataCache.put(track.id, LocalMediaMetadata(title = title, artist = artist, album = album, albumArtUri = localArtUrl))
                        }
                        if (localMetadata != null && localMetadata.hasRichMetadata()) {
                            delay(30_000)
                            saveLocalMetadataFallback(track.id, localMetadataCache.get(track.id) ?: localMetadata)
                        } else if (localArtUrl != null) {
                            delay(20_000)
                            updateTrackAlbumArtIfNeeded(track.id, localArtUrl)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error inserting track", e)
                    }
                }
            }
        }
    }

    inner class PackageSpecificCallback(private val packageName: String) : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            TrackingServiceHeartbeat.markMediaSessionCallback(applicationContext)
            activeControllers[packageName]?.let { processMediaControllerState(it); updateServiceLifecycle() }
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            TrackingServiceHeartbeat.markMediaSessionCallback(applicationContext)
            activeControllers[packageName]?.let { processMediaControllerState(it); updateServiceLifecycle() }
        }
        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            TrackingServiceHeartbeat.markMediaSessionCallback(applicationContext)
            activeControllers.remove(packageName)
            packageSpecificCallbacks.remove(packageName)
            playbackStates.remove(packageName)?.let { it.pause(); saveListeningEvent(it) }
            updateServiceLifecycle()
        }
    }

    private fun createNotificationChannel() {
        val trackingChannel = NotificationChannel(CHANNEL_ID, "Music Tracking", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows when Tempo is actively tracking your music"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val silentChannel = NotificationChannel(CHANNEL_ID + "_silent", "Background Monitoring", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Silent notification when waiting for music"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setSound(null, null); enableVibration(false); enableLights(false)
        }
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(trackingChannel); createNotificationChannel(silentChannel)
        }
    }

    private fun startForegroundServiceWithNotification() {
        createNotificationChannel()
        val notification = buildTrackingNotification(null, null)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground state", e)
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        }
    }

    private fun buildTrackingNotification(currentTrack: String?, currentArtist: String?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (shouldPauseForBattery()) {
            val batteryLevel = BatteryUtils.getBatteryLevel(this)
            return NotificationCompat.Builder(this, CHANNEL_ID + "_silent")
                .setContentTitle("Tempo — Tracking paused")
                .setContentText("🪫 Battery at $batteryLevel%. Tracking resumes above ${BatteryUtils.CRITICAL_BATTERY_LEVEL}%.")
                .setSmallIcon(R.drawable.ic_notification).setOngoing(true).setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET).setSilent(true).build()
        }
        val activeMusicSessions = playbackStates.count { it.value.isLikelyMusic && it.value.isPlaying }
        val isActivelyTracking = currentTrack != null || activeMusicSessions > 0
        val channelId = if (isActivelyTracking) CHANNEL_ID else CHANNEL_ID + "_silent"
        return if (isActivelyTracking) {
            val contentText = if (currentTrack != null && currentArtist != null) {
                val session = playbackStates.values.find { it.title == currentTrack && it.artist == currentArtist }
                val timeInfo = session?.let { val secs = it.accumulatedPositionMs / 1000; if (secs > 0) " (${secs}s)" else "" } ?: ""
                val mutedWarning = if (getCurrentMusicStreamVolume() == 0) " [Muted playback detected - XP paused]" else ""
                "🎵 $currentTrack - $currentArtist$timeInfo$mutedWarning"
            } else "🎵 Tracking $activeMusicSessions music session(s)"
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("Tempo is tracking music").setContentText(contentText)
                .setSmallIcon(R.drawable.ic_notification).setOngoing(true).setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW).setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        } else {
            NotificationCompat.Builder(this, channelId)
                .setContentTitle("Tempo").setContentText("Waiting for music...")
                .setSmallIcon(R.drawable.ic_notification).setOngoing(true).setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET).setSilent(true).build()
        }
    }

    private fun updateTrackingNotification(currentTrack: String?, currentArtist: String?) {
        val now = System.currentTimeMillis()
        val currentVolume = getCurrentMusicStreamVolume()
        val isPausedForBattery = shouldPauseForBattery()
        val contentId = "$currentTrack|$currentArtist|${hasActivePlayback()}|$currentVolume|$isPausedForBattery"
        if (contentId == lastNotificationContent && now - lastNotificationUpdate < 2000) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildTrackingNotification(currentTrack, currentArtist)
        )
        lastNotificationUpdate = now
        lastNotificationContent = contentId
    }

    private fun getCurrentMusicStreamVolume(): Int =
        (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
            .getStreamVolume(android.media.AudioManager.STREAM_MUSIC)

    override fun onDestroy() {
        autoSaveJob?.cancel(); positionPollingJob?.cancel()
        batteryStateReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        batteryStateReceiver = null
        synchronized(connectionLock) { isListenerConnected = false }
        isMediaSessionManagerInitialized = false
        val controllerSnapshot = synchronized(activeControllers) { activeControllers.toList() }
        controllerSnapshot.forEach { (packageName, controller) ->
            try { controller.unregisterCallback(packageSpecificCallbacks[packageName] ?: sharedCallback) } catch (_: Exception) { }
        }
        activeControllers.clear(); packageSpecificCallbacks.clear()
        val playbackSnapshot = playbackStates.values.toList()
        val flushed = try {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(SHUTDOWN_FLUSH_TIMEOUT_MS) {
                    saveSessionsToPersistence()
                    playbackSnapshot.forEach { it.pause(); saveListeningEventSuspend(it) }
                    trackingManager.flushAll()
                    true
                } ?: false
            }
        } catch (_: Exception) { false }
        playbackStates.clear()
        if (flushed) {
            try { sessionPersistence.clearSessions(); sessionPersistence.markServiceInactive() } catch (_: Exception) { }
        }
        heartbeatJob?.cancel(); heartbeatJob = null
        TrackingServiceHeartbeat.markListenerDisconnected(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    @Volatile private var cachedPauseOnLowBattery: Boolean = true

    private fun watchBatteryPreference() {
        serviceScope.launch {
            userPreferencesDao.preferences().collect { prefs ->
                cachedPauseOnLowBattery = prefs?.pauseTrackingOnLowBattery ?: true
            }
        }
    }

    private fun shouldPauseForBattery(): Boolean {
        if (!cachedPauseOnLowBattery) return false
        return BatteryUtils.isCriticalBattery(this)
    }

    fun getServiceHealth(): ServiceHealth = ServiceHealth(
        isListenerConnected = isListenerConnected,
        activeSessionCount = playbackStates.size,
        trackingMetrics = trackingManager.getMetrics(),
        durationEstimatorStats = durationEstimator.getStats(),
        uptimeMs = System.currentTimeMillis() - serviceStartTime.get()
    )
}

data class ServiceHealth(
    val isListenerConnected: Boolean,
    val activeSessionCount: Int,
    val trackingMetrics: TrackingMetrics,
    val durationEstimatorStats: EstimatorStats,
    val uptimeMs: Long
)
