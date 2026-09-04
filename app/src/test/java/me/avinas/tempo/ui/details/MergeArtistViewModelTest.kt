package me.avinas.tempo.ui.details

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.avinas.tempo.data.local.AppDatabase
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.data.repository.ArtistMergeRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class MergeArtistViewModelTest {

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
    fun `searchArtists returns artists with imageUrl and excludes source artist`() = runTest(testDispatcher) {
        val artistsList = listOf(
            Artist(
                id = 2L,
                name = "Target Artist",
                imageUrl = "https://example.com/artist_photo.jpg"
            )
        )

        val customRepo = object : ArtistMergeRepository(
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createFakeDatabase(),
            createProxy { _, _ -> null }
        ) {
            override suspend fun searchArtists(query: String, excludeArtistId: Long?): List<Artist> {
                return artistsList.filter { it.id != excludeArtistId }
            }
        }

        val viewModel = MergeArtistViewModel(customRepo)

        viewModel.setSourceArtistId(1L)
        viewModel.onQueryChange("Target")
        advanceUntilIdle()

        val results = viewModel.uiState.value.searchResults
        assertEquals(1, results.size)
        assertEquals(2L, results[0].id)
        assertEquals("https://example.com/artist_photo.jpg", results[0].imageUrl)
    }

    @Test
    fun `selectArtistForMerge and cancelMerge manage pendingMergeTarget`() = runTest(testDispatcher) {
        val artist = Artist(
            id = 2L,
            name = "Target Artist",
            imageUrl = "https://example.com/target_artist.jpg"
        )

        val customRepo = object : ArtistMergeRepository(
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createProxy { _, _ -> null },
            createFakeDatabase(),
            createProxy { _, _ -> null }
        ) {}

        val viewModel = MergeArtistViewModel(customRepo)

        viewModel.selectArtistForMerge(artist)
        assertEquals(artist, viewModel.uiState.value.pendingMergeTarget)
        assertNotNull(viewModel.uiState.value.pendingMergeTarget?.imageUrl)

        viewModel.cancelMerge()
        assertEquals(null, viewModel.uiState.value.pendingMergeTarget)
    }
}
