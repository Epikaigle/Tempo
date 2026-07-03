package me.avinas.tempo.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import me.avinas.tempo.R
import me.avinas.tempo.service.MusicTrackingService
import me.avinas.tempo.service.TrackingServiceHeartbeat
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * ServiceHealthWorker periodically checks if MusicTrackingService is running
 * and attempts to restart it if needed.
 * 
 * This is a safety net for cases where the system might kill the service
 * and fail to restart it automatically.
 */
@HiltWorker
class ServiceHealthWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ServiceHealthWorker"
        private const val WORK_NAME = "service_health_check"
        private const val NOTIFICATION_CHANNEL_ID = "service_health_worker"
        private const val NOTIFICATION_ID = 3007 // Distinct from ChallengeWorker (3005) and NotificationWorker challenge (3006)

        /**
         * Schedule periodic health checks for the tracking service.
         * Runs every 15 minutes (minimum for periodic work).
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(false) // Run even on low battery
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ServiceHealthWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already scheduled
                workRequest
            )

            Log.i(TAG, "Service health check scheduled")
        }

        /**
         * Cancel the periodic health check.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Service health check cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Running service health check")
        val componentName = ComponentName(
            applicationContext,
            MusicTrackingService::class.java
        )

        // Check if notification listener permission is granted
        if (!isNotificationListenerEnabled(componentName)) {
            Log.w(TAG, "Notification listener not enabled, cannot restart service")
            return Result.success() // Don't retry, user action needed
        }

        val heartbeat = TrackingServiceHeartbeat.snapshot(applicationContext)
        if (heartbeat.shouldRequestRebind()) {
            Log.w(TAG, "Tracking listener heartbeat stale: $heartbeat")
            if (heartbeat.shouldForceRestartAfterRebind()) {
                Log.w(TAG, "Previous rebind did not refresh heartbeat, forcing component restart")
                restartService(componentName)
            } else {
                requestListenerRebind(componentName)
            }
        } else {
            Log.d(TAG, "Tracking listener heartbeat healthy: $heartbeat")
        }

        return Result.success()
    }

    private fun isNotificationListenerEnabled(componentName: ComponentName): Boolean {
        val flat = Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        )
        
        val expectedComponent = componentName.flattenToString()
        val expectedShortComponent = componentName.flattenToShortString()

        return (flat
            ?.split(':')
            ?.any { it == expectedComponent || it == expectedShortComponent }
            == true)
    }

    private fun requestListenerRebind(componentName: ComponentName) {
        try {
            NotificationListenerService.requestRebind(componentName)
            TrackingServiceHeartbeat.markRebindRequested(applicationContext)
            Log.i(TAG, "Notification listener rebind requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request listener rebind", e)
        }
    }

    private suspend fun restartService(componentName: ComponentName) {
        try {
            // Toggle component state to force system rebind
            val pm = applicationContext.packageManager
            
            pm.setComponentEnabledSetting(
                componentName,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            
            kotlinx.coroutines.delay(100) // Use coroutine delay instead of Thread.sleep
            
            pm.setComponentEnabledSetting(
                componentName,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            
            TrackingServiceHeartbeat.markRebindRequested(applicationContext)
            Log.i(TAG, "Service restart requested via component toggle")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service", e)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Service Health Check",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Service Health Check")
            .setContentText("Monitoring music tracking service...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
