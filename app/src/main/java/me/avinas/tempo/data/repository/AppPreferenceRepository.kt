package me.avinas.tempo.data.repository

import me.avinas.tempo.data.local.dao.AppPreferenceDao
import me.avinas.tempo.data.local.dao.DetectedAppDao
import me.avinas.tempo.data.local.entities.AppPreference
import me.avinas.tempo.data.local.entities.DetectedApp
import me.avinas.tempo.data.DefaultMusicApps
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing user app preferences.
 *
 * Provides methods for:
 * - Getting enabled/blocked app lists
 * - Toggling app tracking status
 * - Adding custom apps
 * - Blocking/unblocking apps
 * - Reviewing apps rejected by the default-deny model (the detected-apps inbox)
 */
@Singleton
class AppPreferenceRepository @Inject constructor(
    private val appPreferenceDao: AppPreferenceDao,
    private val detectedAppDao: DetectedAppDao,
) {
    
    /**
     * Get all app preferences as a flow.
     */
    fun getAllApps(): Flow<List<AppPreference>> = appPreferenceDao.getAllApps()
    
    /**
     * Get enabled apps (not blocked and isEnabled=true).
     */
    fun getEnabledApps(): Flow<List<AppPreference>> = appPreferenceDao.getEnabledApps()
    
    /**
     * Get blocked apps.
     */
    fun getBlockedApps(): Flow<List<AppPreference>> = appPreferenceDao.getBlockedApps()
    
    /**
     * Get user-added apps.
     */
    fun getUserAddedApps(): Flow<List<AppPreference>> = appPreferenceDao.getUserAddedApps()
    
    /**
     * Get pre-installed apps (from default list).
     */
    fun getPreinstalledApps(): Flow<List<AppPreference>> = appPreferenceDao.getPreinstalledApps()
    
    /**
     * Get package names of enabled apps for fast lookup.
     */
    suspend fun getEnabledPackageNames(): Set<String> = 
        appPreferenceDao.getEnabledPackageNames().toSet()
    
    /**
     * Get package names of blocked apps for fast lookup.
     */
    suspend fun getBlockedPackageNames(): Set<String> = 
        appPreferenceDao.getBlockedPackageNames().toSet()
    
    /**
     * Check if an app is enabled for tracking.
     * Returns true if app exists and is enabled, false otherwise.
     */
    suspend fun isAppEnabled(packageName: String): Boolean =
        appPreferenceDao.isAppEnabled(packageName) ?: false
    
    /**
     * Check if an app is blocked.
     */
    suspend fun isAppBlocked(packageName: String): Boolean =
        appPreferenceDao.isAppBlocked(packageName) ?: false
    
    /**
     * Toggle enabled status for an app.
     */
    suspend fun setAppEnabled(packageName: String, enabled: Boolean) {
        appPreferenceDao.setEnabled(packageName, enabled)
    }
    
    /**
     * Block or unblock an app.
     * When blocking, sets enabled to false.
     * When unblocking, restores enabled to true.
     */
    suspend fun setAppBlocked(packageName: String, blocked: Boolean) {
        appPreferenceDao.setBlocked(packageName, blocked)
        if (blocked) {
            appPreferenceDao.setEnabled(packageName, false)
        } else {
            // When unblocking, restore enabled so the app actually tracks
            appPreferenceDao.setEnabled(packageName, true)
        }
    }
    
    /**
     * Add a custom app for tracking.
     */
    suspend fun addCustomApp(packageName: String, displayName: String, category: String = "MUSIC") {
        appPreferenceDao.upsert(
            AppPreference(
                packageName = packageName,
                displayName = displayName,
                isEnabled = true,
                isUserAdded = true,
                isBlocked = false,
                category = category
            )
        )
    }
    
    /**
     * Remove a user-added app.
     */
    suspend fun removeApp(packageName: String) {
        appPreferenceDao.delete(packageName)
    }
    
    /**
     * Get preference for a specific app.
     */
    suspend fun getAppPreference(packageName: String): AppPreference? =
        appPreferenceDao.getAppPreference(packageName)

    // ---- Default-deny inbox -------------------------------------------------

    /**
     * Apps that produced media Tempo refused to track because they are on no allowlist.
     * This is the review queue that makes default-deny actionable.
     */
    fun getDetectedApps(): Flow<List<DetectedApp>> = detectedAppDao.getAll()

    /**
     * Grants tracking permission to an app that was previously rejected: adds it as a
     * user-added app so it survives future curated-list changes, then clears the inbox entry.
     *
     * A hard never-track app (voice notes, dialers, recorders) is not preventable here by UI —
     * the screen does not offer Allow for those — but the policy would refuse it regardless.
     */
    suspend fun allowDetectedApp(packageName: String, displayName: String) {
        addCustomApp(packageName, displayName)
        detectedAppDao.delete(packageName)
    }

    /**
     * Blocks an app and clears any inbox entry for it.
     *
     * Works whether or not the app already has a preference row. That matters now that the app
     * list is the permission surface: the apps a user most wants to block are precisely the ones
     * Tempo has no row for (an unrecognized app it offered in the inbox, or one found by search).
     * Blocking must stick, so a row is created when missing, while an existing row keeps its
     * category and user-added flag.
     */
    suspend fun blockApp(packageName: String, displayName: String) {
        val existing = appPreferenceDao.getAppPreference(packageName)
        val blocked =
            existing?.copy(isEnabled = false, isBlocked = true)
                ?: AppPreference(
                    packageName = packageName,
                    displayName = displayName,
                    isEnabled = false,
                    isUserAdded = false,
                    isBlocked = true,
                    category = "OTHER"
                )
        appPreferenceDao.upsert(blocked)
        detectedAppDao.delete(packageName)
    }

    /** Clears every inbox entry. */
    suspend fun clearDetectedApps() {
        detectedAppDao.clear()
    }
    
    /**
     * Seed database with default apps if empty.
     * Called on first app launch or fresh install.
     *
     * Also backfills any missing default apps for existing users
     * (e.g., when new apps are added to DefaultMusicApps in a later build).
     * Uses IGNORE conflict strategy so existing user customisations are never overwritten.
     */
    suspend fun seedDefaultAppsIfNeeded() {
        val existing = appPreferenceDao.getCount()
        
        if (existing == 0) {
            // Fresh install — seed everything
            val musicPrefs = DefaultMusicApps.MUSIC_APPS.map { app ->
                AppPreference(
                    packageName = app.packageName,
                    displayName = app.displayName,
                    isEnabled = true,
                    isUserAdded = false,
                    isBlocked = false,
                    category = app.category
                )
            }
            appPreferenceDao.insertAll(musicPrefs)
            
            val blockedPrefs = DefaultMusicApps.BLOCKED_APPS.map { app ->
                AppPreference(
                    packageName = app.packageName,
                    displayName = app.displayName,
                    isEnabled = false,
                    isUserAdded = false,
                    isBlocked = true,
                    category = app.category
                )
            }
            appPreferenceDao.insertAll(blockedPrefs)
        } else {
            // Existing install — only insert apps that are genuinely missing from the DB.
            // insertAll uses OnConflictStrategy.IGNORE, so apps the user has already
            // customised (enabled, disabled, blocked) are left untouched.
            val newMusicPrefs = DefaultMusicApps.MUSIC_APPS.map { app ->
                AppPreference(
                    packageName = app.packageName,
                    displayName = app.displayName,
                    isEnabled = true,
                    isUserAdded = false,
                    isBlocked = false,
                    category = app.category
                )
            }
            appPreferenceDao.insertAll(newMusicPrefs)   // no-ops for apps already present
            
            val newBlockedPrefs = DefaultMusicApps.BLOCKED_APPS.map { app ->
                AppPreference(
                    packageName = app.packageName,
                    displayName = app.displayName,
                    isEnabled = false,
                    isUserAdded = false,
                    isBlocked = true,
                    category = app.category
                )
            }
            appPreferenceDao.insertAll(newBlockedPrefs) // no-ops for apps already present
        }
    }
}
