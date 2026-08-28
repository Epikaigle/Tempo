package me.avinas.tempo.worker

import me.avinas.tempo.data.drive.GoogleDriveService
import me.avinas.tempo.data.drive.LocalBackupStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveBackupWorkerStateTest {

    @Test
    fun driveArchiveReuseRequiresSameLogicalRun() {
        assertTrue(DriveBackupWorker.isArchiveReusable(true, 42L, "run-a", "run-a"))
        assertFalse(DriveBackupWorker.isArchiveReusable(false, 42L, "run-a", "run-a"))
        assertFalse(DriveBackupWorker.isArchiveReusable(true, 0L, "run-a", "run-a"))
        assertFalse(DriveBackupWorker.isArchiveReusable(true, 42L, null, "run-a"))
        assertFalse(DriveBackupWorker.isArchiveReusable(true, 42L, "old-period", "new-period"))
    }

    @Test
    fun localArchiveReuseRequiresSameLogicalRun() {
        assertTrue(LocalBackupWorker.isArchiveReusable(true, 42L, "run-a", "run-a"))
        assertFalse(LocalBackupWorker.isArchiveReusable(false, 42L, "run-a", "run-a"))
        assertFalse(LocalBackupWorker.isArchiveReusable(true, 0L, "run-a", "run-a"))
        assertFalse(LocalBackupWorker.isArchiveReusable(true, 42L, null, "run-a"))
        assertFalse(LocalBackupWorker.isArchiveReusable(true, 42L, "old-period", "new-period"))
    }

    @Test
    fun localRetryFileNameIsStableForOnePeriodAndChangesForTheNext() {
        val first = LocalBackupStorage.buildAutomaticBackupFileName(
            timestamp = "2099-01-01_00-00-00-000",
            idempotencyKey = "2026-08-28_23-00-00-000_run-a"
        )
        val retry = LocalBackupStorage.buildAutomaticBackupFileName(
            timestamp = "2099-12-31_23-59-59-999",
            idempotencyKey = "2026-08-28_23-00-00-000_run-a"
        )
        val nextPeriod = LocalBackupStorage.buildAutomaticBackupFileName(
            timestamp = "2099-12-31_23-59-59-999",
            idempotencyKey = "2026-08-29_23-00-00-000_run-b"
        )

        assertEquals(first, retry)
        assertNotEquals(first, nextPeriod)
    }

    @Test
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

    @Test
    fun wifiOnlyMeansValidatedActualWifi() {
        assertTrue(
            DriveBackupWorker.networkSatisfiesDrivePolicy(
                hasValidatedInternet = true,
                wifiOnly = true,
                isWifiTransport = true
            )
        )
        assertFalse(
            DriveBackupWorker.networkSatisfiesDrivePolicy(
                hasValidatedInternet = true,
                wifiOnly = true,
                isWifiTransport = false
            )
        )
        assertTrue(
            DriveBackupWorker.networkSatisfiesDrivePolicy(
                hasValidatedInternet = true,
                wifiOnly = false,
                isWifiTransport = false
            )
        )
        assertFalse(
            DriveBackupWorker.networkSatisfiesDrivePolicy(
                hasValidatedInternet = false,
                wifiOnly = false,
                isWifiTransport = true
            )
        )
    }

    @Test
    fun drive403QuotaErrorsDoNotInvalidateAuthorization() {
        assertFalse(GoogleDriveService.shouldInvalidateAuthFor403("storageQuotaExceeded"))
        assertFalse(GoogleDriveService.shouldInvalidateAuthFor403("dailyLimitExceeded"))
        assertFalse(GoogleDriveService.shouldInvalidateAuthFor403("domainPolicy"))
        assertTrue(GoogleDriveService.shouldInvalidateAuthFor403("insufficientPermissions"))
        assertTrue(GoogleDriveService.shouldInvalidateAuthFor403("appNotAuthorizedToFile"))
    }

    @Test
    fun drive403RateLimitsAreRetryableWithoutReauth() {
        assertTrue(GoogleDriveService.isRetryable403Reason("rateLimitExceeded"))
        assertTrue(GoogleDriveService.isRetryable403Reason("userRateLimitExceeded"))
        assertFalse(GoogleDriveService.isRetryable403Reason("storageQuotaExceeded"))
        assertFalse(GoogleDriveService.isRetryable403Reason("insufficientPermissions"))
    }

    @Test
    fun localWorkerStopsRetryingOneBrokenPeriodSoFuturePeriodsCanRun() {
        assertTrue(LocalBackupWorker.shouldRetryFailure(0))
        assertTrue(LocalBackupWorker.shouldRetryFailure(1))
        assertTrue(LocalBackupWorker.shouldRetryFailure(2))
        assertFalse(LocalBackupWorker.shouldRetryFailure(3))
        assertFalse(LocalBackupWorker.shouldRetryFailure(10))
    }

    @Test
    fun driveWorkerStopsRetryingOneBrokenPeriodSoFuturePeriodsCanRun() {
        assertTrue(DriveBackupWorker.shouldRetryFailure(0))
        assertTrue(DriveBackupWorker.shouldRetryFailure(1))
        assertTrue(DriveBackupWorker.shouldRetryFailure(2))
        assertFalse(DriveBackupWorker.shouldRetryFailure(3))
        assertFalse(DriveBackupWorker.shouldRetryFailure(10))
    }
}
