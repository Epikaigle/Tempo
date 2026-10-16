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
 * Verifies MIGRATION_55_56 (detected_apps inbox + seeding from listening history).
 *
 * Two things matter here:
 * 1. The CREATE TABLE must match the Room-exported schema exactly, or the identity-hash check
 *    fails at open time and the app crashes on upgrade for every existing user.
 * 2. Seeding must admit real app packages from history and reject the import markers that share
 *    the `source` column, otherwise the inbox opens full of "import.json" noise.
 *
 * Runs the shipped migration against an in-memory SQLite database through a reflection proxy,
 * matching the harness used by the other migration tests (SQLite on Android 8–9 cannot parse
 * window functions or UPSERT, so those are also asserted against).
 */
class Migration55To56Test {
    private lateinit var connection: Connection

    /** listening_events exactly as exported in 55.json. */
    private val listeningEvents55CreateSql =
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

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = OFF")
            stmt.execute(listeningEvents55CreateSql)
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
                "MIGRATION_55_56 must not use '$token': API 26 SQLite cannot parse it",
                sql.contains(token),
            )
        }
    }

    // ---- Schema ------------------------------------------------------------

    @Test
    fun `creates the detected_apps table with the exported columns`() {
        runRealMigration()

        assertEquals(
            listOf(
                "packageName",
                "lastTitle",
                "lastArtist",
                "detectionCount",
                "firstSeenAt",
                "lastSeenAt",
            ),
            tableColumns("detected_apps"),
        )
    }

    @Test
    fun `creates the lastSeenAt index used for inbox ordering`() {
        runRealMigration()

        val indexes = mutableListOf<String>()
        connection.createStatement().executeQuery("PRAGMA index_list(detected_apps)").use { rs ->
            while (rs.next()) indexes.add(rs.getString("name"))
        }
        assertTrue(
            "Expected index_detected_apps_lastSeenAt, got $indexes",
            indexes.contains("index_detected_apps_lastSeenAt"),
        )
    }

    @Test
    fun `detectionCount defaults to one and timestamps default to zero`() {
        runRealMigration()

        // Column defaults matter: the DTO relies on them for rows seeded before a field existed.
        connection.createStatement().use { stmt ->
            stmt.execute("INSERT INTO detected_apps (packageName) VALUES ('com.example.player')")
        }

        connection
            .createStatement()
            .executeQuery("SELECT detectionCount, firstSeenAt, lastSeenAt, lastTitle FROM detected_apps")
            .use { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("detectionCount"))
                assertEquals(0L, rs.getLong("firstSeenAt"))
                assertEquals(0L, rs.getLong("lastSeenAt"))
                assertEquals(null, rs.getString("lastTitle"))
            }
    }

    // ---- Seeding -----------------------------------------------------------

    @Test
    fun `seeds one inbox row per live app package found in history`() {
        insertEvent(source = "com.spotify.music", timestamp = 1_000)
        insertEvent(source = "com.spotify.music", timestamp = 3_000)
        insertEvent(source = "com.example.niche.player", timestamp = 2_000)

        runRealMigration()

        assertEquals(2, rowCount("detected_apps"))
        assertTrue(seededPackages().containsAll(listOf("com.spotify.music", "com.example.niche.player")))
    }

    @Test
    fun `seeded counts and timestamps summarise the app's history`() {
        insertEvent(source = "com.example.niche.player", timestamp = 5_000)
        insertEvent(source = "com.example.niche.player", timestamp = 1_000)
        insertEvent(source = "com.example.niche.player", timestamp = 9_000)

        runRealMigration()

        connection
            .createStatement()
            .executeQuery(
                "SELECT detectionCount, firstSeenAt, lastSeenAt FROM detected_apps " +
                    "WHERE packageName = 'com.example.niche.player'",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals(3, rs.getInt("detectionCount"))
                assertEquals(1_000L, rs.getLong("firstSeenAt"))
                assertEquals(9_000L, rs.getLong("lastSeenAt"))
            }
    }

    @Test
    fun `import markers sharing the source column are not seeded`() {
        // These all live in listening_events.source but are not installable app packages.
        insertEvent(source = "lastfm:import.json", timestamp = 1_000)
        insertEvent(source = "import.reconstructed", timestamp = 2_000)
        insertEvent(source = "desktop:abc123", timestamp = 3_000)
        insertEvent(source = "fm.last.import", timestamp = 4_000)
        insertEvent(source = "spotify.import.json", timestamp = 5_000)
        insertEvent(source = "com.example.niche.player", timestamp = 6_000)

        runRealMigration()

        assertEquals(listOf("com.example.niche.player"), seededPackages())
    }

    @Test
    fun `undotted and blank sources are not seeded`() {
        insertEvent(source = "local", timestamp = 1_000)
        insertEvent(source = "", timestamp = 2_000)

        runRealMigration()

        assertEquals(0, rowCount("detected_apps"))
    }

    @Test
    fun `seeding is a no-op when history is empty`() {
        runRealMigration()

        assertEquals(0, rowCount("detected_apps"))
    }

    @Test
    fun `migration is idempotent`() {
        insertEvent(source = "com.example.niche.player", timestamp = 1_000)

        runRealMigration()
        val firstPass = rowCount("detected_apps")
        // Re-running must not throw and must not duplicate rows.
        AppDatabase.MIGRATION_55_56.migrate(proxyDatabase())

        assertEquals(firstPass, rowCount("detected_apps"))
    }

    // ---- Helpers -----------------------------------------------------------

    private fun runRealMigration() {
        AppDatabase.MIGRATION_55_56.migrate(proxyDatabase())
    }

    private fun insertEvent(
        source: String,
        timestamp: Long,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO listening_events " +
                    "(`track_id`, `timestamp`, `playDuration`, `completionPercentage`, `source`) " +
                    "VALUES (1, ?, 1000, 50, ?)",
            ).use { ps ->
                ps.setLong(1, timestamp)
                ps.setString(2, source)
                ps.executeUpdate()
            }
    }

    private fun seededPackages(): List<String> {
        val packages = mutableListOf<String>()
        connection
            .createStatement()
            .executeQuery("SELECT packageName FROM detected_apps ORDER BY packageName")
            .use { rs ->
                while (rs.next()) packages.add(rs.getString(1))
            }
        return packages
    }

    private fun tableColumns(table: String): List<String> {
        val columns = mutableListOf<String>()
        connection.createStatement().executeQuery("PRAGMA table_info($table)").use { rs ->
            while (rs.next()) columns.add(rs.getString("name"))
        }
        return columns
    }

    private fun rowCount(table: String): Int =
        connection.createStatement().executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
            rs.next()
            rs.getInt(1)
        }

    private fun migrationSqlText(): String {
        val captured = StringBuilder()
        AppDatabase.MIGRATION_55_56.migrate(capturingDatabase(captured))
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
                    "getColumnIndex" -> 1
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
