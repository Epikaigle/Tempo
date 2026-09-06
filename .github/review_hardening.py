from pathlib import Path

changes = 0

# -----------------------------------------------------------------------------
# 1. History artist-level corrections must preserve a more-specific ALWAYS_MUSIC
#    exception instead of deleting every track for that artist indiscriminately.
# -----------------------------------------------------------------------------
history_path = Path("app/src/main/java/me/avinas/tempo/ui/history/HistoryViewModel.kt")
history = history_path.read_text()

old_history = '''                // 3. Delete ALL listening events from this artist
                val deletedEventsCount = listeningRepository.deleteByArtist(artistName)
                Log.d(TAG, "Deleted $deletedEventsCount listening events from artist '$artistName'")

                // 4. Delete ALL tracks from this artist
                val deletedTracksCount = trackRepository.deleteByArtist(artistName)
                Log.d(TAG, "Deleted $deletedTracksCount tracks from artist '$artistName'")
                
                // Show success feedback
                val contentTypeName = contentType.lowercase().replaceFirstChar { it.uppercase() }
                val feedbackMsg = "Blocked \\"$artistName\\" - removed all content from history & stats"
'''

new_history = '''                // 3. Apply the artist-level correction only where it is the effective rule.
                // A TITLE or TITLE_ARTIST ALWAYS_MUSIC exception is more specific and must win;
                // deleting every row by artist here would otherwise destroy history that the
                // tracking service intentionally keeps.
                val artistTracks = trackRepository.all().first()
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
                        protectedTracks++
                        continue
                    }

                    if (deleteFromHistory) {
                        listeningRepository.deleteByTrackId(artistTrack.id)
                        trackRepository.deleteById(artistTrack.id)
                    } else {
                        trackRepository.update(
                            artistTrack.copy(contentType = effectiveType ?: contentType)
                        )
                    }
                    affectedTracks++
                }

                Log.d(
                    TAG,
                    "Applied artist correction '$contentType' to $affectedTracks track(s) for '$artistName'; " +
                        "preserved $protectedTracks more-specific ALWAYS_MUSIC exception(s)"
                )
                
                // Show success feedback
                val contentTypeName = contentType.lowercase().replaceFirstChar { it.uppercase() }
                val feedbackMsg = if (protectedTracks > 0) {
                    "Blocked \\"$artistName\\" - kept $protectedTracks specific Always Music exception${if (protectedTracks == 1) "" else "s"}"
                } else {
                    "Blocked \\"$artistName\\" - removed all matching content from history & stats"
                }
'''

if old_history not in history:
    raise SystemExit("History artist deletion anchor not found; refusing to patch")
history = history.replace(old_history, new_history, 1)
history_path.write_text(history)
changes += 1

# -----------------------------------------------------------------------------
# 2. Active sessions must be fully re-evaluated when manual rules change.
#    Removing ALWAYS_MUSIC can expose a podcast/audiobook heuristic again, and adding
#    a PODCAST/AUDIOBOOK mark should stop the current session immediately as well.
# 3. Make equal-specificity resolution explicitly newest-wins.
# -----------------------------------------------------------------------------
service_path = Path("app/src/main/java/me/avinas/tempo/service/MusicTrackingService.kt")
service = service_path.read_text()

old_resolver = '''            .maxByOrNull { it.second }
            ?.first
'''
new_resolver = '''            .maxWithOrNull(
                compareBy<Pair<ManualContentMark, Int>> { it.second }
                    .thenBy { it.first.markedAt }
            )
            ?.first
'''
if old_resolver not in service:
    raise SystemExit("Manual rule resolver anchor not found; refusing to patch")
service = service.replace(old_resolver, new_resolver, 1)
changes += 1

old_watcher = '''    private fun watchManualContentMarks() {
        serviceScope.launch {
            manualContentMarkDao.getAllMarks().collect { marks ->
                cachedManualContentMarks = marks
                playbackStates.values.toList().forEach { session ->
                    if (manualContentOverride(session.title, session.artist) == ContentOverrideType.VIDEO) {
                        removeRejectedSession(session.packageName, session.title, session.artist)
                    }
                }

                // Re-evaluate active sources after a manual rule change so an ALWAYS_MUSIC
                // correction can start tracking immediately, and removing an override also
                // returns the current media to normal classification without another player event.
                // The first Room emission can arrive during onCreate before tracking components
                // are initialized, so only rescan once the manager is ready.
                if (::trackingManager.isInitialized) {
                    withContext(Dispatchers.Main) {
                        rescanActiveMediaSessions()
                        activeControllers.values.toList().forEach { controller ->
                            try {
                                processMediaControllerState(controller)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to re-evaluate MediaSession after manual rule change", e)
                            }
                        }
                        try {
                            activeNotifications?.forEach { sbn ->
                                if (isMusicNotification(sbn)) processNotificationPosted(sbn)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to re-evaluate notifications after manual rule change", e)
                        }
                        updateServiceLifecycle()
                    }
                }
            }
        }
    }
'''

new_watcher = '''    private fun watchManualContentMarks() {
        serviceScope.launch {
            manualContentMarkDao.getAllMarks().collect { marks ->
                cachedManualContentMarks = marks

                // Manual rules are live controls. Re-evaluate the complete content policy for
                // every active session, not only NON_MUSIC. This matters when ALWAYS_MUSIC is
                // removed (automatic podcast/audiobook filtering becomes active again) and when
                // a PODCAST/AUDIOBOOK mark is added while the media is already playing.
                if (::trackingManager.isInitialized) {
                    playbackStates.values.toList().forEach { session ->
                        val rejectedByTrackingRule = shouldRejectByTrackingRules(
                            session.packageName,
                            session.title,
                            session.artist,
                            session.estimatedDurationMs ?: 0L
                        )
                        val filteredByContent = if (rejectedByTrackingRule) {
                            false
                        } else {
                            shouldFilterContent(
                                session.packageName,
                                session.trackId?.let { localMetadataCache.get(it) },
                                session.title,
                                session.artist
                            )
                        }
                        if (rejectedByTrackingRule || filteredByContent) {
                            removeRejectedSession(session.packageName, session.title, session.artist)
                        }
                    }

                    // Re-evaluate active sources after a manual rule change so an ALWAYS_MUSIC
                    // correction can start tracking immediately, and removing an override also
                    // returns the current media to normal classification without another player event.
                    withContext(Dispatchers.Main) {
                        rescanActiveMediaSessions()
                        activeControllers.values.toList().forEach { controller ->
                            try {
                                processMediaControllerState(controller)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to re-evaluate MediaSession after manual rule change", e)
                            }
                        }
                        try {
                            activeNotifications?.forEach { sbn ->
                                if (isMusicNotification(sbn)) processNotificationPosted(sbn)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to re-evaluate notifications after manual rule change", e)
                        }
                        updateServiceLifecycle()
                    }
                }
            }
        }
    }
'''

if old_watcher not in service:
    raise SystemExit("Manual mark watcher anchor not found; refusing to patch")
service = service.replace(old_watcher, new_watcher, 1)
service_path.write_text(service)
changes += 1

print(f"Applied {changes} hardening change(s)")
