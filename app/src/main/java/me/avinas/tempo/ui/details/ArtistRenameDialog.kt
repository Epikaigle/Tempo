package me.avinas.tempo.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.avinas.tempo.R
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSurfaceChip
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import me.avinas.tempo.ui.theme.TempoWarningBright

/**
 * Dialog for renaming an artist with smart auto-merge detection.
 *
 * Flow:
 * 1. User types a new name
 * 2. On "Check & Rename", the system detects if any other artists are split fragments
 * 3. If split artists are found, shows a merge confirmation
 * 4. User confirms → rename + merge + save as known artist
 */
@Composable
fun ArtistRenameDialog(
    currentName: String,
    artistId: Long,
    splitArtists: List<Artist>,
    isDetecting: Boolean,
    isRenaming: Boolean,
    renameSuccess: Boolean?,
    onDetectSplits: (String) -> Unit,
    onConfirmRenameAndMerge: (String, List<Long>) -> Unit,
    onConfirmRenameOnly: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    var showMergeConfirmation by remember { mutableStateOf(false) }

    // When split artists are detected, show the merge confirmation
    LaunchedEffect(splitArtists) {
        if (splitArtists.isNotEmpty()) {
            showMergeConfirmation = true
        }
    }

    // Auto-dismiss on success
    LaunchedEffect(renameSuccess) {
        if (renameSuccess == true) {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = { if (!isRenaming) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = TempoSurfaceDialog
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = TempoPrimary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.details_rename_artist),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.details_rename_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(20.dp))

                if (!showMergeConfirmation) {
                    // Step 1: Name input
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.details_rename_artist_name_label), color = TextSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = TempoPrimary,
                            unfocusedBorderColor = TextTertiary,
                            cursorColor = TempoPrimary
                        ),
                        enabled = !isDetecting && !isRenaming
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            enabled = !isDetecting && !isRenaming,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TextSecondary
                            )
                        ) {
                            Text(stringResource(R.string.common_cancel))
                        }

                        Button(
                            onClick = {
                                val trimmed = newName.trim()
                                if (trimmed.isNotBlank() && trimmed != currentName) {
                                    onDetectSplits(trimmed)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = newName.trim().isNotBlank() &&
                                    newName.trim() != currentName &&
                                    !isDetecting && !isRenaming,
                            colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary)
                        ) {
                            if (isDetecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.details_rename_button), color = Color.White)
                            }
                        }
                    }
                } else {
                    // Step 2: Merge confirmation
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = TempoWarningBright,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.details_split_artists_detected),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TempoWarningBright
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.details_split_artists_detected_desc, newName),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // List of split artists
                    splitArtists.forEach { artist ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = TempoSurfaceChip
                            )
                        ) {
                            Text(
                                text = "• ${artist.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.details_merge_listening_data, newName),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Merge buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Rename only (no merge)
                        OutlinedButton(
                            onClick = { onConfirmRenameOnly(newName.trim()) },
                            modifier = Modifier.weight(1f),
                            enabled = !isRenaming,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = TextSecondary
                            )
                        ) {
                            Text(stringResource(R.string.details_just_rename), style = MaterialTheme.typography.labelSmall)
                        }

                        // Rename + merge
                        Button(
                            onClick = {
                                onConfirmRenameAndMerge(
                                    newName.trim(),
                                    splitArtists.map { it.id }
                                )
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isRenaming,
                            colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary)
                        ) {
                            if (isRenaming) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(stringResource(R.string.details_merge_and_rename), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Cancel button
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            showMergeConfirmation = false
                        },
                        enabled = !isRenaming
                    ) {
                        Text(stringResource(R.string.common_back), color = TextTertiary)
                    }
                }

                // Error state
                if (renameSuccess == false) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.details_rename_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = TempoError
                    )
                }
            }
        }
    }
}
