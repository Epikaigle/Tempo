package me.avinas.tempo.data.repository

import kotlinx.coroutines.test.runTest
import me.avinas.tempo.data.local.dao.TrackAliasDao
import me.avinas.tempo.data.local.entities.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * A rename or merge records the abandoned title in `track_aliases`. Imports used to skip
 * that table, so re-importing the old title created a second track — and because the play
 * landed on a new track_id, neither the content fingerprint nor the per-track
 * reconciliation in `ListeningEventDao` could see it as a repeat.
 */
class TrackResolverAliasTest {

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

    private class Harness(
        val resolver: TrackResolver,
        val inserted: () -> List<Track>,
    )

    /**
     * @param exactTitleMatch what `findByTitleAndArtist` answers, i.e. a row that
     *   genuinely carries the incoming title today.
     * @param aliasTarget what `findAliasTargetTrack` answers, i.e. the track the user
     *   renamed this name away from.
     */
    private fun harness(exactTitleMatch: Track? = null, aliasTarget: Track? = null): Harness {
        val inserted = mutableListOf<Track>()

        val trackRepository = createProxy<TrackRepository> { method, args ->
            when (method) {
                "findByTitleAndArtist" -> exactTitleMatch
                "getById" -> null
                "insert" -> {
                    inserted.add(args!![0] as Track)
                    NEW_TRACK_ID
                }
                else -> null
            }
        }
        val trackAliasDao = createProxy<TrackAliasDao> { method, _ ->
            if (method == "findAliasTargetTrack") aliasTarget else null
        }

        return Harness(TrackResolver(trackRepository, trackAliasDao), { inserted })
    }

    private fun track(id: Long, title: String, artist: String = "A.R. Rahman") = Track(
        id = id,
        title = title,
        artist = artist,
        album = null,
        duration = null,
        albumArtUrl = null,
        spotifyId = null,
        musicbrainzId = null,
    )

    private fun query(title: String, artist: String = "A.R. Rahman") =
        TrackResolver.Query(title = title, artist = artist)

    @Test
    fun `an old title resolves to the track that absorbed it instead of creating a duplicate`() = runTest {
        val surviving = track(id = 7L, title = "Mayiliragae")
        val h = harness(aliasTarget = surviving)

        val resolution = h.resolver.resolve(query("Mayiliragae song from Anbe aaruyire..."))

        assertEquals(7L, resolution.trackId)
        assertFalse("An aliased title is an existing track, not a new one", resolution.isNewTrack)
        assertTrue("No second track may be created for a renamed title", h.inserted().isEmpty())
    }

    @Test
    fun `a title with no alias still becomes a new track`() = runTest {
        val h = harness()

        val resolution = h.resolver.resolve(query("Something Never Seen Before"))

        assertEquals(NEW_TRACK_ID, resolution.trackId)
        assertTrue(resolution.isNewTrack)
        assertEquals(1, h.inserted().size)
        assertEquals("Something Never Seen Before", h.inserted().single().title)
    }

    @Test
    fun `a row that literally carries the title wins over the alias`() = runTest {
        // Ordering rule: exact title first, alias second. A duplicate created before the
        // alias step existed is therefore left alone rather than silently absorbed — the
        // user merges it deliberately.
        val live = track(id = 11L, title = "Mayiliragae")
        val renamedAway = track(id = 7L, title = "Mayiliragae (Remastered)")
        val h = harness(exactTitleMatch = live, aliasTarget = renamedAway)

        val resolution = h.resolver.resolve(query("Mayiliragae"))

        assertEquals(11L, resolution.trackId)
        assertFalse(resolution.isNewTrack)
        assertTrue(h.inserted().isEmpty())
    }

    @Test
    fun `a provider id still decides before any title matching`() = runTest {
        val byId = track(id = 3L, title = "Whatever The API Calls It", artist = "Some Artist")
        val inserted = mutableListOf<Track>()
        val trackRepository = createProxy<TrackRepository> { method, args ->
            when (method) {
                "findBySpotifyId" -> byId
                "findByTitleAndArtist" -> track(id = 11L, title = "Mayiliragae")
                "insert" -> {
                    inserted.add(args!![0] as Track)
                    NEW_TRACK_ID
                }
                else -> null
            }
        }
        val trackAliasDao = createProxy<TrackAliasDao> { method, _ ->
            if (method == "findAliasTargetTrack") track(id = 7L, title = "Renamed Away") else null
        }
        val resolver = TrackResolver(trackRepository, trackAliasDao)

        val resolution = resolver.resolve(
            TrackResolver.Query(
                title = "Mayiliragae",
                artist = "A.R. Rahman",
                spotifyId = "spotify-123",
            )
        )

        assertEquals("An authoritative id outranks every name-based step", 3L, resolution.trackId)
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun `an alias whose target is gone falls through to normal matching`() = runTest {
        // findAliasTargetTrack joins tracks, so a deleted target simply returns null.
        val h = harness(aliasTarget = null)

        val resolution = h.resolver.resolve(query("Mayiliragae song from Anbe aaruyire..."))

        assertNotNull(resolution)
        assertTrue("Nothing to alias onto, so a real new track is inserted", resolution.isNewTrack)
        assertEquals(1, h.inserted().size)
        assertNull(h.inserted().single().spotifyId)
    }

    private companion object {
        const val NEW_TRACK_ID = 99L
    }
}
