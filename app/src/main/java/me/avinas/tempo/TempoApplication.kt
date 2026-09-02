package me.avinas.tempo

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.avinas.tempo.data.drive.BackupInterval
import me.avinas.tempo.data.drive.BackupSettingsManager
import me.avinas.tempo.data.drive.LocalBackupStorage
import me.avinas.tempo.data.local.dao.UserKnownArtistDao
import me.avinas.tempo.data.local.dao.UserPreferencesDao
import me.avinas.tempo.utils.ArtistParser
import me.avinas.tempo.worker.ChallengeWorker
import me.avinas.tempo.worker.DriveBackupWorker
import me.avinas.tempo.worker.EnrichmentWorker
import me.avinas.tempo.worker.LocalBackupWorker
import me.avinas.tempo.worker.ServiceHealthWorker
import me.avinas.tempo.worker.SpotifyPollingWorker
import me.avinas.tempo.worker.SpotlightUnlockWorker
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Application class configuring Hilt DI, WorkManager HiltWorkerFactory,
 * and Coil SingletonImageLoader.
 */
@HiltAndroidApp
class TempoApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var userPreferencesDao: UserPreferencesDao

    @Inject
    lateinit var userKnownArtistDao: UserKnownArtistDao

    @Inject
    lateinit var artistRepairService: me.avinas.tempo.data.repository.ArtistRepairService

    @Inject
    lateinit var listColumnRepairService: me.avinas.tempo.data.repository.ListColumnRepairService

    @Inject
    lateinit var backupSettingsManager: BackupSettingsManager

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return imageLoader
    }

    override fun onCreate() {
        super.onCreate()

        loadUserKnownArtists()
        reconcileAutomaticBackupSchedules()
        scheduleBackgroundWorkDeferred()
    }

    private fun loadUserKnownArtists() {
        applicationScope.launch {
            try {
                val names = userKnownArtistDao.getAllNormalizedNames().toSet()
                ArtistParser.loadUserKnownBands(names)

                // One-time data repair; no-ops once applied (versioned flag).
                try {
                    artistRepairService.runRepairIfNeeded()
                } catch (e: Exception) {
                    android.util.Log.w("TempoApplication", "Artist repair invocation failed", e)
                }

                // One-time repair of legacy JSON-array values in list columns
                try {
                    listColumnRepairService.runRepairIfNeeded()
                } catch (e: Exception) {
                    android.util.Log.w("TempoApplication", "List column repair invocation failed", e)
                }
            } catch (e: Exception) {
                android.util.Log.w("TempoApplication", "Failed to load user known artists", e)
            }
        }
    }

    /**
     * Reconcile persisted automatic-backup settings on every process start.
     *
     * This is important when upgrading from the old combined Drive/local worker:
     * users who already selected Daily/Weekly/Monthly must receive the dedicated
     * LocalBackupWorker without having to revisit the Backup & Restore screen.
     * ExistingPeriodicWorkPolicy.UPDATE keeps healthy periodic cadence while also
     * repairing a missing WorkManager registration after an app/process restart.
     */
    private fun reconcileAutomaticBackupSchedules() {
        applicationScope.launch {
            try {
                val settings = backupSettingsManager.settings.first()

                if (settings.backupInterval == BackupInterval.MANUAL) {
                    LocalBackupWorker.cancel(this@TempoApplication)
                    DriveBackupWorker.cancel(this@TempoApplication)
                    return@launch
                }

                // Only the persisted SAF write grant is checked here. A valid
                // external/SD-card provider can be temporarily unavailable during
                // boot; do not erase the user's folder choice merely because the
                // provider cannot be queried at this exact moment.
                if (LocalBackupStorage.hasSelectedDirectory(this@TempoApplication)) {
                    LocalBackupWorker.schedule(
                        this@TempoApplication,
                        settings.backupInterval.hours
                    )
                } else {
                    if (LocalBackupStorage.getSelectedDirectoryUri(this@TempoApplication) != null) {
                        LocalBackupStorage.clearSelectedDirectory(this@TempoApplication)
                    }
                    LocalBackupWorker.cancel(this@TempoApplication)
                }

                if (settings.isGoogleDriveEnabled) {
                    DriveBackupWorker.schedule(
                        this@TempoApplication,
                        settings.backupInterval.hours,
                        settings.wifiOnly
                    )
                } else {
                    DriveBackupWorker.cancel(this@TempoApplication)
                }
            } catch (e: Exception) {
                // Backup schedule repair is best-effort and must never prevent the
                // application process from starting.
                android.util.Log.w(
                    "TempoApplication",
                    "Failed to reconcile automatic backup schedules",
                    e
                )
            }
        }
    }

    private fun scheduleBackgroundWorkDeferred() {
        backgroundExecutor.execute {
            Thread.sleep(500)
            Handler(Looper.getMainLooper()).post {
                scheduleBackgroundWork()
            }
        }
    }

    private fun scheduleBackgroundWork() {
        // Self-heal: if a previous listener-restart attempt left the tracking
        // component DISABLED (process died mid-toggle), re-enable it now so
        // tracking recovers without a reinstall.
        me.avinas.tempo.service.MusicTrackingService.ensureComponentEnabled(this)

        ServiceHealthWorker.schedule(this)

        EnrichmentWorker.schedulePeriodic(this)

        EnrichmentWorker.enqueueImmediate(this)

        me.avinas.tempo.worker.GamificationWorker.enqueuePeriodicRefresh(this)
        me.avinas.tempo.worker.GamificationWorker.enqueueImmediateRefresh(this)

        scheduleSpotifyPollingIfEnabled()

        Handler(Looper.getMainLooper()).postDelayed({
            SpotlightUnlockWorker.scheduleWeekly(this)
            ChallengeWorker.scheduleDaily(this)
        }, 30_000L)
    }

    /**
     * Check if Spotify-API-Only mode is enabled and schedule polling if so.
     * This ensures the worker resumes after app restart.
     */
    private fun scheduleSpotifyPollingIfEnabled() {
        applicationScope.launch {
            try {
                val prefs = userPreferencesDao.getSync()
                if (prefs?.spotifyApiOnlyMode == true) {
                    Handler(Looper.getMainLooper()).post {
                        SpotifyPollingWorker.schedule(this@TempoApplication)
                    }
                }
            } catch (e: Exception) {
                // Ignore - worker will be scheduled when user enables mode
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        backgroundExecutor.shutdown()
    }
}
