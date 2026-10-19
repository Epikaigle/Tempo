package me.avinas.tempo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import me.avinas.tempo.service.MusicTrackingService
import me.avinas.tempo.utils.BatteryUtils

/**
 * Listens for battery state changes and notifies [MusicTrackingService] to resume tracking when
 * battery recovers above the low-battery threshold. Handles both [Intent.ACTION_BATTERY_OKAY] and
 * [Intent.ACTION_BATTERY_CHANGED], acting only on upward threshold crossings so a fine-grained
 * level update cannot cause constant wake-ups.
 *
 * Registered dynamically inside [MusicTrackingService], so it is only active while the service
 * runs and needs no manifest entry.
 *
 * [Intent.ACTION_BATTERY_CHANGED] is sticky: [Context.registerReceiver] delivers it *immediately*
 * with the last known status, even when the level has not changed. Without a guard that fires a
 * spurious "battery recovered" dispatch on every service start, so the [markReady] call (made by
 * [MusicTrackingService] right after registration) signals that the initial delivery has been
 * absorbed and later deliveries are genuine level changes.
 */
class BatteryStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BatteryStateReceiver"
    }

    /** True once the first sticky [Intent.ACTION_BATTERY_CHANGED] delivery has been absorbed. */
    @Volatile
    private var isReady: Boolean = false

    fun markReady() {
        isReady = true
        Log.d(TAG, "BatteryStateReceiver is now armed for threshold-crossing detection")
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BATTERY_OKAY -> {
                // Not sticky, so no spurious-delivery guard is needed here.
                Log.i(TAG, "ACTION_BATTERY_OKAY received — instructing service to resume tracking scan")
                BatteryUtils.invalidateCache()
                notifyService(context, MusicTrackingService.ACTION_BATTERY_RECOVERED)
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                // Sticky: the first delivery on registration is the current level, not a change.
                if (!isReady) {
                    Log.d(TAG, "Ignoring initial sticky ACTION_BATTERY_CHANGED on registration")
                    return
                }

                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level < 0 || scale <= 0) return

                val pct = level * 100 / scale
                // Only act when we cross from low → normal to avoid constant noise.
                // Read wasLow from the CACHED value (pre-invalidation) then refresh.
                if (pct > BatteryUtils.CRITICAL_BATTERY_LEVEL) {
                    val wasLow = BatteryUtils.isCriticalBattery(context) // uses cache
                    if (wasLow) {
                        BatteryUtils.invalidateCache()
                        val isNowOkay = !BatteryUtils.isCriticalBattery(context, forceRefresh = true)
                        if (isNowOkay) {
                            Log.i(TAG, "Battery recovered ($pct%) — instructing service to resume tracking scan")
                            notifyService(context, MusicTrackingService.ACTION_BATTERY_RECOVERED)
                        }
                    }
                }
            }
        }
    }

    /**
     * The service is already running in the foreground, so this delivers the action intent rather
     * than starting anything from the background.
     */
    private fun notifyService(context: Context, action: String) {
        val intent = Intent(context, MusicTrackingService::class.java).apply {
            this.action = action
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // Service may not be running; that's fine — it will check battery on next wake
            Log.w(TAG, "Could not deliver $action to MusicTrackingService: ${e.message}")
        }
    }
}
