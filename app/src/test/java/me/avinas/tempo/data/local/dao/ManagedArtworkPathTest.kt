package me.avinas.tempo.data.local.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Artwork URLs reach the delete/repair paths from untrusted places: MediaSession
 * metadata published by whichever app is playing audio, restored backups, and
 * imported exports. Checking the `file://` scheme alone let such a value name any
 * file the app can write, so containment inside Tempo's artwork directory is the
 * property under test here.
 */
class ManagedArtworkPathTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Mirrors `filesDir/album_art`, the only directory Tempo manages artwork in. */
    private fun managedRoot(): File = tempFolder.newFolder("files", "album_art")

    /** A stand-in for a sensitive app file outside the artwork directory. */
    private fun decoyAppFile(): File {
        tempFolder.newFolder("databases")
        return tempFolder.newFile("databases/tempo.db").apply { writeText("sqlite") }
    }

    @Test
    fun fileInsideManagedDirectoryIsAccepted() {
        val root = managedRoot()
        val artwork = File(root, "art_deadbeef.jpg").apply { writeText("jpeg") }

        val resolved = managedArtworkFileOrNull("file://${artwork.absolutePath}", root)

        assertNotNull(resolved)
        assertEquals(artwork.canonicalPath, resolved!!.path)
    }

    @Test
    fun parentTraversalOutOfManagedDirectoryIsRejected() {
        val root = managedRoot()
        val databaseFile = decoyAppFile()

        // The attack: a MediaSession album-art URI that traverses out of the
        // artwork directory and names the app's own database.
        val crafted = "file://${root.absolutePath}/../../databases/tempo.db"

        // Sanity check: the crafted value really does resolve to the decoy file.
        assertEquals(databaseFile.canonicalPath, File(crafted.removePrefix("file://")).canonicalPath)
        assertNull(managedArtworkFileOrNull(crafted, root))
    }

    @Test
    fun absolutePathOutsideManagedDirectoryIsRejected() {
        val root = managedRoot()
        val databaseFile = decoyAppFile()

        assertNull(managedArtworkFileOrNull("file://${databaseFile.absolutePath}", root))
    }

    @Test
    fun deepTraversalIsRejected() {
        val root = managedRoot()
        decoyAppFile()

        assertNull(
            managedArtworkFileOrNull(
                "file://${root.absolutePath}/../../../../../../etc/hosts",
                root,
            )
        )
    }

    @Test
    fun siblingDirectorySharingTheRootPrefixIsRejected() {
        val root = managedRoot()
        tempFolder.newFolder("files", "album_art_backup")
        val sibling = tempFolder.newFile("files/album_art_backup/art_stolen.jpg")

        // "album_art_backup" starts with "album_art", so a bare prefix check would
        // wrongly accept it.
        assertTrue(sibling.absolutePath.contains("album_art_backup"))
        assertNull(managedArtworkFileOrNull("file://${sibling.absolutePath}", root))
    }

    @Test
    fun managedDirectoryItselfIsNotAFile() {
        val root = managedRoot()

        assertNull(managedArtworkFileOrNull("file://${root.absolutePath}", root))
    }

    @Test
    fun selfReferentialTraversalInsideManagedDirectoryStaysAllowed() {
        val root = managedRoot()
        tempFolder.newFolder("files", "album_art", "nested")
        val artwork = tempFolder.newFile("files/album_art/nested/art_cafe.jpg")

        // "nested/../nested" never actually leaves the root, so it remains valid.
        val url = "file://${root.absolutePath}/nested/../nested/art_cafe.jpg"

        val resolved = managedArtworkFileOrNull(url, root)
        assertNotNull(resolved)
        assertEquals(artwork.canonicalPath, resolved!!.path)
    }

    @Test
    fun nonFileSchemesAndMalformedValuesAreRejected() {
        val root = managedRoot()

        assertNull(managedArtworkFileOrNull(null, root))
        assertNull(managedArtworkFileOrNull("", root))
        assertNull(managedArtworkFileOrNull("content://media/external/audio/1", root))
        assertNull(managedArtworkFileOrNull("https://i.scdn.co/image/abc", root))
        // A relative path must not be resolved against the working directory.
        assertNull(managedArtworkFileOrNull("file://album_art/art.jpg", root))
        assertNull(managedArtworkFileOrNull("file://", root))
    }
}
