package me.avinas.tempo.data.local

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Album-grouped stats queries must surface art when ANY track in the album has it.
 *
 * A bare column next to an aggregate (`COALESCE(t.album_art_url, ...)` under
 * `GROUP BY t.album`) is resolved by SQLite from an arbitrary row of the group, so
 * an album whose first-scanned track has no art reported NO art even when its
 * siblings did. The queries wrap the column in `MAX(...)` to pick a non-null one.
 *
 * Two guards here:
 *  1. the SQL semantics (MAX recovers art, the bare form drops it), and
 *  2. a source scan asserting every album-grouped art column in StatsDao.kt is
 *     aggregated — so a future query can't silently reintroduce the bare form.
 */
class AlbumArtAggregationTest {
    private lateinit var connection: Connection

    @Before
    fun setUp() {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE tracks (id INTEGER PRIMARY KEY, title TEXT, artist TEXT, album TEXT, album_art_url TEXT)")
            stmt.execute("CREATE TABLE enriched_metadata (track_id INTEGER PRIMARY KEY, album_art_url TEXT, release_type TEXT)")
            stmt.execute(
                "CREATE TABLE listening_events (id INTEGER PRIMARY KEY, track_id INTEGER, playDuration INTEGER, timestamp INTEGER)",
            )
        }
        // Album A: art only on the LAST-scanned track (the bare-COALESCE failure case).
        track(1, "A1", "Artist", "Album A", null)
        track(2, "A2", "Artist", "Album A", null)
        track(3, "A3", "Artist", "Album A", "file:///a3.jpg")
        // Album B: local art on one track, enriched art on another (enriched must win).
        track(4, "B1", "Artist", "Album B", "file:///b1.jpg")
        track(5, "B2", "Artist", "Album B", null)
        enrichedArt(5, "https://cdn.example/b2.jpg")
        for (id in 1..5) event(id, id)
    }

    @After
    fun tearDown() {
        connection.close()
    }

    @Test
    fun `aggregated art column recovers art from any track in the album`() {
        val rows = queryTopAlbums(aggregated = true)
        assertEquals(2, rows.size)
        assertEquals("Album A", rows[0].first)
        assertEquals("file:///a3.jpg", rows[0].second)
        assertEquals("Album B", rows[1].first)
        assertEquals("https://cdn.example/b2.jpg", rows[1].second)
    }

    @Test
    fun `bare column next to an aggregate drops art from sibling tracks`() {
        // Documents the bug the MAX(...) form exists to fix.
        val rows = queryTopAlbums(aggregated = false)
        assertNull("Bare column resolves from an arbitrary row and loses sibling art", rows[0].second)
    }

    @Test
    fun `every album-grouped query aggregates its art column`() {
        val source = File("src/main/java/me/avinas/tempo/data/local/dao/StatsDao.kt")
        check(source.isFile) { "StatsDao.kt not found at ${source.absolutePath} (unit tests run from the app module dir)" }
        val queries =
            Regex("@Query\\(\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
                .findAll(source.readText())
                .map { it.groupValues[1].replace(Regex("\\s+"), " ") }
                .toList()
        // Track-grouped queries (GROUP BY t.id) and ungrouped ones are fine as-is: each group is
        // a single row, so the bare column is not arbitrary. An album group spans many tracks.
        val albumGrouped = queries.filter { Regex("GROUP BY t\\.album", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        check(albumGrouped.size >= 13) {
            "Expected the 13 album-grouped stats queries, parsed ${albumGrouped.size} — the guard below would pass vacuously"
        }
        val offenders =
            albumGrouped.filter {
                it.contains("em.album_art_url") && !it.contains("COALESCE(MAX(NULLIF(em.album_art_url")
            }
        assertEquals(
            "Album-grouped queries must aggregate art with MAX(...); offenders:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /** Mirrors StatsDao.getTopAlbums, with `aggregated` toggling the art column only. */
    private fun queryTopAlbums(aggregated: Boolean): List<Pair<String, String?>> {
        val art =
            if (aggregated) {
                "COALESCE(MAX(NULLIF(em.album_art_url, '')), MAX(NULLIF(t.album_art_url, '')))"
            } else {
                "COALESCE(NULLIF(em.album_art_url, ''), NULLIF(t.album_art_url, ''))"
            }
        val sql =
            """
            SELECT t.album, t.artist, $art as album_art_url,
                   COUNT(le.id) as play_count,
                   SUM(le.playDuration) as total_time_ms,
                   COUNT(DISTINCT t.id) as unique_tracks
            FROM listening_events le
            INNER JOIN tracks t ON le.track_id = t.id
            LEFT JOIN enriched_metadata em ON t.id = em.track_id
            WHERE le.timestamp >= 0 AND le.timestamp <= 9999999999
            AND t.album IS NOT NULL AND t.album != ''
            AND (em.release_type IS NULL OR em.release_type NOT IN ('Single', 'single'))
            GROUP BY t.album, t.artist
            HAVING COUNT(DISTINCT t.id) > 1
            ORDER BY play_count DESC, total_time_ms DESC
            LIMIT 10 OFFSET 0
            """.trimIndent()
        val out = mutableListOf<Pair<String, String?>>()
        connection.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) out.add(rs.getString("album") to rs.getString("album_art_url"))
        }
        return out
    }

    private fun track(
        id: Int,
        title: String,
        artist: String,
        album: String,
        art: String?,
    ) {
        connection.prepareStatement("INSERT INTO tracks VALUES (?,?,?,?,?)").use { ps ->
            ps.setInt(1, id)
            ps.setString(2, title)
            ps.setString(3, artist)
            ps.setString(4, album)
            ps.setString(5, art)
            ps.executeUpdate()
        }
    }

    private fun enrichedArt(
        trackId: Int,
        art: String,
    ) {
        connection.prepareStatement("INSERT INTO enriched_metadata (track_id, album_art_url, release_type) VALUES (?,?,NULL)").use { ps ->
            ps.setInt(1, trackId)
            ps.setString(2, art)
            ps.executeUpdate()
        }
    }

    private fun event(
        id: Int,
        trackId: Int,
    ) {
        connection.prepareStatement("INSERT INTO listening_events VALUES (?,?,?,?)").use { ps ->
            ps.setInt(1, id)
            ps.setInt(2, trackId)
            ps.setLong(3, 1000L)
            ps.setLong(4, 1000L + id)
            ps.executeUpdate()
        }
    }
}
