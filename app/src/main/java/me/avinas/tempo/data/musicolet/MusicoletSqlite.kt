package me.avinas.tempo.data.musicolet

import java.io.File
import java.util.Locale

/**
 * One table read out of a decrypted Musicolet database.
 *
 * Musicolet's databases each hold a single table (`TABLE_SONGS` in `DB_SONGS_LOG`,
 * and a play-count table with `COL_PATH`/`COL_NUM_PLAYED` in each `PCs_*` file), so
 * there is no need for a general SQL surface. Column lookup is case-insensitive
 * because column names are not guaranteed to keep their documented casing.
 */
class MusicoletTable(
    val name: String,
    columns: List<String>,
    val rows: List<List<Any?>>,
) {
    private val index: Map<String, Int> =
        columns.withIndex().associate { (position, column) -> column.lowercase(Locale.ROOT) to position }

    fun has(column: String): Boolean = index.containsKey(column.lowercase(Locale.ROOT))

    private fun value(
        row: List<Any?>,
        column: String,
    ): Any? = index[column.lowercase(Locale.ROOT)]?.let { row.getOrNull(it) }

    /** Text value of [column], or null when absent or SQL NULL. */
    fun string(
        row: List<Any?>,
        column: String,
    ): String? = value(row, column)?.let { stringify(it) }?.takeIf { it.isNotEmpty() }

    /** Integer value of [column], or null when absent, NULL, or not a number. */
    fun long(
        row: List<Any?>,
        column: String,
    ): Long? =
        when (val raw = value(row, column)) {
            null -> null
            is Number -> raw.toLong()
            else -> stringify(raw).trim().toLongOrNull()
        }

    private fun stringify(raw: Any?): String =
        when (raw) {
            is ByteArray -> raw.toString(Charsets.UTF_8)
            is String -> raw
            else -> raw.toString()
        }
}

/**
 * Opens a decrypted Musicolet database file and returns its only table.
 *
 * This is the one seam that cannot run on a plain JVM: Android reads the file with
 * `android.database.sqlite.SQLiteDatabase`, while unit tests read it through the
 * bundled SQLite JDBC driver. Everything above it — period aggregation, path
 * joining, reconstruction — is pure and tested on both.
 */
fun interface MusicoletTableOpener {
    /** Returns the database's first table, or null when [dbFile] is missing or unreadable. */
    fun openSingleTable(dbFile: File): MusicoletTable?
}
