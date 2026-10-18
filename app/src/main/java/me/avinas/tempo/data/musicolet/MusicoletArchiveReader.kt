package me.avinas.tempo.data.musicolet

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * The play-count databases Musicolet keeps alongside `DB_SONGS_LOG`.
 *
 * Musicolet does not store a timestamp per play. It stores a *count* per song per
 * period, in one SQLite file per period: `PCs_M_<year>.<month>` for months,
 * `PCs_W_<year>.<week>` for weeks and `PCs_Y_<year>` for years. Those period files
 * are what makes a dated reconstruction possible at all.
 */
internal enum class MusicoletPeriodKind { MONTH, WEEK, YEAR }

/** One decrypted `PCs_*` play-count database, plus the period it covers. */
internal data class MusicoletPeriodFile(
    val file: File,
    val kind: MusicoletPeriodKind,
    val year: Int,
    /** Month 1–12 or week number; null when the file name carries no bucket. */
    val bucket: Int?,
)

/** What a Musicolet backup (or a bare database file) yielded. */
internal data class MusicoletArchive(
    val songLog: File?,
    val periodFiles: List<MusicoletPeriodFile>,
    val entriesSeen: Int,
    val periodFilesSkipped: Int,
)

/**
 * Extracts the listening databases from a Musicolet backup.
 *
 * Musicolet writes a ZIP whose members are Blowfish-encrypted databases (see
 * [MusicoletCrypto]). A user may instead hand us a single member they already
 * pulled out of the ZIP — the `DB_SONGS_LOG` file on its own — so both shapes are
 * accepted and distinguished by content, not by extension.
 *
 * Entry names are never used to build a path. Only a strict allow-list of names is
 * written, and each is written to a name this class composes, so a hostile or
 * merely odd backup cannot escape the temporary directory.
 */
internal object MusicoletArchiveReader {
    /** `DB_SONGS_LOG` — the per-song totals, ratings and last-played times. */
    const val SONG_DB_NAME = "DB_SONGS_LOG"

    /** A backup carries one file per month of use; this is years beyond plausible. */
    private const val MAX_ENTRIES = 4_000

    /** Per-member cap: a large library's song database is a few MB. */
    private const val MAX_MEMBER_BYTES = 256L * 1024 * 1024

    /** Total decompressed cap, so a zip bomb cannot fill the cache directory. */
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024

    private val PERIOD_NAME = Regex("^PCs_([MWY])_(\\d{4})(?:\\.(\\d+))?$", RegexOption.IGNORE_CASE)

    /** `PCs_M_2026.6`, `PCs_W_2026.23`, `PCs_Y_2026`, `PCs_M_2019` (no bucket). */
    fun parsePeriodName(name: String): MusicoletPeriodFile? {
        val match = PERIOD_NAME.matchEntire(name.trim()) ?: return null
        val (code, year, bucket) = match.destructured
        return MusicoletPeriodFile(
            file = File(name),
            kind =
                when (code.uppercase(Locale.ROOT)) {
                    "M" -> MusicoletPeriodKind.MONTH
                    "W" -> MusicoletPeriodKind.WEEK
                    else -> MusicoletPeriodKind.YEAR
                },
            year = year.toIntOrNull() ?: return null,
            bucket = bucket.toIntOrNull(),
        )
    }

    /**
     * Reads [stream] into [workDir], decrypting every listening database it holds.
     *
     * [fileName] is only consulted to decide ZIP-versus-bare before the bytes are
     * sniffed; a `.zip` that is not a ZIP and a ZIP with no extension both work.
     */
    fun read(
        stream: InputStream,
        fileName: String,
        workDir: File,
    ): MusicoletArchive {
        workDir.mkdirs()
        val buffered = BufferedInputStream(stream, 64 * 1024)
        buffered.mark(4)
        val magic = ByteArray(4)
        val read = buffered.read(magic)
        buffered.reset()
        val isZip =
            (read >= 4 &&
                magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()) ||
                fileName.endsWith(".zip", ignoreCase = true) ||
                fileName.endsWith(".backup", ignoreCase = true)

        return if (isZip) readZip(buffered, workDir) else readBareDatabase(buffered, workDir)
    }

    private fun readBareDatabase(
        stream: InputStream,
        workDir: File,
    ): MusicoletArchive {
        val raw = readCapped(stream, MAX_MEMBER_BYTES) ?: return MusicoletArchive(null, emptyList(), 0, 0)
        val decrypted = MusicoletCrypto.decryptSqlite(raw) ?: return MusicoletArchive(null, emptyList(), 1, 0)
        val target = File(workDir, SONG_DB_NAME)
        target.writeBytes(decrypted)
        return MusicoletArchive(
            songLog = target,
            periodFiles = emptyList(),
            entriesSeen = 1,
            periodFilesSkipped = 0,
        )
    }

    private fun readZip(
        stream: InputStream,
        workDir: File,
    ): MusicoletArchive {
        var songLog: File? = null
        val periods = mutableListOf<MusicoletPeriodFile>()
        var entriesSeen = 0
        var skipped = 0
        var totalBytes = 0L

        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                entriesSeen++
                if (entriesSeen > MAX_ENTRIES) break

                // Only the base name matters: Musicolet writes at the ZIP root, but a
                // user-rezipped backup may nest the members.
                val baseName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                val period = parsePeriodName(baseName)
                val isSongLog = baseName.equals(SONG_DB_NAME, ignoreCase = true)
                if (!isSongLog && period == null) {
                    // Not a listening database (settings, playlists, the `0.musicolet.backup`
                    // MD5 manifest). Skipped without reading, so a large unrelated member
                    // costs nothing.
                    continue
                }

                val remaining = MAX_TOTAL_BYTES - totalBytes
                if (remaining <= 0) break
                val raw = readCapped(zip, minOf(MAX_MEMBER_BYTES, remaining)) ?: continue
                totalBytes += raw.size

                val decrypted = MusicoletCrypto.decryptSqlite(raw)
                if (decrypted == null) {
                    skipped++
                    continue
                }

                if (isSongLog) {
                    val target = File(workDir, SONG_DB_NAME)
                    target.writeBytes(decrypted)
                    songLog = target
                } else if (period != null) {
                    // The written name is composed from the parsed period, never from
                    // the entry name, so a name like `../../x` cannot reach outside.
                    val safeName = periodFileName(period)
                    val target = File(workDir, safeName)
                    target.writeBytes(decrypted)
                    periods.add(period.copy(file = target))
                }
            }
        }

        return MusicoletArchive(
            songLog = songLog,
            periodFiles = periods,
            entriesSeen = entriesSeen,
            periodFilesSkipped = skipped,
        )
    }

    private fun periodFileName(period: MusicoletPeriodFile): String {
        val code =
            when (period.kind) {
                MusicoletPeriodKind.MONTH -> "M"
                MusicoletPeriodKind.WEEK -> "W"
                MusicoletPeriodKind.YEAR -> "Y"
            }
        val bucket = period.bucket?.let { ".$it" } ?: ""
        return "PCs_${code}_${period.year}$bucket"
    }

    /**
     * Reads an entry, refusing anything past [limit] rather than trusting the ZIP's
     * declared size. Returns null when the cap was hit or the read failed.
     */
    private fun readCapped(
        stream: InputStream,
        limit: Long,
    ): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
