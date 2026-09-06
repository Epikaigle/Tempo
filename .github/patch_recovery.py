from pathlib import Path

path = Path("app/src/main/java/me/avinas/tempo/service/MusicTrackingService.kt")
text = path.read_text()
sentinel = "Skipping recovered session from disabled/blocked app"

if sentinel in text:
    print("Recovery guard already present")
    raise SystemExit(0)

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

path.write_text(text.replace(old, new, 1))
print("Recovery guard applied")
