package me.avinas.tempo.data.drive

import android.util.Log
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.avinas.tempo.BuildConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Google Drive appDataFolder client dedicated to cross-device history.
 * It intentionally does not share the visible "Tempo Backups" folder used by
 * [GoogleDriveService].
 */
@Singleton
class DriveAppDataClient @Inject constructor(
    private val authManager: GoogleAuthManager
) {
    companion object {
        private const val TAG = "DriveAppDataClient"
        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val MIME_GZIP = "application/gzip"
        private const val MIME_JSON = "application/json"
        private const val DISABLE_MARKER_NAME = "tempo_history_control_v1.json"
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_DELAY_MS = 1_500L
    }

    private var driveService: Drive? = null
    private var cachedAccessToken: String? = null

    private suspend fun service(): Drive = withContext(Dispatchers.IO) {
        val token = authManager.getAccessToken()
            ?: throw DriveException.Auth("Google Drive authorization is unavailable")

        if (driveService == null || cachedAccessToken != token) {
            cachedAccessToken = token
            driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                HttpRequestInitializer { request ->
                    request.headers.authorization = "Bearer $token"
                    request.connectTimeout = 30_000
                    request.readTimeout = 30_000

                }
            )
                .setApplicationName("Tempo/${BuildConfig.VERSION_NAME}")
                .build()
        }
        driveService!!
    }

    private suspend fun <T> executeWithRetry(
        attempt: Int = 0,
        block: suspend (Drive) -> T
    ): T {
        val api = service()
        return try {
            block(api)
        } catch (e: GoogleJsonResponseException) {
            when (e.statusCode) {
                401 -> {
                    driveService = null
                    cachedAccessToken = null
                    authManager.clearPersistedAccessToken()
                    if (attempt < 1 && authManager.refreshAccessToken()) {
                        executeWithRetry(attempt + 1, block)
                    } else {
                        throw DriveException.Auth("Google Drive session expired", e)
                    }
                }
                403 -> {
                    driveService = null
                    cachedAccessToken = null
                    authManager.invalidateAuthorization()
                    authManager.clearPersistedAccessToken()
                    throw DriveException.Auth(
                        "Google Drive app-data permission is missing. Reconnect Google Drive.",
                        e
                    )
                }
                429, 500, 502, 503, 504 -> retryTransient(attempt, e, block)
                else -> throw DriveException.Server("Drive error ${e.statusCode}: ${e.message}", e)
            }
        } catch (e: IOException) {
            retryTransient(attempt, e, block)
        }
    }

    private suspend fun <T> retryTransient(
        attempt: Int,
        error: Exception,
        block: suspend (Drive) -> T
    ): T {
        if (attempt >= MAX_RETRIES) {
            if (error is IOException) throw DriveException.Network("Drive network request failed", error)
            throw DriveException.Server("Drive is temporarily unavailable", error)
        }
        val delayMs = RETRY_BASE_DELAY_MS * (1L shl attempt)
        Log.w(TAG, "Transient Drive failure; retrying in ${delayMs}ms", error)
        delay(delayMs)
        driveService = null
        cachedAccessToken = null
        return executeWithRetry(attempt + 1, block)
    }

    /**
     * Create an immutable history batch unless the same deterministic filename is
     * already present. The existence check makes the operation idempotent across
     * the dangerous window where Drive accepted an upload but the process died
     * before Tempo could advance its local cursor.
     */
    suspend fun uploadHistoryBatch(
        fileName: String,
        compressedBytes: ByteArray,
        appProperties: Map<String, String>
    ): DriveAppDataFile = withContext(Dispatchers.IO) {
        require(compressedBytes.size <= DriveHistoryProtocol.MAX_COMPRESSED_BYTES) {
            "Tempo Drive history batch exceeds the compressed size limit"
        }
        executeWithRetry { api ->
            findByExactName(api, fileName)?.let { existing ->
                Log.d(TAG, "History batch already exists; treating retry as success: $fileName")
                return@executeWithRetry existing.toAppDataFile()
            }

            val metadata = DriveFile().apply {
                name = fileName
                parents = listOf(APP_DATA_FOLDER)
                this.appProperties = appProperties
            }
            val result = api.files()
                .create(metadata, ByteArrayContent(MIME_GZIP, compressedBytes))
                .setFields("id,name,size,createdTime,modifiedTime,appProperties")
                .execute()
            result.toAppDataFile()
        }
    }

    /**
     * Lists history batches. Passing [createdAfterMillis] keeps steady-state sync
     * cheap while a new device can pass null to discover the full history.
     */
    suspend fun listHistoryBatches(createdAfterMillis: Long? = null): List<DriveAppDataFile> =
        withContext(Dispatchers.IO) {
            executeWithRetry { api ->
                val clauses = mutableListOf(
                    "name contains '${DriveHistoryProtocol.FILE_PREFIX}'",
                    "trashed = false"
                )
                if (createdAfterMillis != null && createdAfterMillis > 0L) {
                    clauses += "createdTime > '${Instant.ofEpochMilli(createdAfterMillis)}'"
                }
                val query = clauses.joinToString(" and ")
                val files = mutableListOf<DriveAppDataFile>()
                var pageToken: String? = null
                do {
                    val result = api.files().list()
                        .setSpaces(APP_DATA_FOLDER)
                        .setQ(query)
                        .setOrderBy("createdTime asc")
                        .setPageSize(1_000)
                        .setPageToken(pageToken)
                        .setFields("nextPageToken,files(id,name,size,createdTime,modifiedTime,appProperties)")
                        .execute()
                    result.files.orEmpty().forEach { files += it.toAppDataFile() }
                    pageToken = result.nextPageToken
                } while (!pageToken.isNullOrBlank())
                files
            }
        }

    suspend fun download(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        executeWithRetry { api ->
            val output = BoundedByteArrayOutputStream(DriveHistoryProtocol.MAX_COMPRESSED_BYTES)
            api.files().get(fileId).executeMediaAndDownloadTo(output)
            output.toByteArray()
        }
    }

    /**
     * Server-side version of the shared deletion marker. Clients persist the
     * marker version they explicitly accepted when enabling sync. If another
     * client later updates this marker, stale clients know a global delete was
     * requested and must stop before uploading anything new.
     */
    suspend fun getHistoryDisableMarkerVersion(): Long = withContext(Dispatchers.IO) {
        executeWithRetry { api ->
            val marker = findByExactName(api, DISABLE_MARKER_NAME) ?: return@executeWithRetry 0L
            marker.modifiedTime?.value?.takeIf { it > 0L }
                ?: throw DriveException.Server("Google Drive did not return a valid deletion marker version")
        }
    }

    /**
     * Create or update the single shared deletion marker and return Google's
     * server-side modifiedTime. Using Drive's timestamp avoids device-clock skew.
     */
    suspend fun bumpHistoryDisableMarker(): Long = withContext(Dispatchers.IO) {
        executeWithRetry { api ->
            val payload = JSONObject()
                .put("schema_version", 1)
                .put("history_sync_disabled", true)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val media = ByteArrayContent(MIME_JSON, payload)
            val existing = findByExactName(api, DISABLE_MARKER_NAME)
            val result = if (existing != null) {
                api.files()
                    .update(
                        existing.id,
                        DriveFile().apply {
                            appProperties = mapOf("tempo_kind" to "history_sync_control")
                        },
                        media
                    )
                    .setFields("id,name,size,createdTime,modifiedTime,appProperties")
                    .execute()
            } else {
                api.files()
                    .create(
                        DriveFile().apply {
                            name = DISABLE_MARKER_NAME
                            parents = listOf(APP_DATA_FOLDER)
                            appProperties = mapOf("tempo_kind" to "history_sync_control")
                        },
                        media
                    )
                    .setFields("id,name,size,createdTime,modifiedTime,appProperties")
                    .execute()
            }
            result.modifiedTime?.value?.takeIf { it > 0L }
                ?: throw DriveException.Server("Google Drive did not return a valid deletion marker version")
        }
    }

    /** Best-effort single-file delete retained for non-destructive callers. */
    suspend fun delete(fileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            deleteStrict(fileId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete appData file $fileId", e)
            false
        }
    }

    /**
     * Delete only batches from generations older than [generation]. Old clients
     * and pre-upgrade files have no generation property and are treated as 0.
     * This is the key race guard: after a user deliberately re-enables sync and
     * starts publishing generation N, a stale device honoring the deletion marker
     * may clean generation < N but can never erase the newly seeded generation N.
     */
    suspend fun deleteHistoryBatchesBeforeGeneration(generation: Long): Int =
        withContext(Dispatchers.IO) {
            require(generation >= 0L) { "Drive history generation cannot be negative" }
            val files = listHistoryBatches()
            var deleted = 0
            for (file in files) {
                val fileGeneration = file.appProperties[DriveHistoryProtocol.APP_PROPERTY_GENERATION]
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0L)
                    ?: 0L
                if (fileGeneration >= generation) continue
                deleteStrict(file.fileId)
                deleted++
            }
            deleted
        }

    /**
     * Legacy/global cleanup retained for diagnostics and migration tooling. New
     * deletion-marker flows must use generation-aware cleanup above.
     */
    suspend fun deleteAllHistoryBatches(): Int = withContext(Dispatchers.IO) {
        val files = listHistoryBatches()
        var deleted = 0
        for (file in files) {
            deleteStrict(file.fileId)
            deleted++
        }
        deleted
    }

    private suspend fun deleteStrict(fileId: String) {
        try {
            executeWithRetry { api -> api.files().delete(fileId).execute() }
        } catch (e: DriveException.Server) {
            // Another linked client may have deleted the same immutable batch.
            if (!e.message.orEmpty().contains("Drive error 404")) throw e
        }
    }

    fun clearCache() {
        driveService = null
        cachedAccessToken = null
    }

    private fun findByExactName(api: Drive, fileName: String): DriveFile? {
        val safeName = fileName
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        return api.files().list()
            .setSpaces(APP_DATA_FOLDER)
            .setQ("name = '$safeName' and trashed = false")
            .setPageSize(1)
            .setFields("files(id,name,size,createdTime,modifiedTime,appProperties)")
            .execute()
            .files
            .orEmpty()
            .firstOrNull()
    }

    private fun DriveFile.toAppDataFile(): DriveAppDataFile = DriveAppDataFile(
        fileId = id,
        fileName = name,
        sizeBytes = getSize()?.toLong() ?: 0L,
        createdAt = createdTime?.value ?: 0L,
        modifiedAt = modifiedTime?.value ?: 0L,
        appProperties = appProperties.orEmpty()
    )

    /**
     * ByteArrayOutputStream with a hard cap so a corrupt or unexpectedly large
     * remote file cannot allocate unbounded heap before protocol validation runs.
     */
    private class BoundedByteArrayOutputStream(
        private val maxBytes: Int
    ) : ByteArrayOutputStream() {
        private fun checkAdditional(additional: Int) {
            require(additional >= 0 && count <= maxBytes - additional) {
                "Tempo Drive history batch exceeds the compressed size limit"
            }
        }

        override fun write(b: Int) {
            checkAdditional(1)
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            checkAdditional(len)
            super.write(b, off, len)
        }
    }
}

data class DriveAppDataFile(
    val fileId: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val modifiedAt: Long,
    val appProperties: Map<String, String>
)