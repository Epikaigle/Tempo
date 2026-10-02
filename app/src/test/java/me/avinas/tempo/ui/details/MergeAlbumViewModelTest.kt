package me.avinas.tempo.ui.details

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.dao.AlbumSearchResult
import me.avinas.tempo.data.repository.AlbumMergeRepository
import me.avinas.tempo.data.repository.TrackAliasRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class MergeAlbumViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    private fun createFakeDatabase(): AppDatabase {
        val clazz = Class.forName("me.avinas.tempo.data.local.AppDatabase_Impl")
        @Suppress("UNCHECKED_CAST")
        return allocateInstance(clazz as Class<AppDatabase>)
    }

    @Test
    fun `setSourceAlbum resets state and loads artist albums`() = runTest(testDispatcher) {
        val artistAlbumsList = listOf(
            AlbumSearchResult(
                id = 2L,
                title = "Abbey Road",
                artistId = 100L,
                artistName = "The Beatles",
                releaseYear = 1969,
                artworkUrl = null
            )
        )

        var requestedArtistId: Long? = null
        var requestedExcludeId: Long? = null

        val customRepo = object : AlbumMergeRepository(
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            allocateInstance(TrackAliasRepository::class.java),
            createProxy { _, _ -> null },
            createFakeDatabase(),
            createProxy { _, _ -> null }
        ) {
            override suspend fun getAlbumsForArtist(
                artistId: Long,
                excludeAlbumId: Long?
            ): List<AlbumSearchResult> {
                requestedArtistId = artistId
                requestedExcludeId = excludeAlbumId
                return artistAlbumsList
            }
        }

        val viewModel = MergeAlbumViewModel(customRepo)

        viewModel.setSourceAlbum(albumId = 1L, artistId = 100L)
        advanceUntilIdle()

        assertEquals(100L, requestedArtistId)
        assertEquals(1L, requestedExcludeId)
        assertEquals(artistAlbumsList, viewModel.uiState.value.artistAlbums)
        assertEquals(false, viewModel.uiState.value.isLoadingArtistAlbums)
    }

    @Test
    fun `onQueryChange debounces and executes search`() = runTest(testDispatcher) {
        val searchResultsList = listOf(
            AlbumSearchResult(
                id = 3L,
                title = "Rubber Soul",
                artistId = 100L,
                artistName = "The Beatles",
                releaseYear = 1965,
                artworkUrl = null
            )
        )

        var searchedQuery: String? = null

        val customRepo = object : AlbumMergeRepository(
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            allocateInstance(TrackAliasRepository::class.java),
            createProxy { _, _ -> null },
            createFakeDatabase(),
            createProxy { _, _ -> null }
        ) {
            override suspend fun searchAlbums(
                query: String,
                excludeAlbumId: Long?,
                limit: Int
            ): List<AlbumSearchResult> {
                searchedQuery = query
                return searchResultsList
            }
        }

        val viewModel = MergeAlbumViewModel(customRepo)
        viewModel.setSourceAlbum(1L, 100L)
        advanceUntilIdle()

        // Short query: no search executed
        viewModel.onQueryChange("R")
        advanceTimeBy(300)
        assertNull(searchedQuery)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())

        // 2+ chars query: debounced search executed
        viewModel.onQueryChange("Rubb")
        advanceTimeBy(100) // Before 200ms debounce
        assertNull(searchedQuery)

        advanceTimeBy(150) // After 200ms debounce
        advanceUntilIdle()
        assertEquals("Rubb", searchedQuery)
        assertEquals(searchResultsList, viewModel.uiState.value.searchResults)
    }

    @Test
    fun `selectAlbumForMerge and cancelMerge manage pending target`() = runTest(testDispatcher) {
        val target = AlbumSearchResult(
            id = 2L,
            title = "Abbey Road",
            artistId = 100L,
            artistName = "The Beatles",
            releaseYear = 1969,
            artworkUrl = null
        )

        val customRepo = object : AlbumMergeRepository(
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            allocateInstance(TrackAliasRepository::class.java),
            createProxy { _, _ -> null },
            createFakeDatabase(),
            createProxy { _, _ -> null }
        ) {}

        val viewModel = MergeAlbumViewModel(customRepo)
        viewModel.setSourceAlbum(1L, 100L)
        advanceUntilIdle()

        viewModel.selectAlbumForMerge(target)
        assertEquals(target, viewModel.uiState.value.pendingMergeTarget)

        viewModel.cancelMerge()
        assertNull(viewModel.uiState.value.pendingMergeTarget)
    }

    @Test
    fun `confirmMerge updates status to Success on successful merge`() = runTest(testDispatcher) {
        val target = AlbumSearchResult(
            id = 2L,
            title = "Abbey Road",
            artistId = 100L,
            artistName = "The Beatles",
            releaseYear = 1969,
            artworkUrl = null
        )

        var mergedSource: Long? = null
        var mergedTarget: Long? = null

        val customRepo = object : AlbumMergeRepository(
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            allocateInstance(TrackAliasRepository::class.java),
            createProxy { _, _ -> null },
            createFakeDatabase(),
            createProxy { _, _ -> null }
        ) {
            override suspend fun mergeAlbums(sourceAlbumId: Long, targetAlbumId: Long): Boolean {
                mergedSource = sourceAlbumId
                mergedTarget = targetAlbumId
                return true
            }
        }

        val viewModel = MergeAlbumViewModel(customRepo)
        viewModel.setSourceAlbum(1L, 100L)
        advanceUntilIdle()

        viewModel.selectAlbumForMerge(target)
        viewModel.confirmMerge()
        advanceUntilIdle()

        assertEquals(1L, mergedSource)
        assertEquals(2L, mergedTarget)
        assertTrue(viewModel.uiState.value.mergeStatus is AlbumMergeStatus.Success)
        assertEquals(2L, (viewModel.uiState.value.mergeStatus as AlbumMergeStatus.Success).targetAlbumId)
        assertNull(viewModel.uiState.value.pendingMergeTarget)
    }

    @Test
    fun `confirmMerge updates status to Error on failed merge`() = runTest(testDispatcher) {
        val target = AlbumSearchResult(
            id = 2L,
            title = "Abbey Road",
            artistId = 100L,
            artistName = "The Beatles",
            releaseYear = 1969,
            artworkUrl = null
        )

        val customRepo = object : AlbumMergeRepository(
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            allocateInstance(TrackAliasRepository::class.java),
            createProxy { _, _ -> null },
            createFakeDatabase(),
            createProxy { _, _ -> null }
        ) {
            override suspend fun mergeAlbums(sourceAlbumId: Long, targetAlbumId: Long): Boolean {
                return false
            }
        }

        val viewModel = MergeAlbumViewModel(customRepo)
        viewModel.setSourceAlbum(1L, 100L)
        advanceUntilIdle()

        viewModel.selectAlbumForMerge(target)
        viewModel.confirmMerge()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.mergeStatus is AlbumMergeStatus.Error)
    }

    @Test
    fun `resetStatus clears mergeStatus and pending target`() = runTest(testDispatcher) {
        val customRepo = object : AlbumMergeRepository(
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            allocateInstance(TrackAliasRepository::class.java),
            createProxy { _, _ -> null },
            createFakeDatabase(),
            createProxy { _, _ -> null }
        ) {}

        val viewModel = MergeAlbumViewModel(customRepo)
        viewModel.resetStatus()

        assertEquals(AlbumMergeStatus.Idle, viewModel.uiState.value.mergeStatus)
        assertNull(viewModel.uiState.value.pendingMergeTarget)
    }
}
