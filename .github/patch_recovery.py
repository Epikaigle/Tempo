from pathlib import Path

path = Path("app/src/main/java/me/avinas/tempo/service/MusicTrackingService.kt")
text = path.read_text()
changes = 0

# 1) Crash recovery must respect the current app preference rather than the state
# that existed before the process died.
recovery_sentinel = "Skipping recovered session from disabled/blocked app"
if recovery_sentinel not in text:
    old = '''                    for (state in recoveredSessions) {
                        val alreadySaved = try {
                            listeningRepository.getEventsBySessionId(state.sessionId).isNotEmpty()
                        } catch (_: Exception) { false }
'''
    new = '''                    for (state in recoveredSessions) {
                        // Recovery must respect the CURRENT app preference, not the state that
                        // existed before the process died. This direct Room read avoids a race
                        // with the asynchronously populated in-memory app cache.
                        val appPreference = try {
                            appPreferenceDao.getAppPreference(state.packageName)
                        } catch (_: Exception) { null }
                        val disabledOrBlocked = appPreference?.let { !it.isEnabled || it.isBlocked } == true
                        val staticallyBlockedWithoutExplicitEnable =
                            appPreference == null && state.packageName in BLOCKED_APPS
                        if (disabledOrBlocked || staticallyBlockedWithoutExplicitEnable) {
                            Log.d(TAG, "Skipping recovered session from disabled/blocked app: ${state.packageName}")
                            continue
                        }

                        val alreadySaved = try {
                            listeningRepository.getEventsBySessionId(state.sessionId).isNotEmpty()
                        } catch (_: Exception) { false }
'''
    if old not in text:
        raise SystemExit("Expected recovery anchor not found; refusing to patch")
    text = text.replace(old, new, 1)
    changes += 1

# 2) Never bypass the persistence guard when queueEvent itself fails. The manager's
# immediate path performs the same pre/post rule checks as the async batch path.
fallback_sentinel = "Queue failed; using guarded immediate persistence"
if fallback_sentinel not in text:
    old = '''        try {
            if (trackingManager.queueEvent(event, session.sessionId)) {
                (statsRepository as? RoomStatsRepository)?.onNewListeningEvent(event.timestamp)
                refreshCoordinator.notifyNewTrackRecorded()
            }
        } catch (_: Exception) {
            listeningRepository.insert(event)
            (statsRepository as? RoomStatsRepository)?.onNewListeningEvent(event.timestamp)
            refreshCoordinator.notifyNewTrackRecorded()
        }
'''
    new = '''        try {
            if (trackingManager.queueEvent(event, session.sessionId)) {
                (statsRepository as? RoomStatsRepository)?.onNewListeningEvent(event.timestamp)
                refreshCoordinator.notifyNewTrackRecorded()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Queue failed; using guarded immediate persistence", e)
            val immediateResult = trackingManager.saveEventImmediate(event)
            val savedId = immediateResult.getOrNull() ?: 0L
            if (savedId > 0L) {
                (statsRepository as? RoomStatsRepository)?.onNewListeningEvent(event.timestamp)
                refreshCoordinator.notifyNewTrackRecorded()
            } else {
                immediateResult.exceptionOrNull()?.let {
                    Log.e(TAG, "Guarded immediate persistence failed", it)
                }
            }
        }
'''
    if old not in text:
        raise SystemExit("Expected queue fallback anchor not found; refusing to patch")
    text = text.replace(old, new, 1)
    changes += 1

# 3) Manual rules are live controls. Re-evaluate current MediaSessions and active
# notifications when a rule changes, so adding/removing ALWAYS_MUSIC or NON_MUSIC
# takes effect without waiting for the player to emit another callback.
watcher_sentinel = "Re-evaluate active sources after a manual rule change"
if watcher_sentinel not in text:
    old = '''    private fun watchManualContentMarks() {
        serviceScope.launch {
            manualContentMarkDao.getAllMarks().collect { marks ->
                cachedManualContentMarks = marks
                playbackStates.values.toList().forEach { session ->
                    if (manualContentOverride(session.title, session.artist) == ContentOverrideType.VIDEO) {
                        removeRejectedSession(session.packageName, session.title, session.artist)
                    }
                }
            }
        }
    }
'''
    new = '''    private fun watchManualContentMarks() {
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
    if old not in text:
        raise SystemExit("Expected manual-mark watcher anchor not found; refusing to patch")
    text = text.replace(old, new, 1)
    changes += 1

path.write_text(text)
print(f"Applied {changes} source patch(es)")
