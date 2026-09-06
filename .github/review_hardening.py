from pathlib import Path

changes = 0

# -----------------------------------------------------------------------------
# 1. Re-apply the current minimum/max-duration rules at the actual Room persistence
#    boundary. An event may wait in the batching/offline queue while enrichment learns
#    a reliable duration or while the user changes the configured threshold.
#    ALWAYS_MUSIC still bypasses only the maximum/content filters, not minimum listen time.
# -----------------------------------------------------------------------------
repo_path = Path("app/src/main/java/me/avinas/tempo/data/repository/RoomListeningRepository.kt")
repo = repo_path.read_text()

old_imports = '''package me.avinas.tempo.data.repository

import kotlinx.coroutines.flow.Flow
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.dao.ManualContentMarkDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.ManualContentMark
import javax.inject.Inject
import javax.inject.Singleton
'''
new_imports = '''package me.avinas.tempo.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import me.avinas.tempo.data.local.dao.EnrichedMetadataDao
import me.avinas.tempo.data.local.dao.ListeningEventDao
import me.avinas.tempo.data.local.dao.ManualContentMarkDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.ManualContentMark
import me.avinas.tempo.data.preferences.TrackingRulesPreferences
import javax.inject.Inject
import javax.inject.Singleton
'''
if old_imports not in repo:
    raise SystemExit("RoomListeningRepository imports anchor not found")
repo = repo.replace(old_imports, new_imports, 1)
changes += 1

old_ctor = '''class RoomListeningRepository @Inject constructor(
    private val dao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val manualContentMarkDao: ManualContentMarkDao
) : ListeningRepository {
'''
new_ctor = '''class RoomListeningRepository @Inject constructor(
    private val dao: ListeningEventDao,
    private val trackDao: TrackDao,
    private val manualContentMarkDao: ManualContentMarkDao,
    private val enrichedMetadataDao: EnrichedMetadataDao,
    @param:ApplicationContext context: Context
) : ListeningRepository {
    private val trackingRules = TrackingRulesPreferences(context)
'''
if old_ctor not in repo:
    raise SystemExit("RoomListeningRepository constructor anchor not found")
repo = repo.replace(old_ctor, new_ctor, 1)
changes += 1

old_return = '''        return when (matchingMark?.contentType?.uppercase()) {
            "NON_MUSIC", "VIDEO" -> false
            "ALWAYS_MUSIC" -> {
                // Stats/history also filter by Track.contentType. Normalize the track itself
                // so an old PODCAST/AUDIOBOOK classification cannot hide an allowed play.
                if (track.contentType != "MUSIC") {
                    trackDao.update(track.copy(contentType = "MUSIC"))
                }
                true
            }
            else -> true
        }
'''
new_return = '''        val isAlwaysMusic = when (matchingMark?.contentType?.uppercase()) {
            "NON_MUSIC", "VIDEO" -> return false
            "ALWAYS_MUSIC" -> {
                // Stats/history also filter by Track.contentType. Normalize the track itself
                // so an old PODCAST/AUDIOBOOK classification cannot hide an allowed play.
                if (track.contentType != "MUSIC") {
                    trackDao.update(track.copy(contentType = "MUSIC"))
                }
                true
            }
            else -> false
        }

        // Minimum listening time is a counting rule, not a content-classification rule;
        // Always Music therefore still has to meet it.
        if (event.playDuration < trackingRules.minimumPlayDurationMs) return false

        // Only use a reliable persisted/enriched duration here. event.estimatedDurationMs may
        // come from SmartDurationEstimator and must never be treated as proof that media is long.
        // The pre/post persistence calls around batch/offline writes mean a duration discovered
        // while an insert is in flight is still caught.
        if (!isAlwaysMusic) {
            val reliableDuration = track.duration?.takeIf { it > 0L }
                ?: enrichedMetadataDao.forTrackSync(event.track_id)?.trackDurationMs?.takeIf { it > 0L }
            if (reliableDuration != null) {
                val maxDuration = trackingRules.getMaxMusicDurationMs(event.source)
                if (maxDuration != null && reliableDuration > maxDuration) return false
            }
        }

        return true
'''
if old_return not in repo:
    raise SystemExit("RoomListeningRepository rule return anchor not found")
repo = repo.replace(old_return, new_return, 1)
repo_path.write_text(repo)
changes += 1

# -----------------------------------------------------------------------------
# 2. Removing an override must immediately expose/re-apply the rule underneath it.
#    Example: Artist=NON_MUSIC, Song=ALWAYS_MUSIC. Deleting the song exception must
#    remove its retained history immediately rather than only blocking future plays.
# -----------------------------------------------------------------------------
vm_path = Path("app/src/main/java/me/avinas/tempo/ui/settings/AppPreferenceViewModel.kt")
vm = vm_path.read_text()

old_remove = '''    fun removeContentOverride(mark: ManualContentMark) {
        viewModelScope.launch {
            manualContentMarkDao.deleteMark(mark)
        }
    }
'''
new_remove = '''    fun removeContentOverride(mark: ManualContentMark) {
        viewModelScope.launch {
            manualContentMarkDao.deleteMark(mark)

            // Deleting a specific exception can reveal a broader rule underneath it.
            // Re-apply that effective rule to existing local data immediately so history/stats
            // and future tracking do not disagree until the next play.
            val remainingMarks = manualContentMarkDao.getAllSync()
            val affectedTracks = trackRepository.all().first().filter { track ->
                when (mark.patternType.uppercase()) {
                    "TITLE_ARTIST" ->
                        track.title.equals(mark.originalTitle, ignoreCase = true) &&
                            track.artist.equals(mark.originalArtist, ignoreCase = true)
                    "TITLE" -> track.title.equals(mark.originalTitle, ignoreCase = true)
                    "ARTIST" -> track.artist.equals(mark.originalArtist, ignoreCase = true)
                    else -> false
                }
            }

            var changed = false
            affectedTracks.forEach { track ->
                when (effectiveContentType(track.title, track.artist, remainingMarks)) {
                    CONTENT_TYPE_NON_MUSIC, CONTENT_TYPE_LEGACY_VIDEO -> {
                        listeningRepository.deleteByTrackId(track.id)
                        changed = true
                    }
                    CONTENT_TYPE_ALWAYS_MUSIC -> {
                        if (track.contentType != "MUSIC") {
                            trackRepository.update(track.copy(contentType = "MUSIC"))
                            changed = true
                        }
                    }
                }
            }

            if (changed) statsRepository.invalidateCache()
        }
    }
'''
if old_remove not in vm:
    raise SystemExit("removeContentOverride anchor not found")
vm = vm.replace(old_remove, new_remove, 1)
vm_path.write_text(vm)
changes += 1

print(f"Applied {changes} final persistence hardening change(s)")
