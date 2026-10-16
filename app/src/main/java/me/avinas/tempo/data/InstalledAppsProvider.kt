package me.avinas.tempo.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** An app on the device, resolved from PackageManager. */
data class InstalledApp(
    val packageName: String,
    val displayName: String
)

/**
 * Resolves the launchable apps on the device, with labels, and remembers the result.
 *
 * ## Why this is a singleton
 *
 * `ResolveInfo.loadLabel()` is not a cheap call: it opens the target app's resources to read its
 * label, so resolving every launchable app costs one asset/resource load per app — the very thing
 * the Add-App dialog's comment warns can block for seconds on app-heavy devices. Manage Apps needs
 * the list to search it and to name apps in the detected-apps inbox, and it was doing that work
 * on every visit to the screen. Memoising keeps it to one scan per process per few minutes.
 *
 * The TTL bounds staleness: install or uninstall an app and it is reflected within
 * [CACHE_TTL_MS] without paying the scan on every screen open.
 */
@Singleton
class InstalledAppsProvider
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: List<InstalledApp>? = null

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * Launchable apps with their labels, most-recently-scanned first.
     *
     * Safe to call from any dispatcher; callers should still treat it as IO and call it from a
     * background context, since a cold call does package-manager and resource work.
     */
    suspend fun get(): List<InstalledApp> {
        val snapshot = cached
        if (snapshot != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return snapshot
        }

        return mutex.withLock {
            // Another caller may have refreshed while this one waited for the lock.
            val current = cached
            if (current != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                return@withLock current
            }
            scan().also {
                cached = it
                cachedAt = System.currentTimeMillis()
            }
        }
    }

    /** Drops the cached list, e.g. after the user installs something. */
    fun invalidate() {
        cached = null
        cachedAt = 0L
    }

    private fun scan(): List<InstalledApp> =
        try {
            val packageManager = context.packageManager
            val launcherIntent =
                android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                }
            packageManager
                .queryIntentActivities(launcherIntent, 0)
                .mapNotNull { resolveInfo ->
                    try {
                        InstalledApp(
                            packageName = resolveInfo.activityInfo.packageName,
                            displayName = resolveInfo.loadLabel(packageManager).toString()
                        )
                    } catch (_: Exception) {
                        null // one bad app must not kill the whole list
                    }
                }
                .distinctBy { it.packageName }
                .sortedBy { it.displayName.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }

    companion object {
        /**
         * How long a scan stays fresh. Long enough that browsing and searching Manage Apps is
         * free, short enough that a freshly installed app shows up without restarting Tempo.
         */
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
