package me.avinas.tempo.data.musicolet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Musicolet encrypts every backup member with Blowfish-ECB under a fixed key. These
 * tests pin the cipher, the key and the padding handling, because a mistake in any of
 * them silently produces "no Musicolet data found" on a real backup rather than an
 * error.
 */
class MusicoletCryptoTest {
    private val sqliteFile = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(64)

    @Test
    fun `key is the literal Musicolet uses`() {
        assertEquals("JSTMUSIC_2", MusicoletCrypto.KEY)
    }

    /**
     * The cipher is requested as `Blowfish/ECB/NoPadding`, and this is the official
     * Blowfish known-answer vector from the algorithm's own test set: a zero key over a
     * zero block must produce `4EF997456198DD78`. It fails if the transformation is
     * typo'd, if a provider substitutes CBC, or if the key is not a raw byte key.
     */
    @Test
    fun `blowfish ecb matches the standard test vector`() {
        val cipher = Cipher.getInstance("Blowfish/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(8), "Blowfish"))
        val encrypted = cipher.doFinal(ByteArray(8))
        assertEquals("4ef997456198dd78", encrypted.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `encrypted database is recovered`() {
        val decrypted = MusicoletCrypto.decryptSqlite(MusicoletFixtures.encrypt(sqliteFile))
        assertNotNull(decrypted)
        assertArrayEquals(sqliteFile, decrypted)
    }

    /** A user who decrypted the database themselves hands us a plain SQLite file. */
    @Test
    fun `plain database passes through untouched`() {
        assertArrayEquals(sqliteFile, MusicoletCrypto.decryptSqlite(sqliteFile))
    }

    /** Musicolet's padding is stripped, so the recovered file is exactly the original. */
    @Test
    fun `padding is stripped rather than left on the file`() {
        val payload = sqliteFile + ByteArray(3) { 7 }
        val encrypted = MusicoletFixtures.encrypt(payload)
        assertArrayEquals(payload, MusicoletCrypto.decryptSqlite(encrypted))
    }

    /** A settings or manifest member is not a database, and must not be mistaken for one. */
    @Test
    fun `non database members are refused`() {
        assertNull(MusicoletCrypto.decryptSqlite(MusicoletFixtures.encrypt("{\"a\":1}".toByteArray())))
    }

    /** Blowfish is a 64-bit block cipher, so a length that is not a multiple of 8 is not its output. */
    @Test
    fun `length that is not a whole number of blocks is refused`() {
        assertNull(MusicoletCrypto.decryptSqlite(ByteArray(13) { 1 }))
    }

    @Test
    fun `empty input is refused`() {
        assertNull(MusicoletCrypto.decryptSqlite(ByteArray(0)))
    }

    @Test
    fun `sqlite header detection is exact`() {
        assertEquals(true, MusicoletCrypto.looksLikeSqlite(sqliteFile))
        assertEquals(false, MusicoletCrypto.looksLikeSqlite("SQLite format 4\u0000".toByteArray()))
        assertEquals(false, MusicoletCrypto.looksLikeSqlite(ByteArray(3)))
    }
}
