package me.avinas.tempo.data.musicolet

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Reads a decrypted Musicolet database with Android's own SQLite implementation.
 *
 * The databases are opened read-only from the import's temporary directory, one at a
 * time, and closed before the next is read — a Musicolet library of tens of thousands
 * of songs is a few megabytes, so materialising one table is cheaper than keeping a
 * connection open for the length of an import.
 */
internal class AndroidMusicoletTableOpener : MusicoletTableOpener {
    override fun openSingleTable(dbFile: File): MusicoletTable? {
        if (!dbFile.exists() || !dbFile.canRead()) return null
        return try {
            SQLiteDatabase
                .openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .use { database -> readFirstTable(database) }
        } catch (e: Exception) {
            // A truncated, half-decrypted or foreign file lands here; the caller treats
            // an unreadable database as "nothing to import", never as a crash.
            Log.w(TAG, "Could not read Musicolet database ${dbFile.name}", e)
            null
        }
    }

    private fun readFirstTable(database: SQLiteDatabase): MusicoletTable? {
        val tableName =
            database
                .rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name LIMIT 1", null)
                .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: return null

        val quoted = "\"" + tableName.replace("\"", "\"\"") + "\""
        database.rawQuery("SELECT * FROM $quoted", null).use { cursor ->
            val columns = cursor.columnNames.toList()
            val rows = ArrayList<List<Any?>>(cursor.count.coerceAtLeast(0))
            while (cursor.moveToNext()) {
                val row = ArrayList<Any?>(columns.size)
                for (position in columns.indices) {
                    row.add(readValue(cursor, position))
                }
                rows.add(row)
            }
            return MusicoletTable(tableName, columns, rows)
        }
    }

    private fun readValue(
        cursor: Cursor,
        position: Int,
    ): Any? =
        when (cursor.getType(position)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(position)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(position)
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(position)
            else -> cursor.getString(position)
        }

    private companion object {
        const val TAG = "MusicoletSqlite"
    }
}
