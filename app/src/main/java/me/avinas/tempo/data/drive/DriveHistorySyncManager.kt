package me.avinas.tempo.data.drive

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.entities.ListeningEvent
import me.avinas.tempo.data.local.entities.ListeningEventOrigin
import me.avinas.tempo.data.local.entities.ListeningEventOrigin
import me.avinas.tempo.data.repository.TrackResolver
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bidirectional cross-device history sync through Google Drive appDataFolder.
 *
 * Local -> Drive uses an id cursor over Room rows and immutable compressed batch
 * files. Drive -> Local resolves tracks through Tempo's normal TrackResolver and
 * inserts listening events through the source-aware dedup pipeline.
 */
@Singleton
class DriveHistorySyncManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val trackResolver: TrackResolver,
    private val appDataClient: DriveAppDataClient,
    private val authManager: GoogleAuthManager,
    private val settingsManager: DriveHistorySyncSettingsManager
) {
    companion object {
        private const val TAG = "DriveHistorySync"
        private const val STATE_PREFS = "drive_history_sync_state"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_UPLOAD_CURSOR = "upload_cursor"
        private const val KEY_DOWNLOAD_CREATED_CURSOR = "download_created_cursor"
        private const val KEY_ACCEPTED_DISABLE_VERSION = "accepted_disable_marker_version"
        private const val KEY_GOOGLE_ACCOUNT_EMAIL = "google_account_email"
        private const val PAGE_SIZE = 200
        private const val BATCH_SIZE = 50
        private const val DOWNLOAD_OVERLAP_MS = 24L * 60L * 60L * 1000L
        private const val IMPORT_FINGERPRINT_PREFIX = "drive:v1:"

        /**
         * Original identity of a LAN-delivered event. Only authenticated LAN
         * payloads with an intact producer ID and event fingerprint qualify.
         * Keep the original ID on Drive so relaying cannot double-count a play.
         */
        /**
         * Once an Android-native play has an origin ID, its identity must not
         * change if the user later corrects the track's title or artist.
         */
        internal fun stableLocalEventId(
            existingOriginId: String?,
            deviceId: String,
            localEventId: Long,
            timestampUtc: Long,
            title: String,
            artist: String
        ): String {
            if (existingOriginId != null) {
                require(existingOriginId.matches(Regex("^[0-9a-f]{64}$"))) {
                    "Invalid persisted Android playback origin"
                }
                return existingOriginId
            }
            return DriveHistoryProtocol.createEventId(
                deviceId = deviceId,
                localEventId = localEventId,
                timestampUtc = timestampUtc,
                title = title,
                artist = artist
            )
        }

        internal fun lanBatchProducer(source: String): BatchProducer? {
            val parts = source.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != "lan" ||
                !DriveHistoryProtocol.isValidDeviceId(parts[1]) || parts[2].isBlank()
            ) return null
            val platform = when {
                parts[2].startsWith("desktop:") -> "desktop"
                parts[2].startsWith("browser:") -> "browser"
                else -> "android"
            }
            return BatchProducer(parts[1], platform)
        }

        internal fun lanRelayIdentity(source: String, fingerprint: String?): Pair<String, String>? {
            val parts = source.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != "lan" ||
                !DriveHistoryProtocol.isValidDeviceId(parts[1]) || parts[2].isBlank()
            ) return null
            val rawFingerprint = fingerprint ?: return null
            if (!rawFingerprint.startsWith(IMPORT_FINGERPRINT_PREFIX)) return null
            val eventId = rawFingerprint.removePrefix(IMPORT_FINGERPRINT_PREFIX)
            if (!eventId.matches(Regex("^[0-9a-f]{64}$"))) return null
            return parts[2] to eventId
        }

        /**
         * Android stores the device-specific audio stream index, not a portable
         * percentage. Preserve a known mute as 0 and report non-zero indices as
         * unknown instead of misrepresenting (for example) level 8/15 as 8%.
         */
        internal fun protocolVolumeLevel(androidStreamIndex: Int?): Int? = when (androidStreamIndex) {
            0 -> 0
            else -> null
        }
    }

    private val mutex = Mutex()
    private val statePrefs by lazy {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
    }

    val deviceId: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val existing = statePrefs.getString(KEY_DEVICE_ID, null)
        if (existing != null && DriveHistoryProtocol.isValidDeviceId(existing)) return@lazy existing
        val generated = UUID.randomUUID().toString()
        check(statePrefs.edit().putString(KEY_DEVICE_ID, generated).commit()) {
            "Could not persist Tempo's Drive device identity"
        }
        generated
    }

    val deviceName: String
        get() = "Tempo Android"

    /**
     * Explicit user opt-in. A shared deletion marker is acknowledged only here,
     * never silently by a background worker. If cloud history was deleted while
     * this device was disabled/offline, reset cursors so the user's explicit
     * re-enable can intentionally seed Drive again from locally-owned history.
     */
    suspend fun enableSync(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!ensureAuthorized()) return@withContext false
            appDataClient.withAccountBoundSession { accountEmail ->
                reconcileGoogleAccountBoundary(accountEmail)
                val currentMarker = appDataClient.getHistoryDisableMarkerVersion()
                val acceptedMarker = statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L)
                if (currentMarker > acceptedMarker) {
                    resetCursorsLocked()
                }
                statePrefs.edit().putLong(KEY_ACCEPTED_DISABLE_VERSION, currentMarker).apply()
                settingsManager.setEnabled(true)
                true
            }
        }
    }

    suspend fun disableSync() = mutex.withLock {
        settingsManager.setEnabled(false)
    }

    /** Re-read all cloud batches for explicit historical recovery; never delete local plays. */
    suspend fun restoreFullHistory(): DriveHistorySyncResult = syncNow(forceFullRestore = true)

    suspend fun syncNow(forceFullRestore: Boolean = false): DriveHistorySyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val settings = settingsManager.settings.first()
            if (!settings.enabled) {
                return@withContext DriveHistorySyncResult.Disabled
            }

            settingsManager.markRunning()
            try {
                if (!ensureAuthorized()) {
                    val message = "Google Drive authorization is required"
                    settingsManager.markFailure(message)
                    return@withContext DriveHistorySyncResult.Error(message)
                }

                appDataClient.withAccountBoundSession { accountEmail ->
                    if (reconcileGoogleAccountBoundary(accountEmail)) {
                        val message =
                            "Google account changed. Cross-device sync was turned off; enable it again to use the new Drive account."
                        settingsManager.setEnabled(false)
                        settingsManager.markFailure(message)
                        return@withAccountBoundSession DriveHistorySyncResult.RemoteDisabled(message)
                    }

                    val remoteDisable = handleRemoteDisableIfNeeded()
                    if (remoteDisable != null) return@withAccountBoundSession remoteDisable

                    val uploaded = uploadLocalHistory()
                    if (forceFullRestore) {
                        // Clear only the receive cursor. If the download fails, retry
                        // again from the beginning rather than losing an old batch.
                        statePrefs.edit().remove(KEY_DOWNLOAD_CREATED_CURSOR).commit()
                            .also { check(it) { "Could not persist Drive full-restore request" } }
                    }
                    val download = downloadRemoteHistory(includeOwnDeviceBatches = forceFullRestore)
                    settingsManager.markSuccess(
                        uploaded = uploaded,
                        imported = download.inserted,
                        message = if (download.skipped > 0) {
                            "${download.skipped} duplicate event(s) ignored"
                        } else null
                    )
                    DriveHistorySyncResult.Success(
                        uploaded = uploaded,
                        imported = download.inserted,
                        duplicates = download.skipped,
                        replaced = download.replaced
                    )
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    settingsManager.markFailure("Cross-device history sync was cancelled")
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "History sync failed", e)
                val message = e.message ?: "Cross-device history sync failed"
                settingsManager.markFailure(message)
                DriveHistorySyncResult.Error(message, e)
            }
        }
    }

    private suspend fun ensureAuthorized(): Boolean {
        if (!authManager.isSignedIn.value && !authManager.restoreSessionSilently()) {
            return false
        }
        return authManager.getAccessToken() != null
    }

    /**
     * Keep Drive cursors scoped to one Google account. The account identity is
     * intentionally stored in the sync state (not in auth token storage), so it
     * survives Google sign-out long enough to detect a later account switch.
     *
     * Returns true only when a previously-known account changed. Cursors and the
     * accepted deletion marker are reset before any operation against the new
     * account. The caller decides whether that account change is an explicit
     * opt-in (enableSync) or must stop a background/manual sync (syncNow).
     */
    private fun reconcileGoogleAccountBoundary(current: String): Boolean {
        val previous = statePrefs.getString(KEY_GOOGLE_ACCOUNT_EMAIL, null)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        if (previous == current) return false

        val changed = previous != null
        val editor = statePrefs.edit()
            .putString(KEY_GOOGLE_ACCOUNT_EMAIL, current)
        if (changed) {
            editor
                .remove(KEY_UPLOAD_CURSOR)
                .remove(KEY_DOWNLOAD_CREATED_CURSOR)
                .remove(KEY_ACCEPTED_DISABLE_VERSION)
        }
        editor.apply()
        return changed
    }

    /**
     * If another linked device bumped the shared deletion marker after this
     * device last explicitly enabled Drive sync, honor that deletion before any
     * upload. Only generations older than the marker are removed: a different
     * device may already have explicitly re-enabled sync and started generation N,
     * and this stale device must never erase that newly-seeded generation.
     */
    private suspend fun handleRemoteDisableIfNeeded(): DriveHistorySyncResult.RemoteDisabled? {
        val currentMarker = appDataClient.getHistoryDisableMarkerVersion()
        val acceptedMarker = statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L)
        if (currentMarker <= acceptedMarker) return null

        val message = "Cross-device sync was turned off because another linked Tempo device deleted the shared Drive history."
        acceptDeletionMarker(currentMarker, message)
        appDataClient.deleteHistoryBatchesBeforeGeneration(currentMarker)
        return DriveHistorySyncResult.RemoteDisabled(message)
    }

    private suspend fun acceptDeletionMarker(marker: Long, message: String? = null) {
        withContext(NonCancellable) {
            // Persist the stop before network cleanup, which may fail or be cancelled.
            settingsManager.markStopped(message ?: "Cloud history sync was turned off after deletion")
            statePrefs.edit().putLong(KEY_ACCEPTED_DISABLE_VERSION, marker)
                .remove(KEY_UPLOAD_CURSOR).remove(KEY_DOWNLOAD_CREATED_CURSOR).apply()
        }
    }

    /** The authenticated LAN producer is the owner of relayed events.
     * Writing all relays as Android-origin batches would wrongly classify two
     * distinct producers' captures as two events from one producer, breaking
     * one-to-one deduplication on the receiving clients.
     */
    internal data class BatchProducer(val deviceId: String, val platform: String) {
        val displayName: String get() = "Tempo ${platform.replaceFirstChar { it.uppercase() }}"
    }

    private fun uploadProducer(event: ListeningEvent): BatchProducer {
        if (!event.source.startsWith("lan:")) {
            return BatchProducer(deviceId, "android")
        }
        return lanBatchProducer(event.source)
            ?: error("Cannot recover producer identity for relayed LAN listening event ${event.id}")
    }

    /**
     * Uploads locally-owned Room events in id order. Imported Drive events are
     * skipped so another device's event can never bounce back into Drive as a new
     * event. The cursor advances only after every eligible event in a page has
     * been safely uploaded.
     */
    private suspend fun uploadLocalHistory(): Int {
        val dao = database.listeningEventDao()
        val maxId = dao.getMaxEventId()
        val storedCursor = statePrefs.getLong(KEY_UPLOAD_CURSOR, 0L)
        val generation = statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L).coerceAtLeast(0L)
        // A database restore can move Room row ids backwards while SharedPreferences
        // survive. If the persisted cursor is now beyond the database snapshot,
        // keeping it would make every newly-created row look already scanned until
        // ids eventually caught up. Restart from zero instead; deterministic Drive
        // event/batch ids make replay safe and preferable to silently losing plays.
        var afterId = if (storedCursor > maxId) {
            statePrefs.edit().remove(KEY_UPLOAD_CURSOR).apply()
            0L
        } else {
            storedCursor
        }
        var uploaded = 0

        while (afterId < maxId) {
            val page = dao.getEventsPage(afterId, maxId, PAGE_SIZE)
            if (page.isEmpty()) break

            // Resolve per-play identities before serialization. Persist new
            // Android-native IDs BEFORE network I/O: a crash after the Drive
            // upload must never allow a later metadata correction to change
            // the event ID on the retry or a full-history restore.
            val ownOrigins = dao.getOriginClaimsForEvents(page.map { it.id })
                .filter { it.sourceDeviceId == deviceId }
                .associate { it.listeningEventId to it.originEventId }
            val newOwnOrigins = mutableListOf<ListeningEventOrigin>()
            val eventsByProducer = linkedMapOf<BatchProducer, MutableList<DriveHistoryEvent>>()
            for (event in page) {
                // Downloaded Drive history must not bounce back into the cloud.
                // LAN history is different: the sender can have Drive disabled,
                // so Android relays it once using the sender's original event ID.
                if (event.contentFingerprint?.startsWith(IMPORT_FINGERPRINT_PREFIX) == true &&
                    !event.source.startsWith("lan:")
                ) continue
                // Never move the persistent upload cursor past a local play that
                // cannot be exported (for example, a temporarily missing Track).
                // Surface the row ID and retry after its metadata is repaired.
                val exported = localEventToProtocol(event, ownOrigins[event.id])
                    ?: error("Tempo cannot export listening event ${event.id}; restore its track metadata before retrying")
                if (!event.source.startsWith("lan:") && !event.source.startsWith("drive:") &&
                    ownOrigins[event.id] == null
                ) {
                    newOwnOrigins.add(ListeningEventOrigin(exported.eventId, event.id, deviceId))
                }
                eventsByProducer.getOrPut(uploadProducer(event)) { mutableListOf() }.add(exported)
            }
            if (newOwnOrigins.isNotEmpty()) dao.insertOriginAliases(newOwnOrigins)

            // Keep each producer's events in its own immutable Drive batch.
            // Event IDs and batch IDs are still deterministic across retries.
            for ((producer, producerEvents) in eventsByProducer) {
                for (events in producerEvents.chunked(BATCH_SIZE)) {
                    val batchId = DriveHistoryProtocol.createBatchId(events)
                    val batch = DriveHistoryBatch(
                        batchId = batchId,
                        sourceDeviceId = producer.deviceId,
                        sourceDeviceName = producer.displayName,
                        sourcePlatform = producer.platform,
                        createdAtUtc = events.maxOf { it.timestampUtc },
                        events = events
                    )
                    val bytes = DriveHistoryProtocol.encodeCompressed(batch)
                    appDataClient.uploadHistoryBatch(
                        fileName = DriveHistoryProtocol.fileName(producer.deviceId, batchId, generation),
                        compressedBytes = bytes,
                        appProperties = mapOf(
                            DriveHistoryProtocol.APP_PROPERTY_KIND to DriveHistoryProtocol.KIND_HISTORY_BATCH,
                            DriveHistoryProtocol.APP_PROPERTY_SCHEMA to DriveHistoryProtocol.SCHEMA_VERSION.toString(),
                            DriveHistoryProtocol.APP_PROPERTY_DEVICE_ID to producer.deviceId,
                            DriveHistoryProtocol.APP_PROPERTY_PLATFORM to producer.platform,
                            DriveHistoryProtocol.APP_PROPERTY_GENERATION to generation.toString()
                        )
                    )
                    uploaded += events.size
                }
            }

            afterId = page.last().id
            statePrefs.edit().putLong(KEY_UPLOAD_CURSOR, afterId).apply()
        }

        return uploaded
    }

    private suspend fun localEventToProtocol(
        event: ListeningEvent,
        persistedOriginId: String?
    ): DriveHistoryEvent? {
        val track = database.trackDao().getTrackById(event.track_id) ?: return null
        val title = DriveHistoryProtocol.truncateText(track.title.trim()).takeIf { it.isNotBlank() } ?: return null
        val artist = DriveHistoryProtocol.truncateText(track.artist.trim()).takeIf { it.isNotBlank() } ?: return null
        if (event.timestamp !in 1..DriveHistoryProtocol.MAX_WIRE_INTEGER) return null
        val durationMs = (event.estimatedDurationMs ?: track.duration ?: event.playDuration)
            .coerceAtLeast(event.playDuration)
            .coerceAtLeast(0L)
            .coerceAtMost(DriveHistoryProtocol.MAX_WIRE_INTEGER)
        // A LAN import carries the precise original event ID and music source.
        // Publish it through this Android account only if the sender has not
        // used Drive itself; the remote clients will deduplicate using event_id.
        // A *cloud* import never enters this export path.
        val lanRelay = event.source.startsWith("lan:")
        val origin = if (lanRelay) {
            lanRelayIdentity(event.source, event.contentFingerprint) ?: return null
        } else null
        val originalSource = origin?.first ?: event.source
        val sourceApp = originalSource
            .removePrefix("desktop:")
            .removePrefix("browser:")
            .ifBlank { "android" }
            .let { DriveHistoryProtocol.truncateText(it) }
        val source = DriveHistoryProtocol.truncateText(originalSource.ifBlank { "android" })
        val originalEventId = origin?.second

        return DriveHistoryEvent(
            eventId = originalEventId ?: stableLocalEventId(
                existingOriginId = persistedOriginId,
                deviceId = deviceId,
                localEventId = event.id,
                timestampUtc = event.timestamp,
                title = title,
                artist = artist
            ),
            title = title,
            artist = artist,
            album = track.album?.trim()?.let { DriveHistoryProtocol.truncateText(it) }?.takeIf { it.isNotBlank() },
            timestampUtc = event.timestamp,
            durationMs = durationMs,
            listenedMs = event.playDuration.coerceIn(0L, DriveHistoryProtocol.MAX_WIRE_INTEGER),
            sourceApp = sourceApp,
            source = source,
            skipped = event.wasSkipped,
            replayCount = if (event.isReplay) 1 else 0,
            completionPercentage = event.completionPercentage.coerceIn(0, 100),
            pauseCount = event.pauseCount.coerceAtLeast(0),
            seekCount = event.seekCount.coerceAtLeast(0),
            sessionId = event.sessionId?.let { DriveHistoryProtocol.truncateText(it) },
            site = null,
            contentType = DriveHistoryProtocol.truncateText(track.contentType.ifBlank { "MUSIC" }),
            volumeLevel = protocolVolumeLevel(event.volumeLevel),
            totalPauseDurationMs = event.totalPauseDurationMs
                .coerceIn(0L, DriveHistoryProtocol.MAX_WIRE_INTEGER),
            positionUpdatesCount = event.positionUpdatesCount.coerceAtLeast(0)
        )
    }

    /**
     * Uses a 24-hour overlap around the Drive created-time cursor. Overlap makes
     * the cursor resilient to delayed/out-of-order uploads; exact event ids plus
     * Tempo's temporal reconciliation make re-reading those files harmless.
     */
    private suspend fun downloadRemoteHistory(includeOwnDeviceBatches: Boolean = false): ImportSummary {
        val cursor = statePrefs.getLong(KEY_DOWNLOAD_CREATED_CURSOR, 0L)
        val acceptedGeneration = statePrefs.getLong(KEY_ACCEPTED_DISABLE_VERSION, 0L).coerceAtLeast(0L)
        val createdAfter = if (cursor > 0L) {
            (cursor - DOWNLOAD_OVERLAP_MS).coerceAtLeast(0L)
        } else null

        val files = appDataClient.listHistoryBatches(createdAfter)
        var maxCreated = cursor
        var inserted = 0
        var skipped = 0
        var replaced = 0

        for ((index, file) in files.sortedBy { it.createdAt }.withIndex()) {
            // Full restoration may process thousands of batches. Persist the
            // last completed prefix periodically so process death or a work
            // timeout does not force replaying the entire historic archive.
            if (index > 0 && index % 50 == 0 && maxCreated > cursor) {
                check(statePrefs.edit().putLong(KEY_DOWNLOAD_CREATED_CURSOR, maxCreated).commit()) {
                    "Could not checkpoint Drive history restoration"
                }
            }
            val fileGeneration = DriveHistoryProtocol.parseGeneration(
                file.appProperties[DriveHistoryProtocol.APP_PROPERTY_GENERATION]
            )
            if (fileGeneration == null) {
                Log.w(TAG, "Skipping history file with an invalid generation: ${file.fileName}")
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }
            if (fileGeneration < acceptedGeneration) {
                // Pre-delete data (including an upload that finished after the
                // delete request) is never allowed to resurrect. Cleanup is best
                // effort here because failing to delete stale data must not block
                // current-generation sync.
                appDataClient.delete(file.fileId)
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }

            val sourceDeviceId = file.appProperties[DriveHistoryProtocol.APP_PROPERTY_DEVICE_ID]
                ?.takeIf(DriveHistoryProtocol::isValidDeviceId)
            val metadataValid = file.fileName.startsWith(DriveHistoryProtocol.FILE_PREFIX) &&
                file.appProperties[DriveHistoryProtocol.APP_PROPERTY_KIND] == DriveHistoryProtocol.KIND_HISTORY_BATCH &&
                file.appProperties[DriveHistoryProtocol.APP_PROPERTY_SCHEMA] == DriveHistoryProtocol.SCHEMA_VERSION.toString() &&
                file.appProperties[DriveHistoryProtocol.APP_PROPERTY_SHA256]
                    ?.matches(Regex("^[0-9a-f]{64}$")) == true &&
                sourceDeviceId != null
            if (!metadataValid) {
                Log.w(TAG, "Skipping history file with invalid Tempo metadata: ${file.fileName}")
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }
            val remoteDeviceId = requireNotNull(sourceDeviceId)
            if (!includeOwnDeviceBatches && remoteDeviceId == deviceId) {
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }

            // Network/API failures are retryable. Do not move the created-time
            // cursor past a file we did not actually obtain, otherwise after the
            // 24-hour overlap expires that batch could be lost forever.
            // A permanently malformed/oversized payload must not block all later
            // history forever. Treat that specific file as consumed, but never do
            // the same for a transient download failure above.
            val batch = try {
                val bytes = appDataClient.download(file)
                DriveHistoryProtocol.decodeCompressed(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed history batch ${file.fileName}", e)
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }
            val sourcePlatform = file.appProperties[DriveHistoryProtocol.APP_PROPERTY_PLATFORM]
            val expectedName = DriveHistoryProtocol.fileName(remoteDeviceId, batch.batchId, fileGeneration)
            if (batch.sourceDeviceId != remoteDeviceId ||
                sourcePlatform != batch.sourcePlatform ||
                file.fileName != expectedName
            ) {
                Log.w(TAG, "Skipping history batch whose payload does not match Drive metadata: ${file.fileName}")
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }
            if (!includeOwnDeviceBatches && batch.sourceDeviceId == deviceId) {
                maxCreated = maxOf(maxCreated, file.createdAt)
                continue
            }

            val incoming = mutableListOf<ListeningEvent>()
            for (event in batch.events) {
                protocolEventToLocal(event, batch.sourceDeviceId)?.let(incoming::add)
            }

            // Resolver/database failures are not safe to skip. Let them abort this
            // sync so the cursor remains behind the uncommitted batch and the next
            // run can retry it idempotently.
            val result = database.listeningEventDao().insertAllBatchedWithDedup(incoming)
            inserted += result.inserted
            skipped += result.skipped
            replaced += result.replaced
            maxCreated = maxOf(maxCreated, file.createdAt)
        }

        if (maxCreated > cursor) {
            statePrefs.edit().putLong(KEY_DOWNLOAD_CREATED_CURSOR, maxCreated).apply()
        }
        return ImportSummary(inserted, skipped, replaced)
    }

    private suspend fun protocolEventToLocal(
        event: DriveHistoryEvent,
        sourceDeviceId: String
    ): ListeningEvent? {
        if (event.timestampUtc <= 0L || event.title.isBlank() || event.artist.isBlank()) return null

        val resolution = trackResolver.resolve(
            TrackResolver.Query(
                title = event.title.trim(),
                artist = event.artist.trim(),
                album = event.album?.trim()?.takeIf { it.isNotBlank() },
                duration = event.durationMs.takeIf { it > 0L }
            ),
            contentType = event.contentType.ifBlank { "MUSIC" }
        )

        val listenedMs = event.listenedMs.coerceAtLeast(0L)
        return ListeningEvent(
            track_id = resolution.trackId,
            timestamp = event.timestampUtc,
            playDuration = listenedMs,
            completionPercentage = event.completionPercentage.coerceIn(0, 100),
            source = "drive:$sourceDeviceId:${event.source.ifBlank { event.sourceApp }}",
            wasSkipped = event.skipped,
            isReplay = event.replayCount > 0,
            estimatedDurationMs = event.durationMs.takeIf { it > 0L },
            pauseCount = event.pauseCount.coerceAtLeast(0),
            sessionId = event.sessionId,
            endTimestamp = if (listenedMs > 0L) event.timestampUtc + listenedMs else null,
            totalPauseDurationMs = event.totalPauseDurationMs.coerceAtLeast(0L),
            seekCount = event.seekCount.coerceAtLeast(0),
            positionUpdatesCount = event.positionUpdatesCount.coerceAtLeast(0),
            wasInterrupted = event.skipped,
            volumeLevel = event.volumeLevel?.takeIf { it == 0 },
            contentFingerprint = "$IMPORT_FINGERPRINT_PREFIX${event.eventId}"
        )
    }

    suspend fun deleteCloudHistoryAndReset(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!ensureAuthorized()) throw DriveException.Auth("Google Drive authorization is required")
            appDataClient.withAccountBoundSession { accountEmail ->
                if (reconcileGoogleAccountBoundary(accountEmail)) {
                    settingsManager.setEnabled(false)
                    throw DriveException.Auth(
                        "Google account changed. Enable cross-device sync for the new account before deleting its Drive history."
                    )
                }

                // Bump the shared server-timestamped generation BEFORE deleting old
                // batches. A client that explicitly re-enables after this point writes
                // generation N and is therefore protected from stale-device cleanup.
                val markerVersion = appDataClient.bumpHistoryDisableMarker()
                acceptDeletionMarker(markerVersion)
                val deleted = appDataClient.deleteHistoryBatchesBeforeGeneration(markerVersion)
                deleted
            }
        }
    }

    /**
     * Reset only cloud-sync cursors. Local listening history is never deleted.
     * Re-enabling Drive history sync later intentionally republishes locally-owned
     * history if a newer shared deletion marker was acknowledged.
     */
    private fun resetCursorsLocked() {
        statePrefs.edit()
            .remove(KEY_UPLOAD_CURSOR)
            .remove(KEY_DOWNLOAD_CREATED_CURSOR)
            .apply()
    }

    /**
     * Serialize a local import/restore with Drive synchronization and invalidate
     * both cursors afterwards. Without holding the same mutex as [syncNow], a
     * worker could advance its upload cursor while Room is being replaced and
     * permanently skip restored rows whose ids fall behind that cursor.
     *
     * Cursors are reset even when the import fails because importers may already
     * have committed part of the database before reporting an error.
     */
    suspend fun <T> withLocalHistoryRestore(block: suspend () -> T): T = mutex.withLock {
        try {
            block()
        } finally {
            resetCursorsLocked()
        }
    }

    private data class ImportSummary(
        val inserted: Int,
        val skipped: Int,
        val replaced: Int
    )
}

sealed class DriveHistorySyncResult {
    data object Disabled : DriveHistorySyncResult()
    data class RemoteDisabled(val message: String) : DriveHistorySyncResult()
    data class Success(
        val uploaded: Int,
        val imported: Int,
        val duplicates: Int,
        val replaced: Int
    ) : DriveHistorySyncResult()
    data class Error(val message: String, val exception: Exception? = null) : DriveHistorySyncResult()
}
