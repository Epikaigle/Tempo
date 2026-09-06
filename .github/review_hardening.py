from pathlib import Path

changes = 0

# -----------------------------------------------------------------------------
# History hardening:
# - Never create a broad ARTIST rule for the unstable "Unknown Artist" placeholder.
# - For an individual track whose artist is unknown, use a TITLE rule so the future
#   corrected metadata does not make the user's block silently stop matching.
# - Resolve rule precedence in Kotlin, matching the service/repository semantics and
#   avoiding SQLite LOWER()'s ASCII-only edge cases for accented artist names.
# -----------------------------------------------------------------------------
history_path = Path("app/src/main/java/me/avinas/tempo/ui/history/HistoryViewModel.kt")
history = history_path.read_text()

old_track_mark = '''                // 1. Save the block pattern for future content
                val mark = me.avinas.tempo.data.local.entities.ManualContentMark(
                    targetTrackId = trackId,
                    patternType = "TITLE_ARTIST",
                    originalTitle = track.title,
                    originalArtist = track.artist,
                    patternValue = track.title,
                    contentType = contentType,
                    markedAt = System.currentTimeMillis()
                )
'''
new_track_mark = '''                // 1. Save the block pattern for future content. "Unknown Artist" is a
                // transient metadata placeholder, so binding an exact TITLE_ARTIST rule to it
                // would stop matching as soon as the real artist/channel is discovered.
                val hasStableArtist = !me.avinas.tempo.utils.ArtistParser.isUnknownArtist(track.artist)
                val mark = me.avinas.tempo.data.local.entities.ManualContentMark(
                    targetTrackId = trackId,
                    patternType = if (hasStableArtist) "TITLE_ARTIST" else "TITLE",
                    originalTitle = track.title,
                    originalArtist = if (hasStableArtist) track.artist else "",
                    patternValue = track.title,
                    contentType = contentType,
                    markedAt = System.currentTimeMillis()
                )
'''
if old_track_mark not in history:
    raise SystemExit("Track mark anchor not found")
history = history.replace(old_track_mark, new_track_mark, 1)
changes += 1

old_artist_start = '''                val artistName = track.artist

                // 1. Save the artist-level block pattern for future content
'''
new_artist_start = '''                val artistName = track.artist
                if (me.avinas.tempo.utils.ArtistParser.isUnknownArtist(artistName)) {
                    _uiState.update {
                        it.copy(
                            feedbackMessage = "Artist/channel is unknown — use the track-level correction instead",
                            isMarking = false
                        )
                    }
                    return@launch
                }

                // 1. Save the artist-level block pattern for future content
'''
if old_artist_start not in history:
    raise SystemExit("Artist stability guard anchor not found")
history = history.replace(old_artist_start, new_artist_start, 1)
changes += 1

old_artist_resolution = '''                val artistTracks = trackRepository.all().first()
                    .filter { it.artist.equals(artistName, ignoreCase = true) }
                var affectedTracks = 0
                var protectedTracks = 0

                for (artistTrack in artistTracks) {
                    val effectiveMark = manualContentMarkDao.findMatchingMark(
                        artistTrack.title,
                        artistTrack.artist
                    )
                    val effectiveType = effectiveMark?.contentType?.uppercase()

                    if (effectiveType == "ALWAYS_MUSIC") {
'''
new_artist_resolution = '''                val artistTracks = trackRepository.all().first()
                    .filter { it.artist.equals(artistName, ignoreCase = true) }
                val allMarks = manualContentMarkDao.getAllSync()
                var affectedTracks = 0
                var protectedTracks = 0

                for (artistTrack in artistTracks) {
                    val effectiveType = resolveEffectiveContentType(
                        artistTrack.title,
                        artistTrack.artist,
                        allMarks
                    )

                    if (effectiveType == "ALWAYS_MUSIC") {
'''
if old_artist_resolution not in history:
    raise SystemExit("Artist rule resolution anchor not found")
history = history.replace(old_artist_resolution, new_artist_resolution, 1)
changes += 1

helper_anchor = '''    private suspend fun checkShouldShowCoachMark(history: List<HistoryItem>): Boolean {
'''
helper_code = '''    /** Resolve manual content rules exactly like the tracking service. */
    private fun resolveEffectiveContentType(
        title: String,
        artist: String,
        marks: List<me.avinas.tempo.data.local.entities.ManualContentMark>
    ): String? {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()

        return marks.asSequence()
            .mapNotNull { mark ->
                val patternType = mark.patternType.uppercase()
                val matches = when (patternType) {
                    "TITLE_ARTIST" ->
                        mark.originalTitle.equals(cleanTitle, ignoreCase = true) &&
                            mark.originalArtist.equals(cleanArtist, ignoreCase = true)
                    "TITLE" -> mark.originalTitle.equals(cleanTitle, ignoreCase = true)
                    "ARTIST" -> mark.originalArtist.equals(cleanArtist, ignoreCase = true)
                    else -> false
                }
                if (!matches) return@mapNotNull null

                val specificity = when (patternType) {
                    "TITLE_ARTIST" -> 3
                    "TITLE" -> 2
                    "ARTIST" -> 1
                    else -> 0
                }
                Triple(mark, specificity, mark.markedAt)
            }
            .maxWithOrNull(
                compareBy<Triple<me.avinas.tempo.data.local.entities.ManualContentMark, Int, Long>> { it.second }
                    .thenBy { it.third }
            )
            ?.first
            ?.contentType
            ?.uppercase()
    }

    private suspend fun checkShouldShowCoachMark(history: List<HistoryItem>): Boolean {
'''
if helper_anchor not in history:
    raise SystemExit("History helper insertion anchor not found")
history = history.replace(helper_anchor, helper_code, 1)
history_path.write_text(history)
changes += 1

# -----------------------------------------------------------------------------
# Fresh-install app preference hardening. A newly-created current-version Room DB
# has an empty app_preferences table until Manage Apps performs its seed. Empty is
# therefore "not initialized", not an explicit user decision to disable everything.
# Keep the original static allow/block fallbacks until real preference rows exist.
# -----------------------------------------------------------------------------
service_path = Path("app/src/main/java/me/avinas/tempo/service/MusicTrackingService.kt")
service = service_path.read_text()

old_cache = '''    private fun applyAppPreferenceCache(apps: List<me.avinas.tempo.data.local.entities.AppPreference>) {
        cachedEnabledApps = apps.asSequence()
            .filter { it.isEnabled && !it.isBlocked }
            .map { it.packageName }
            .toSet()
        cachedBlockedApps = apps.asSequence()
            .filter { it.isBlocked }
            .map { it.packageName }
            .toSet()
        cachedAllKnownPackages = apps.map { it.packageName }.toSet()
        lastAppPreferenceFetch = System.currentTimeMillis()
        isAppPreferenceCacheInitialized = true
    }
'''
new_cache = '''    private fun applyAppPreferenceCache(apps: List<me.avinas.tempo.data.local.entities.AppPreference>) {
        lastAppPreferenceFetch = System.currentTimeMillis()

        // On a fresh install the current Room schema creates app_preferences empty; the
        // Manage Apps screen seeds it later. Treat an empty table as "not initialized" so
        // the original static music/block lists remain the safe startup fallback instead of
        // accidentally interpreting zero rows as an explicit decision to disable every app.
        if (apps.isEmpty()) {
            cachedEnabledApps = emptySet()
            cachedBlockedApps = emptySet()
            cachedAllKnownPackages = emptySet()
            isAppPreferenceCacheInitialized = false
            return
        }

        cachedEnabledApps = apps.asSequence()
            .filter { it.isEnabled && !it.isBlocked }
            .map { it.packageName }
            .toSet()
        cachedBlockedApps = apps.asSequence()
            .filter { it.isBlocked }
            .map { it.packageName }
            .toSet()
        cachedAllKnownPackages = apps.map { it.packageName }.toSet()
        isAppPreferenceCacheInitialized = true
    }
'''
if old_cache not in service:
    raise SystemExit("App preference cache anchor not found")
service = service.replace(old_cache, new_cache, 1)
service_path.write_text(service)
changes += 1

print(f"Applied {changes} second-pass hardening change(s)")
