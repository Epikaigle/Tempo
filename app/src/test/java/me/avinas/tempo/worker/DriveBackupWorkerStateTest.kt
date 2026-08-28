package me.avinas.tempo.worker

import me.avinas.tempo.data.drive.GoogleDriveService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveBackupWorkerStateTest {

    @Test
    fun driveArchiveReuseRequiresCompletedNonEmptyArchive() {
        assertTrue(DriveBackupWorker.isArchiveReusable(true, 42L, true))
        assertFalse(DriveBackupWorker.isArchiveReusable(false, 42L, true))
        assertFalse(DriveBackupWorker.isArchiveReusable(true, 0L, true))
        assertFalse(DriveBackupWorker.isArchiveReusable(true, 42L, false))
    }

    @Test
    fun localArchiveReuseRequiresCompletedNonEmptyArchive() {
        assertTrue(LocalBackupWorker.isArchiveReusable(true, 42L, true))
        assertFalse(LocalBackupWorker.isArchiveReusable(false, 42L, true))
        assertFalse(LocalBackupWorker.isArchiveReusable(true, 0L, true))
        assertFalse(LocalBackupWorker.isArchiveReusable(true, 42L, false))
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
}
