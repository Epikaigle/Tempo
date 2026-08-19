package me.avinas.tempo.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSurfaceCard
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextTertiary

/**
 * Dialog for splitting an artist: shows the artist's tracks grouped by their
 * raw artist string and lets the user move selected tracks to separate
 * (new or existing) artists. The inverse of [ArtistMergeSearchDialog].
 */
@Composable
fun ArtistSplitDialog(
    sourceArtistId: Long,
    sourceArtistName: String,
    onDismiss: () -> Unit,
    onSplitComplete: (sourceDeleted: Boolean) -> Unit,
    viewModel: SplitArtistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(sourceArtistId) {
        viewModel.setSourceArtist(sourceArtistId)
    }

    LaunchedEffect(uiState.status) {
        val status = uiState.status
        if (status is ArtistSplitStatus.Success) {
            onSplitComplete(status.sourceDeleted)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 640.dp),
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.details_split_artist_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.details_split_grouped_under, sourceArtistName),
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

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = TempoPrimary)
                        }
                    }
                    uiState.groups.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.details_no_tracks_found_artist),
                            color = TextTertiary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.details_split_group_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.groups, key = { it.rawName }) { group ->
                                SplitGroupItem(
                                    group = group,
                                    onToggleExpanded = { viewModel.toggleGroupExpanded(group.rawName) },
                                    onToggleAll = { select -> viewModel.toggleAllInGroup(group.rawName, select) },
                                    onToggleTrack = { trackId -> viewModel.toggleTrack(group.rawName, trackId) },
                                    onTargetNameChange = { viewModel.updateTargetName(group.rawName, it) }
                                )
                            }
                        }
                    }
                }

                // Error message
                val status = uiState.status
                if (status is ArtistSplitStatus.Error) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = status.message,
                        color = TempoError,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Processing indicator
                if (status is ArtistSplitStatus.Processing) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TempoPrimary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.details_splitting_artist),
                            color = TextTertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Footer buttons
                if (!uiState.isLoading && uiState.groups.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.common_cancel), color = TextTertiary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.confirmSplit() },
                            enabled = viewModel.selectedTrackCount > 0 &&
                                status !is ArtistSplitStatus.Processing,
                            colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary)
                        ) {
                            Text(stringResource(R.string.details_split_button, viewModel.selectedTrackCount), color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One raw-name group: header row with select-all checkbox and expand toggle,
 * expandable track list, and target artist name field when anything is selected.
 */
@Composable
private fun SplitGroupItem(
    group: SplitGroupUi,
    onToggleExpanded: () -> Unit,
    onToggleAll: (Boolean) -> Unit,
    onToggleTrack: (Long) -> Unit,
    onTargetNameChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TempoSurfaceCard, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = group.selectedCount > 0,
                onCheckedChange = { onToggleAll(it) },
                colors = CheckboxDefaults.colors(
                    checkedColor = TempoPrimary,
                    uncheckedColor = TextTertiary
                )
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggleExpanded)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.rawName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (group.isSourceName) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "current",
                            style = MaterialTheme.typography.labelSmall,
                            color = TempoPrimary
                        )
                    }
                }
                Text(
                    text = "${group.tracks.size} tracks • ${group.selectedCount} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
            IconButton(onClick = onToggleExpanded) {
                Icon(
                    if (group.expanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (group.expanded) "Collapse" else "Expand",
                    tint = TextTertiary
                )
            }
        }

        // Expanded track list
        if (group.expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            group.tracks.forEach { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleTrack(track.id) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = track.selected,
                        onCheckedChange = { onToggleTrack(track.id) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = TempoPrimary,
                            uncheckedColor = TextTertiary
                        )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!track.album.isNullOrBlank()) {
                            Text(
                                text = track.album,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

        // Target artist name (only when something is selected in this group)
        if (group.selectedCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = group.targetName,
                onValueChange = onTargetNameChange,
                label = { Text(stringResource(R.string.details_move_to_artist)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = TempoPrimary,
                    unfocusedBorderColor = TextTertiary,
                    focusedLabelColor = TempoPrimary,
                    unfocusedLabelColor = TextTertiary,
                    cursorColor = TempoPrimary
                )
            )
        }
    }
    }
}
