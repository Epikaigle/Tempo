package me.avinas.tempo.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceAuthorityTest {

    @Test
    fun liveDeezerPlaybackStillOutranksImportedHistory() {
        assertEquals(
            100,
            SourceAuthority.rank("deezer.android.app"),
        )
    }
}
