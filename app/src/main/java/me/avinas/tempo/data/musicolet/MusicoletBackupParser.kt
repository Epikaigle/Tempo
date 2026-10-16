package me.avinas.tempo.data.musicolet

/** A song as Musicolet recorded it. Path keys are dropped once the join is done. */
internal data class MusicoletSong(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val totalPlays: Long,
    val lastPlayedMs: Long?,
)

/** Plays Musicolet counted for one song in one period. */
internal data class MusicoletPeriodPlays(
    val songIndex: Int,
    val kind: MusicoletPeriodKind,
    val year: Int,
    val bucket: Int?,
    val plays: Long,
)

/** Everything a decrypted backup contributes to an import. */
internal data class MusicoletParsedBackup(
    val songs: List<MusicoletSong>,
    val periodPlays: List<MusicoletPeriodPlays>,
    val periodFilesRead: Int,
    /** Plays whose song is no longer in `DB_SONGS_LOG`; counted, never guessed at. */
    val unmatchedPeriodPlays: Long,
    val warnings: List<String>,
)

/**
 * Turns a decrypted Musicolet backup into songs and per-period play counts.
 *
 * The two halves of the data live in different files and are joined on the audio
 * file path: `DB_SONGS_LOG.TABLE_SONGS` carries `COL_LOGPATH`/`COL_PATH` along with
 * the lifetime count and the last-played time, while each `PCs_*` database carries
 * `COL_PATH` -> `COL_NUM_PLAYED` for one period. A play in a period file whose path
 * is no longer in `DB_SONGS_LOG` (the song was deleted from the device) cannot be
 * attributed to a track and is counted, not guessed at.
 */
internal class MusicoletBackupParser(
    private val opener: MusicoletTableOpener,
) {
    fun parse(archive: MusicoletArchive): MusicoletParsedBackup {
        val warnings = mutableListOf<String>()
        val songLog = archive.songLog
        if (songLog == null) {
            return MusicoletParsedBackup(
                songs = emptyList(),
                periodPlays = emptyList(),
                periodFilesRead = 0,
                unmatchedPeriodPlays = 0,
                warnings = listOf(
                    "No $SONG_DB_NAME database found. In Musicolet, use Settings → " +
                        "Backup and restore → Backup now, then import the ZIP it creates.",
                ),
            )
        }

        val table = opener.openSingleTable(songLog)
        if (table == null) {
            return MusicoletParsedBackup(
                songs = emptyList(),
                periodPlays = emptyList(),
                periodFilesRead = 0,
                unmatchedPeriodPlays = 0,
                warnings = listOf("$SONG_DB_NAME could not be read."),
            )
        }
        if (table.rows.isEmpty()) {
            return MusicoletParsedBackup(
                songs = emptyList(),
                periodPlays = emptyList(),
                periodFilesRead = 0,
                unmatchedPeriodPlays = 0,
                warnings = listOf("$SONG_DB_NAME contains no songs yet."),
            )
        }

        val totalColumnPresent = table.has(COL_NUM_PLAYED)
        if (!totalColumnPresent) {
            warnings.add("Play totals were read from the period files only.")
        }
        val pathColumnPresent = table.has(COL_LOGPATH) || table.has(COL_PATH)
        if (!pathColumnPresent) {
            warnings.add(
                "This database has no file paths, so per-month play counts could not be " +
                    "matched to songs; only each song's last-played time was used.",
            )
        }

        // Path -> song index. The first song claiming a path wins; duplicate paths
        // are a Musicolet quirk, not something to re-attribute plays over.
        val pathIndex = HashMap<String, Int>()
        val songs = ArrayList<MusicoletSong>(table.rows.size)
        for (row in table.rows) {
            val title = table.string(row, COL_TITLE)
            val artist = primaryArtist(table.string(row, COL_ARTIST))
            if (title.isNullOrBlank()) continue

            val index = songs.size
            songs.add(
                MusicoletSong(
                    title = title,
                    artist = artist,
                    album = table.string(row, COL_ALBUM)?.takeIf { it.isNotBlank() },
                    durationMs = table.long(row, COL_DURATION)?.takeIf { it > 0 },
                    totalPlays = table.long(row, COL_NUM_PLAYED)?.coerceAtLeast(0) ?: 0L,
                    lastPlayedMs = normalizeTimestamp(table.long(row, COL_LAST_PLAYED)),
                ),
            )
            if (pathColumnPresent) {
                for (column in listOf(COL_LOGPATH, COL_PATH)) {
                    val raw = table.string(row, column) ?: continue
                    for (key in pathKeys(raw)) {
                        pathIndex.putIfAbsent(key, index)
                    }
                }
            }
        }

        // (song, kind, year, bucket) -> plays. Duplicate period members inside one
        // backup are summed rather than overwriting each other.
        val merged = LinkedHashMap<PeriodKey, Long>()
        var periodFilesRead = 0
        var unmatchedPlays = 0L
        for (periodFile in archive.periodFiles) {
            val periodTable = opener.openSingleTable(periodFile.file) ?: continue
            if (!periodTable.has(COL_PATH) || !periodTable.has(COL_NUM_PLAYED)) continue
            periodFilesRead++
            for (row in periodTable.rows) {
                val plays = periodTable.long(row, COL_NUM_PLAYED) ?: 0L
                if (plays <= 0) continue
                val rawPath = periodTable.string(row, COL_PATH) ?: continue
                val songIndex =
                    pathKeys(rawPath).firstNotNullOfOrNull { pathIndex[it] }
                if (songIndex == null) {
                    unmatchedPlays += plays
                    continue
                }
                val key = PeriodKey(songIndex, periodFile.kind, periodFile.year, periodFile.bucket)
                merged[key] = (merged[key] ?: 0L) + plays
            }
        }

        val periodPlays =
            merged.map { (key, plays) ->
                MusicoletPeriodPlays(key.songIndex, key.kind, key.year, key.bucket, plays)
            }

        // A database without a lifetime column (or with it zeroed) still knows how
        // many plays each period held, so the total is the sum of what it does know.
        val resolvedSongs =
            if (totalColumnPresent) {
                songs
            } else {
                val totals = LongArray(songs.size)
                for (play in periodPlays) totals[play.songIndex] += play.plays
                songs.mapIndexed { index, song -> song.copy(totalPlays = totals[index]) }
            }

        return MusicoletParsedBackup(
            songs = resolvedSongs,
            periodPlays = periodPlays,
            periodFilesRead = periodFilesRead,
            unmatchedPeriodPlays = unmatchedPlays,
            warnings = warnings,
        )
    }

    private data class PeriodKey(
        val songIndex: Int,
        val kind: MusicoletPeriodKind,
        val year: Int,
        val bucket: Int?,
    )

    companion object {
        const val SONG_DB_NAME = MusicoletArchiveReader.SONG_DB_NAME

        // Documented Musicolet column names.
        const val COL_TITLE = "COL_TITLE"
        const val COL_ARTIST = "COL_ARTIST"
        const val COL_ALBUM = "COL_ALBUM"
        const val COL_DURATION = "COL_DURATION"
        const val COL_NUM_PLAYED = "COL_NUM_PLAYED"
        const val COL_LAST_PLAYED = "COL_LAST_PLAYED"
        const val COL_LOGPATH = "COL_LOGPATH"
        const val COL_PATH = "COL_PATH"

        /** `Storage/primary/...` is how Musicolet reports the primary volume internally. */
        private const val INTERNAL_STORAGE_PREFIX = "Storage/primary/"
        private const val PRIMARY_STORAGE_PREFIX = "/storage/emulated/0/"

        /**
         * Musicolet 6+ stores multiple artists slash-separated in one field
         * (`COL_ARTIST`), the same convention its own UI splits on. The first entry is
         * the primary artist; keeping the whole string would create one artist named
         * "A/B" and split the plays away from both real artists.
         */
        fun primaryArtist(raw: String?): String {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return UNKNOWN_ARTIST
            return value.substringBefore('/').trim().ifEmpty { UNKNOWN_ARTIST }
        }

        const val UNKNOWN_ARTIST = "Unknown Artist"

        /**
         * Every key a stored path may be looked up under, so a period row joins its
         * song whichever of the two forms either side happens to use.
         */
        fun pathKeys(raw: String): List<String> {
            val normalized = raw.trim().replace('\\', '/')
            if (normalized.isEmpty()) return emptyList()
            val rewritten =
                if (normalized.startsWith(INTERNAL_STORAGE_PREFIX, ignoreCase = true)) {
                    PRIMARY_STORAGE_PREFIX + normalized.substring(INTERNAL_STORAGE_PREFIX.length)
                } else {
                    normalized
                }
            return if (rewritten == normalized) listOf(normalized) else listOf(normalized, rewritten)
        }

        /**
         * Last-played is milliseconds since the epoch, but a value small enough to be
         * seconds is scaled rather than silently read as 1970. Values that are zero,
         * negative or absurd are treated as "no timestamp" instead of landing a play
         * decades away from the truth.
         */
        fun normalizeTimestamp(raw: Long?): Long? {
            val value = raw ?: return null
            if (value <= 0) return null
            val millis = if (value < SECONDS_UPPER_BOUND) value * 1000 else value
            return millis.takeIf { it in MIN_PLAUSIBLE_MS..MAX_PLAUSIBLE_MS }
        }

        /** Anything below this is seconds, not milliseconds (1973-03-03 in ms). */
        private const val SECONDS_UPPER_BOUND = 100_000_000_000L

        /** Musicolet shipped well after this; earlier stamps are corrupt. */
        private const val MIN_PLAUSIBLE_MS = 1_262_304_000_000L // 2010-01-01

        /** A stamp far past today is a broken clock, not listening. */
        private const val MAX_PLAUSIBLE_MS = 4_102_444_800_000L // 2100-01-01
    }
}
