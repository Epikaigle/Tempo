package me.avinas.tempo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.avinas.tempo.data.local.entities.DetectedApp

/**
 * Read/write access to the default-deny "detected but not tracked" inbox.
 *
 * Writes come from [me.avinas.tempo.service.MusicTrackingService] on a background coroutine and
 * are throttled per package, so the read-modify-write in [record] never contends in practice.
 * The transaction keeps it correct anyway.
 */
@Dao
abstract class DetectedAppDao {

    @Query("SELECT * FROM detected_apps ORDER BY lastSeenAt DESC")
    abstract fun getAll(): Flow<List<DetectedApp>>

    @Query("SELECT * FROM detected_apps ORDER BY lastSeenAt DESC")
    abstract suspend fun getAllSync(): List<DetectedApp>

    @Query("SELECT * FROM detected_apps WHERE packageName = :packageName")
    abstract suspend fun get(packageName: String): DetectedApp?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(app: DetectedApp)

    @Query("DELETE FROM detected_apps WHERE packageName = :packageName")
    abstract suspend fun delete(packageName: String)

    @Query("DELETE FROM detected_apps")
    abstract suspend fun clear()

    /**
     * Records one rejection, incrementing the count for an app that has been seen before.
     *
     * Implemented as a transaction over read+upsert because the alternative — a SQLite
     * `INSERT ... ON CONFLICT DO UPDATE` — requires SQLite 3.24, which only ships from
     * Android 11 (API 30). Tempo supports API 26.
     */
    @Transaction
    open suspend fun record(
        packageName: String,
        title: String?,
        artist: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        val existing = get(packageName)
        upsert(
            DetectedApp(
                packageName = packageName,
                lastTitle = title?.takeIf { it.isNotBlank() } ?: existing?.lastTitle,
                lastArtist = artist?.takeIf { it.isNotBlank() } ?: existing?.lastArtist,
                detectionCount = (existing?.detectionCount ?: 0) + 1,
                firstSeenAt = existing?.firstSeenAt ?: now,
                lastSeenAt = now,
            ),
        )
    }
}
