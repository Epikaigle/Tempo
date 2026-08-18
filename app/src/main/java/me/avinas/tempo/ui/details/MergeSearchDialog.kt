package me.avinas.tempo.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.avinas.tempo.ui.theme.GlassFrostMedium
import me.avinas.tempo.ui.theme.TempoDarkSurfaceElevated
import me.avinas.tempo.ui.theme.TempoDarkSurfaceSunken
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import me.avinas.tempo.data.local.entities.Track
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun MergeSearchDialog(
    sourceTrackId: Long,
    onDismiss: () -> Unit,
    onTrackSelected: (Track) -> Unit,
    viewModel: MergeTrackViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Set source track ID once
    LaunchedEffect(sourceTrackId) {
        viewModel.setSourceTrackId(sourceTrackId)
    }

    // Handle merge completion
    LaunchedEffect(uiState.mergeStatus) {
        if (uiState.mergeStatus is MergeStatus.Success) {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = TempoDarkSurfaceElevated)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Merge with...",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Info text
                Text(
                    text = "Search for the correct track to merge into. All listening history will be combined.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Search Input
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search for the correct version...", color = TextTertiary) },
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
                
                when {
                    uiState.isSearching -> {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = TempoPrimary, trackColor = GlassFrostMedium)
                        }
                    }
                    uiState.searchResults.isEmpty() && uiState.query.length >= 2 -> {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text("No tracks found", color = TextSecondary)
                        }
                    }
                    uiState.searchResults.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Type to search for tracks",
                                color = TextSecondary,
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
                                key = { track -> track.id }
                            ) { track ->
                                TrackSearchItem(track = track, onClick = { 
                                    viewModel.selectTrackForMerge(track)
                                })
                            }
                        }
                    }
                }
                
                // Confirmation dialog for pending merge
                if (uiState.pendingMergeTarget != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = TempoDarkSurfaceSunken)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Confirm Merge",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Merge into \"${uiState.pendingMergeTarget!!.title}\" by ${uiState.pendingMergeTarget!!.artist}?",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "This action cannot be undone. All listening history will be combined.",
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
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = { viewModel.confirmMerge() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TempoPrimary
                                    )
                                ) {
                                    Text("Merge", color = TextOnAccent)
                                }
                            }
                        }
                    }
                }
                
                // Error message
                if (uiState.mergeStatus is MergeStatus.Error) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = (uiState.mergeStatus as MergeStatus.Error).message,
                        color = TempoError,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                if (uiState.mergeStatus is MergeStatus.Processing) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TempoPrimary)
                }
            }
        }
    }
}

@Composable
fun TrackSearchItem(track: Track, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Simple placeholder for art if not available
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(GlassFrostMedium, RoundedCornerShape(10.dp))
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}
