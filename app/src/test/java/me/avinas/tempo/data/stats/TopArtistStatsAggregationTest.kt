package me.avinas.tempo.data.stats

import kotlinx.coroutines.test.runTest
import me.avinas.tempo.data.enrichment.ITunesEnrichmentService
import me.avinas.tempo.data.enrichment.SpotifyEnrichmentService
import me.avinas.tempo.data.local.dao.*
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.local.entities.ArtistAlias
import me.avinas.tempo.data.local.entities.UserPreferences
import me.avinas.tempo.data.repository.RoomStatsRepository
import me.avinas.tempo.data.repository.SortBy
import me.avinas.tempo.domain.insights.InsightGenerator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class TopArtistStatsAggregationTest {

    private inline fun <reified T : Any> createDaoProxy(crossinline handler: (methodName: String, args: Array<Any?>?) -> Any?): T {
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java)
        ) { _, method, args ->
            val custom = handler(method.name, args)
            if (custom != null) {
                custom
            } else if (method.returnType == List::class.java) {
                emptyList<Any>()
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

    private fun createRepository(
        statsDao: StatsDao,
        artistDao: ArtistDao,
        aliasDao: ArtistAliasDao,
        userPrefsDao: UserPreferencesDao = createDaoProxy { method, _ -> if (method == "getSync") UserPreferences() else null }
    ): RoomStatsRepository {
        return RoomStatsRepository(
            statsDao = statsDao,
            listeningEventDao = createDaoProxy { _, _ -> null },
            trackDao = createDaoProxy { _, _ -> null },
            artistDao = artistDao,
            insightGenerator = InsightGenerator(),
            albumDao = createDaoProxy { _, _ -> null },
            enrichedMetadataDao = createDaoProxy<EnrichedMetadataDao> { _, _ -> null },
            spotifyEnrichmentService = allocateInstance(SpotifyEnrichmentService::class.java),
            iTunesEnrichmentService = allocateInstance(ITunesEnrichmentService::class.java),
            userPreferencesDao = userPrefsDao,
            artistAliasDao = aliasDao
        )
    }

    @Test
    fun `system of a down split entries are merged into single canonical top artist`() = runTest {
        val rawStats = listOf(
            RawArtistStats(
                artist = "System of A Down",
                playCount = 50,
                totalTimeMs = 150_000L,
                uniqueTracks = 4,
                firstPlayed = 1000L,
                lastPlayed = 5000L
            ),
            RawArtistStats(
                artist = "System of a Down",
                playCount = 30,
                totalTimeMs = 90_000L,
                uniqueTracks = 2,
                firstPlayed = 2000L,
                lastPlayed = 6000L
            )
        )

        val canonicalArtist = Artist(
            id = 42L,
            name = "System of a Down",
            normalizedName = "system of a down",
            imageUrl = "https://example.com/soad.jpg",
            musicbrainzId = null,
            spotifyId = null,
            country = "US"
        )

        val statsDao = createDaoProxy<StatsDao> { method, _ ->
            when (method) {
                "getAllArtistStatsRawFiltered" -> rawStats
                "getArtistImageByArtistId" -> canonicalArtist.imageUrl
                "getArtistCountry" -> canonicalArtist.country
                else -> null
            }
        }

        val artistDao = createDaoProxy<ArtistDao> { method, args ->
            when (method) {
                "getArtistsByNormalizedNames" -> {
                    @Suppress("UNCHECKED_CAST")
                    val names = args?.get(0) as? List<String> ?: emptyList()
                    if ("system of a down" in names) listOf(canonicalArtist) else emptyList()
                }
                "getArtistsByIds" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = args?.get(0) as? List<Long> ?: emptyList()
                    if (42L in ids) listOf(canonicalArtist) else emptyList()
                }
                "getArtistById" -> if (args?.get(0) == 42L) canonicalArtist else null
                "getArtistByNormalizedName" -> if (args?.get(0) == "system of a down") canonicalArtist else null
                "getArtistByName" -> canonicalArtist
                else -> null
            }
        }

        val aliasDao = createDaoProxy<ArtistAliasDao> { method, _ ->
            when (method) {
                "getAllSync" -> emptyList<ArtistAlias>()
                "findAlias" -> null
                else -> null
            }
        }

        val repository = createRepository(
            statsDao = statsDao,
            artistDao = artistDao,
            aliasDao = aliasDao
        )

        val result = repository.getTopArtists(
            timeRange = TimeRange.ALL_TIME,
            sortBy = SortBy.PLAY_COUNT,
            page = 0,
            pageSize = 10,
            withLeeway = false
        )

        assertEquals("Should merge into exactly 1 top artist", 1, result.items.size)
        val artist = result.items.first()
        assertEquals("Canonical name from DB should be used", "System of a Down", artist.artist)
        assertEquals("Canonical artist ID should be set", 42L, artist.artistId)
        assertEquals("Play count must be summed (50 + 30)", 80, artist.playCount)
        assertEquals("Total time must be summed (150k + 90k)", 240_000L, artist.totalTimeMs)
        assertEquals("Unique tracks must be summed (4 + 2)", 6, artist.uniqueTracks)
        assertEquals("First played must be min (1000)", 1000L, artist.firstPlayed)
        assertEquals("Last played must be max (6000)", 6000L, artist.lastPlayed)
    }

    @Test
    fun `case differences without database artist still merge by normalized name`() = runTest {
        val rawStats = listOf(
            RawArtistStats(
                artist = "System of A Down",
                playCount = 50,
                totalTimeMs = 150_000L,
                uniqueTracks = 4,
                firstPlayed = 1000L,
                lastPlayed = 5000L
            ),
            RawArtistStats(
                artist = "System of a Down",
                playCount = 30,
                totalTimeMs = 90_000L,
                uniqueTracks = 2,
                firstPlayed = 2000L,
                lastPlayed = 6000L
            )
        )

        val statsDao = createDaoProxy<StatsDao> { method, _ ->
            when (method) {
                "getAllArtistStatsRawFiltered" -> rawStats
                else -> null
            }
        }
        val artistDao = createDaoProxy<ArtistDao> { _, _ -> null }
        val aliasDao = createDaoProxy<ArtistAliasDao> { method, _ ->
            when (method) {
                "getAllSync" -> emptyList<ArtistAlias>()
                else -> null
            }
        }

        val repository = createRepository(
            statsDao = statsDao,
            artistDao = artistDao,
            aliasDao = aliasDao
        )

        val result = repository.getTopArtists(
            timeRange = TimeRange.ALL_TIME,
            sortBy = SortBy.PLAY_COUNT,
            page = 0,
            pageSize = 10,
            withLeeway = false
        )

        assertEquals("Even without DB entry, normalized name must merge into 1 artist", 1, result.items.size)
        val artist = result.items.first()
        assertEquals(80, artist.playCount)
        assertEquals(240_000L, artist.totalTimeMs)
    }

    @Test
    fun `alias maps to target artist and merges with existing name variants`() = runTest {
        val rawStats = listOf(
            RawArtistStats(
                artist = "SOAD",
                playCount = 20,
                totalTimeMs = 60_000L,
                uniqueTracks = 1,
                firstPlayed = 500L,
                lastPlayed = 2500L
            ),
            RawArtistStats(
                artist = "System of A Down",
                playCount = 50,
                totalTimeMs = 150_000L,
                uniqueTracks = 4,
                firstPlayed = 1000L,
                lastPlayed = 5000L
            )
        )

        val canonicalArtist = Artist(
            id = 42L,
            name = "System of a Down",
            normalizedName = "system of a down",
            imageUrl = null,
            musicbrainzId = null,
            spotifyId = null
        )

        val alias = ArtistAlias(
            id = 1L,
            originalName = "SOAD",
            originalNameNormalized = "soad",
            targetArtistId = 42L
        )

        val statsDao = createDaoProxy<StatsDao> { method, _ ->
            when (method) {
                "getAllArtistStatsRawFiltered" -> rawStats
                else -> null
            }
        }

        val artistDao = createDaoProxy<ArtistDao> { method, args ->
            when (method) {
                "getArtistsByNormalizedNames" -> {
                    @Suppress("UNCHECKED_CAST")
                    val names = args?.get(0) as? List<String> ?: emptyList()
                    if ("system of a down" in names) listOf(canonicalArtist) else emptyList()
                }
                "getArtistsByIds" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = args?.get(0) as? List<Long> ?: emptyList()
                    if (42L in ids) listOf(canonicalArtist) else emptyList()
                }
                "getArtistById" -> if (args?.get(0) == 42L) canonicalArtist else null
                "getArtistByNormalizedName" -> if (args?.get(0) == "system of a down") canonicalArtist else null
                else -> null
            }
        }

        val aliasDao = createDaoProxy<ArtistAliasDao> { method, _ ->
            when (method) {
                "getAllSync" -> listOf(alias)
                else -> null
            }
        }

        val repository = createRepository(
            statsDao = statsDao,
            artistDao = artistDao,
            aliasDao = aliasDao
        )

        val result = repository.getTopArtists(
            timeRange = TimeRange.ALL_TIME,
            sortBy = SortBy.PLAY_COUNT,
            page = 0,
            pageSize = 10,
            withLeeway = false
        )

        assertEquals("Both SOAD and System of A Down should merge into 1 artist", 1, result.items.size)
        val artist = result.items.first()
        assertEquals("System of a Down", artist.artist)
        assertEquals(42L, artist.artistId)
        assertEquals(70, artist.playCount)
        assertEquals(210_000L, artist.totalTimeMs)
    }

    @Test
    fun `searchTopArtists returns single merged entry for query`() = runTest {
        val rawStats = listOf(
            RawArtistStats(
                artist = "System of A Down",
                playCount = 50,
                totalTimeMs = 150_000L,
                uniqueTracks = 4,
                firstPlayed = 1000L,
                lastPlayed = 5000L
            ),
            RawArtistStats(
                artist = "System of a Down",
                playCount = 30,
                totalTimeMs = 90_000L,
                uniqueTracks = 2,
                firstPlayed = 2000L,
                lastPlayed = 6000L
            ),
            RawArtistStats(
                artist = "Metallica",
                playCount = 40,
                totalTimeMs = 120_000L,
                uniqueTracks = 3,
                firstPlayed = 500L,
                lastPlayed = 4000L
            )
        )

        val canonicalArtist = Artist(
            id = 42L,
            name = "System of a Down",
            normalizedName = "system of a down",
            imageUrl = null,
            musicbrainzId = null,
            spotifyId = null
        )

        val statsDao = createDaoProxy<StatsDao> { method, _ ->
            when (method) {
                "getAllArtistStatsRawFiltered" -> rawStats
                else -> null
            }
        }

        val artistDao = createDaoProxy<ArtistDao> { method, args ->
            when (method) {
                "getArtistsByNormalizedNames" -> {
                    @Suppress("UNCHECKED_CAST")
                    val names = args?.get(0) as? List<String> ?: emptyList()
                    if ("system of a down" in names) listOf(canonicalArtist) else emptyList()
                }
                "getArtistsByIds" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = args?.get(0) as? List<Long> ?: emptyList()
                    if (42L in ids) listOf(canonicalArtist) else emptyList()
                }
                "getArtistById" -> if (args?.get(0) == 42L) canonicalArtist else null
                "getArtistByNormalizedName" -> if (args?.get(0) == "system of a down") canonicalArtist else null
                else -> null
            }
        }

        val repository = createRepository(
            statsDao = statsDao,
            artistDao = artistDao,
            aliasDao = createDaoProxy { method, _ -> if (method == "getAllSync") emptyList<ArtistAlias>() else null }
        )

        val searchResults = repository.searchTopArtists(
            timeRange = TimeRange.ALL_TIME,
            sortBy = SortBy.PLAY_COUNT,
            query = "system",
            limit = 10
        )

        assertEquals("Should return exactly 1 matching artist for 'system'", 1, searchResults.size)
        val match = searchResults.first()
        assertEquals("System of a Down", match.artist)
        assertEquals(80, match.playCount)
        assertEquals(1, match.rank)
    }

    @Test
    fun `multi-artist collaboration splits and merges correctly with solo plays`() = runTest {
        val rawStats = listOf(
            RawArtistStats(
                artist = "System of A Down feat. Serj Tankian",
                playCount = 15,
                totalTimeMs = 45_000L,
                uniqueTracks = 1,
                firstPlayed = 1000L,
                lastPlayed = 3000L
            ),
            RawArtistStats(
                artist = "System of a Down",
                playCount = 35,
                totalTimeMs = 105_000L,
                uniqueTracks = 3,
                firstPlayed = 500L,
                lastPlayed = 5000L
            )
        )

        val soadArtist = Artist(
            id = 42L,
            name = "System of a Down",
            normalizedName = "system of a down",
            imageUrl = null,
            musicbrainzId = null,
            spotifyId = null
        )

        val serjArtist = Artist(
            id = 99L,
            name = "Serj Tankian",
            normalizedName = "serj tankian",
            imageUrl = null,
            musicbrainzId = null,
            spotifyId = null
        )

        val statsDao = createDaoProxy<StatsDao> { method, _ ->
            when (method) {
                "getAllArtistStatsRawFiltered" -> rawStats
                else -> null
            }
        }

        val artistDao = createDaoProxy<ArtistDao> { method, args ->
            when (method) {
                "getArtistsByNormalizedNames" -> {
                    @Suppress("UNCHECKED_CAST")
                    val names = args?.get(0) as? List<String> ?: emptyList()
                    listOfNotNull(
                        if ("system of a down" in names) soadArtist else null,
                        if ("serj tankian" in names) serjArtist else null
                    )
                }
                "getArtistsByIds" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = args?.get(0) as? List<Long> ?: emptyList()
                    listOfNotNull(
                        if (42L in ids) soadArtist else null,
                        if (99L in ids) serjArtist else null
                    )
                }
                "getArtistById" -> when (args?.get(0)) {
                    42L -> soadArtist
                    99L -> serjArtist
                    else -> null
                }
                "getArtistByNormalizedName" -> when (args?.get(0)) {
                    "system of a down" -> soadArtist
                    "serj tankian" -> serjArtist
                    else -> null
                }
                else -> null
            }
        }

        val repository = createRepository(
            statsDao = statsDao,
            artistDao = artistDao,
            aliasDao = createDaoProxy { method, _ -> if (method == "getAllSync") emptyList<ArtistAlias>() else null }
        )

        val result = repository.getTopArtists(
            timeRange = TimeRange.ALL_TIME,
            sortBy = SortBy.PLAY_COUNT,
            page = 0,
            pageSize = 10,
            withLeeway = false
        )

        assertEquals("Should have 2 distinct artists", 2, result.items.size)
        val soad = result.items.find { it.artistId == 42L }
        val serj = result.items.find { it.artistId == 99L }

        org.junit.Assert.assertNotNull("System of a Down should be present", soad)
        org.junit.Assert.assertNotNull("Serj Tankian should be present", serj)

        assertEquals("System of a Down should have combined plays (15 + 35)", 50, soad?.playCount)
        assertEquals("Serj Tankian should have collaboration plays", 15, serj?.playCount)
    }
}
