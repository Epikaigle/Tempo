package me.avinas.tempo.data.importexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The restored profile image path comes from an untrusted backup file. A
 * legitimate export always stores a file:// URI inside app-private storage and
 * remaps it through the bundled image manifest; anything else must be dropped
 * instead of being persisted as-is.
 */
class RestorePathResolutionTest {

    @Test
    fun `blank and null inputs resolve to null`() {
        assertNull(resolveRestoredProfileImagePath(null, emptyMap()))
        assertNull(resolveRestoredProfileImagePath("", emptyMap()))
        assertNull(resolveRestoredProfileImagePath("   ", emptyMap()))
    }

    @Test
    fun `file uri present in the manifest is remapped`() {
        val mapping = mapOf(
            "file:///data/user/0/me.avinas.tempo/files/profile/profile.jpg" to
                "file:///data/user/0/me.avinas.tempo/files/profile/img_4_profile.jpg"
        )
        val exported = "file:///data/user/0/me.avinas.tempo/files/profile/profile.jpg"
        assertEquals(
            "file:///data/user/0/me.avinas.tempo/files/profile/img_4_profile.jpg",
            resolveRestoredProfileImagePath(exported, mapping)
        )
    }

    @Test
    fun `file uri missing from the manifest resolves to null`() {
        assertNull(
            resolveRestoredProfileImagePath(
                "file:///data/user/0/me.avinas.tempo/files/profile/gone.jpg",
                emptyMap()
            )
        )
    }

    @Test
    fun `non file uri values from an untrusted backup are discarded`() {
        // Before the fix these were persisted as-is; now they must never survive.
        assertNull(
            resolveRestoredProfileImagePath(
                "https://evil.example.com/avatar.jpg",
                emptyMap()
            )
        )
        assertNull(
            resolveRestoredProfileImagePath(
                "content://media/external/images/media/42",
                emptyMap()
            )
        )
        assertNull(
            resolveRestoredProfileImagePath(
                "/storage/emulated/0/DCIM/arbitrary_path.jpg",
                mapOf("/storage/emulated/0/DCIM/arbitrary_path.jpg" to "file:///somewhere")
            )
        )
    }
}
