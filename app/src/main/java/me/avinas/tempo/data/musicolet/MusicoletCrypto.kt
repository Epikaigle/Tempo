package me.avinas.tempo.data.musicolet

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Musicolet's backup members are Blowfish-ECB encrypted with a fixed, hard-coded key.
 *
 * Musicolet (Krosbits) writes every file inside its backup ZIP — the `DB_SONGS_LOG`
 * SQLite database and each `PCs_*` play-count database — through the same Blowfish
 * ECB cipher using the literal key `JSTMUSIC_2`, PKCS#5/PKCS#7 padded. This is
 * obfuscation rather than secrecy: the key ships inside the app and two independent
 * third-party decoders use the same constant.
 *
 * The cipher is requested with no explicit provider so the platform picks one:
 * `Blowfish/ECB/NoPadding` is served by `SunJCE` on the JVM and by
 * `com.android.org.bouncycastle` on Android, which registers
 * `Cipher.BLOWFISH -> Blowfish$ECB` and keeps `BlowfishEngine` in its non-removed
 * set. Padding is stripped by hand because Musicolet's trailing pad byte is not
 * always well-formed; a strict `PKCS5Padding` would throw on the truncated final
 * block some backups contain.
 *
 * Nothing here is secret-dependent, and nothing is trusted: a member that does not
 * decrypt to a SQLite header is reported as not-a-database rather than parsed.
 */
internal object MusicoletCrypto {
    /** The literal key Musicolet uses for every backup member. */
    const val KEY = "JSTMUSIC_2"

    private const val TRANSFORMATION = "Blowfish/ECB/NoPadding"
    private const val KEY_ALGORITHM = "Blowfish"
    private const val BLOCK_SIZE = 8

    /** Every SQLite database file starts with this 16-byte header. */
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** True when [bytes] begins with the SQLite file header. */
    fun looksLikeSqlite(bytes: ByteArray): Boolean {
        if (bytes.size < SQLITE_MAGIC.size) return false
        for (i in SQLITE_MAGIC.indices) {
            if (bytes[i] != SQLITE_MAGIC[i]) return false
        }
        return true
    }

    /**
     * Returns the SQLite database in [raw], decrypting it when necessary.
     *
     * Returns the bytes unchanged when they are already a SQLite file — which is what
     * a user who decrypted the database themselves will hand us, and what a future
     * Musicolet that stops encrypting would produce. Returns null when [raw] is
     * neither a database nor Blowfish ciphertext that decrypts to one.
     */
    fun decryptSqlite(raw: ByteArray): ByteArray? {
        if (looksLikeSqlite(raw)) return raw

        // Blowfish is a 64-bit block cipher: anything not a whole number of blocks
        // is not ciphertext this key produced.
        if (raw.isEmpty() || raw.size % BLOCK_SIZE != 0) return null

        val plain =
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), KEY_ALGORITHM))
                cipher.doFinal(raw)
            } catch (e: Exception) {
                // A platform without a Blowfish provider, or malformed input: both are
                // "this member is not a database we can read", never a crash.
                return null
            }

        val unpadded = stripPadding(plain)
        return if (looksLikeSqlite(unpadded)) unpadded else null
    }

    /**
     * Removes PKCS#5/PKCS#7 padding, tolerating a pad byte that does not match the
     * block it claims to be (some Musicolet versions leave the tail block raw).
     */
    private fun stripPadding(plain: ByteArray): ByteArray {
        if (plain.isEmpty()) return plain
        val pad = plain[plain.size - 1].toInt() and 0xFF
        if (pad < 1 || pad > BLOCK_SIZE || pad > plain.size) return plain
        for (i in plain.size - pad until plain.size) {
            if ((plain[i].toInt() and 0xFF) != pad) return plain
        }
        return plain.copyOf(plain.size - pad)
    }
}
