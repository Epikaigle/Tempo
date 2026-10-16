package me.avinas.tempo.data.musicolet

import java.io.File
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Reads a Musicolet database through the SQLite JDBC driver bundled for tests.
 *
 * Production uses Android's `SQLiteDatabase`; this is the same seam with a driver that
 * runs on a plain JVM, so the parsing and reconstruction above it are exercised against
 * real SQLite files rather than a hand-written stand-in for one.
 */
internal class JdbcMusicoletTableOpener : MusicoletTableOpener {
    override fun openSingleTable(dbFile: File): MusicoletTable? {
        if (!dbFile.exists()) return null
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            val tableName =
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name LIMIT 1")
                        .use { rs -> if (rs.next()) rs.getString(1) else null }
                } ?: return null

            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM \"${tableName.replace("\"", "\"\"")}\"").use { rs ->
                    val meta = rs.metaData
                    val columns = (1..meta.columnCount).map { meta.getColumnName(it) }
                    val rows = mutableListOf<List<Any?>>()
                    while (rs.next()) {
                        rows.add((1..meta.columnCount).map { rs.getObject(it) })
                    }
                    return MusicoletTable(tableName, columns, rows)
                }
            }
        }
    }
}

/** Builds synthetic Musicolet backups: real SQLite files, encrypted the way Musicolet does. */
internal object MusicoletFixtures {
    const val SONG_TABLE_DDL = """
        CREATE TABLE TABLE_SONGS (
            COL_TITLE TEXT, COL_ARTIST TEXT, COL_ALBUM TEXT, album_artist TEXT,
            COL_GENRE TEXT, COL_YEAR INTEGER, COL_DURATION INTEGER, COL_TRACK_NO INTEGER,
            COL_NUM_PLAYED INTEGER, COL_NUM_PLAYED_W INTEGER, COL_NUM_PLAYED_M INTEGER,
            COL_NUM_PLAYED_Y INTEGER, COL_LAST_PLAYED INTEGER, COL_RATING INTEGER,
            COL_LOGPATH TEXT, COL_PATH TEXT
        )
    """

    const val SONG_INSERT = """
        INSERT INTO TABLE_SONGS VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
    """

    const val PERIOD_TABLE_DDL = "CREATE TABLE PLAY_COUNTS (COL_PATH TEXT, COL_NUM_PLAYED INTEGER)"
    const val PERIOD_INSERT = "INSERT INTO PLAY_COUNTS VALUES (?,?)"

    /** One row of `TABLE_SONGS`, with the columns the importer reads. */
    data class SongRow(
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Long? = 180_000L,
        val totalPlays: Long = 0,
        val lastPlayedMs: Long? = null,
        val logPath: String? = null,
    ) {
        fun asValues(): List<Any?> =
            listOf(
                title, artist, album, artist, "Genre", 2026, durationMs, 1,
                totalPlays, 0, 0, 0, lastPlayedMs, 0, logPath, logPath,
            )
    }

    /**
     * Blowfish-ECB encrypts [plain] exactly as Musicolet does: the literal key
     * `JSTMUSIC_2` with PKCS#5 padding.
     */
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("Blowfish/ECB/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(MusicoletCrypto.KEY.toByteArray(Charsets.UTF_8), "Blowfish"),
        )
        return cipher.doFinal(plain)
    }

    fun passThrough(plain: ByteArray): ByteArray = plain

    /** Writes a real SQLite database to [file] using the given DDL and rows. */
    fun writeDatabase(
        file: File,
        ddl: String,
        insertSql: String,
        rows: List<List<Any?>>,
    ) {
        file.parentFile?.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { it.execute(ddl) }
            connection.prepareStatement(insertSql).use { statement ->
                rows.forEach { row ->
                    row.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    fun writeSongDatabase(
        file: File,
        songs: List<SongRow>,
    ) = writeDatabase(file, SONG_TABLE_DDL, SONG_INSERT, songs.map { it.asValues() })

    fun writePeriodDatabase(
        file: File,
        plays: List<Pair<String, Long>>,
    ) = writeDatabase(file, PERIOD_TABLE_DDL, PERIOD_INSERT, plays.map { listOf(it.first, it.second) })

    /**
     * Zips [entries] into [target]. Entries given as `ByteArray` are written verbatim,
     * so a caller decides per member whether it is encrypted.
     */
    fun zip(
        target: File,
        entries: Map<String, ByteArray>,
    ) {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
