package me.avinas.tempo.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Junction table between tracks and artists. `role` decides what the link means: an artist can be
 * PRIMARY, FEATURED (feat., ft., with), PRODUCER or REMIXER on the same track.
 */
@Entity(
    tableName = "track_artists",
    primaryKeys = ["track_id", "artist_id"],
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Artist::class,
            parentColumns = ["id"],
            childColumns = ["artist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["track_id"]),
        Index(value = ["artist_id"]),
        Index(value = ["track_id", "role"]),
        Index(value = ["artist_id", "role"])
    ]
)
data class TrackArtist(
    @ColumnInfo(name = "track_id")
    val trackId: Long,
    
    @ColumnInfo(name = "artist_id")
    val artistId: Long,
    
    /** See [ArtistRole]. */
    val role: ArtistRole = ArtistRole.PRIMARY,
    
    /** 0 = first credited artist. */
    @ColumnInfo(name = "credit_order")
    val creditOrder: Int = 0
)

enum class ArtistRole {
    PRIMARY,    // Main artist(s)
    FEATURED,   // Featured artist (feat., ft., with)
    PRODUCER,   // Producer credit
    REMIXER,    // Remix credit
    COMPOSER,   // Songwriter/composer
    PERFORMER   // Additional performer
}
