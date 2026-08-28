from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"pattern not found: {label}")
    return text.replace(old, new, 1)


worker = Path("app/src/main/java/me/avinas/tempo/worker/DriveBackupWorker.kt")
text = worker.read_text()

old_helper = '''        internal fun networkSatisfiesDrivePolicy(
            hasValidatedInternet: Boolean,
            wifiOnly: Boolean,
            isWifiTransport: Boolean
        ): Boolean = hasValidatedInternet && (!wifiOnly || isWifiTransport)
'''
new_helper = old_helper + '''
        internal fun shouldSkipBackup(
            backupInterval: BackupInterval,
            driveEnabled: Boolean,
            isManualRequest: Boolean
        ): Boolean =
            !driveEnabled ||
                (backupInterval == BackupInterval.MANUAL && !isManualRequest)
'''
text = replace_once(text, old_helper, new_helper, "Drive skip helper")

old_guard = '''        // A worker already running/retrying may observe Manual/sign-out before
        // scheduler cancellation reaches it. Finish quietly instead of creating
        // another automatic backup with stale settings.
        if (settings.backupInterval == BackupInterval.MANUAL ||
            !settings.isGoogleDriveEnabled
        ) {
            cleanupRunState()
            return@withContext Result.success()
        }
'''
new_guard = '''        // A periodic worker already running/retrying may observe Manual/sign-out
        // before scheduler cancellation reaches it. A one-time manual request uses
        // this same worker class, so Manual must suppress only the periodic path.
        val isManualRequest = tags.contains(MANUAL_WORK_NAME)
        if (shouldSkipBackup(
                backupInterval = settings.backupInterval,
                driveEnabled = settings.isGoogleDriveEnabled,
                isManualRequest = isManualRequest
            )
        ) {
            cleanupRunState()
            return@withContext Result.success()
        }
'''
text = replace_once(text, old_guard, new_guard, "Drive Manual guard")
worker.write_text(text)


tests = Path("app/src/test/java/me/avinas/tempo/worker/DriveBackupWorkerStateTest.kt")
test_text = tests.read_text()
marker = '''    @Test
    fun wifiOnlyMeansValidatedActualWifi() {
'''
addition = '''    @Test
    fun manualIntervalSkipsPeriodicDriveButNotExplicitOneTimeDrive() {
        assertTrue(
            DriveBackupWorker.shouldSkipBackup(
                backupInterval = me.avinas.tempo.data.drive.BackupInterval.MANUAL,
                driveEnabled = true,
                isManualRequest = false
            )
        )
        assertFalse(
            DriveBackupWorker.shouldSkipBackup(
                backupInterval = me.avinas.tempo.data.drive.BackupInterval.MANUAL,
                driveEnabled = true,
                isManualRequest = true
            )
        )
        assertFalse(
            DriveBackupWorker.shouldSkipBackup(
                backupInterval = me.avinas.tempo.data.drive.BackupInterval.WEEKLY,
                driveEnabled = true,
                isManualRequest = false
            )
        )
        assertTrue(
            DriveBackupWorker.shouldSkipBackup(
                backupInterval = me.avinas.tempo.data.drive.BackupInterval.WEEKLY,
                driveEnabled = false,
                isManualRequest = true
            )
        )
    }

'''
if addition not in test_text:
    test_text = replace_once(test_text, marker, addition + marker, "manual Drive worker regression test")
tests.write_text(test_text)
