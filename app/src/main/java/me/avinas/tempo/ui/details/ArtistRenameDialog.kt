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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.avinas.tempo.data.local.entities.Artist
import me.avinas.tempo.ui.components.*
import me.avinas.tempo.ui.theme.*

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
        TempoDialogSurface {
            if (!showMergeConfirmation) {
                // Step 1: Name input
                TempoDialogIcon(
                    icon = Icons.Default.Edit,
                    tint = TempoPrimary,
                    size = 48
                )

                Spacer(modifier = Modifier.height(16.dp))

                TempoDialogTitle(text = "Rename Artist")

                Spacer(modifier = Modifier.height(8.dp))

                TempoDialogBody(
                    text = "If this artist was incorrectly split, enter the full name and we'll merge the data."
                )

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Artist Name", color = TextTertiary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = TempoPrimary,
                        unfocusedBorderColor = TextQuaternary,
                        cursorColor = TempoPrimary,
                        focusedLabelColor = TempoPrimary
                    ),
                    enabled = !isDetecting && !isRenaming,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                if (isDetecting) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = TempoPrimary,
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    TempoDialogButtonRow(
                        primaryText = "Rename",
                        onPrimary = {
                            val trimmed = newName.trim()
                            if (trimmed.isNotBlank() && trimmed != currentName) {
                                onDetectSplits(trimmed)
                            }
                        },
                        secondaryText = "Cancel",
                        onSecondary = onDismiss,
                        primaryEnabled = newName.trim().isNotBlank() && newName.trim() != currentName
                    )
                }
            } else {
                // Step 2: Merge confirmation
                TempoDialogIcon(
                    icon = Icons.Default.Warning,
                    tint = TempoWarning,
                    size = 48
                )

                Spacer(modifier = Modifier.height(16.dp))

                TempoDialogTitle(text = "Split Artists Detected")

                Spacer(modifier = Modifier.height(8.dp))

                TempoDialogBody(
                    text = "It looks like these artists were incorrectly split from \"$newName\":"
                )

                Spacer(modifier = Modifier.height(12.dp))

                // List of split artists
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    splitArtists.forEach { artist ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(TempoDarkSurfaceSunken)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TempoDialogBody(
                    text = "Merge their listening data into \"$newName\"?"
                )

                Spacer(modifier = Modifier.height(20.dp))

                if (isRenaming) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = TempoPrimary,
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    TempoDialogButtonRow(
                        primaryText = "Merge & Rename",
                        onPrimary = {
                            onConfirmRenameAndMerge(
                                newName.trim(),
                                splitArtists.map { it.id }
                            )
                        },
                        secondaryText = "Just Rename",
                        onSecondary = { onConfirmRenameOnly(newName.trim()) }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    TempoDialogSecondaryButton(
                        text = "Back",
                        onClick = { showMergeConfirmation = false }
                    )
                }
            }

            // Error state
            if (renameSuccess == false) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Rename failed. Please try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TempoError,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
