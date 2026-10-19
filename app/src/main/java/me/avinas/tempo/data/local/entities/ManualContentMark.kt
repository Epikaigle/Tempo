package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User-defined content classifications that override tracking. A mark stores a pattern
 * (title/artist/album) so future content matches automatically: marking "Chapter 5 of The Great
 * Gatsby" as audiobook stores `"Chapter % of The Great Gatsby"`, where `%` wildcards the chapter
 * number.
 */
@Entity(
    tableName = "manual_content_marks",
    // Note: We intentionally do NOT use a foreign key to Track here.
    // Artist-level marks should persist even after the original track is deleted,
    // as they are meant to filter FUTURE content from that artist.
    // If we had CASCADE delete, deleting an orphaned track would also delete
    // the artist block, defeating the purpose.
    indices = [
        Index(value = ["original_title", "original_artist"], unique = true),
        Index(value = ["target_track_id"]),
        Index(value = ["content_type"]),
        Index(value = ["pattern_type", "original_artist"]) // For faster artist-level lookups
    ]
)
data class ManualContentMark(
    @PrimaryKey(autoGenerate = true) 
    val id: Long = 0,
    
    /** 0 when the rule was created directly from settings rather than from a track. */
    @ColumnInfo(name = "target_track_id")
    val targetTrackId: Long,
    
    /** TITLE_ARTIST, TITLE or ARTIST: which of the fields below are matched. */
    @ColumnInfo(name = "pattern_type")
    val patternType: String,
    
    @ColumnInfo(name = "original_title")
    val originalTitle: String,
    
    @ColumnInfo(name = "original_artist")
    val originalArtist: String,
    
    /**
     * Legacy value retained for database and archive compatibility.
     * Current matching uses [originalTitle], [originalArtist], and [patternType].
     */
    @ColumnInfo(name = "pattern_value")
    val patternValue: String,
    
    /**
     * Content type: PODCAST, AUDIOBOOK, ALWAYS_MUSIC, NON_MUSIC, or legacy VIDEO.
     */
    @ColumnInfo(name = "content_type")
    val contentType: String,
    
    /**
     * Timestamp when this mark was created.
     */
    @ColumnInfo(name = "marked_at")
    val markedAt: Long = System.currentTimeMillis()
)
