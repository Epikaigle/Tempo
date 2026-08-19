package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.Normalizer

/**
 * Represents an artist in the database.
 * 
 * Artists are linked to tracks via:
 * - Track.primary_artist_id (for the main artist)
 * - TrackArtist junction table (for all artists including features)
 * 
 * Artists are also linked to albums via Album.artist_id
 */
@Entity(
    tableName = "artists", 
    indices = [
        Index(value = ["musicbrainz_id"], unique = true),
        Index(value = ["spotify_id"]),
        Index(value = ["name"]), // For name lookups
        Index(value = ["normalized_name"], unique = true) // For deduplication
    ]
)
data class Artist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    /**
     * Display name of the artist as it appears in credits.
     */
    val name: String,
    
    /**
     * Normalized name for deduplication (lowercase, trimmed, special chars removed).
     * This helps prevent duplicate entries like "KR$NA" vs "Krsna" vs "KRSNA".
     */
    @ColumnInfo(name = "normalized_name")
    val normalizedName: String = normalizeName(name),
    
    @ColumnInfo(name = "image_url") 
    val imageUrl: String?,
    
    val genres: List<String> = emptyList(),
    
    @ColumnInfo(name = "musicbrainz_id") 
    val musicbrainzId: String?,
    
    @ColumnInfo(name = "spotify_id") 
    val spotifyId: String?,
    
    /**
     * Country/region of the artist (e.g., "IN", "US", "GB")
     */
    val country: String? = null,
    
    /**
     * Type of artist: Person, Group, Orchestra, etc.
     */
    @ColumnInfo(name = "artist_type")
    val artistType: String? = null
) {
    companion object {
        // Pre-compiled regex patterns to avoid repeated native memory allocation
        // Unicode-aware: keeps letters (\p{L}) and numbers (\p{N}) from ANY script
        // (Latin, Japanese, Korean, Cyrillic, Arabic, ...) and only strips
        // punctuation/symbols. The old [^a-z0-9\s] pattern erased every non-ASCII
        // character, collapsing all CJK artist names into the same "" key.
        // \p{M} keeps combining marks of non-Latin scripts (Devanagari matras,
        // Thai vowels, Arabic harakat) so distinct names keep distinct keys.
        private val SPECIAL_CHARS_PATTERN = Regex("[^\\p{L}\\p{N}\\p{M}\\s]")
        private val WHITESPACE_PATTERN = Regex("\\s+")

        /**
         * Normalize an artist name for comparison and deduplication.
         *
         * Steps:
         * 1. NFKC normalization (folds full-width Latin "ＡＢＣ" -> "ABC",
         *    half-width katakana "ｶ" -> "カ", and other compatibility chars)
         * 2. Lowercase
         * 3. Strip punctuation/symbols but keep letters/numbers of any script
         * 4. Collapse whitespace
         *
         * If everything is stripped (e.g. symbol/emoji-only names), falls back to
         * the NFKC-lowercased original so two different names never share the
         * same empty dedup key.
         */
        fun normalizeName(name: String): String {
            val nfkc = Normalizer.normalize(name, Normalizer.Form.NFKC)
            val normalized = nfkc
                .lowercase()
                .trim()
                .replace(SPECIAL_CHARS_PATTERN, "") // Remove special chars (any-script aware)
                .replace(WHITESPACE_PATTERN, " ") // Normalize whitespace
                .trim()
            return normalized.ifBlank { nfkc.lowercase().trim() }
        }
    }
}
