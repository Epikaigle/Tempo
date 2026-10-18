package me.avinas.tempo.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

/**
 * Verifies MIGRATION_56_57 (per-play timezone offset, plus backfill of existing history).
 *
 * Two things matter here:
 * 1. The added column must match the Room-exported schema 57 exactly -- nullable INTEGER with
 *    `DEFAULT NULL` -- or Room's identity check rejects the database at open time and the app
 *    cannot start for anyone upgrading.
 * 2. Backfill must not move a single existing play to another local day or hour. The whole point
 *    of freezing the offset is that the past keeps rendering exactly as it did; an off-by-one in
 *    the arithmetic would make streaks and timing badges change *because* of the upgrade.
 *
 * Runs the shipped migration against in-memory SQLite through a reflection proxy, matching the
 * harness used by the other migration tests.
 */
class Migration56To57Test {
    private lateinit var connection: Connection

    /** listening_events exactly as exported in 56.json. */
    private val listeningEvents56CreateSql =
        """
        CREATE TABLE IF NOT EXISTS `listening_events` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `track_id` INTEGER NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `playDuration` INTEGER NOT NULL,
            `completionPercentage` INTEGER NOT NULL,
            `source` TEXT NOT NULL,
            `was_skipped` INTEGER NOT NULL DEFAULT 0,
            `is_replay` INTEGER NOT NULL DEFAULT 0,
            `estimated_duration_ms` INTEGER DEFAULT NULL,
            `pause_count` INTEGER NOT NULL DEFAULT 0,
            `session_id` TEXT DEFAULT NULL,
            `end_timestamp` INTEGER DEFAULT NULL,
            `total_pause_duration_ms` INTEGER NOT NULL DEFAULT 0,
            `seek_count` INTEGER NOT NULL DEFAULT 0,
            `position_updates_count` INTEGER NOT NULL DEFAULT 0,
            `was_interrupted` INTEGER NOT NULL DEFAULT 0,
            `volume_level` INTEGER DEFAULT NULL,
            `content_fingerprint` TEXT DEFAULT NULL
        )
        """.trimIndent()

    /**
     * Epochs chosen so that the frozen renderings cross day and month boundaries in zones east and
     * west of the machine's own, including 22:13 UTC (already tomorrow in Asia) and a far-future
     * date that no stored DST rule covers.
     */
    private val sampleTimestamps =
        listOf(
            0L, // 1970-01-01T00:00Z
            86_400_000L,
            1_700_000_000_000L, // 2023-11-14T22:13:20Z
            1_700_003_600_000L,
            1_706_731_200_000L, // 2024-01-31T20:00:00Z -- February in +07:00
            4_102_444_800_000L, // 2100-01-01T00:00Z
        )

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = OFF")
            stmt.execute(listeningEvents56CreateSql)
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    // ---- Portability -------------------------------------------------------

    @Test
    fun `migration SQL avoids features missing on API 26 SQLite`() {
        // minSdk 26 devices ship SQLite 3.18-3.22: no window functions, no UPSERT.
        val unsupported = listOf(
            "over (", "row_number", "dense_rank", "ntile", "lag(", "lead(",
            "on conflict", "do update set", "returning",
        )
        val sql = migrationSqlText().lowercase()
        unsupported.forEach { token ->
            assertFalse(
                "MIGRATION_56_57 must not use '$token': API 26 SQLite cannot parse it",
                sql.contains(token),
            )
        }
    }

    // ---- Schema ------------------------------------------------------------

    @Test
    fun `adds tz_offset_minutes as the last listening_events column`() {
        runRealMigration()

        assertEquals(
            "tz_offset_minutes",
            tableColumns("listening_events").last(),
        )
    }

    @Test
    fun `tz_offset_minutes matches the exported nullable integer contract`() {
        runRealMigration()

        val info = columnInfo("listening_events", "tz_offset_minutes")
        assertEquals("INTEGER", info.type)
        assertFalse("must be nullable so legacy rows can be absent", info.notNull)
        assertEquals("must default to NULL like the exported schema", "NULL", info.defaultValue)
        assertEquals("must not be part of the primary key", 0, info.pk)
    }

    // ---- Backfill ----------------------------------------------------------

    @Test
    fun `backfills every existing row and leaves none without an offset`() {
        sampleTimestamps.forEach(::insertEvent)

        runRealMigration()

        assertEquals(0, rowCount("listening_events WHERE tz_offset_minutes IS NULL"))
    }

    @Test
    fun `existing rows keep the local day they rendered on before the migration`() {
        sampleTimestamps.forEach(::insertEvent)
        val before = dayByTimestamp("date(timestamp / 1000, 'unixepoch', 'localtime')")

        runRealMigration()

        // LOCAL_DAY reads the frozen offset; the pre-migration expression read the device's live
        // zone. They must agree for every row, which is what makes the upgrade invisible.
        assertEquals(before, dayByTimestamp(LocalTimeSql.LOCAL_DAY))
    }

    @Test
    fun `existing rows keep the local hour they rendered on before the migration`() {
        sampleTimestamps.forEach(::insertEvent)
        val before = dayByTimestamp("strftime('%H', timestamp / 1000, 'unixepoch', 'localtime')")

        runRealMigration()

        assertEquals(before, dayByTimestamp("strftime('%H', ${LocalTimeSql.LOCAL_EPOCH}, 'unixepoch')"))
    }

    @Test
    fun `backfills whole fifteen-minute offsets`() {
        sampleTimestamps.forEach(::insertEvent)

        runRealMigration()

        val offsets = scalarLongs("SELECT tz_offset_minutes FROM listening_events")
        assertEquals(sampleTimestamps.size, offsets.size)
        offsets.forEach { offset ->
            assertEquals("offset $offset is not a whole 15-minute step", 0L, offset % 15)
        }
    }

    // ---- Re-entrancy -------------------------------------------------------

    @Test
    fun `migration is re-entrant and does not rewrite frozen offsets`() {
        sampleTimestamps.forEach(::insertEvent)
        runRealMigration()

        // Re-entry is detected with a value the backfill would never produce: if the guard failed,
        // the duplicate ALTER would throw; if the UPDATE re-ran, the stamp would be overwritten.
        connection.createStatement().use {
            it.execute("UPDATE listening_events SET tz_offset_minutes = 999")
        }
        AppDatabase.MIGRATION_56_57.migrate(proxyDatabase())

        assertEquals(
            List(sampleTimestamps.size) { 999L },
            scalarLongs("SELECT tz_offset_minutes FROM listening_events"),
        )
    }

    @Test
    fun `an empty history migrates cleanly`() {
        runRealMigration()

        assertEquals(0, rowCount("listening_events"))
        assertTrue(tableColumns("listening_events").contains("tz_offset_minutes"))
    }

    // ---- Helpers -----------------------------------------------------------

    private fun runRealMigration() {
        AppDatabase.MIGRATION_56_57.migrate(proxyDatabase())
    }

    private fun insertEvent(timestamp: Long) {
        connection
            .prepareStatement(
                "INSERT INTO listening_events " +
                    "(`track_id`, `timestamp`, `playDuration`, `completionPercentage`, `source`) " +
                    "VALUES (1, ?, 1000, 50, 'com.spotify.music')",
            ).use { ps ->
                ps.setLong(1, timestamp)
                ps.executeUpdate()
            }
    }

    private fun dayByTimestamp(expression: String): Map<Long, String> {
        val rendered = linkedMapOf<Long, String>()
        connection
            .createStatement()
            .executeQuery("SELECT timestamp, $expression FROM listening_events ORDER BY timestamp")
            .use { rs ->
                while (rs.next()) rendered[rs.getLong(1)] = rs.getString(2)
            }
        return rendered
    }

    private fun scalarLongs(sql: String): List<Long> {
        val values = mutableListOf<Long>()
        connection.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) values.add(rs.getLong(1))
        }
        return values
    }

    private fun tableColumns(table: String): List<String> {
        val columns = mutableListOf<String>()
        connection.createStatement().executeQuery("PRAGMA table_info($table)").use { rs ->
            while (rs.next()) columns.add(rs.getString("name"))
        }
        return columns
    }

    private data class ColumnInfo(
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val pk: Int,
    )

    private fun columnInfo(
        table: String,
        column: String,
    ): ColumnInfo =
        connection.createStatement().executeQuery("PRAGMA table_info($table)").use { rs ->
            while (rs.next()) {
                if (rs.getString("name") == column) {
                    return ColumnInfo(
                        type = rs.getString("type"),
                        notNull = rs.getInt("notnull") != 0,
                        defaultValue = rs.getString("dflt_value"),
                        pk = rs.getInt("pk"),
                    )
                }
            }
            throw AssertionError("column $column not found in $table")
        }

    private fun rowCount(fromClause: String): Int =
        connection.createStatement().executeQuery("SELECT COUNT(*) FROM $fromClause").use { rs ->
            rs.next()
            rs.getInt(1)
        }

    private fun migrationSqlText(): String {
        val captured = StringBuilder()
        AppDatabase.MIGRATION_56_57.migrate(capturingDatabase(captured))
        return captured.toString()
    }

    private fun capturingDatabase(sink: StringBuilder): SupportSQLiteDatabase =
        proxy { sql ->
            sink.append(sql).append('\n')
            connection.createStatement().use { it.execute(sql) }
        }

    private fun proxyDatabase(): SupportSQLiteDatabase =
        proxy { sql -> connection.createStatement().use { it.execute(sql) } }

    /** Minimal [SupportSQLiteDatabase] proxy over JDBC. */
    private fun proxy(onExecSql: (String) -> Unit): SupportSQLiteDatabase {
        val handler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "execSQL" -> {
                        onExecSql(args[0] as String)
                        null
                    }

                    "query" -> {
                        val stmt = connection.createStatement()
                        val rs = stmt.executeQuery(args[0] as String)
                        cursorProxy(stmt, rs)
                    }

                    "inTransaction" -> false
                    "isDbLockedByCurrentThread" -> false
                    "isOpen" -> true
                    else -> throw UnsupportedOperationException(
                        "Test adapter: unexpected SupportSQLiteDatabase call '${method.name}'",
                    )
                }
            }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler,
        ) as SupportSQLiteDatabase
    }

    private fun cursorProxy(
        stmt: Statement,
        rs: ResultSet,
    ): Cursor {
        val handler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "moveToNext" -> rs.next()
                    "getString" -> rs.getString((args[0] as Int) + 1)
                    "getLong" -> rs.getLong((args[0] as Int) + 1)
                    "getInt" -> rs.getInt((args[0] as Int) + 1)
                    // Real Cursor semantics: 0-based index for the named column.
                    "getColumnIndex" -> rs.findColumn(args[0] as String) - 1
                    "getColumnCount" -> rs.metaData.columnCount
                    "close" -> {
                        rs.close()
                        stmt.close()
                        null
                    }

                    "isAfterLast" -> rs.isAfterLast
                    "isClosed" -> rs.isClosed
                    else -> throw UnsupportedOperationException(
                        "Test adapter: unexpected Cursor call '${method.name}'",
                    )
                }
            }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Cursor::class.java),
            handler,
        ) as Cursor
    }
}
