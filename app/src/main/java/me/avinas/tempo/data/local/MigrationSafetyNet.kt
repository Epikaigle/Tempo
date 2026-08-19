package me.avinas.tempo.data.local

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Pre-migration safety net for the schema-52 reconciliation release (v4.8.3).
 *
 * Context: two divergent schema-51 lineages exist in the wild (public 4.8.2 and
 * internal builds). MIGRATION_51_52 reconciles them, but the app manifest sets
 * android:allowBackup="false", so for most users the on-device database is the
 * ONLY copy of their listening history.
 *
 * This net takes a one-time raw file snapshot of the database BEFORE Room ever
 * opens/migrates it on this install. If the migration were to fail on some
 * unforeseen third schema shape, the untouched pre-migration files remain on
 * disk for manual recovery (rename back to tempo.db — the previous version can
 * open it again).
 *
 * Design constraints:
 * - Never opens the database through SQLite/Room (no WAL recovery side effects,
 *   no locks): it only copies files.
 * - Runs at most once per target schema version per install (flagged in
 *   SharedPreferences), so there is no per-launch cost.
 * - Any failure here is logged and swallowed: the safety net must never itself
 *   block app startup.
 */
object MigrationSafetyNet {

    private const val TAG = "MigrationSafetyNet"
    private const val PREFS_NAME = "migration_safety_net"
    private const val KEY_SNAPSHOT_DONE_FOR_VERSION = "snapshot_done_for_version"

    /** The schema version this safety net guards. Bump when the next risky migration ships. */
    private const val GUARDED_VERSION = 52

    /** Raw suffixes Room/SQLite may have created next to the main database file. */
    private val DB_FILE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

    fun snapshotBeforeMigrationIfNeeded(context: Context) {
        try {
            val dbFile = context.getDatabasePath("tempo.db")
            if (!dbFile.exists()) {
                // Fresh install — nothing to protect.
                return
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_SNAPSHOT_DONE_FOR_VERSION, 0) >= GUARDED_VERSION) {
                // Already snapshotted for this schema step.
                return
            }

            val stamp = java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss", java.util.Locale.US
            ).format(java.util.Date())

            var copied = 0
            for (suffix in DB_FILE_SUFFIXES) {
                val source = File(dbFile.parentFile, "tempo.db$suffix")
                if (!source.exists()) continue
                val target = File(dbFile.parentFile, "tempo.db.pre$GUARDED_VERSION-$stamp$suffix")
                try {
                    source.copyTo(target, overwrite = false)
                    copied++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to snapshot ${source.name}", e)
                }
            }

            // Mark done regardless of partial success: retrying every launch would
            // stack snapshots; a partial snapshot is still better than none.
            prefs.edit().putInt(KEY_SNAPSHOT_DONE_FOR_VERSION, GUARDED_VERSION).apply()

            if (copied > 0) {
                Log.i(TAG, "Pre-migration safety snapshot taken ($copied file(s)) before opening schema $GUARDED_VERSION")
            }
        } catch (e: Exception) {
            // Absolutely never block startup because of the safety net itself.
            Log.w(TAG, "Safety snapshot skipped due to error", e)
        }
    }
}
