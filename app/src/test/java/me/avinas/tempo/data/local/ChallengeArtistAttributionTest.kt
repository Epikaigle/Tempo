package me.avinas.tempo.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Daily-challenge artist attribution must use the canonical `track_artists` junction, not the
 * raw `tracks.artist` display string.
 *
 * The challenge target is picked by StatsDao.getTopArtistsByPlayCount, which resolves individual
 * artists through `track_artists` -> `artists`. The progress queries matched that target against
 * `LOWER(TRIM(t.artist))` with exact equality, so a collaboration stored as
 * "Arijit Singh, Shreya Ghoshal" equalled neither artist and counted for neither. "Deep Dive for
 * an Artist" was therefore unachievable for anyone listening mostly to duets, and the same defect
 * skewed the variety and discovery challenges.
 *
 * This test runs the real SQL parsed out of GamificationDao.kt (so it cannot drift from the DAO),
 * asserts the fixed semantics, and documents the old raw-string behaviour it replaces.
 *
 * The same file covers the all-time unique-artist count (identical defect, feeds XP/level/badges)
 * and the genre challenge, whose target used to be a whole '|||'-delimited combo string.
 */
class ChallengeArtistAttributionTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE tracks (id INTEGER PRIMARY KEY, title TEXT, artist TEXT, primary_artist_id INTEGER)")
            stmt.execute("CREATE TABLE artists (id INTEGER PRIMARY KEY, name TEXT, normalized_name TEXT)")
            stmt.execute(
                "CREATE TABLE track_artists (track_id INTEGER, artist_id INTEGER, role TEXT, credit_order INTEGER, PRIMARY KEY (track_id, artist_id))",
            )
            stmt.execute(
                "CREATE TABLE listening_events (id INTEGER PRIMARY KEY, track_id INTEGER, playDuration INTEGER, timestamp INTEGER, volume_level INTEGER)",
            )
            stmt.execute("CREATE TABLE enriched_metadata (track_id INTEGER PRIMARY KEY, genres TEXT, tags TEXT)")
        }

        artist(1, "Arijit Singh")
        artist(2, "Shreya Ghoshal")
        artist(3, "Newcomer")

        // Track 1: solo. Track 2: duet (both PRIMARY). Track 3: duet with a FEATURED newcomer.
        // Track 4: other half of the duet, solo.
        track(1, "Solo", "Arijit Singh")
        track(2, "Duet", "Arijit Singh, Shreya Ghoshal")
        track(3, "Duet Feat", "Arijit Singh, Newcomer")
        track(4, "Solo 2", "Shreya Ghoshal")
        credit(1, 1, "PRIMARY")
        credit(2, 1, "PRIMARY")
        credit(2, 2, "PRIMARY")
        credit(3, 1, "PRIMARY")
        credit(3, 3, "FEATURED")
        credit(4, 2, "PRIMARY")

        // Genre columns for the "Genre Focus" challenge: a canonical combo, a near-miss token
        // ("k-pop" must not satisfy a "Pop" target), a legacy whitespace-padded value, and a row
        // whose genre lives only in `tags`.
        metadata(1, "Pop|||dance pop")
        metadata(2, "k-pop")
        metadata(3, " jazz ||| pop ")
        metadata(4, null, "Shoegaze")

        // Yesterday: only the duet. Today: everything else.
        event(1, 2, timestamp = 50)
        event(2, 1, timestamp = 200)
        event(3, 2, timestamp = 201)
        event(4, 3, timestamp = 202)
        event(5, 4, timestamp = 203)
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `deep dive counts a duet for both credited artists`() {
        val sql = daoQuery("getTodayPlayCountForArtist")
        // Arijit: solo + duet + duet-with-feature. Shreya: duet + her solo.
        assertEquals(3, count(sql, mapOf("startOfDayMs" to "100", "artistName" to "'Arijit Singh'")))
        assertEquals(2, count(sql, mapOf("startOfDayMs" to "100", "artistName" to "'Shreya Ghoshal'")))
    }

    @Test
    fun `raw-string equality drops every collaboration`() {
        // Documents the bug the junction join replaces.
        val buggy =
            """
            SELECT COUNT(*) FROM listening_events le JOIN tracks t ON le.track_id = t.id
            WHERE le.timestamp >= 100 AND (le.volume_level IS NULL OR le.volume_level > 0)
            AND LOWER(TRIM(t.artist)) = LOWER(TRIM(:artistName))
            """.trimIndent()
        // Newcomer only ever appears on a collaboration today: the raw form sees the combined
        // string "Arijit Singh, Newcomer" and credits them zero plays.
        assertEquals(0, count(buggy, mapOf("artistName" to "'Newcomer'")))
        assertEquals(1, count(daoQuery("getTodayPlayCountForArtist"), mapOf("startOfDayMs" to "100", "artistName" to "'Newcomer'")))
        // Arijit's collaborations are invisible too, so only his solo track counts.
        assertEquals(1, count(buggy, mapOf("artistName" to "'Arijit Singh'")))
    }

    @Test
    fun `variety counts artists in a duet individually`() {
        val sql = daoQuery("getTodayUniqueArtists")
        // Today's distinct credited artists: Arijit, Shreya, Newcomer = 3.
        assertEquals(3, count(sql, mapOf("startOfDayMs" to "100")))
        // The raw display strings number 4, one of which is a combined duet string.
        assertEquals(
            4,
            count(
                sql
                    .replace(
                        "COUNT(DISTINCT ta.artist_id)",
                        "COUNT(DISTINCT t.artist)",
                    ).replace("INNER JOIN track_artists ta ON ta.track_id = le.track_id", "JOIN tracks t ON le.track_id = t.id"),
                mapOf("startOfDayMs" to "100"),
            ),
        )
    }

    @Test
    fun `discovery counts an artist who only appears on today's collaboration`() {
        val sql = daoQuery("getTodayNewArtists")
        // Newcomer is the only artist not heard before today; Arijit/Shreya were heard yesterday.
        assertEquals(1, count(sql, mapOf("startOfDayMs" to "100")))

        // The raw-string form counted Arijit and Shreya as "new" because yesterday they appeared
        // only as the combined duet string.
        val buggy =
            """
            SELECT COUNT(DISTINCT t.artist) FROM listening_events le JOIN tracks t ON le.track_id = t.id
            WHERE le.timestamp >= 100 AND (le.volume_level IS NULL OR le.volume_level > 0)
            AND t.artist NOT IN (
                SELECT DISTINCT t2.artist FROM listening_events le2 JOIN tracks t2 ON le2.track_id = t2.id
                WHERE le2.timestamp < 100
            )
            """.trimIndent()
        assertEquals(3, count(buggy, emptyMap()))
    }

    @Test
    fun `all-time unique artist count resolves collaborators individually`() {
        val sql = daoQuery("getUniqueArtistCount")
        // Every credited artist across all history: Arijit, Shreya, Newcomer.
        assertEquals(3, count(sql, emptyMap()))

        // The raw display strings number 4, because the duet is one extra "artist" of its own
        // and its two members are never seen individually. Feeds XP, level and badges.
        val buggy =
            """
            SELECT COUNT(DISTINCT t.artist) FROM listening_events le JOIN tracks t ON le.track_id = t.id
            WHERE (le.volume_level IS NULL OR le.volume_level > 0)
            """.trimIndent()
        assertEquals(4, count(buggy, emptyMap()))
    }

    @Test
    fun `genre challenge matches one segment instead of the raw combo`() {
        // An extra k-pop track: the old substring form counted it towards a "Pop" target.
        track(5, "Kpop", "Someone")
        metadata(5, "k-pop")
        event(6, 5, timestamp = 204)

        val sql = daoQuery("getTodayPlayCountForGenre")

        fun plays(genre: String) = count(sql, mapOf("startOfDayMs" to "100", "genre" to "'$genre'"))

        // Pop appears as its own segment in the canonical combo and in the padded legacy value.
        assertEquals(2, plays("Pop"))
        assertEquals(1, plays("dance pop"))
        assertEquals(1, plays("jazz"))
        // A whole segment is required: neither a word inside one, nor another token ending in it.
        assertEquals(0, plays("dance"))
        assertEquals(2, plays("k-pop"))
        // Genres that live only in `tags` are still countable (getTopGenresRaw coalesces them).
        assertEquals(1, plays("Shoegaze"))
    }

    @Test
    fun `raw substring genre matching over-counts and ignores tags`() {
        track(5, "Kpop", "Someone")
        metadata(5, "k-pop")
        event(6, 5, timestamp = 204)

        val buggy =
            """
            SELECT COUNT(*) FROM listening_events le
            LEFT JOIN enriched_metadata em ON em.track_id = le.track_id
            WHERE le.timestamp >= 100 AND (le.volume_level IS NULL OR le.volume_level > 0)
            AND LOWER(em.genres) LIKE '%' || LOWER(:genre) || '%'
            """.trimIndent()

        // The reported bug: the picker fed the whole combo as the target, so the old counter
        // credited only the one track tagged with that exact combo — 1 of the user's 2 pop plays.
        assertEquals(1, count(buggy, mapOf("genre" to "'Pop|||dance pop'")))
        assertEquals(2, count(daoQuery("getTodayPlayCountForGenre"), mapOf("startOfDayMs" to "100", "genre" to "'Pop'")))

        // Bare substring matching counted both k-pop tracks towards a "Pop" challenge...
        assertEquals(4, count(buggy, mapOf("genre" to "'Pop'")))
        // ...and ignored `tags` entirely, so a tags-only genre could never be matched.
        assertEquals(0, count(buggy, mapOf("genre" to "'Shoegaze'")))
    }

    @Test
    fun `artist challenge queries attribute through the junction table`() {
        for (name in listOf("getTodayPlayCountForArtist", "getTodayUniqueArtists", "getTodayNewArtists", "getUniqueArtistCount")) {
            val sql = daoQuery(name)
            assertTrue("$name must join track_artists", sql.contains("track_artists"))
            assertTrue(
                "$name must not identify artists by the raw tracks.artist string: $sql",
                !sql.contains("t.artist") && !sql.contains("t2.artist"),
            )
        }
    }

    @Test
    fun `unique genres counts each combo segment, not the primary genre or the raw combo`() {
        // Today's genres, segmented: Pop, dance pop, k-pop, jazz, pop(dup), Shoegaze(tags).
        // Distinct & lowercased -> pop, dance pop, k-pop, jazz, shoegaze = 5.
        val sql = daoQuery("getTodayUniqueGenres")
        assertEquals(5, count(sql, mapOf("startOfDayMs" to "100")))

        // The previous primary-genre-only form under-counted: it saw Pop, k-pop, jazz and
        // ignored both the secondary "dance pop" segment and the tags-only Shoegaze = 3.
        val buggy =
            """
            SELECT COUNT(DISTINCT LOWER(TRIM(
                CASE WHEN instr(em.genres, '|||') > 0
                     THEN substr(em.genres, 1, instr(em.genres, '|||') - 1)
                     ELSE em.genres
                END)))
            FROM listening_events le
            LEFT JOIN enriched_metadata em ON em.track_id = le.track_id
            WHERE le.timestamp >= 100 AND (le.volume_level IS NULL OR le.volume_level > 0)
              AND em.genres IS NOT NULL AND em.genres != ''
            """.trimIndent()
        assertEquals(3, count(buggy, emptyMap()))
    }

    @Test
    fun `new genres counts only genres never heard before today`() {
        // Yesterday's only event is track 2 (k-pop). So today: pop, dance pop, jazz and
        // shoegaze are new; k-pop is not. Distinct new genres today = 4.
        val sql = daoQuery("getTodayNewGenres")
        assertEquals(4, count(sql, mapOf("startOfDayMs" to "100")))

        // Sanity: counting ALL of today's genres (the old behaviour) would report 5, wrongly
        // crediting the already-heard k-pop toward a "discover new genres" challenge.
        assertEquals(5, count(daoQuery("getTodayUniqueGenres"), mapOf("startOfDayMs" to "100")))
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Parses the real @Query SQL out of GamificationDao.kt, keyed by function name. */
    private fun daoQuery(funName: String): String {
        val source = File("src/main/java/me/avinas/tempo/data/local/dao/GamificationDao.kt")
        check(source.isFile) { "GamificationDao.kt not found at ${source.absolutePath} (unit tests run from the app module dir)" }
        val regex =
            Regex(
                "@Query\\(\\s*\"\"\"(.*?)\"\"\"\\s*,?\\s*\\)\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:suspend\\s+)?fun\\s+(\\w+)",
                RegexOption.DOT_MATCHES_ALL,
            )
        val queries =
            regex
                .findAll(
                    source.readText(),
                ).associate { it.groupValues[2] to it.groupValues[1].replace(Regex("\\s+"), " ").trim() }
        return queries[funName] ?: error("No @Query found for $funName; parsed: ${queries.keys}")
    }

    /** Substitutes Room-style named params with literals and runs the query. */
    private fun count(
        sql: String,
        params: Map<String, String>,
    ): Int {
        var bound = sql
        for ((key, value) in params) bound = bound.replace(Regex(":$key\\b"), value)
        connection.createStatement().executeQuery(bound).use { rs ->
            check(rs.next()) { "Query returned no row: $bound" }
            return rs.getInt(1)
        }
    }

    private fun artist(
        id: Int,
        name: String,
    ) {
        connection.prepareStatement("INSERT INTO artists VALUES (?,?,?)").use { ps ->
            ps.setInt(1, id)
            ps.setString(2, name)
            ps.setString(3, name.lowercase())
            ps.executeUpdate()
        }
    }

    private fun track(
        id: Int,
        title: String,
        artist: String,
    ) {
        connection.prepareStatement("INSERT INTO tracks VALUES (?,?,?,NULL)").use { ps ->
            ps.setInt(1, id)
            ps.setString(2, title)
            ps.setString(3, artist)
            ps.executeUpdate()
        }
    }

    private fun credit(
        trackId: Int,
        artistId: Int,
        role: String,
    ) {
        connection.prepareStatement("INSERT INTO track_artists VALUES (?,?,?,0)").use { ps ->
            ps.setInt(1, trackId)
            ps.setInt(2, artistId)
            ps.setString(3, role)
            ps.executeUpdate()
        }
    }

    private fun metadata(
        trackId: Int,
        genres: String?,
        tags: String? = null,
    ) {
        connection.prepareStatement("INSERT INTO enriched_metadata VALUES (?,?,?)").use { ps ->
            ps.setInt(1, trackId)
            ps.setString(2, genres)
            ps.setString(3, tags)
            ps.executeUpdate()
        }
    }

    private fun event(
        id: Int,
        trackId: Int,
        timestamp: Long,
    ) {
        connection.prepareStatement("INSERT INTO listening_events VALUES (?,?,?,?,?)").use { ps ->
            ps.setInt(1, id)
            ps.setInt(2, trackId)
            ps.setLong(3, 1000L)
            ps.setLong(4, timestamp)
            ps.setInt(5, 1)
            ps.executeUpdate()
        }
    }
}
