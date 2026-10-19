package me.avinas.tempo.data.repository

import kotlinx.coroutines.test.runTest
import me.avinas.tempo.data.analytics.NoOpAnalyticsTracker
import me.avinas.tempo.data.local.dao.ScrobbleArchiveDao
import me.avinas.tempo.data.local.dao.TrackAliasDao
import me.avinas.tempo.data.local.dao.TrackDao
import me.avinas.tempo.data.local.entities.ScrobbleArchive
import me.avinas.tempo.data.local.entities.TrackAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Regression coverage for renaming a track title.
 *
 * The rename used to write `tracks.title` without touching the stats cache, so the
 * details screen — which reloads through the memoized `getTrackDetails` — painted the
 * pre-rename title back and the edit looked like it never saved. It also reported
 * success when the UPDATE matched no row at all, and left the compressed long-tail
 * archive serving the old name.
 */
class TrackRenameCacheTest {

    private inline fun <reified T : Any> createProxy(
        crossinline handler: (methodName: String, args: Array<Any?>?) -> Any?
    ): T {
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java)
        ) { _, method, args ->
            val result = handler(method.name, args)
            if (result != null) {
                result
            } else if (method.returnType == List::class.java) {
                emptyList<Any>()
            } else if (method.returnType == java.lang.Boolean.TYPE) {
                false
            } else if (method.returnType == java.lang.Integer.TYPE) {
                0
            } else if (method.returnType == java.lang.Long.TYPE) {
                0L
            } else {
                null
            }
        } as T
    }

    private fun <T> allocateInstance(clazz: Class<T>): T {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        @Suppress("UNCHECKED_CAST")
        return unsafe.allocateInstance(clazz) as T
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private class Harness(
        val repo: TrackAliasRepository,
        val invalidations: () -> Int,
        val insertedAliases: () -> List<TrackAlias>,
        val archiveUpserts: () -> List<ScrobbleArchive>,
        val archiveDeletes: () -> List<ScrobbleArchive>,
    )

    private fun archiveRow(title: String, artist: String) = ScrobbleArchive(
        trackHash = ScrobbleArchive.generateTrackHash(artist, title),
        trackTitle = title,
        artistName = artist,
        artistNameNormalized = artist.lowercase().trim(),
        timestampsBlob = ByteArray(0),
        playCount = 4,
        firstScrobble = 1_000L,
        lastScrobble = 2_000L,
        importId = 1L,
    )

    private fun harness(updatedRows: Int, archiveRow: ScrobbleArchive? = null): Harness {
        var invalidations = 0
        val aliases = mutableListOf<TrackAlias>()
        val archiveUpserts = mutableListOf<ScrobbleArchive>()
        val archiveDeletes = mutableListOf<ScrobbleArchive>()

        val trackDao = createProxy<TrackDao> { method, _ ->
            if (method == "updateTitle") updatedRows else null
        }
        val trackAliasDao = createProxy<TrackAliasDao> { method, args ->
            if (method == "insertAlias" && args != null) {
                aliases.add(args[0] as TrackAlias)
                1L
            } else {
                null
            }
        }
        val statsRepository = createProxy<StatsRepository> { method, _ ->
            if (method == "invalidateCache") {
                invalidations++
                Unit
            } else {
                null
            }
        }
        val scrobbleArchiveDao = createProxy<ScrobbleArchiveDao> { method, args ->
            when (method) {
                "findByArtistAndTitle" -> archiveRow
                "upsertWithMerge" -> {
                    archiveUpserts.add(args!![0] as ScrobbleArchive)
                    Pair(1L, 0)
                }
                "delete" -> {
                    archiveDeletes.add(args!![0] as ScrobbleArchive)
                    Unit
                }
                else -> null
            }
        }

        val repo = allocateInstance(TrackAliasRepository::class.java)
        setField(repo, "trackDao", trackDao)
        setField(repo, "trackAliasDao", trackAliasDao)
        setField(repo, "statsRepository", statsRepository)
        setField(repo, "scrobbleArchiveDao", scrobbleArchiveDao)
        setField(repo, "tracker", NoOpAnalyticsTracker())
        // Unsafe skips initialisers, so the Room transaction runner must be stubbed.
        repo.transactionRunner = { it() }

        return Harness(
            repo = repo,
            invalidations = { invalidations },
            insertedAliases = { aliases },
            archiveUpserts = { archiveUpserts },
            archiveDeletes = { archiveDeletes },
        )
    }

    @Test
    fun `rename writes the alias and invalidates the stats cache`() = runTest {
        val h = harness(updatedRows = 1)

        val renamed = h.repo.renameTrack(
            trackId = 7L,
            previousTitle = "Mayiliragae song from Anbe aaruyire...",
            newTitle = "Mayiliragae",
            artist = "A.R. Rahman",
        )

        assertTrue("A matched row must report success", renamed)
        assertEquals("The stale details/top-tracks caches must be dropped", 1, h.invalidations())
        assertEquals(1, h.insertedAliases().size)
        val alias = h.insertedAliases().single()
        assertEquals(7L, alias.targetTrackId)
        assertEquals("Mayiliragae song from Anbe aaruyire...", alias.originalTitle)
        assertEquals("A.R. Rahman", alias.originalArtist)
    }

    @Test
    fun `rename that matches no row reports failure and changes nothing`() = runTest {
        val h = harness(updatedRows = 0)

        val renamed = h.repo.renameTrack(
            trackId = 7L,
            previousTitle = "Old Title",
            newTitle = "New Title",
            artist = "Someone",
        )

        assertFalse("A rename with no affected row must not report success", renamed)
        assertNull("No alias may be left behind", h.insertedAliases().firstOrNull())
        assertEquals(0, h.invalidations())
    }

    @Test
    fun `rename re-keys the compressed archive row onto the new name`() = runTest {
        // scrobbles_archive is keyed by (artist, title), so it does not follow a rename on
        // its own: the old title would keep appearing in All History and could never be
        // recognised as already promoted.
        val archived = archiveRow("Mayiliragae song from Anbe aaruyire...", "A.R. Rahman")
        val h = harness(updatedRows = 1, archiveRow = archived)

        val renamed = h.repo.renameTrack(
            trackId = 7L,
            previousTitle = "Mayiliragae song from Anbe aaruyire...",
            newTitle = "Mayiliragae",
            artist = "A.R. Rahman",
        )

        assertTrue(renamed)
        val rekeyed = h.archiveUpserts().single()
        assertEquals("Mayiliragae", rekeyed.trackTitle)
        assertEquals("A.R. Rahman", rekeyed.artistName)
        assertEquals(
            ScrobbleArchive.generateTrackHash("A.R. Rahman", "Mayiliragae"),
            rekeyed.trackHash,
        )
        assertEquals("The re-key must insert under the new hash, not update the old row", 0L, rekeyed.id)
        assertEquals(listOf(archived), h.archiveDeletes())
    }

    @Test
    fun `rename without an archive row leaves the archive alone`() = runTest {
        val h = harness(updatedRows = 1)

        assertTrue(h.repo.renameTrack(7L, "Old Title", "New Title", "Someone"))

        assertTrue(h.archiveUpserts().isEmpty())
        assertTrue(h.archiveDeletes().isEmpty())
    }
}
