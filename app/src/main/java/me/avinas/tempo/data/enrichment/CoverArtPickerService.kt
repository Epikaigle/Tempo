package me.avinas.tempo.data.enrichment

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import me.avinas.tempo.data.local.entities.EnrichedMetadata
import me.avinas.tempo.data.local.entities.Track
import javax.inject.Inject
import javax.inject.Singleton

enum class CoverArtProvider {
    CURRENT,
    SPOTIFY,
    APPLE_MUSIC,
    MUSICBRAINZ,
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
 * User-driven artwork lookup. Providers are independent so the UI can surface
 * results progressively; no database state is changed until the user selects one.
 */
@Singleton
class CoverArtPickerService @Inject constructor(
    private val spotifyEnrichmentService: SpotifyEnrichmentService,
    private val iTunesEnrichmentService: ITunesEnrichmentService,
    private val musicBrainzEnrichmentService: MusicBrainzEnrichmentService,
    private val lastFmEnrichmentService: LastFmEnrichmentService,
    private val deezerEnrichmentService: DeezerEnrichmentService,
) {
    companion object {
        val REMOTE_PROVIDERS = listOf(
            CoverArtProvider.SPOTIFY,
            CoverArtProvider.APPLE_MUSIC,
            CoverArtProvider.MUSICBRAINZ,
            CoverArtProvider.DEEZER,
            CoverArtProvider.LASTFM,
        )
    }

    fun currentCandidate(track: Track): CoverArtCandidate? =
        track.albumArtUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { current ->
                CoverArtCandidate(
                    provider = CoverArtProvider.CURRENT,
                    albumArtUrl = current,
                    albumArtUrlLarge = current,
                    albumTitle = track.album,
                    isCurrent = true,
                )
            }

    suspend fun searchProvider(
        provider: CoverArtProvider,
        track: Track,
        currentMetadata: EnrichedMetadata?,
    ): CoverArtCandidate? {
        val albumHint = track.album ?: currentMetadata?.albumTitle

        return when (provider) {
            CoverArtProvider.CURRENT -> currentCandidate(track)

            CoverArtProvider.SPOTIFY ->
                runCatching { spotifyEnrichmentService.fetchBasicMetadata(track) }
                    .getOrNull()
                    .let { it as? SpotifyEnrichmentService.BasicMetadataResult.Success }
                    ?.let { result ->
                        val best = result.albumArtUrlLarge ?: result.albumArtUrl
                        if (best.isNullOrBlank()) null else CoverArtCandidate(
                            provider = provider,
                            albumArtUrl = best,
                            albumArtUrlSmall = result.albumArtUrlSmall,
                            albumArtUrlLarge = result.albumArtUrlLarge,
                            albumTitle = result.albumTitle,
                        )
                    }

            CoverArtProvider.APPLE_MUSIC ->
                runCatching {
                    iTunesEnrichmentService.searchAlbumArt(
                        artist = track.artist,
                        album = albumHint,
                        track = track.title,
                    )
                }.getOrNull()
                    .let { it as? ITunesEnrichmentService.iTunesResult.Success }
                    ?.let { result ->
                        CoverArtCandidate(
                            provider = provider,
                            albumArtUrl = result.albumArtUrlLarge ?: result.albumArtUrl,
                            albumArtUrlSmall = result.albumArtUrlSmall,
                            albumArtUrlLarge = result.albumArtUrlLarge,
                            albumTitle = result.albumTitle,
                        )
                    }

            CoverArtProvider.MUSICBRAINZ ->
                runCatching {
                    musicBrainzEnrichmentService.searchCoverArt(track, currentMetadata)
                }.getOrNull()
                    ?.let { result ->
                        CoverArtCandidate(
                            provider = provider,
                            albumArtUrl = result.albumArtUrlLarge ?: result.albumArtUrl,
                            albumArtUrlSmall = result.albumArtUrlSmall,
                            albumArtUrlLarge = result.albumArtUrlLarge,
                            albumTitle = result.albumTitle,
                        )
                    }

            CoverArtProvider.LASTFM ->
                runCatching {
                    lastFmEnrichmentService.searchTrackInfo(
                        title = track.title,
                        artist = track.artist,
                    )
                }.getOrNull()
                    .let { it as? LastFmEnrichmentService.LastFmResult.Success }
                    ?.let { result ->
                        result.albumArtUrl
                            ?.takeIf { it.isNotBlank() }
                            ?.let { url ->
                                CoverArtCandidate(
                                    provider = provider,
                                    albumArtUrl = url,
                                    albumArtUrlLarge = url,
                                    albumTitle = result.albumTitle,
                                )
                            }
                    }

            CoverArtProvider.DEEZER ->
                runCatching {
                    deezerEnrichmentService.searchAlbumArt(
                        artist = track.artist,
                        track = track.title,
                        album = albumHint,
                    )
                }.getOrNull()
                    ?.let { result ->
                        CoverArtCandidate(
                            provider = provider,
                            albumArtUrl = result.albumArtUrlLarge ?: result.albumArtUrl,
                            albumArtUrlSmall = result.albumArtUrlSmall,
                            albumArtUrlLarge = result.albumArtUrlLarge,
                            albumTitle = result.albumTitle,
                        )
                    }
        }
    }

    suspend fun search(
        track: Track,
        currentMetadata: EnrichedMetadata?,
    ): List<CoverArtCandidate> = supervisorScope {
        val remote = REMOTE_PROVIDERS.associateWith { provider ->
            async { searchProvider(provider, track, currentMetadata) }
        }

        buildList {
            currentCandidate(track)?.let(::add)
            for (provider in REMOTE_PROVIDERS) {
                remote.getValue(provider).await()?.let(::add)
            }
        }.distinctBy { candidate ->
            candidate.albumArtUrl
                .substringBefore('?')
                .replace("http://", "https://")
                .lowercase()
        }
    }
}
