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
 * Verifies MIGRATION_57_58 (the stored hide-streaks display preference).
 *
 * What matters here:
 * 1. The added column must match the Room-exported schema 58 exactly -- `hideStreaks` INTEGER
 *    NOT NULL -- or Room's identity check rejects the database at open time and the app cannot
 *    start for anyone upgrading.
 * 2. It must default to 0. Nobody should lose their streaks because they updated the app; only
 *    turning the setting on hides them.
 * 3. Everything already in user_preferences has to come through untouched.
 *
 * Runs the shipped migration against in-memory SQLite through a reflection proxy, matching the
 * harness used by the other migration tests.
 */
class Migration57To58Test {
    private lateinit var connection: Connection

    /** user_preferences exactly as exported in 57.json. */
    private val userPreferences57CreateSql =
        """
        CREATE TABLE IF NOT EXISTS `user_preferences` (
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
            `lastSpotlightStoryViewed` TEXT,
            PRIMARY KEY(`id`)
        )
        """.trimIndent()

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { it.execute(userPreferences57CreateSql) }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    // ---- Portability -------------------------------------------------------

    @Test
    fun `migration SQL avoids features missing on API 26 SQLite`() {
        // minSdk 26 devices ship SQLite 3.18-3.22: no window functions, no UPSERT, and no
        // `ADD COLUMN IF NOT EXISTS` -- the guard has to be a PRAGMA, not SQL syntax.
        val unsupported = listOf(
            "over (", "row_number", "dense_rank", "ntile", "lag(", "lead(",
            "on conflict", "do update set", "returning", "if not exists",
        )
        val sql = migrationSqlText().lowercase()
        unsupported.forEach { token ->
            assertFalse(
                "MIGRATION_57_58 must not use '$token': API 26 SQLite cannot parse it",
                sql.contains(token),
            )
        }
    }

    // ---- Schema ------------------------------------------------------------

    @Test
    fun `adds hideStreaks as the last user_preferences column`() {
        runRealMigration()

        assertEquals(
            "hideStreaks",
            tableColumns("user_preferences").last(),
        )
    }

    @Test
    fun `hideStreaks matches the exported not-null integer contract`() {
        runRealMigration()

        val info = columnInfo("user_preferences", "hideStreaks")
        assertEquals("INTEGER", info.type)
        assertTrue("Room expects a non-null boolean column", info.notNull)
        assertEquals("must default to 0 so upgrading changes nothing", "0", info.defaultValue)
        assertEquals("must not be part of the primary key", 0, info.pk)
    }

    // ---- Existing data -----------------------------------------------------

    @Test
    fun `existing preferences survive and keep streaks visible`() {
        insertPreferences(
            id = 0,
            isGamificationEnabled = 0,
            pauseTrackingOnLowBattery = 0,
            lastSpotlightStoryViewed = "M2026-08",
            lastfmUsername = "listener",
        )

        runRealMigration()

        assertEquals(1, rowCount("user_preferences"))
        assertEquals(
            "the gamification switch must come through as the user left it",
            listOf(0L),
            scalarLongs("SELECT isGamificationEnabled FROM user_preferences"),
        )
        assertEquals(0, scalarLongs("SELECT pauseTrackingOnLowBattery FROM user_preferences").first())
        assertEquals("listener", scalarStrings("SELECT lastfmUsername FROM user_preferences").first())
        assertEquals("M2026-08", scalarStrings("SELECT lastSpotlightStoryViewed FROM user_preferences").first())
        assertEquals(
            "an existing install must find streaks visible after the upgrade",
            listOf(0L),
            scalarLongs("SELECT hideStreaks FROM user_preferences"),
        )
    }

    // ---- Re-entrancy -------------------------------------------------------

    @Test
    fun `migration is re-entrant and keeps a choice the user already made`() {
        insertPreferences(id = 0, isGamificationEnabled = 1, pauseTrackingOnLowBattery = 1)
        runRealMigration()

        // A value the fresh migration would never produce: if the guard failed, the duplicate
        // ALTER would throw; if it silently re-added the column, this would be reset to 0.
        connection.createStatement().use {
            it.execute("UPDATE user_preferences SET hideStreaks = 1")
        }
        AppDatabase.MIGRATION_57_58.migrate(proxyDatabase())

        assertEquals(listOf(1L), scalarLongs("SELECT hideStreaks FROM user_preferences"))
    }

    @Test
    fun `an install with no preferences row migrates cleanly`() {
        runRealMigration()

        assertEquals(0, rowCount("user_preferences"))
        assertTrue(tableColumns("user_preferences").contains("hideStreaks"))
    }

    // ---- Helpers -----------------------------------------------------------

    private fun runRealMigration() {
        AppDatabase.MIGRATION_57_58.migrate(proxyDatabase())
    }

    private fun insertPreferences(
        id: Int,
        isGamificationEnabled: Int,
        pauseTrackingOnLowBattery: Int,
        lastSpotlightStoryViewed: String? = null,
        lastfmUsername: String? = null,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO user_preferences (
                    `id`, `theme`, `notifications`, `spotifyLinked`, `extendedAudioAnalysis`,
                    `mergeAlternateVersions`, `filterPodcasts`, `filterAudiobooks`,
                    `hasSeenHistoryCoachMark`, `hasSeenSpotlightTutorial`, `hasSeenStatsSortTutorial`,
                    `hasSeenStatsItemClickTutorial`, `spotifyApiOnlyMode`, `lastfmConnected`,
                    `lastfmSyncFrequency`, `isGamificationEnabled`, `pauseTrackingOnLowBattery`,
                    `lastSpotlightStoryViewed`, `lastfmUsername`
                ) VALUES (?, 'system', 1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 'NONE', ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setInt(1, id)
                ps.setInt(2, isGamificationEnabled)
                ps.setInt(3, pauseTrackingOnLowBattery)
                ps.setString(4, lastSpotlightStoryViewed)
                ps.setString(5, lastfmUsername)
                ps.executeUpdate()
            }
    }

    private fun scalarLongs(sql: String): List<Long> {
        val values = mutableListOf<Long>()
        connection.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) values.add(rs.getLong(1))
        }
        return values
    }

    private fun scalarStrings(sql: String): List<String?> {
        val values = mutableListOf<String?>()
        connection.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) values.add(rs.getString(1))
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

    private fun rowCount(table: String): Int =
        connection.createStatement().executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
            rs.next()
            rs.getInt(1)
        }

    private fun migrationSqlText(): String {
        val captured = StringBuilder()
        AppDatabase.MIGRATION_57_58.migrate(capturingDatabase(captured))
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
