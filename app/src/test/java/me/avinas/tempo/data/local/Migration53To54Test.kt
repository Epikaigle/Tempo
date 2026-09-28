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
 * Verifies MIGRATION_53_54 (challenge dedupe + banked_challenge_xp) compatibility and ordering.
 *
 * Ensures compatibility with SQLite 3.18-3.22 on Android 8.0-9 (minSdk 26), where
 * window functions (`ROW_NUMBER() OVER`) are unsupported. Uses a correlated EXISTS instead.
 *
 * Executes [AppDatabase.MIGRATION_53_54] against an in-memory SQLite database via a reflection
 * proxy to verify the actual SQL statements.
 */
class Migration53To54Test {
    private lateinit var connection: Connection

    /** daily_challenges exactly as exported in 53.json (no unique (challenge_id, date) index). */
    private val dailyChallenges53CreateSql =
        """
        CREATE TABLE IF NOT EXISTS `daily_challenges` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `challenge_id` TEXT NOT NULL,
            `date` TEXT NOT NULL,
            `title` TEXT NOT NULL,
            `description` TEXT NOT NULL,
            `xp_reward` INTEGER NOT NULL,
            `target_value` INTEGER NOT NULL,
            `current_progress` INTEGER NOT NULL,
            `is_completed` INTEGER NOT NULL,
            `completed_at` INTEGER NOT NULL,
            `category` TEXT NOT NULL,
            `difficulty` TEXT NOT NULL,
            `target_metadata` TEXT
        )
        """.trimIndent()

    /** user_level as exported in 53.json (without banked_challenge_xp). */
    private val userLevel53CreateSql =
        """
        CREATE TABLE IF NOT EXISTS `user_level` (
            `id` INTEGER NOT NULL,
            `total_xp` INTEGER NOT NULL,
            `current_level` INTEGER NOT NULL,
            `xp_for_current_level` INTEGER NOT NULL,
            `xp_for_next_level` INTEGER NOT NULL,
            `last_xp_awarded_at` INTEGER NOT NULL,
            `current_streak` INTEGER NOT NULL,
            `longest_streak` INTEGER NOT NULL,
            `last_streak_date` TEXT NOT NULL,
            PRIMARY KEY(`id`)
        )
        """.trimIndent()

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("PRAGMA foreign_keys = OFF")
            stmt.execute(dailyChallenges53CreateSql)
            stmt.execute(userLevel53CreateSql)
            stmt.execute("INSERT INTO user_level VALUES (1, 500, 3, 400, 600, 0, 2, 5, '2026-09-01')")
        }
    }

    @After
    fun tearDown() {
        connection.close()
    }

    // The shipped statement must not use window functions, which SQLite on Android 8/9 cannot parse.

    @Test
    fun `migration SQL contains no window functions`() {
        val windowFunctionTokens = listOf("over (", "row_number", "rank(", "dense_rank", "ntile", "lag(", "lead(")
        val sql = migrationSqlText()
        windowFunctionTokens.forEach { token ->
            assertFalse(
                "MIGRATION_53_54 must not use window functions ('$token'): minSdk-26 devices " +
                    "ship SQLite 3.18-3.22, which cannot parse them",
                sql.lowercase().contains(token),
            )
        }
    }

    // Deduplication ordering: completed, highest progress, highest id.

    @Test
    fun `dedupe keeps the completed row over an uncompleted one`() {
        insertChallenge(challengeId = "listen_10", date = "2026-09-20", progress = 9, completed = 0, id = 1)
        insertChallenge(challengeId = "listen_10", date = "2026-09-20", progress = 2, completed = 1, id = 2)

        runRealMigration()

        assertEquals(listOf(2L), survivingIds())
    }

    @Test
    fun `dedupe breaks ties on progress when completion is equal`() {
        insertChallenge(challengeId = "listen_10", date = "2026-09-20", progress = 3, completed = 0, id = 1)
        insertChallenge(challengeId = "listen_10", date = "2026-09-20", progress = 7, completed = 0, id = 2)

        runRealMigration()

        assertEquals(listOf(2L), survivingIds())
    }

    @Test
    fun `dedupe breaks full ties on the highest id`() {
        insertChallenge(challengeId = "listen_10", date = "2026-09-20", progress = 5, completed = 1, id = 1)
        insertChallenge(challengeId = "listen_10", date = "2026-09-20", progress = 5, completed = 1, id = 2)

        runRealMigration()

        assertEquals(listOf(2L), survivingIds())
    }

    @Test
    fun `dedupe never merges rows from different challenges or dates`() {
        // Same date, different challenge
        insertChallenge(challengeId = "listen_10", date = "2026-09-20", progress = 1, completed = 0, id = 1)
        insertChallenge(challengeId = "listen_20", date = "2026-09-20", progress = 1, completed = 0, id = 2)
        // Same challenge, different date
        insertChallenge(challengeId = "listen_10", date = "2026-09-21", progress = 1, completed = 0, id = 3)
        // A duplicate of the first group that must lose
        insertChallenge(challengeId = "listen_10", date = "2026-09-20", progress = 1, completed = 0, id = 4)

        runRealMigration()

        assertEquals(listOf(2L, 3L, 4L), survivingIds())
    }

    @Test
    fun `a single row per challenge survives untouched`() {
        insertChallenge(challengeId = "only_one", date = "2026-09-20", progress = 4, completed = 1, id = 1)

        runRealMigration()

        assertEquals(listOf(1L), survivingIds())
        assertEquals(1, rowCount("daily_challenges"))
    }

    @Test
    fun `dedupe is a no-op on an already-deduped table`() {
        insertChallenge(challengeId = "a", date = "2026-09-20", progress = 1, completed = 0, id = 1)
        insertChallenge(challengeId = "b", date = "2026-09-20", progress = 1, completed = 0, id = 2)

        runRealMigration()
        val firstPass = survivingIds()
        // Re-running must not delete anything else (idempotent).
        AppDatabase.MIGRATION_53_54.migrate(proxyDatabase())

        assertEquals(firstPass, survivingIds())
    }

    // Schema effects: unique index + the new column.

    @Test
    fun `migration adds the unique challenge index`() {
        insertChallenge(challengeId = "a", date = "2026-09-20", progress = 1, completed = 0, id = 1)
        insertChallenge(challengeId = "a", date = "2026-09-20", progress = 2, completed = 0, id = 2)

        runRealMigration()

        val uniqueIndexes = mutableListOf<String>()
        connection.createStatement().executeQuery("PRAGMA index_list(daily_challenges)").use { rs ->
            while (rs.next()) {
                // PRAGMA index_list columns: seq, name, unique, origin, partial
                if (rs.getInt("unique") == 1) uniqueIndexes.add(rs.getString("name"))
            }
        }
        assertTrue(
            "Expected unique index on daily_challenges(challenge_id, date), got $uniqueIndexes",
            uniqueIndexes.contains("index_daily_challenges_challenge_id_date"),
        )
    }

    @Test
    fun `migration adds banked_challenge_xp defaulting to zero and preserves the row`() {
        runRealMigration()

        val columns = tableColumns("user_level")
        assertTrue("banked_challenge_xp must exist", "banked_challenge_xp" in columns)

        connection
            .createStatement()
            .executeQuery(
                "SELECT total_xp, current_level, banked_challenge_xp FROM user_level WHERE id = 1",
            ).use { rs ->
                assertTrue(rs.next())
                assertEquals(500L, rs.getLong("total_xp"))
                assertEquals(3, rs.getInt("current_level"))
                assertEquals("Existing XP must not change", 0L, rs.getLong("banked_challenge_xp"))
            }
    }

    @Test
    fun `unique index rejects a new duplicate after migration`() {
        insertChallenge(challengeId = "a", date = "2026-09-20", progress = 1, completed = 0, id = 1)
        runRealMigration()

        val threw =
            try {
                insertChallenge(challengeId = "a", date = "2026-09-20", progress = 9, completed = 1, id = 2)
                false
            } catch (e: java.sql.SQLException) {
                true
            }
        assertTrue("The unique (challenge_id, date) index must block duplicate generation", threw)
    }

    // Helpers

    private fun runRealMigration() {
        AppDatabase.MIGRATION_53_54.migrate(proxyDatabase())
    }

    private fun insertChallenge(
        challengeId: String,
        date: String,
        progress: Int,
        completed: Int,
        id: Long,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO daily_challenges " +
                    "(id, challenge_id, date, title, description, xp_reward, target_value, " +
                    "current_progress, is_completed, completed_at, category, difficulty, target_metadata) " +
                    "VALUES (?, ?, ?, 't', 'd', 10, 10, ?, ?, 0, 'TIME', 'EASY', NULL)",
            ).use { ps ->
                ps.setLong(1, id)
                ps.setString(2, challengeId)
                ps.setString(3, date)
                ps.setInt(4, progress)
                ps.setInt(5, completed)
                ps.executeUpdate()
            }
    }

    private fun survivingIds(): List<Long> {
        val ids = mutableListOf<Long>()
        connection.createStatement().executeQuery("SELECT id FROM daily_challenges ORDER BY id").use { rs ->
            while (rs.next()) ids.add(rs.getLong(1))
        }
        return ids
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

    /**
     * Runs the migration against a capturing proxy to collect the executed SQL statements.
     */
    private fun migrationSqlText(): String {
        val captured = StringBuilder()
        AppDatabase.MIGRATION_53_54.migrate(capturingDatabase(captured))
        return captured.toString()
    }

    private fun capturingDatabase(sink: StringBuilder): SupportSQLiteDatabase {
        val handler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "execSQL" -> {
                        sink.append(args[0] as String).append('\n')
                        connection.createStatement().use { it.execute(args[0] as String) }
                        null
                    }

                    "query" -> {
                        val sql = args[0] as String
                        val stmt = connection.createStatement()
                        val rs = stmt.executeQuery(sql)
                        cursorProxy(stmt, rs)
                    }

                    "inTransaction" -> {
                        false
                    }

                    "isDbLockedByCurrentThread" -> {
                        false
                    }

                    "isOpen" -> {
                        true
                    }

                    else -> {
                        throw UnsupportedOperationException(
                            "Test adapter: unexpected SupportSQLiteDatabase call '${method.name}'",
                        )
                    }
                }
            }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler,
        ) as SupportSQLiteDatabase
    }

    /**
     * Minimal [SupportSQLiteDatabase] proxy over JDBC for driving the migration in memory.
     */
    private fun proxyDatabase(): SupportSQLiteDatabase {
        val handler =
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "execSQL" -> {
                        connection.createStatement().use { it.execute(args[0] as String) }
                        null
                    }

                    "query" -> {
                        val sql = args[0] as String
                        val stmt = connection.createStatement()
                        val rs = stmt.executeQuery(sql)
                        cursorProxy(stmt, rs)
                    }

                    "inTransaction" -> {
                        false
                    }

                    "isDbLockedByCurrentThread" -> {
                        false
                    }

                    "isOpen" -> {
                        true
                    }

                    else -> {
                        throw UnsupportedOperationException(
                            "Test adapter: unexpected SupportSQLiteDatabase call '${method.name}'",
                        )
                    }
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
                    "moveToNext" -> {
                        rs.next()
                    }

                    // Cursor indexes are 0-based; JDBC is 1-based. PRAGMA table_info's
                    // 'name' column is Cursor index 1 → JDBC index 2.
                    "getString" -> {
                        rs.getString((args[0] as Int) + 1)
                    }

                    "getColumnIndex" -> {
                        1
                    }

                    "getColumnCount" -> {
                        rs.metaData.columnCount
                    }

                    "close" -> {
                        rs.close()
                        stmt.close()
                        null
                    }

                    "isAfterLast" -> {
                        rs.isAfterLast
                    }

                    "isClosed" -> {
                        rs.isClosed
                    }

                    else -> {
                        throw UnsupportedOperationException(
                            "Test adapter: unexpected Cursor call '${method.name}'",
                        )
                    }
                }
            }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Cursor::class.java),
            handler,
        ) as Cursor
    }
}
