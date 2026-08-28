package me.avinas.tempo.data.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists automatic backup archives in a directory explicitly chosen by the user.
 *
 * The directory is selected with Android's Storage Access Framework. The picker
 * activity takes a persistable URI permission so WorkManager can continue writing
 * backups while Tempo is in the background or after a process restart.
 */
object LocalBackupStorage {
    private const val TAG = "LocalBackupStorage"
    private const val PREFS_NAME = "local_backup_storage"
    private const val DIRECTORY_URI_KEY = "directory_uri"
    private const val FILE_PREFIX = "tempo_auto_backup_"
    private const val MIME_TYPE_ZIP = "application/zip"
    private const val MAX_BACKUPS = 5

    fun getSelectedDirectoryUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(DIRECTORY_URI_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)

    fun hasSelectedDirectory(context: Context): Boolean {
        val uri = getSelectedDirectoryUri(context) ?: return false
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isWritePermission
        }
    }

    /**
     * True only when the persisted tree permission still resolves to a real
     * directory. A stale URI can remain in persistedUriPermissions after the
     * folder/provider disappears, so permission alone is not enough.
     */
    fun isSelectedDirectoryAccessible(context: Context): Boolean {
        val treeUri = getSelectedDirectoryUri(context) ?: return false
        if (!hasSelectedDirectory(context)) return false

        return try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val directoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE)
            context.contentResolver.query(directoryUri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Selected automatic backup folder is not accessible", e)
            false
        }
    }

    fun clearSelectedDirectory(context: Context) {
        val uri = getSelectedDirectoryUri(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(DIRECTORY_URI_KEY)
            .apply()

        if (uri != null) {
            val permissionFlags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.releasePersistableUriPermission(uri, permissionFlags)
            }.onFailure { error ->
                Log.w(TAG, "Unable to release stale automatic backup folder permission", error)
            }
        }
    }

    fun setSelectedDirectory(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(DIRECTORY_URI_KEY, uri.toString())
            .apply()
    }

    internal fun buildAutomaticBackupFileName(
        timestamp: String,
        idempotencyKey: String?
    ): String {
        val stableKey = idempotencyKey
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(180)
            ?.takeIf { it.isNotBlank() }
        return "$FILE_PREFIX${stableKey ?: timestamp}.tempo"
    }

    fun persist(
        context: Context,
        sourceFile: File,
        idempotencyKey: String? = null
    ): String {
        require(sourceFile.exists() && sourceFile.length() > 0L) {
            "Backup source file is missing or empty"
        }

        val treeUri = getSelectedDirectoryUri(context)
            ?: throw IOException("No automatic local backup folder has been selected")

        val hasWritePermission = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isWritePermission
        }
        if (!hasWritePermission) {
            throw IOException("Access to the selected automatic backup folder has expired")
        }
        if (!isSelectedDirectoryAccessible(context)) {
            throw IOException("The selected automatic backup folder is no longer available")
        }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        val fileName = buildAutomaticBackupFileName(timestamp, idempotencyKey)

        return persistToTree(context, treeUri, sourceFile, fileName)
    }

    private fun persistToTree(
        context: Context,
        treeUri: Uri,
        sourceFile: File,
        fileName: String
    ): String {
        val resolver = context.contentResolver
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            throw IOException("The selected backup folder is no longer available", e)
        }

        val directoryUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)

        // A worker retry must not create a second file if the previous process was
        // killed after the copy completed but before WorkManager recorded success.
        // The worker supplies a stable per-period file name, so exact-name lookup
        // gives the local destination the same idempotency guarantee as Drive.
        val existing = findExistingBackup(context, treeUri, treeDocumentId, fileName)
        if (existing != null) {
            val (existingUri, existingSize) = existing
            if (existingSize == sourceFile.length()) {
                cleanupOldBackups(context, treeUri, treeDocumentId)
                Log.i(TAG, "Reusing already-saved automatic local backup: $existingUri")
                return existingUri.toString()
            }

            val deleted = runCatching {
                DocumentsContract.deleteDocument(resolver, existingUri)
            }.getOrDefault(false)
            if (!deleted) {
                throw IOException("Unable to replace an incomplete automatic backup file")
            }
        }

        val outputUri = DocumentsContract.createDocument(
            resolver,
            directoryUri,
            MIME_TYPE_ZIP,
            fileName
        ) ?: throw IOException("Unable to create a backup file in the selected folder")

        try {
            val output = resolver.openOutputStream(outputUri, "w")
                ?: throw IOException("Unable to open the selected backup file for writing")

            output.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            cleanupOldBackups(context, treeUri, treeDocumentId)
            Log.i(TAG, "Saved automatic local backup: $outputUri")
            return outputUri.toString()
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, outputUri) }
            throw e
        }
    }

    private fun findExistingBackup(
        context: Context,
        treeUri: Uri,
        treeDocumentId: String,
        fileName: String
    ): Pair<Uri, Long?>? {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE
        )

        return try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) != fileName) continue
                    val documentId = cursor.getString(idColumn)
                    val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        cursor.getLong(sizeColumn)
                    } else {
                        null
                    }
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    return@use uri to size
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to check for an existing retry-safe local backup", e)
            null
        }
    }

    private fun cleanupOldBackups(context: Context, treeUri: Uri, treeDocumentId: String) {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        val backups = mutableListOf<Pair<String, String>>()
        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idColumn)
                    val displayName = cursor.getString(nameColumn) ?: continue
                    if (displayName.startsWith(FILE_PREFIX)) {
                        backups += documentId to displayName
                    }
                }
            }
        } catch (e: Exception) {
            // Retention cleanup is best effort. Never fail a valid backup because
            // a document provider refused to enumerate the selected directory.
            Log.w(TAG, "Unable to enumerate automatic local backups for cleanup", e)
            return
        }

        // The filename starts with an ISO-like timestamp, so lexical descending
        // order is also newest-first even when a provider omits modified dates.
        backups.sortedByDescending { it.second }
            .drop(MAX_BACKUPS)
            .forEach { (documentId, _) ->
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
                    .onFailure { error ->
                        Log.w(TAG, "Failed to delete old automatic local backup $documentId", error)
                    }
            }
    }
}
