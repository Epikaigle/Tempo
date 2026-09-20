package me.avinas.tempo.ui.deezer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.R
import me.avinas.tempo.data.deezer.DeezerDataImportService
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary

private val DeezerPurple = Color(0xFFA238FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeezerImportScreen(
    onNavigateBack: () -> Unit,
    viewModel: DeezerImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        selectedUri = uri
    }

    DeepOceanBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = DeezerPurple,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_import_deezer),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back),
                                tint = TextPrimary,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                when (val state = uiState) {
                    DeezerImportUiState.Idle -> {
                        IdleContent(
                            hasSelection = selectedUri != null,
                            onSelect = {
                                picker.launch(
                                    arrayOf(
                                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                        "application/octet-stream",
                                        "*/*",
                                    ),
                                )
                            },
                            onClear = { selectedUri = null },
                            onImport = {
                                selectedUri?.let { viewModel.importFile(context, it) }
                            },
                        )
                    }

                    DeezerImportUiState.Importing -> ImportingContent(importState)

                    is DeezerImportUiState.Completed -> {
                        CompletedContent(
                            result = state.result,
                            onDone = onNavigateBack,
                            onImportAnother = {
                                selectedUri = null
                                viewModel.resetState()
                            },
                        )
                    }

                    is DeezerImportUiState.Error -> {
                        ErrorContent(
                            message = state.message,
                            onRetry = {
                                selectedUri = null
                                viewModel.resetState()
                            },
                            onBack = onNavigateBack,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun IdleContent(
    hasSelection: Boolean,
    onSelect: () -> Unit,
    onClear: () -> Unit,
    onImport: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.FileOpen,
                contentDescription = null,
                tint = DeezerPurple,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Import from Deezer Data Export",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Select the Deezer personal-data XLSX file. Tempo reads only the listening-history sheet; account, IP and device data are ignored.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onSelect,
                colors = ButtonDefaults.buttonColors(containerColor = DeezerPurple),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Select Deezer XLSX")
            }

            if (hasSelection) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Deezer export selected",
                    color = DeezerPurple,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                        Text("Clear")
                    }
                    Button(
                        onClick = onImport,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DeezerPurple),
                    ) {
                        Text("Start Import")
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    GlassCard(contentPadding = PaddingValues(16.dp)) {
        Column {
            Text(
                text = "How to get your Deezer data",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            listOf(
                "1. On a computer, open Deezer in your web browser",
                "2. Open Settings → My data / My personal data",
                "3. Choose Request my data",
                "4. Download the personal-data export when Deezer emails you",
                "5. Select the deezer-data_*.xlsx file here",
            ).forEach { step ->
                Text(
                    text = step,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Plays shorter than 30 seconds are ignored, matching Tempo's Spotify history import.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }
}

@Composable
private fun ImportingContent(state: DeezerDataImportService.ImportState) {
    GlassCard(contentPadding = PaddingValues(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = DeezerPurple, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "Importing Deezer history…",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))

            val (message, progress) = when (state) {
                is DeezerDataImportService.ImportState.Parsing ->
                    ("Reading " + state.fileName + "…") to 0.05f
                is DeezerDataImportService.ImportState.Importing ->
                    (
                        "Importing " + state.current + "/" + state.total + " entries\n" +
                            state.tracksImported + " tracks, " + state.eventsCreated + " events"
                    ) to (state.current.toFloat() / state.total.coerceAtLeast(1))
                else -> "Preparing…" to 0f
            }

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = DeezerPurple,
            )
        }
    }
}

@Composable
private fun CompletedContent(
    result: DeezerDataImportService.ImportResult,
    onDone: () -> Unit,
    onImportAnother: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF27AE60),
                modifier = Modifier.size(52.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Deezer Import Complete",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = result.eventsCreated.toString() + " listening events imported\n" +
                    result.tracksImported + " new tracks\n" +
                    result.duplicatesSkipped + " duplicates skipped\n" +
                    result.shortPlaysSkipped + " plays under 30 seconds skipped" +
                    (if (result.malformedRows > 0) "\n" + result.malformedRows + " malformed rows skipped" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = DeezerPurple),
            ) {
                Text("Done")
            }
            OutlinedButton(
                onClick = onImportAnother,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import another file")
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    GlassCard(contentPadding = PaddingValues(24.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(52.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Deezer Import Failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = DeezerPurple),
            ) {
                Text("Try again")
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}
