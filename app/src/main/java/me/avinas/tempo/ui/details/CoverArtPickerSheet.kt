package me.avinas.tempo.ui.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import me.avinas.tempo.data.enrichment.CoverArtCandidate
import me.avinas.tempo.data.enrichment.CoverArtLookupStatus
import me.avinas.tempo.data.enrichment.CoverArtPickerService
import me.avinas.tempo.data.enrichment.CoverArtProvider
import me.avinas.tempo.ui.components.AlbumArtImage
import me.avinas.tempo.ui.theme.GlassBorderMedium
import me.avinas.tempo.ui.theme.GlassBorderSoft
import me.avinas.tempo.ui.theme.GlassFrostSoft
import me.avinas.tempo.ui.theme.TempoDarkSurface
import me.avinas.tempo.ui.theme.TempoDarkSurfaceSunken
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary

internal fun reconcileCoverPickerSelection(
    selectedProvider: CoverArtProvider?,
    candidates: List<CoverArtCandidate>,
): CoverArtProvider? =
    selectedProvider?.takeIf { selected ->
        candidates.any { candidate -> candidate.provider == selected }
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CoverArtPickerSheet(
    state: SongDetailsUiState,
    onSelect: (CoverArtCandidate) -> Unit,
    onResetAutomatic: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedProvider by remember { mutableStateOf<CoverArtProvider?>(null) }

    LaunchedEffect(state.coverCandidates) {
        // Never auto-select "Current" (or any remote result). Persist only an explicit
        // user tap while that provider still has a candidate.
        selectedProvider = reconcileCoverPickerSelection(
            selectedProvider = selectedProvider,
            candidates = state.coverCandidates,
        )
    }

    val selected = state.coverCandidates.firstOrNull { it.provider == selectedProvider }
    val providers = buildList {
        if (state.coverCandidates.any { it.provider == CoverArtProvider.CURRENT }) {
            add(CoverArtProvider.CURRENT)
        }
        addAll(CoverArtPickerService.REMOTE_PROVIDERS)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TempoDarkSurface,
        contentColor = TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.details_change_cover_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.details_change_cover_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            if (state.isManualCover) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.details_cover_manual),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TempoPrimary,
                )
            }

            selected?.let { candidate ->
                Spacer(modifier = Modifier.height(18.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AlbumArtImage(
                        albumArtUrl = candidate.albumArtUrlLarge ?: candidate.albumArtUrl,
                        contentDescription = providerLabel(candidate.provider),
                        modifier = Modifier
                            .size(168.dp)
                            .clip(RoundedCornerShape(18.dp)),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = providerLabel(candidate.provider),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )
                    candidate.albumTitle?.takeIf { it.isNotBlank() }?.let { album ->
                        Text(
                            text = album,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val cardWidth = (maxWidth - 12.dp) / 2
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    maxItemsInEachRow = 2,
                ) {
                    providers.forEach { provider ->
                        val candidate = state.coverCandidates.firstOrNull { it.provider == provider }
                        val status = if (provider == CoverArtProvider.CURRENT) {
                            if (candidate != null) CoverArtLookupStatus.FOUND else CoverArtLookupStatus.NOT_FOUND
                        } else {
                            state.coverProviderStatuses[provider] ?: CoverArtLookupStatus.LOADING
                        }
                        CoverProviderCard(
                            provider = provider,
                            candidate = candidate,
                            status = status,
                            selected = selectedProvider == provider && candidate != null,
                            onClick = {
                                if (candidate != null && !state.isSavingCover) {
                                    selectedProvider = provider
                                }
                            },
                            modifier = Modifier
                                .width(cardWidth)
                                .heightIn(min = 196.dp),
                        )
                    }
                }
            }

            val hasProviderError = state.coverProviderStatuses.values
                .any { it == CoverArtLookupStatus.ERROR }
            if (state.coverPickerError != null || hasProviderError) {
                Spacer(modifier = Modifier.height(14.dp))
                state.coverPickerError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(
                    onClick = onRetry,
                    enabled = !state.isLoadingCoverCandidates && !state.isSavingCover,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.details_cover_try_again))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { selected?.let(onSelect) },
                enabled = selected != null && !state.isSavingCover,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSavingCover) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.details_cover_use))
                }
            }

            if (state.isManualCover) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = GlassBorderSoft)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onResetAutomatic,
                    enabled = !state.isSavingCover,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, GlassBorderMedium),
                ) {
                    Text(stringResource(R.string.details_cover_restore_auto))
                }
                Text(
                    text = stringResource(R.string.details_cover_restore_auto_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CoverProviderCard(
    provider: CoverArtProvider,
    candidate: CoverArtCandidate?,
    status: CoverArtLookupStatus,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (selected) TempoPrimary else GlassBorderSoft

    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) GlassFrostSoft else TempoDarkSurfaceSunken)
            .border(if (selected) 1.5.dp else 0.8.dp, borderColor, shape)
            .clickable(enabled = candidate != null, onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassFrostSoft),
            contentAlignment = Alignment.Center,
        ) {
            when {
                candidate != null -> {
                    AlbumArtImage(
                        albumArtUrl = candidate.albumArtUrlLarge ?: candidate.albumArtUrl,
                        contentDescription = providerLabel(provider),
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = TempoPrimary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(22.dp),
                        )
                    }
                }
                status == CoverArtLookupStatus.LOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = TempoPrimary,
                    )
                }
                status == CoverArtLookupStatus.UNAVAILABLE -> {
                    Text(
                        text = stringResource(R.string.details_cover_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
                status == CoverArtLookupStatus.ERROR -> {
                    Text(
                        text = stringResource(R.string.details_cover_error),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    Text(
                        text = stringResource(R.string.details_cover_not_found),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = providerLabel(provider),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (candidate != null) TextPrimary else TextTertiary,
            maxLines = 1,
        )
        Text(
            text = when {
                candidate?.albumTitle?.isNotBlank() == true -> candidate.albumTitle.orEmpty()
                status == CoverArtLookupStatus.LOADING -> stringResource(R.string.details_cover_searching)
                status == CoverArtLookupStatus.UNAVAILABLE -> stringResource(R.string.details_cover_unavailable)
                status == CoverArtLookupStatus.ERROR -> stringResource(R.string.details_cover_error)
                else -> ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun providerLabel(provider: CoverArtProvider): String =
    when (provider) {
        CoverArtProvider.CURRENT -> stringResource(R.string.details_cover_current)
        CoverArtProvider.SPOTIFY -> stringResource(R.string.details_cover_provider_spotify)
        CoverArtProvider.APPLE_MUSIC -> stringResource(R.string.details_cover_provider_apple_music)
        CoverArtProvider.MUSICBRAINZ -> stringResource(R.string.details_cover_provider_musicbrainz)
        CoverArtProvider.DEEZER -> stringResource(R.string.details_cover_provider_deezer)
        CoverArtProvider.LASTFM -> stringResource(R.string.details_cover_provider_lastfm)
    }
