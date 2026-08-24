package me.avinas.tempo.data.importexport

import com.squareup.moshi.*
import me.avinas.tempo.data.local.Converters.LIST_DELIMITER
import me.avinas.tempo.data.local.entities.*
import java.lang.reflect.Type

/**
 * Moshi adapter for ArtistRole enum.
 */
class ArtistRoleAdapter {
    @ToJson
    fun toJson(role: ArtistRole): String = role.name
    
    @FromJson
    fun fromJson(value: String): ArtistRole = try {
        ArtistRole.valueOf(value)
    } catch (e: IllegalArgumentException) {
        ArtistRole.PRIMARY
    }
}

/**
 * Moshi adapter for EnrichmentStatus enum.
 */
class EnrichmentStatusAdapter {
    @ToJson
    fun toJson(status: EnrichmentStatus): String = status.name
    
    @FromJson
    fun fromJson(value: String): EnrichmentStatus = try {
        EnrichmentStatus.valueOf(value)
    } catch (e: IllegalArgumentException) {
        EnrichmentStatus.PENDING
    }
}

/**
 * Moshi adapter for SpotifyEnrichmentStatus enum.
 */
class SpotifyEnrichmentStatusAdapter {
    @ToJson
    fun toJson(status: SpotifyEnrichmentStatus): String = status.name
    
    @FromJson
    fun fromJson(value: String): SpotifyEnrichmentStatus = try {
        SpotifyEnrichmentStatus.valueOf(value)
    } catch (e: IllegalArgumentException) {
        SpotifyEnrichmentStatus.NOT_ATTEMPTED
    }
}

/**
 * Moshi adapter for AudioFeaturesSource enum.
 */
class AudioFeaturesSourceAdapter {
    @ToJson
    fun toJson(source: AudioFeaturesSource): String = source.name
    
    @FromJson
    fun fromJson(value: String): AudioFeaturesSource = try {
        AudioFeaturesSource.valueOf(value)
    } catch (e: IllegalArgumentException) {
        AudioFeaturesSource.NONE
    }
}

/**
 * Moshi adapter factory for List<String> that tolerates malformed data.
 *
 * Why this exists: a backup must never fail because of a single malformed field.
 * Two failure modes are handled:
 *
 * 1. Serialization ("Export failed: java.lang.String cannot be cast to
 *    java.util.List"): the exported entities (Artist.genres, EnrichedMetadata.tags
 *    and .genres) declare List<String> fields backed by "|||"-delimited TEXT
 *    columns. If a field ever ends up holding the raw delimited string (or any
 *    other non-List value) at runtime, the synthetic bridge method of a
 *    JsonAdapter<List<String>> subclass inserts a hard checkcast to
 *    java.util.List and the whole export aborts with a ClassCastException.
 *    Implementing the adapter with an erased JsonAdapter<Any> signature avoids
 *    the bridge cast entirely and lets us recover the data instead.
 *
 * 2. Deserialization ("Expected BEGIN_ARRAY but was STRING"): old or corrupted
 *    backups may store a bare string where the array is expected. Recover it by
 *    splitting on the database delimiter instead of failing the restore.
 */
class StringListAdapterFactory : JsonAdapter.Factory {
    override fun create(type: Type, annotations: MutableSet<out Annotation>, moshi: Moshi): JsonAdapter<*>? {
        if (Types.getRawType(type) != List::class.java) return null
        val elementType = Types.collectionElementType(type, List::class.java)
        if (elementType != String::class.java) return null

        return object : JsonAdapter<Any>() {
            override fun fromJson(reader: JsonReader): Any {
                if (reader.peek() == JsonReader.Token.NULL) {
                    reader.nextNull<Any>()
                    return emptyList<String>()
                }

                // Malformed backups may store a bare string (the raw "|||"-delimited
                // database format) instead of a JSON array. Recover it.
                if (reader.peek() == JsonReader.Token.STRING) {
                    val raw = reader.nextString()
                    return raw.split(LIST_DELIMITER).filter { it.isNotEmpty() }
                }

                val result = mutableListOf<String>()
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonReader.Token.NULL) {
                        reader.nextNull<Any>()
                    } else {
                        result.add(reader.nextString())
                    }
                }
                reader.endArray()
                return result
            }

            override fun toJson(writer: JsonWriter, value: Any?) {
                if (value == null) {
                    writer.nullValue()
                    return
                }

                // Never trust the declared type: recover whatever runtime shape the
                // field actually holds so one bad row cannot kill the whole backup.
                val items: List<String> = when (value) {
                    is String -> value.split(LIST_DELIMITER).filter { it.isNotEmpty() }
                    is Iterable<*> -> value.mapNotNull { it?.toString() }
                    is Array<*> -> value.mapNotNull { it?.toString() }
                    else -> listOf(value.toString())
                }

                writer.beginArray()
                for (item in items) {
                    writer.value(item)
                }
                writer.endArray()
            }
        }
    }
}


/**
 * Build Moshi instance with all required adapters for import/export.
 */
fun buildImportExportMoshi(): Moshi = Moshi.Builder()
    .add(ArtistRoleAdapter())
    .add(EnrichmentStatusAdapter())
    .add(SpotifyEnrichmentStatusAdapter())
    .add(AudioFeaturesSourceAdapter())
    .add(StringListAdapterFactory())
    .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
    .build()
