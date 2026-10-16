package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a "Smart Alias" for track deduplication.
 *
 * When a user merges "Track A (Live)" into "Track A", or renames a track, an alias is
 * created here. Later plays of "Track A (Live)" — including ones arriving from an
 * import — are re-mapped to "Track A" instead of becoming a second track with its own
 * play count.
 */
@Entity(
    tableName = "track_aliases",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["target_track_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["original_title", "original_artist"], unique = true),
        Index(value = ["target_track_id"])
    ]
)
data class TrackAlias(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    @ColumnInfo(name = "target_track_id")
    val targetTrackId: Long,
    
    @ColumnInfo(name = "original_title")
    val originalTitle: String,
    
    @ColumnInfo(name = "original_artist")
    val originalArtist: String,
    
    /**
     * Timestamp when this alias was created.
     */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
