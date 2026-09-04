package me.avinas.tempo.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allowlist guards the bulk background pre-cache against attacker-chosen
 * URLs smuggled inside a crafted backup file, so every parse edge case matters.
 */
class ImageUrlHostAllowlistTest {

    @Test
    fun `legitimate music art CDN hosts are allowed`() {
        val allowed = listOf(
            "https://i.scdn.co/image/ab67616d00001e02abc",
            "https://thisis-images.scdn.co/37i9dQZF1DXcBWIG.jpg",
            "https://coverartarchive.org/release/12345/front-250.jpg",
            "https://archive.org/download/mbid-123/cover.jpg",
            "https://is1-ssl.mzstatic.com/image/thumb/AMCArtistImages/v4/x/y/600x600bb.jpg",
            "https://is5-ssl.mzstatic.com/image/thumb/Features/x/y/source/1000x1000.jpg",
            "https://e-cdns-images.dzcdn.net/images/artist/abc/500x500.jpg",
            "https://lastfm.freetls.fastly.net/i/u/300x300/abc.png",
            "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            "https://api.discogs.com/image/R-150-abc.jpg",
            "https://musicbrainz.org/mbid/cover"
        )
        allowed.forEach { url ->
            assertTrue("Expected allowed: $url", ImageUrlHostAllowlist.isAllowed(url))
        }
    }

    @Test
    fun `attacker chosen hosts are rejected`() {
        val rejected = listOf(
            "https://evil.example.com/phone-home.png",
            "http://169.254.169.254/latest/meta-data",
            "https://attacker.io/collect?ua=1",
            "https://i.scdn.co.attacker.com/image.jpg",   // suffix must be dot-anchored
            "https://scdn.co.evil.net/image.jpg",
            "https://coverartarchive.org.evil.net/x.jpg",
            "https://mzstatic.com.evil.net/x.jpg",
            "https://fake-archive.org/x.jpg",
            "https://notlastfm.freetls.fastly.net.evil.net/x.jpg"
        )
        rejected.forEach { url ->
            assertFalse("Expected rejected: $url", ImageUrlHostAllowlist.isAllowed(url))
        }
    }

    @Test
    fun `non http schemes are rejected`() {
        val rejected = listOf(
            "file:///data/data/me.avinas.tempo/files/secret.db",
            "content://media/external/images/media/1",
            "javascript:alert(1)",
            "data:image/png;base64,iVBORw0KGgo=",
            "ftp://i.scdn.co/image.jpg",
            "ws://i.scdn.co/image.jpg"
        )
        rejected.forEach { url ->
            assertFalse("Expected rejected: $url", ImageUrlHostAllowlist.isAllowed(url))
        }
    }

    @Test
    fun `malformed urls are rejected not crashed on`() {
        val rejected = listOf(
            "",
            "   ",
            "not-a-url",
            "https://",
            "https:///path/only",
            "://missing-scheme.com/x",
            "/relative/path.png"
        )
        rejected.forEach { url ->
            assertFalse("Expected rejected: '$url'", ImageUrlHostAllowlist.isAllowed(url))
        }
    }

    @Test
    fun `parsing handles userinfo ports queries and fragments`() {
        // Host extraction must see the real host, never userinfo or path data.
        assertTrue(ImageUrlHostAllowlist.isAllowed("https://i.scdn.co:443/image.jpg?q=1#frag"))
        assertFalse(ImageUrlHostAllowlist.isAllowed("https://evil.com@google.com/image.jpg"))
        assertTrue(ImageUrlHostAllowlist.isAllowed("https://google.com@i.scdn.co/image.jpg"))
        assertEquals("i.scdn.co", ImageUrlHostAllowlist.extractHost("https://user:pass@i.scdn.co/img"))
        assertEquals("i.scdn.co", ImageUrlHostAllowlist.extractHost("HTTPS://I.SCDN.CO/IMG"))
        assertEquals(null, ImageUrlHostAllowlist.extractHost("https://:443/nohost"))
    }

    @Test
    fun `filterAllowed preserves order and only keeps allowed entries`() {
        val input = listOf(
            "https://evil.example.com/a.png",
            "https://i.scdn.co/b.jpg",
            "https://coverartarchive.org/c.jpg"
        )
        assertEquals(
            listOf("https://i.scdn.co/b.jpg", "https://coverartarchive.org/c.jpg"),
            ImageUrlHostAllowlist.filterAllowed(input)
        )
        assertTrue(ImageUrlHostAllowlist.filterAllowed(emptyList()).isEmpty())
    }
}
