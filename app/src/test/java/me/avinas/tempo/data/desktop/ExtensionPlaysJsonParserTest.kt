package me.avinas.tempo.data.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ExtensionPlaysJsonParser] verifying schema variations, resilience to
 * malformed records, and value bounds normalization.
 */
class ExtensionPlaysJsonParserTest {

    private val now = 1_700_000_000_000L

    private val parser = ExtensionPlaysJsonParser(nowMillis = { now })

    private data class Parsed(
        val summary: ExtensionPlaysJsonParser.Summary,
        val records: List<ExtensionPlaysJsonParser.Record>,
    )

    private fun parse(json: String, parser: ExtensionPlaysJsonParser = this.parser): Parsed =
        runBlocking {
            val records = mutableListOf<ExtensionPlaysJsonParser.Record>()
            val summary = parser.parse(json.byteInputStream()) { records.add(it) }
            Parsed(summary, records)
        }


    @Test
    fun `reads the array the extension writes today`() {
        val json = """
            [
              {
                "id": 41,
                "title": "Starboy",
                "artist": "The Weeknd",
                "album": "Starboy",
                "durationMs": 230000,
                "timestampUtc": 1699000000000,
                "sourceApp": "Browser Extension",
                "status": "queued",
                "listenedMs": 215000,
                "skipped": false,
                "replayCount": 2,
                "isMuted": false,
                "completionPercentage": 93,
                "pauseCount": 3,
                "seekCount": 1,
                "sessionId": "session-abc",
                "site": "open.spotify.com",
                "contentType": "MUSIC",
                "volumeLevel": 0.8,
                "anomalies": ["Excessive seeking: 1 seeks in 215000ms"],
                "totalPauseDurationMs": 12000,
                "positionUpdatesCount": 87
              }
            ]
        """.trimIndent()

        val (summary, records) = parse(json)

        assertEquals(1, summary.parsed)
        assertEquals(0, summary.malformed)
        assertFalse(summary.truncated)

        val record = records.single()
        assertEquals("Starboy", record.title)
        assertEquals("The Weeknd", record.artist)
        assertEquals("Starboy", record.album)
        assertEquals(1_699_000_000_000L, record.timestampUtc)
        assertEquals(230_000L, record.durationMs)
        assertEquals(215_000L, record.listenedMs)
        assertEquals("Browser Extension", record.sourceApp)
        assertEquals("MUSIC", record.contentType)
        assertEquals(false, record.skipped)
        assertFalse(record.isMuted)
        assertEquals(93, record.completionPercentage)
        assertEquals(2, record.replayCount)
        assertEquals(3, record.pauseCount)
        assertEquals(1, record.seekCount)
        assertEquals(87, record.positionUpdatesCount)
        assertEquals(12_000L, record.totalPauseDurationMs)
        assertEquals("session-abc", record.sessionId)
    }

    @Test
    fun `reads a saved sync payload with snake_case fields under a plays key`() {
        val json = """
            {
              "auth_token": "token-value",
              "device_name": "Tempo Stats (Browser)",
              "plays": [
                {
                  "title": "Levitating",
                  "artist": "Dua Lipa",
                  "album": "Future Nostalgia",
                  "timestamp_utc": "1699500000000",
                  "duration_ms": 203000,
                  "source_app": "Browser Extension",
                  "listened_ms": 190000,
                  "skipped": true,
                  "completion_percentage": 12,
                  "session_id": "s-1"
                }
              ]
            }
        """.trimIndent()

        val (summary, records) = parse(json)

        assertEquals(1, summary.parsed)
        assertEquals(0, summary.malformed)
        val record = records.single()
        assertEquals("Levitating", record.title)
        assertEquals(1_699_500_000_000L, record.timestampUtc)
        assertEquals(true, record.skipped)
        assertEquals(12, record.completionPercentage)
        assertEquals("s-1", record.sessionId)
    }

    @Test
    fun `normalizes epoch seconds to milliseconds`() {
        val json =
            """[{"title":"A","artist":"B","timestampUtc":1699500000,"durationMs":100000,"listenedMs":90000}]"""

        val (_, records) = parse(json)

        assertEquals(1_699_500_000_000L, records.single().timestampUtc)
    }

    @Test
    fun `counts rows without a title, artist, or usable timestamp as malformed`() {
        val json = """
            [
              {"artist":"B","timestampUtc":1699500000000},
              {"title":"A","timestampUtc":1699500000000},
              {"title":"A","artist":"B"},
              {"title":"A","artist":"B","timestampUtc":0},
              {"title":"A","artist":"B","timestampUtc":1699500000000}
            ]
        """.trimIndent()

        val (summary, records) = parse(json)

        assertEquals(4, summary.malformed)
        assertEquals(1, summary.parsed)
        assertEquals("A", records.single().title)
    }

    @Test
    fun `one unusable row does not cost the rest of the file`() {
        val json = """
            [
              "nonsense",
              12,
              null,
              {"title":"Good","artist":"Band","timestampUtc":1699500000000,"durationMs":180000,"listenedMs":180000},
              {"title":"","artist":"Band","timestampUtc":1699500000001,"durationMs":180000,"listenedMs":180000}
            ]
        """.trimIndent()

        val (summary, records) = parse(json)

        assertEquals(1, summary.parsed)
        assertEquals(4, summary.malformed)
        assertEquals("Good", records.single().title)
    }

    @Test
    fun `clips out-of-range numbers instead of storing them`() {
        val json = """
            [
              {
                "title":"A","artist":"B","timestampUtc":1699500000000,
                "durationMs": 999999999999, "listenedMs": -5, "completionPercentage": 250,
                "pauseCount": -3, "seekCount": 99999999, "positionUpdatesCount": -1,
                "totalPauseDurationMs": -1, "replayCount": -1
              }
            ]
        """.trimIndent()

        val (_, records) = parse(json)

        val record = records.single()
        assertEquals(86_400_000L, record.durationMs)
        assertEquals(0L, record.listenedMs)
        assertEquals(100, record.completionPercentage)
        assertEquals(0, record.pauseCount)
        assertEquals(1_000_000, record.seekCount)
        assertEquals(0, record.positionUpdatesCount)
        assertEquals(0L, record.totalPauseDurationMs)
        assertEquals(0, record.replayCount)
    }

    @Test
    fun `treats a zero volume level as muted`() {
        val json = """
            [
              {"title":"A","artist":"B","timestampUtc":1699500000000,"volumeLevel":0},
              {"title":"C","artist":"D","timestampUtc":1699500000001,"volumeLevel":0.4},
              {"title":"E","artist":"F","timestampUtc":1699500000002,"isMuted":true},
              {"title":"G","artist":"H","timestampUtc":1699500000003}
            ]
        """.trimIndent()

        val (_, records) = parse(json)

        assertEquals(listOf(true, false, true, false), records.map { it.isMuted })
    }

    @Test
    fun `drops a content type the app cannot classify`() {
        val json = """
            [
              {"title":"A","artist":"B","timestampUtc":1699500000000,"contentType":"video"},
              {"title":"C","artist":"D","timestampUtc":1699500000001,"contentType":"podcast"},
              {"title":"E","artist":"F","timestampUtc":1699500000002}
            ]
        """.trimIndent()

        val (_, records) = parse(json)

        assertNull(records[0].contentType)
        assertEquals("PODCAST", records[1].contentType)
        assertNull(records[2].contentType)
    }

    @Test
    fun `stops at the record cap and reports truncation`() {
        val capped = ExtensionPlaysJsonParser(maxRecords = 2, nowMillis = { now })
        val rows = (1..5).joinToString(separator = ",") { index ->
            """{"title":"T$index","artist":"B","timestampUtc":${1699500000000L + index},"durationMs":100000,"listenedMs":100000}"""
        }

        val (summary, records) = parse("[$rows]", capped)

        assertEquals(2, summary.parsed)
        assertEquals(2, records.size)
        assertTrue(summary.truncated)
    }

    @Test
    fun `an empty or unrelated document yields nothing instead of failing`() {
        assertEquals(0, parse("[]").summary.parsed)
        assertEquals(0, parse("{}").summary.parsed)
        assertEquals(0, parse("""{"meta":{"version":1}}""").summary.parsed)
        assertEquals(0, parse("").summary.parsed)
        assertEquals(0, parse("   \n  ").summary.parsed)
    }
}
