package me.avinas.tempo.data.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveHistoryProtocolTest {

    @Test
    fun `event id is stable for the same originating row`() {
        val first = DriveHistoryProtocol.createEventId(
            deviceId = "device-a",
            localEventId = 42L,
            timestampUtc = 1_700_000_000_000L,
            title = "  Track Name ",
            artist = "Artist"
        )
        val second = DriveHistoryProtocol.createEventId(
            deviceId = "device-a",
            localEventId = 42L,
            timestampUtc = 1_700_000_000_000L,
            title = "track name",
            artist = "artist"
        )

        assertEquals(first, second)
        assertEquals(64, first.length)
    }

    @Test
    fun `event id separates devices and local rows`() {
        val base = DriveHistoryProtocol.createEventId("device-a", 42L, 1000L, "Song", "Artist")
        val otherDevice = DriveHistoryProtocol.createEventId("device-b", 42L, 1000L, "Song", "Artist")
        val otherRow = DriveHistoryProtocol.createEventId("device-a", 43L, 1000L, "Song", "Artist")

        assertNotEquals(base, otherDevice)
        assertNotEquals(base, otherRow)
    }

    @Test
    fun `batch id is deterministic for the ordered event set`() {
        val first = event("event-1", 1000L)
        val second = event("event-2", 2000L)

        val idA = DriveHistoryProtocol.createBatchId(listOf(first, second))
        val idB = DriveHistoryProtocol.createBatchId(listOf(first, second))
        val reversed = DriveHistoryProtocol.createBatchId(listOf(second, first))

        assertEquals(idA, idB)
        assertNotEquals(idA, reversed)
        assertEquals(64, idA.length)
    }

    @Test
    fun `protocol ids match the cross-client golden vectors`() {
        val eventId = DriveHistoryProtocol.createEventId(
            deviceId = "device-1",
            localEventId = 42L,
            timestampUtc = 1_700_000_000_000L,
            title = " Song ",
            artist = " Artist "
        )
        assertEquals(
            "69bd5521a322b3d1aaeca431b7380bd49f3a28e1c1d1b1dc0a754ca37e6a06b4",
            eventId
        )

        val batchId = DriveHistoryProtocol.createBatchId(
            listOf(event(eventId, 1_700_000_000_000L))
        )
        assertEquals(
            "785b57b5c9e86c35176a413093df3c9fce37eb266c70485a0f9e8fff66e95d43",
            batchId
        )
    }

    @Test
    fun `batch filename stays inside Tempo history namespace`() {
        val name = DriveHistoryProtocol.fileName("device-a", "batch-a")
        assertTrue(name.startsWith(DriveHistoryProtocol.FILE_PREFIX))
        assertTrue(name.endsWith(".json.gz"))
    }

    @Test
    fun `batch filename includes accepted deletion generation`() {
        assertEquals(
            "tempo_history_v1_g1234_device-a_batch-a.json.gz",
            DriveHistoryProtocol.fileName("device-a", "batch-a", 1234L)
        )
        // Pre-generation clients/files map to generation zero during migration.
        assertEquals(
            "tempo_history_v1_g0_device-a_batch-a.json.gz",
            DriveHistoryProtocol.fileName("device-a", "batch-a")
        )
    }

    @Test
    fun `decoder rejects a batch with too many events`() {
        val events = (0..DriveHistoryProtocol.MAX_EVENTS_PER_BATCH)
            .map { index -> event("event-$index", 1_700_000_000_000L + index) }
        val batch = DriveHistoryBatch(
            batchId = DriveHistoryProtocol.createBatchId(events),
            sourceDeviceId = "remote-device",
            sourceDeviceName = "Tempo device",
            sourcePlatform = "test",
            createdAtUtc = 1_700_000_000_000L,
            events = events
        )
        val compressed = DriveHistoryProtocol.encodeCompressed(batch)

        val failure = runCatching { DriveHistoryProtocol.decodeCompressed(compressed) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("too many events"))
    }

    @Test
    fun `decoder rejects an oversized compressed payload before inflating it`() {
        val oversized = ByteArray(DriveHistoryProtocol.MAX_COMPRESSED_BYTES + 1)

        val failure = runCatching { DriveHistoryProtocol.decodeCompressed(oversized) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("compressed size limit"))
    }

    private fun event(id: String, timestamp: Long) = DriveHistoryEvent(
        eventId = id,
        title = "Song $id",
        artist = "Artist",
        album = null,
        timestampUtc = timestamp,
        durationMs = 180_000L,
        listenedMs = 170_000L,
        sourceApp = "test",
        source = "test",
        skipped = false,
        replayCount = 0,
        completionPercentage = 94,
        pauseCount = 0,
        seekCount = 0,
        sessionId = null,
        site = null,
        contentType = "MUSIC",
        volumeLevel = 50,
        totalPauseDurationMs = 0L,
        positionUpdatesCount = 10
    )
}