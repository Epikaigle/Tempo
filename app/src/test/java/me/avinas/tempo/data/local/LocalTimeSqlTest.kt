package me.avinas.tempo.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies the query fragments in [LocalTimeSql] bucket a play by the zone it was recorded in.
 *
 * These run against in-memory SQLite so the exact SQL the DAOs ship is what gets exercised. The
 * assertions use explicitly stored offsets rather than the machine's timezone, so they hold on any
 * build host and cannot pass by accident on a box that happens to sit in the zone under test.
 */
class LocalTimeSqlTest {
    private lateinit var connection: Connection

    /** 2024-01-15T22:30:00Z -- 22:30 in the UK, 05:30 the next morning in Bangkok. */
    private val eveningInTheUk = 1_705_357_800_000L

    /** 2024-01-31T20:00:00Z -- January in the UK, February in Bangkok. */
    private val endOfJanuaryInTheUk = 1_706_731_200_000L

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE listening_events (id INTEGER PRIMARY KEY, track_id INTEGER, timestamp INTEGER, tz_offset_minutes INTEGER DEFAULT NULL)")
            stmt.execute("CREATE TABLE tracks (id INTEGER PRIMARY KEY, artist TEXT)")
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `the same play renders in the offset it was recorded in, not the device zone`() {
        // The reported trip, in one row: a UK evening play read as 05:30 the next morning in
        // Thailand is what collapsed a 110-day streak into 40.
        insertEvent(timestamp = eveningInTheUk, offsetMinutes = 0)
        insertEvent(timestamp = eveningInTheUk, offsetMinutes = 420)

        assertEquals(listOf("2024-01-15", "2024-01-16"), strings("SELECT ${LocalTimeSql.LOCAL_DAY} FROM listening_events ORDER BY tz_offset_minutes"))
        assertEquals(listOf("22", "05"), strings("SELECT strftime('%H', ${LocalTimeSql.LOCAL_EPOCH}, 'unixepoch') FROM listening_events ORDER BY tz_offset_minutes"))
    }

    @Test
    fun `a row without an offset still lands on a day instead of dropping out`() {
        // NULL must fall back to the device offset (the pre-57 behaviour), never poison the
        // arithmetic -- a dropped row would silently shrink every day-based statistic.
        val now = System.currentTimeMillis()
        insertEvent(timestamp = now, offsetMinutes = null)

        val deviceDay = strings("SELECT date(${now / 1000}, 'unixepoch', 'localtime')").single()
        assertEquals(listOf(deviceDay), strings("SELECT ${LocalTimeSql.LOCAL_DAY} FROM listening_events"))
    }

    @Test
    fun `an implausible stored offset cannot make a day query fail`() {
        // Offsets are written by the app, but the date arithmetic must never throw and take a
        // statistics query down with it. The anchor lookup validates the range separately.
        insertEvent(timestamp = eveningInTheUk, offsetMinutes = 99_999)

        val day = strings("SELECT ${LocalTimeSql.LOCAL_DAY} FROM listening_events").single()
        assertEquals("expected a YYYY-MM-DD day key, got '$day'", 10, day.length)
    }

    @Test
    fun `discovery month follows the first listen's own offset, not a later play's`() {
        // First listen is 20:00 on 31 January in the UK (offset 0); the next play by the same
        // artist is in Bangkok (offset +420). The discovery bucket belongs to January because the
        // *first* play is the one that counts, even though the group holds two different offsets.
        connection.createStatement().use { it.execute("INSERT INTO tracks (id, artist) VALUES (1, 'Four Tet')") }
        insertEvent(timestamp = endOfJanuaryInTheUk, offsetMinutes = 0, trackId = 1)
        insertEvent(timestamp = endOfJanuaryInTheUk + 86_400_000L, offsetMinutes = 420, trackId = 1)

        val months =
            strings(
                """
                SELECT ${LocalTimeSql.LOCAL_DISCOVERY_MONTH} as month
                FROM (
                    SELECT t.artist, MIN(le.timestamp) as first_listen, le.tz_offset_minutes
                    FROM listening_events le
                    INNER JOIN tracks t ON le.track_id = t.id
                    GROUP BY t.artist
                ) min_timestamps
                GROUP BY month
                ORDER BY month DESC
                """.trimIndent(),
            )

        assertEquals(listOf("2024-01"), months)
    }

    // ---- Helpers -----------------------------------------------------------

    private fun insertEvent(
        timestamp: Long,
        offsetMinutes: Int?,
        trackId: Long = 1,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO listening_events (track_id, timestamp, tz_offset_minutes) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, trackId)
                ps.setLong(2, timestamp)
                if (offsetMinutes == null) ps.setNull(3, java.sql.Types.INTEGER) else ps.setInt(3, offsetMinutes)
                ps.executeUpdate()
            }
    }

    private fun strings(sql: String): List<String> {
        val values = mutableListOf<String>()
        connection.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) values.add(rs.getString(1))
        }
        return values
    }
}
