package me.avinas.tempo.ui.details

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.avinas.tempo.data.local.entities.Track
import me.avinas.tempo.data.repository.TrackAliasRepository
import me.avinas.tempo.data.repository.TrackRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class MergeTrackViewModelTest {

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

    @Test
    fun `searchTracks returns tracks with artwork and excludes source track`() = runTest(testDispatcher) {
        val tracksList = listOf(
            Track(
                id = 1L,
                title = "Song A",
                artist = "Artist X",
                album = "Album 1",
                duration = 200000L,
                albumArtUrl = "https://example.com/art1.jpg",
                spotifyId = null,
                musicbrainzId = null
            ),
            Track(
                id = 2L,
                title = "Song A (Remastered)",
                artist = "Artist X",
                album = "Album 2",
                duration = 205000L,
                albumArtUrl = "https://example.com/art2.jpg",
                spotifyId = null,
                musicbrainzId = null
            )
        )

        val fakeTrackRepo = createProxy<TrackRepository> { methodName, _ ->
            if (methodName == "searchTracks") {
                tracksList
            } else {
                null
            }
        }

        val fakeAliasRepo = allocateInstance(TrackAliasRepository::class.java)
        val viewModel = MergeTrackViewModel(fakeTrackRepo, fakeAliasRepo)

        viewModel.setSourceTrackId(1L)
        viewModel.onQueryChange("Song")
        advanceUntilIdle()

        val results = viewModel.uiState.value.searchResults
        assertEquals(1, results.size)
        assertEquals(2L, results[0].id)
        assertEquals("https://example.com/art2.jpg", results[0].albumArtUrl)
    }

    @Test
    fun `selectTrackForMerge and cancelMerge manage pendingMergeTarget`() = runTest(testDispatcher) {
        val track = Track(
            id = 2L,
            title = "Target Track",
            artist = "Target Artist",
            album = "Target Album",
            duration = 180000L,
            albumArtUrl = "https://example.com/target_art.jpg",
            spotifyId = null,
            musicbrainzId = null
        )

        val fakeTrackRepo = createProxy<TrackRepository> { _, _ -> null }
        val fakeAliasRepo = allocateInstance(TrackAliasRepository::class.java)
        val viewModel = MergeTrackViewModel(fakeTrackRepo, fakeAliasRepo)

        viewModel.selectTrackForMerge(track)
        assertEquals(track, viewModel.uiState.value.pendingMergeTarget)
        assertNotNull(viewModel.uiState.value.pendingMergeTarget?.albumArtUrl)

        viewModel.cancelMerge()
        assertEquals(null, viewModel.uiState.value.pendingMergeTarget)
    }
}
