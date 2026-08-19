package me.avinas.tempo.data.local

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * Verifies that MIGRATION_51_52 correctly reconciles the two divergent
 * schema-51 lineages that exist in the wild:
 *
 *  - public 4.8.2 (column `lastViewedSpotlightPeriod`) → must be rebuilt
 *    into the canonical 52 shape with ALL preference data preserved.
 *  - internal/canonical (column `lastSpotlightStoryViewed`) → must be a
 *    complete no-op (no data loss, no shape change).
 *  - unknown third shape (neither column) → must be defensively rebuilt
 *    with shared-column data preserved.
 *
 * Unlike Migration49To50Test (which re-executes the SQL by hand), this test
 * invokes the REAL [AppDatabase.MIGRATION_51_52] object. A thin reflection
 * proxy adapts the JDBC in-memory SQLite connection to Room's
 * [SupportSQLiteDatabase] interface, so the production PRAGMA probe,
 * rebuild, INSERT…SELECT, DROP and RENAME all execute exactly as shipped.
 */
class Migration51To52Test {

    private lateinit var connection: Connection

    /** Exact CREATE for user_preferences as shipped in public 4.8.2 (upstream 51.json). */
    private val public51CreateSql = """
        CREATE TABLE user_preferences (
            `id` INTEGER NOT NULL,
            `theme` TEXT NOT NULL,
            `notifications` INTEGER NOT NULL,
            `spotifyLinked` INTEGER NOT NULL,
            `extendedAudioAnalysis` INTEGER NOT NULL,
            `mergeAlternateVersions` INTEGER NOT NULL,
            `filterPodcasts` INTEGER NOT NULL,
            `filterAudiobooks` INTEGER NOT NULL,
            `hasSeenHistoryCoachMark` INTEGER NOT NULL,
            `hasSeenSpotlightTutorial` INTEGER NOT NULL,
            `hasSeenStatsSortTutorial` INTEGER NOT NULL,
            `hasSeenStatsItemClickTutorial` INTEGER NOT NULL,
            `lastWeeklyReminderShown` TEXT,
            `lastMonthlyReminderShown` TEXT,
            `lastYearlyReminderShown` TEXT,
            `lastAllTimeReminderShown` TEXT,
            `lastViewedSpotlightPeriod` TEXT,
            `spotifyApiOnlyMode` INTEGER NOT NULL,
            `spotifyImportCursor` TEXT,
            `lastSpotifyImportTimestamp` INTEGER,
            `lastfmUsername` TEXT,
            `lastfmConnected` INTEGER NOT NULL,
            `lastfmSyncFrequency` TEXT NOT NULL,
            `smartChallengeNotifHour` INTEGER,
            `smartChallengeNotifCalcTime` INTEGER,
            `isGamificationEnabled` INTEGER NOT NULL,
            `pauseTrackingOnLowBattery` INTEGER NOT NULL,
            PRIMARY KEY(`id`)
        )
    """.trimIndent()

    /** The one column the two 51-lineages differ on (public side). */
    private val publicOnlyColumn = "lastViewedSpotlightPeriod"
    /** The one column the two 51-lineages differ on (canonical side). */
    private val canonicalColumn = "lastSpotlightStoryViewed"

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    // ──────────────────────────────────────────────────────────────
    // Test 1: the real-world critical path — a public 4.8.2 device
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `public 482 shape rebuilds into canonical 52 shape with all data preserved`() {
        createPublic51Table()
        insertPublic51Row(
            id = 1, theme = "dark", notifications = 1, spotifyLinked = 0,
            extendedAudioAnalysis = 1, mergeAlternateVersions = 0,
            filterPodcasts = 1, filterAudiobooks = 1,
            hasSeenHistoryCoachMark = 1, hasSeenSpotlightTutorial = 1,
            hasSeenStatsSortTutorial = 0, hasSeenStatsItemClickTutorial = 1,
            lastWeeklyReminderShown = "2026-08-01", lastMonthlyReminderShown = null,
            lastYearlyReminderShown = null, lastAllTimeReminderShown = "2025-06-01",
            lastViewedSpotlightPeriod = "W2026-08-17",
            spotifyApiOnlyMode = 0, spotifyImportCursor = "abc123",
            lastSpotifyImportTimestamp = 1755500000000L, lastfmUsername = "listener_x",
            lastfmConnected = 1, lastfmSyncFrequency = "DAILY",
            smartChallengeNotifHour = 21, smartChallengeNotifCalcTime = 1755400000000L,
            isGamificationEnabled = 0, pauseTrackingOnLowBattery = 0
        )

        runRealMigration()

        // Shape assertions
        val columns = tableColumns()
        assertTrue("Canonical column must exist after migration", canonicalColumn in columns)
        assertFalse("Public-only column must be gone after migration", publicOnlyColumn in columns)
        assertEquals(
            "Column set must match the exported 52.json schema exactly",
            expected52Columns(),
            columns
        )
        assertRowOneSurvivedRebuild()
        // Only one row — nothing duplicated by the rebuild
        assertEquals(1, rowCount("user_preferences"))
    }

    /** Verifies every shared preference value from Test 1's row survived verbatim. */
    private fun assertRowOneSurvivedRebuild() {
        connection.createStatement().executeQuery(
            "SELECT * FROM user_preferences WHERE id = 1"
        ).use { rs ->
            assertTrue("Row must survive the rebuild", rs.next())
            assertEquals("dark", rs.getString("theme"))
            assertEquals(1, rs.getInt("notifications"))
            assertEquals(0, rs.getInt("spotifyLinked"))
            assertEquals(1, rs.getInt("extendedAudioAnalysis"))
            assertEquals(0, rs.getInt("mergeAlternateVersions"))
            assertEquals(1, rs.getInt("filterPodcasts"))
            assertEquals(1, rs.getInt("filterAudiobooks"))
            assertEquals(1, rs.getInt("hasSeenHistoryCoachMark"))
            assertEquals(1, rs.getInt("hasSeenSpotlightTutorial"))
            assertEquals(0, rs.getInt("hasSeenStatsSortTutorial"))
            assertEquals(1, rs.getInt("hasSeenStatsItemClickTutorial"))
            assertEquals("2026-08-01", rs.getString("lastWeeklyReminderShown"))
            assertNull("Nullable column must stay null", rs.getString("lastMonthlyReminderShown"))
            assertNull(rs.getString("lastYearlyReminderShown"))
            assertEquals("2025-06-01", rs.getString("lastAllTimeReminderShown"))
            assertEquals(0, rs.getInt("spotifyApiOnlyMode"))
            assertEquals("abc123", rs.getString("spotifyImportCursor"))
            assertEquals(1755500000000L, rs.getLong("lastSpotifyImportTimestamp"))
            assertEquals("listener_x", rs.getString("lastfmUsername"))
            assertEquals(1, rs.getInt("lastfmConnected"))
            assertEquals("DAILY", rs.getString("lastfmSyncFrequency"))
            assertEquals(21, rs.getInt("smartChallengeNotifHour"))
            assertEquals(1755400000000L, rs.getLong("smartChallengeNotifCalcTime"))
            assertEquals(0, rs.getInt("isGamificationEnabled"))
            assertEquals(0, rs.getInt("pauseTrackingOnLowBattery"))
            // The public story-viewed flag is intentionally dropped (cosmetic only)
            assertNull(
                "Canonical story-viewed flag must start NULL for public upgraders",
                rs.getString(canonicalColumn)
            )
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Test 2: canonical shape → no-op
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `canonical shape is a no-op with zero data loss`() {
        createCanonical51Table()
        connection.createStatement().use { stmt ->
            stmt.execute(
                "INSERT INTO user_preferences (id, theme, notifications, spotifyLinked, " +
                    "extendedAudioAnalysis, mergeAlternateVersions, filterPodcasts, filterAudiobooks, " +
                    "hasSeenHistoryCoachMark, hasSeenSpotlightTutorial, hasSeenStatsSortTutorial, " +
                    "hasSeenStatsItemClickTutorial, spotifyApiOnlyMode, lastfmConnected, " +
                    "lastfmSyncFrequency, isGamificationEnabled, pauseTrackingOnLowBattery, " +
                    "$canonicalColumn) VALUES (1, 'light', 0, 1, 0, 1, 0, 0, 0, 0, 1, 1, " +
                    "0, 1, 'NONE', 1, 1, 'W2026-08-03')"
            )
        }

        runRealMigration()

        val columns = tableColumns()
        assertTrue(canonicalColumn in columns)
        assertEquals("Column set must be unchanged", expected52Columns(), columns)

        connection.createStatement().executeQuery(
            "SELECT theme, notifications, spotifyLinked, $canonicalColumn FROM user_preferences WHERE id = 1"
        ).use { rs ->
            assertTrue(rs.next())
            assertEquals("light", rs.getString("theme"))
            assertEquals(0, rs.getInt("notifications"))
            assertEquals(1, rs.getInt("spotifyLinked"))
            // The user's own story-viewed state MUST survive the no-op path
            assertEquals("W2026-08-03", rs.getString(canonicalColumn))
        }
        assertEquals(1, rowCount("user_preferences"))
    }

    // ──────────────────────────────────────────────────────────────
    // Test 3: unknown third shape → defensive rebuild
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `unknown shape rebuilds defensively keeping shared data`() {
        // A hypothetical third 51-variant: neither spotlight column present
        connection.createStatement().use { stmt ->
            stmt.execute(public51CreateSql.replace("`$publicOnlyColumn` TEXT,", ""))
            stmt.execute(
                "INSERT INTO user_preferences (id, theme, notifications, spotifyLinked, " +
                    "extendedAudioAnalysis, mergeAlternateVersions, filterPodcasts, filterAudiobooks, " +
                    "hasSeenHistoryCoachMark, hasSeenSpotlightTutorial, hasSeenStatsSortTutorial, " +
                    "hasSeenStatsItemClickTutorial, spotifyApiOnlyMode, lastfmConnected, " +
                    "lastfmSyncFrequency, isGamificationEnabled, pauseTrackingOnLowBattery) " +
                    "VALUES (1, 'dark', 1, 0, 0, 0, 1, 1, 0, 1, 0, 0, 1, 0, 'WEEKLY', 1, 1)"
            )
        }

        runRealMigration()

        assertEquals(expected52Columns(), tableColumns())
        connection.createStatement().executeQuery(
            "SELECT theme, lastfmSyncFrequency FROM user_preferences WHERE id = 1"
        ).use { rs ->
            assertTrue(rs.next())
            assertEquals("dark", rs.getString("theme"))
            assertEquals("WEEKLY", rs.getString("lastfmSyncFrequency"))
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    /** Runs the REAL production migration object over the JDBC connection. */
    private fun runRealMigration() {
        AppDatabase.MIGRATION_51_52.migrate(proxyDatabase())
    }

    /**
     * Adapts the JDBC connection to Room's SupportSQLiteDatabase via a
     * reflection proxy. Only the surface the migration touches is
     * implemented (query(String) for the PRAGMA probe, execSQL(String) for
     * the rebuild); anything else fails loudly so new migration code paths
     * can't silently bypass testing.
     */
    private fun proxyDatabase(): SupportSQLiteDatabase {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "query" -> {
                    val sql = args[0] as String
                    val stmt = connection.createStatement()
                    val rs = stmt.executeQuery(sql)
                    cursorProxy(stmt, rs)
                }
                "execSQL" -> {
                    val sql = args[0] as String
                    connection.createStatement().use { it.execute(sql) }
                    null
                }
                "inTransaction" -> false
                "isDbLockedByCurrentThread" -> false
                "isOpen" -> true
                else -> throw UnsupportedOperationException(
                    "Test adapter: unexpected SupportSQLiteDatabase call '${method.name}'"
                )
            }
        }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler
        ) as SupportSQLiteDatabase
    }

    /**
     * Adapts a JDBC ResultSet to android.database.Cursor. The migration's
     * PRAGMA probe reads table_info's 'name' column (physical index 1 in
     * Cursor terms), so getString/getColumnIndex are pinned accordingly.
     */
    private fun cursorProxy(stmt: Statement, rs: ResultSet): Cursor {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "moveToNext" -> rs.next()
                // Cursor indexes are 0-based; JDBC is 1-based. PRAGMA table_info's
                // 'name' column is Cursor index 1 → JDBC index 2.
                "getString" -> rs.getString((args[0] as Int) + 1)
                "getColumnIndex" -> 1
                "getColumnCount" -> rs.metaData.columnCount
                "close" -> { rs.close(); stmt.close(); null }
                "isAfterLast" -> rs.isAfterLast
                "isClosed" -> rs.isClosed
                else -> throw UnsupportedOperationException(
                    "Test adapter: unexpected Cursor call '${method.name}'"
                )
            }
        }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Cursor::class.java),
            handler
        ) as Cursor
    }

    private fun createPublic51Table() {
        connection.createStatement().use { it.execute(public51CreateSql) }
    }

    private fun createCanonical51Table() {
        // Canonical internal 51 shape: same 26 shared columns, but the spotlight
        // column is `lastSpotlightStoryViewed` at the END (entity field order),
        // not at the public build's mid-table position. Note: column lines in
        // public51CreateSql end with "TEXT,\n" (no trailing space).
        val canonicalCreate = public51CreateSql
            .replace("`$publicOnlyColumn` TEXT,", "")
            .replace("PRIMARY KEY(`id`)", "`$canonicalColumn` TEXT, PRIMARY KEY(`id`)")
        connection.createStatement().use { stmt ->
            stmt.execute(canonicalCreate)
        }
    }

    private fun insertPublic51Row(
        id: Int, theme: String = "dark", notifications: Int = 1,
        spotifyLinked: Int = 0, extendedAudioAnalysis: Int = 0,
        mergeAlternateVersions: Int = 0, filterPodcasts: Int = 1,
        filterAudiobooks: Int = 1, hasSeenHistoryCoachMark: Int = 0,
        hasSeenSpotlightTutorial: Int = 0, hasSeenStatsSortTutorial: Int = 0,
        hasSeenStatsItemClickTutorial: Int = 0,
        lastWeeklyReminderShown: String? = null, lastMonthlyReminderShown: String? = null,
        lastYearlyReminderShown: String? = null, lastAllTimeReminderShown: String? = null,
        lastViewedSpotlightPeriod: String? = null, spotifyApiOnlyMode: Int = 0,
        spotifyImportCursor: String? = null, lastSpotifyImportTimestamp: Long? = null,
        lastfmUsername: String? = null, lastfmConnected: Int = 0,
        lastfmSyncFrequency: String = "NONE", smartChallengeNotifHour: Int? = null,
        smartChallengeNotifCalcTime: Long? = null, isGamificationEnabled: Int = 1,
        pauseTrackingOnLowBattery: Int = 1
    ) {
        connection.prepareStatement(
            "INSERT INTO user_preferences VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            var i = 0
            fun set(v: Any?) { ps.setObject(++i, v) }
            set(id); set(theme); set(notifications); set(spotifyLinked)
            set(extendedAudioAnalysis); set(mergeAlternateVersions); set(filterPodcasts)
            set(filterAudiobooks); set(hasSeenHistoryCoachMark); set(hasSeenSpotlightTutorial)
            set(hasSeenStatsSortTutorial); set(hasSeenStatsItemClickTutorial)
            set(lastWeeklyReminderShown); set(lastMonthlyReminderShown)
            set(lastYearlyReminderShown); set(lastAllTimeReminderShown)
            set(lastViewedSpotlightPeriod); set(spotifyApiOnlyMode); set(spotifyImportCursor)
            set(lastSpotifyImportTimestamp); set(lastfmUsername); set(lastfmConnected)
            set(lastfmSyncFrequency); set(smartChallengeNotifHour)
            set(smartChallengeNotifCalcTime); set(isGamificationEnabled)
            set(pauseTrackingOnLowBattery)
            assertEquals("All 27 columns bound", 27, i)
            ps.executeUpdate()
        }
    }

    /** Column names of user_preferences in physical order. */
    private fun tableColumns(): List<String> {
        val cols = mutableListOf<String>()
        connection.createStatement().executeQuery("PRAGMA table_info(user_preferences)").use { rs ->
            while (rs.next()) cols.add(rs.getString("name"))
        }
        return cols
    }

    /**
     * The exact column order declared by Room's exported 52.json for
     * user_preferences (entity field order). The rebuild must reproduce it.
     */
    private fun expected52Columns() = listOf(
        "id", "theme", "notifications", "spotifyLinked", "extendedAudioAnalysis",
        "mergeAlternateVersions", "filterPodcasts", "filterAudiobooks",
        "hasSeenHistoryCoachMark", "hasSeenSpotlightTutorial", "hasSeenStatsSortTutorial",
        "hasSeenStatsItemClickTutorial", "lastWeeklyReminderShown", "lastMonthlyReminderShown",
        "lastYearlyReminderShown", "lastAllTimeReminderShown", "spotifyApiOnlyMode",
        "spotifyImportCursor", "lastSpotifyImportTimestamp", "lastfmUsername",
        "lastfmConnected", "lastfmSyncFrequency", "smartChallengeNotifHour",
        "smartChallengeNotifCalcTime", "isGamificationEnabled", "pauseTrackingOnLowBattery",
        canonicalColumn
    )

    private fun rowCount(table: String): Int =
        connection.createStatement().executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
            rs.next(); rs.getInt(1)
        }
}
