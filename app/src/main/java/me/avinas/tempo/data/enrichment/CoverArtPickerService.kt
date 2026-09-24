package me.avinas.tempo.data.enrichment

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.Track
import javax.inject.Inject
import javax.inject.Singleton

enum class CoverArtProvider {
    CURRENT,
    SPOTIFY,
    APPLE_MUSIC,
    LASTFM,
    DEEZER,
}

data class CoverArtCandidate(
    val provider: CoverArtProvider,
    val albumArtUrl: String,
    val albumArtUrlSmall: String? = null,
    val albumArtUrlLarge: String? = null,
    val albumTitle: String? = null,
    val isCurrent: Boolean = false,
)

/**
 * User-driven artwork lookup. Every provider is queried in parallel and no database
 * state is changed until the user explicitly selects one of the returned candidates.
 */
@Singleton
class CoverArtPickerService @Inject constructor(
    private val spotifyEnrichmentService: SpotifyEnrichmentService,
    private val iTunesEnrichmentService: ITunesEnrichmentService,
    private val lastFmEnrichmentService: LastFmEnrichmentService,
    private val deezerEnrichmentService: DeezerEnrichmentService,
) {
    suspend fun search(
        track: Track,
        currentMetadata: EnrichedMetadata?,
    ): List<CoverArtCandidate> = coroutineScope {
        val albumHint = track.album ?: currentMetadata?.albumTitle

        val spotifyDeferred = async { spotifyEnrichmentService.fetchBasicMetadata(track) }
        val iTunesDeferred = async {
            iTunesEnrichmentService.searchAlbumArt(
                artist = track.artist,
                album = albumHint,
                track = track.title,
            )
        }
        val lastFmDeferred = async {
            lastFmEnrichmentService.searchTrackInfo(
                title = track.title,
                artist = track.artist,
            )
        }
        val deezerDeferred = async {
            deezerEnrichmentService.searchAlbumArt(
                artist = track.artist,
                track = track.title,
                album = albumHint,
            )
        }

        val candidates = mutableListOf<CoverArtCandidate>()

        track.albumArtUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { current ->
                candidates += CoverArtCandidate(
                    provider = CoverArtProvider.CURRENT,
                    albumArtUrl = current,
                    albumArtUrlLarge = current,
                    albumTitle = track.album,
                    isCurrent = true,
                )
            }

        (spotifyDeferred.await() as? SpotifyEnrichmentService.BasicMetadataResult.Success)
            ?.let { result ->
                val best = result.albumArtUrlLarge ?: result.albumArtUrl
                if (!best.isNullOrBlank()) {
                    candidates += CoverArtCandidate(
                        provider = CoverArtProvider.SPOTIFY,
                        albumArtUrl = best,
                        albumArtUrlSmall = result.albumArtUrlSmall,
                        albumArtUrlLarge = result.albumArtUrlLarge,
                        albumTitle = result.albumTitle,
                    )
                }
            }

        (iTunesDeferred.await() as? ITunesEnrichmentService.iTunesResult.Success)
            ?.let { result ->
                candidates += CoverArtCandidate(
                    provider = CoverArtProvider.APPLE_MUSIC,
                    albumArtUrl = result.albumArtUrlLarge ?: result.albumArtUrl,
                    albumArtUrlSmall = result.albumArtUrlSmall,
                    albumArtUrlLarge = result.albumArtUrlLarge,
                    albumTitle = result.albumTitle,
                )
            }

        (lastFmDeferred.await() as? LastFmEnrichmentService.LastFmResult.Success)
            ?.let { result ->
                result.albumArtUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { url ->
                        candidates += CoverArtCandidate(
                            provider = CoverArtProvider.LASTFM,
                            albumArtUrl = url,
                            albumArtUrlLarge = url,
                            albumTitle = result.albumTitle,
                        )
                    }
            }

        deezerDeferred.await()?.let { result ->
            candidates += CoverArtCandidate(
                provider = CoverArtProvider.DEEZER,
                albumArtUrl = result.albumArtUrlLarge ?: result.albumArtUrl,
                albumArtUrlSmall = result.albumArtUrlSmall,
                albumArtUrlLarge = result.albumArtUrlLarge,
                albumTitle = result.albumTitle,
            )
        }

        candidates
            .filter { it.albumArtUrl.isNotBlank() }
            .distinctBy { candidate ->
                candidate.albumArtUrl
                    .substringBefore('?')
                    .replace("http://", "https://")
                    .lowercase()
            }
    }
}
