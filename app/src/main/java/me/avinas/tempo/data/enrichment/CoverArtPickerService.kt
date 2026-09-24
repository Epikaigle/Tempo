package me.avinas.tempo.data.enrichment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

enum class CoverArtLookupStatus {
    LOADING,
    FOUND,
    NOT_FOUND,
    UNAVAILABLE,
    ERROR,
}

data class CoverArtCandidate(
    val provider: CoverArtProvider,
    val albumArtUrl: String,
    val albumArtUrlSmall: String? = null,
    val albumArtUrlLarge: String? = null,
    val albumTitle: String? = null,
    val isCurrent: Boolean = false,
)

data class CoverArtLookupResult(
    val provider: CoverArtProvider,
    val status: CoverArtLookupStatus,
    val candidate: CoverArtCandidate? = null,
    val message: String? = null,
)

/**
 * User-driven artwork lookup. Each provider is queried independently and no database
 * state is changed until the user explicitly selects a candidate.
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
    ): CoverArtLookupResult {
        val albumHint = track.album ?: currentMetadata?.albumTitle

        val lookup = try {
            when (provider) {
                CoverArtProvider.CURRENT -> {
                    val candidate = currentCandidate(track)
                    CoverArtLookupResult(
                        provider = provider,
                        status = if (candidate != null) CoverArtLookupStatus.FOUND else CoverArtLookupStatus.NOT_FOUND,
                        candidate = candidate,
                    )
                }

                CoverArtProvider.SPOTIFY ->
                    when (val result = spotifyEnrichmentService.fetchCoverArtForPicker(track, currentMetadata)) {
                        is SpotifyEnrichmentService.SpotifyCoverArtResult.Success ->
                            CoverArtLookupResult(
                                provider = provider,
                                status = CoverArtLookupStatus.FOUND,
                                candidate = CoverArtCandidate(
                                    provider = provider,
                                    albumArtUrl = result.albumArtUrl,
                                    albumArtUrlLarge = result.albumArtUrl,
                                    albumTitle = null,
                                ),
                            )
                        SpotifyEnrichmentService.SpotifyCoverArtResult.Unavailable ->
                            CoverArtLookupResult(provider, CoverArtLookupStatus.UNAVAILABLE)
                        SpotifyEnrichmentService.SpotifyCoverArtResult.NotFound ->
                            CoverArtLookupResult(provider, CoverArtLookupStatus.NOT_FOUND)
                        is SpotifyEnrichmentService.SpotifyCoverArtResult.Error ->
                            CoverArtLookupResult(
                                provider = provider,
                                status = CoverArtLookupStatus.ERROR,
                                message = result.message,
                            )
                    }

                CoverArtProvider.APPLE_MUSIC ->
                    when (
                        val result = iTunesEnrichmentService.searchAlbumArt(
                            artist = track.artist,
                            album = albumHint,
                            track = track.title,
                        )
                    ) {
                        is ITunesEnrichmentService.iTunesResult.Success ->
                            CoverArtLookupResult(
                                provider = provider,
                                status = CoverArtLookupStatus.FOUND,
                                candidate = CoverArtCandidate(
                                    provider = provider,
                                    albumArtUrl = result.albumArtUrlLarge ?: result.albumArtUrl,
                                    albumArtUrlSmall = result.albumArtUrlSmall,
                                    albumArtUrlLarge = result.albumArtUrlLarge,
                                    albumTitle = result.albumTitle,
                                ),
                            )
                        ITunesEnrichmentService.iTunesResult.NotFound ->
                            CoverArtLookupResult(provider, CoverArtLookupStatus.NOT_FOUND)
                        is ITunesEnrichmentService.iTunesResult.Error ->
                            CoverArtLookupResult(
                                provider = provider,
                                status = CoverArtLookupStatus.ERROR,
                                message = result.message,
                            )
                    }

                CoverArtProvider.MUSICBRAINZ ->
                    mapMusicBrainzCoverSearchResult(
                        musicBrainzEnrichmentService.searchCoverArt(track, currentMetadata)
                    )

                CoverArtProvider.LASTFM ->
                    when (
                        val result = lastFmEnrichmentService.searchTrackInfo(
                            title = track.title,
                            artist = track.artist,
                        )
                    ) {
                        is LastFmEnrichmentService.LastFmResult.Success -> {
                            val url = result.albumArtUrl?.takeIf { it.isNotBlank() }
                            if (url == null) {
                                CoverArtLookupResult(provider, CoverArtLookupStatus.NOT_FOUND)
                            } else {
                                CoverArtLookupResult(
                                    provider = provider,
                                    status = CoverArtLookupStatus.FOUND,
                                    candidate = CoverArtCandidate(
                                        provider = provider,
                                        albumArtUrl = url,
                                        albumArtUrlLarge = url,
                                        albumTitle = result.albumTitle,
                                    ),
                                )
                            }
                        }
                        LastFmEnrichmentService.LastFmResult.NotConfigured ->
                            CoverArtLookupResult(provider, CoverArtLookupStatus.UNAVAILABLE)
                        LastFmEnrichmentService.LastFmResult.TrackNotFound,
                        LastFmEnrichmentService.LastFmResult.AlreadyHasData ->
                            CoverArtLookupResult(provider, CoverArtLookupStatus.NOT_FOUND)
                        is LastFmEnrichmentService.LastFmResult.Error ->
                            CoverArtLookupResult(
                                provider = provider,
                                status = CoverArtLookupStatus.ERROR,
                                message = result.message,
                            )
                    }

                CoverArtProvider.DEEZER -> {
                    val result = deezerEnrichmentService.searchAlbumArt(
                        artist = track.artist,
                        track = track.title,
                        album = albumHint,
                    )
                    if (result == null) {
                        CoverArtLookupResult(provider, CoverArtLookupStatus.NOT_FOUND)
                    } else {
                        CoverArtLookupResult(
                            provider = provider,
                            status = CoverArtLookupStatus.FOUND,
                            candidate = CoverArtCandidate(
                                provider = provider,
                                albumArtUrl = result.albumArtUrlLarge ?: result.albumArtUrl,
                                albumArtUrlSmall = result.albumArtUrlSmall,
                                albumArtUrlLarge = result.albumArtUrlLarge,
                                albumTitle = result.albumTitle,
                            ),
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CoverArtLookupResult(
                provider = provider,
                status = CoverArtLookupStatus.ERROR,
                message = e.message,
            )
        }

        // Some legacy enrichment helpers catch broad exceptions internally. Re-check
        // coroutine cancellation before returning so a dismissed picker can never
        // publish a stale provider result.
        currentCoroutineContext().ensureActive()
        return lookup
    }
}

internal fun mapMusicBrainzCoverSearchResult(
    result: MusicBrainzEnrichmentService.CoverArtSearchResult,
): CoverArtLookupResult =
    when (result) {
        is MusicBrainzEnrichmentService.CoverArtSearchResult.Success -> {
            val artwork = result.artwork
            CoverArtLookupResult(
                provider = CoverArtProvider.MUSICBRAINZ,
                status = CoverArtLookupStatus.FOUND,
                candidate = CoverArtCandidate(
                    provider = CoverArtProvider.MUSICBRAINZ,
                    albumArtUrl = artwork.albumArtUrlLarge ?: artwork.albumArtUrl,
                    albumArtUrlSmall = artwork.albumArtUrlSmall,
                    albumArtUrlLarge = artwork.albumArtUrlLarge,
                    albumTitle = artwork.albumTitle,
                ),
            )
        }
        MusicBrainzEnrichmentService.CoverArtSearchResult.NotFound ->
            CoverArtLookupResult(
                provider = CoverArtProvider.MUSICBRAINZ,
                status = CoverArtLookupStatus.NOT_FOUND,
            )
        is MusicBrainzEnrichmentService.CoverArtSearchResult.Error ->
            CoverArtLookupResult(
                provider = CoverArtProvider.MUSICBRAINZ,
                status = CoverArtLookupStatus.ERROR,
                message = result.message,
            )
    }
