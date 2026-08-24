package me.avinas.tempo.data.local

import androidx.room.TypeConverter
import me.avinas.tempo.data.local.entities.AudioFeaturesSource
import me.avinas.tempo.data.local.entities.EnrichmentStatus
import me.avinas.tempo.data.local.entities.SpotifyEnrichmentStatus

object Converters {
    /**
     * Delimiter used to store List<String> entity fields (Artist.genres,
     * EnrichedMetadata.tags/.genres) in TEXT columns.
     *
     * Public because the import/export layer shares it: the Moshi List<String>
     * adapter uses it to recover raw delimited strings that leak into list-typed
     * fields (see [me.avinas.tempo.data.importexport.StringListAdapterFactory]).
     */
    const val LIST_DELIMITER = "|||"
    private const val SEP = LIST_DELIMITER


    @TypeConverter
    @JvmStatic
    fun fromStringList(list: List<String>?): String {
        if (list.isNullOrEmpty()) return ""
        // Canonical form: trimmed, no blank segments. Any malformed element that
        // reached this point (see [repairListColumnValue]) is persisted back in
        // clean delimited form the next time the row is written — the column
        // self-heals instead of accumulating corruption.
        return list.mapNotNull { it?.trim()?.takeIf { item -> item.isNotEmpty() } }
            .joinToString(SEP)
    }

    @TypeConverter
    @JvmStatic
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return repairListColumnValue(value)
    }

    /**
     * Repair a stored List<String> column value (artists.genres,
     * enriched_metadata.tags/.genres) into a clean list.
     *
     * Known legacy corruptions handled:
     * - JSON-array text written by old builds/migrations, e.g. `["jazz", "cool jazz"]`
     *   (previously read back as a ONE-element list containing the whole JSON string).
     * - Whitespace noise and empty segments around the `|||` delimiter, which used to
     *   leak through as `" jazz"` and `""` elements and defeat genre matching/dedup.
     *
     * Bracketed values that are NOT JSON arrays (e.g. a genre literally named
     * `[abstract]`) are left untouched — only `["`-prefixed strings are treated as
     * JSON arrays.
     *
     * Public so non-Room consumers of the raw column text (e.g. genre inference in
     * EnrichmentWorker, the one-time list column repair) share one implementation.
     */
    fun repairListColumnValue(value: String): List<String> {
        parseLegacyJsonArray(value)?.let { return it }
        return value.split(SEP)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Parse a legacy JSON-array-shaped column value into its elements, or null when
     * the value is not JSON-array-shaped. Tolerant parser: quoted items are unquoted
     * (with quote/backslash escapes folded), unquoted items are kept verbatim, null
     * items are dropped.
     */
    private fun parseLegacyJsonArray(value: String): List<String>? {
        val trimmed = value.trim()
        // Empty JSON array — a value the DAOs explicitly filter on (genres = '[]').
        if (trimmed == "[]") return emptyList()
        // Only treat as JSON when the first element is quoted — a genre literally
        // named "[abstract]" must survive round-trips untouched.
        if (!trimmed.startsWith("[\"") && !trimmed.startsWith("[ \"")) return null
        if (!trimmed.endsWith("]")) return null
        val body = trimmed.substring(1, trimmed.length - 1).trim()
        if (body.isEmpty()) return emptyList()
        return body.split(",")
            .mapNotNull { element ->
                val item = element.trim()
                when {
                    item.isEmpty() || item == "null" -> null
                    item.length >= 2 && item.startsWith("\"") && item.endsWith("\"") ->
                        item.substring(1, item.length - 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                            .trim()
                            .takeIf { it.isNotEmpty() }
                    else -> item.takeIf { it.isNotEmpty() }
                }
            }
    }
    
    @TypeConverter
    @JvmStatic
    fun fromEnrichmentStatus(status: EnrichmentStatus): String = status.name
    
    @TypeConverter
    @JvmStatic
    fun toEnrichmentStatus(value: String): EnrichmentStatus {
        return try {
            EnrichmentStatus.valueOf(value)
        } catch (e: Exception) {
            EnrichmentStatus.PENDING
        }
    }
    
    @TypeConverter
    @JvmStatic
    fun fromSpotifyEnrichmentStatus(status: SpotifyEnrichmentStatus): String = status.name
    
    @TypeConverter
    @JvmStatic
    fun toSpotifyEnrichmentStatus(value: String): SpotifyEnrichmentStatus {
        return try {
            SpotifyEnrichmentStatus.valueOf(value)
        } catch (e: Exception) {
            SpotifyEnrichmentStatus.NOT_ATTEMPTED
        }
    }
    
    @TypeConverter
    @JvmStatic
    fun fromAudioFeaturesSource(source: AudioFeaturesSource): String = source.name
    
    @TypeConverter
    @JvmStatic
    fun toAudioFeaturesSource(value: String): AudioFeaturesSource {
        return try {
            AudioFeaturesSource.valueOf(value)
        } catch (e: Exception) {
            AudioFeaturesSource.NONE
        }
    }
}
