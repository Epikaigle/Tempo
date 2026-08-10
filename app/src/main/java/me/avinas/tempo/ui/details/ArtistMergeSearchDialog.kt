package me.avinas.tempo.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSurfaceCard
import me.avinas.tempo.ui.theme.TempoSurfaceChip
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TempoSurfaceSunken
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextTertiary

/**
 * Dialog for searching and selecting an artist to merge into.
 *
 * @param sourceArtistId The ID of the artist being merged (source)
 * @param sourceArtistName The name of the source artist (for display)
 * @param onDismiss Called when dialog should be dismissed
 * @param onMergeComplete Called when merge completes successfully
 * @param viewModel ViewModel for handling search and merge
 */
@Composable
fun ArtistMergeSearchDialog(
    sourceArtistId: Long,
    sourceArtistName: String,
    onDismiss: () -> Unit,
    onMergeComplete: () -> Unit,
    viewModel: MergeArtistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Set source artist ID once
    LaunchedEffect(sourceArtistId) {
        viewModel.setSourceArtistId(sourceArtistId)
    }

    // Handle merge completion
    LaunchedEffect(uiState.mergeStatus) {
        if (uiState.mergeStatus is ArtistMergeStatus.Success) {
            onMergeComplete()
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = TempoSurfaceDialog)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.details_merge_artist),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.merge_merging, sourceArtistName),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Info text
                Text(
                    text = stringResource(R.string.merge_artist_search),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Search Input
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.details_merge_search_target), color = TextTertiary) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextTertiary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = TempoPrimary,
                        focusedBorderColor = TempoPrimary,
                        unfocusedBorderColor = TextTertiary
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Content area
                when {
                    uiState.isSearching -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = TempoPrimary)
                        }
                    }
                    uiState.searchResults.isEmpty() && uiState.query.length >= 2 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.details_no_artists_found), color = TextTertiary)
                        }
                    }
                    uiState.searchResults.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.merge_search_artists),
                                color = TextTertiary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = uiState.searchResults,
                                key = { artist -> artist.id }
                            ) { artist ->
                                ArtistSearchItem(
                                    artist = artist,
                                    onClick = { viewModel.selectArtistForMerge(artist) }
                                )
                            }
                        }
                    }
                }

                // Confirmation dialog for pending merge
                if (uiState.pendingMergeTarget != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = TempoSurfaceSunken)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.merge_confirm),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.merge_artist_into, sourceArtistName, uiState.pendingMergeTarget!!.name),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.merge_cannot_undo),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.cancelMerge() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = TextPrimary
                                    )
                                ) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                                Button(
                                    onClick = { viewModel.confirmMerge() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TempoPrimary
                                    )
                                ) {
                                    Text(stringResource(R.string.details_merge_button), color = Color.White)
                                }
                            }
                        }
                    }
                }

                // Error message
                if (uiState.mergeStatus is ArtistMergeStatus.Error) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = (uiState.mergeStatus as ArtistMergeStatus.Error).message,
                        color = TempoError,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Processing indicator
                if (uiState.mergeStatus is ArtistMergeStatus.Processing) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TempoPrimary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.merge_merging_artists),
                            color = TextTertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual artist item in search results.
 */
@Composable
private fun ArtistSearchItem(
    artist: Artist,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(TempoSurfaceCard, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Artist image or placeholder
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(TempoSurfaceChip),
            contentAlignment = Alignment.Center
        ) {
            if (artist.imageUrl != null) {
                CachedAsyncImage(
                    imageUrl = artist.imageUrl,
                    contentDescription = artist.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetSizeDp = 48
                )
            } else {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (artist.genres.isNotEmpty()) {
                Text(
                    text = artist.genres.take(3).joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (artist.country != null) {
                Text(
                    text = artist.country,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        }

        // Subtle arrow indicator
        Text(
            text = "→",
            color = TextTertiary,
            style = MaterialTheme.typography.titleMedium
        )
    }
}
