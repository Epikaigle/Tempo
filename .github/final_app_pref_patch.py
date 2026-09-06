from pathlib import Path

path = Path("app/src/main/java/me/avinas/tempo/service/MusicTrackingService.kt")
text = path.read_text()
changes = 0

# Ensure app preferences are loaded before any initial MediaSession/notification scan.
sentinel = "Preload app preferences before the first MediaSession/notification scan"
if sentinel not in text:
    old = '''            cachedManualContentMarks = runBlocking(Dispatchers.IO) {
                try { manualContentMarkDao.getAllSync() } catch (_: Exception) { emptyList() }
            }

            serviceScope.launch {
'''
    new = '''            cachedManualContentMarks = runBlocking(Dispatchers.IO) {
                try { manualContentMarkDao.getAllSync() } catch (_: Exception) { emptyList() }
            }

            // Preload app preferences before the first MediaSession/notification scan. Without
            // this, a statically-known music app that the user explicitly disabled could be
            // treated as enabled for a brief startup window while the async Room load runs.
            try {
                val initialApps = runBlocking(Dispatchers.IO) { appPreferenceDao.getAllSync() }
                applyAppPreferenceCache(initialApps)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload app preferences; falling back to startup defaults", e)
            }

            serviceScope.launch {
'''
    if old not in text:
        raise SystemExit("Dependency preload anchor not found")
    text = text.replace(old, new, 1)
    changes += 1

# Re-evaluate currently active notifications as soon as app preferences change.
sentinel = "Re-evaluate active notifications as well as MediaSessions"
if sentinel not in text:
    old = '''                packagesToStop.forEach(::cleanupSessionForPackage)
                withContext(Dispatchers.Main) { rescanActiveMediaSessions() }
            }
        }
    }
'''
    new = '''                packagesToStop.forEach(::cleanupSessionForPackage)
                withContext(Dispatchers.Main) {
                    rescanActiveMediaSessions()

                    // Re-evaluate active notifications as well as MediaSessions. This makes an
                    // explicit enable (including default-blocked YouTube) effective immediately
                    // even when the player does not post a new notification after the toggle.
                    try {
                        activeNotifications?.forEach { sbn ->
                            if (isMusicNotification(sbn)) processNotificationPosted(sbn)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to re-evaluate notifications after app preference change", e)
                    }
                    updateServiceLifecycle()
                }
            }
        }
    }
'''
    if old not in text:
        raise SystemExit("App preference watcher anchor not found")
    text = text.replace(old, new, 1)
    changes += 1

path.write_text(text)
print(f"Applied {changes} final hardening change(s)")
