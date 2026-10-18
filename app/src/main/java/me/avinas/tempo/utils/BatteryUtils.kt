package me.avinas.tempo.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Battery level and charging checks, used by Desktop Sync. The cached level is refreshed at
 * most once per [BATTERY_CHECK_INTERVAL_MS].
 */
object BatteryUtils {
    private const val TAG = "BatteryUtils"
    
    // Battery level thresholds
    const val CRITICAL_BATTERY_LEVEL = 20 // Don't sync when battery <= 20%
    const val LOW_BATTERY_LEVEL = 30      // Warn when battery <= 30%
    
    private var lastBatteryCheckTime = 0L
    @Volatile
    private var cachedBatteryLevel = 100
    private const val BATTERY_CHECK_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
    
    /**
     * Current battery level as a percentage (0-100), or 100 when it cannot be read. Cached for
     * 2 minutes unless `forceRefresh` is set.
     */
    fun getBatteryLevel(context: Context, forceRefresh: Boolean = false): Int {
        val now = System.currentTimeMillis()
        if (forceRefresh || now - lastBatteryCheckTime > BATTERY_CHECK_INTERVAL_MS) {
            try {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                cachedBatteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                lastBatteryCheckTime = now
                Log.d(TAG, "Battery level: $cachedBatteryLevel%")
            } catch (e: Exception) {
                Log.e(TAG, "Error checking battery level", e)
                cachedBatteryLevel = 100 // Assume full battery if unable to check
            }
        }
        return cachedBatteryLevel
    }
    
    /** True at or below [CRITICAL_BATTERY_LEVEL]: Desktop Sync must not run at that level. */
    fun isCriticalBattery(context: Context, forceRefresh: Boolean = false): Boolean {
        return getBatteryLevel(context, forceRefresh) <= CRITICAL_BATTERY_LEVEL
    }
    
    /** True at or below [LOW_BATTERY_LEVEL]; used for warnings and to throttle battery-heavy work. */
    fun isLowBattery(context: Context, forceRefresh: Boolean = false): Boolean {
        return getBatteryLevel(context, forceRefresh) <= LOW_BATTERY_LEVEL
    }
    
    fun isHealthyBattery(context: Context, forceRefresh: Boolean = false): Boolean {
        return getBatteryLevel(context, forceRefresh) > LOW_BATTERY_LEVEL
    }
    
    /** True while charging from any source (AC, USB or wireless). */
    fun isCharging(context: Context): Boolean {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return false
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            Log.e(TAG, "Error checking charging status", e)
            false
        }
    }
    
    /**
     * Force refresh battery cache (for testing or immediate updates).
     */
    fun invalidateCache() {
        lastBatteryCheckTime = 0
    }
}
