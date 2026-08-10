package me.avinas.tempo.ui.onboarding

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.data.drive.DriveBackupInfo
import me.avinas.tempo.data.drive.DriveRestoreResult
import me.avinas.tempo.data.importexport.ImportConflictStrategy
import me.avinas.tempo.data.importexport.ImportExportResult
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.settings.BackupRestoreViewModel
import me.avinas.tempo.ui.settings.DriveOperationState
import me.avinas.tempo.ui.spotify.SpotifyJsonImportViewModel
import me.avinas.tempo.ui.spotify.SpotifyJsonImportUiState
import me.avinas.tempo.data.spotify.SpotifyJsonImportService
import me.avinas.tempo.ui.youtube.YouTubeMusicImportViewModel
import me.avinas.tempo.ui.youtube.YouTubeMusicImportUiState
import me.avinas.tempo.data.youtube.YouTubeMusicImportService
import androidx.compose.material.icons.filled.VideoLibrary
import me.avinas.tempo.ui.theme.TempoBackground
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.Divider
import me.avinas.tempo.ui.theme.SpotifyGreen
import me.avinas.tempo.ui.theme.TempoInfo
import me.avinas.tempo.ui.theme.TempoSuccess
import me.avinas.tempo.ui.theme.TempoSurfaceSunken
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import me.avinas.tempo.ui.theme.TempoError
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.utils.FormatUtils.formatBytes
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize
import me.avinas.tempo.ui.utils.rememberClampedHeightPercentage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.painterResource
import me.avinas.tempo.R
import me.avinas.tempo.ui.lastfm.LastFmViewModel
import me.avinas.tempo.ui.lastfm.LastFmUiState
import me.avinas.tempo.data.lastfm.LastFmImportService
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(
    onFinish: () -> Unit,
    onBack: () -> Unit,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
    lastFmViewModel: LastFmViewModel = hiltViewModel(),
    spotifyJsonImportViewModel: SpotifyJsonImportViewModel = hiltViewModel(),
    youTubeMusicImportViewModel: YouTubeMusicImportViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // Track if we've shown the activity error to avoid spamming
    var activityErrorShown by remember { mutableStateOf(false) }

    // Guard against onFinish being called multiple times from different LaunchedEffect blocks.
    // Multiple paths (import success, Spotify success, Last.fm success, Start Fresh button) can
    // call onFinish concurrently; calling it more than once causes NavController to try to
    // transition a back-stack entry that no longer exists → IllegalStateException.
    var hasFinished by remember { mutableStateOf(false) }
    val safeOnFinish: () -> Unit = {
        if (!hasFinished) {
            hasFinished = true
            onFinish()
        }
    }
    
    // Handle system back press - block during active operations
    val driveOperation by viewModel.driveOperation.collectAsState()
    val importExportProgress by viewModel.importExportProgress.collectAsState()
    
    // Last.fm State
    val lastFmUiState by lastFmViewModel.uiState.collectAsState()
    val lastFmImportProgress by lastFmViewModel.importProgress.collectAsState()
    
    // Spotify JSON Import State
    val spotifyJsonImportUiState by spotifyJsonImportViewModel.uiState.collectAsState()
    val spotifyJsonImportState by spotifyJsonImportViewModel.importState.collectAsState()

    // YouTube Music Import State
    val youTubeMusicImportUiState by youTubeMusicImportViewModel.uiState.collectAsState()
    val youTubeMusicImportState by youTubeMusicImportViewModel.importState.collectAsState()

    val isOperationActive = driveOperation is DriveOperationState.Downloading ||
        driveOperation is DriveOperationState.Restoring ||
        driveOperation is DriveOperationState.Uploading ||
        importExportProgress != null ||
        lastFmUiState.isImporting ||
        lastFmUiState.isLoading ||
        spotifyJsonImportUiState is SpotifyJsonImportUiState.Importing ||
        youTubeMusicImportUiState is YouTubeMusicImportUiState.Importing
    
    androidx.activity.compose.BackHandler(enabled = !isOperationActive, onBack = onBack)

    
    // ViewModel State
    val isSignedIn by viewModel.isSignedIn.collectAsState()
    val driveBackups by viewModel.driveBackups.collectAsState()
    val importExportResult by viewModel.importExportResult.collectAsState()
    
    // Dialog States
    val conflictDialogUri by viewModel.showConflictDialog.collectAsState()
    val driveRestoreDialog by viewModel.showDriveRestoreDialog.collectAsState()
    
    // Error Handling State
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Handle Sign-In/Restore/Consent callbacks
    val signInRequested by viewModel.signInRequested.collectAsState()
    val sessionRestoreRequested by viewModel.sessionRestoreRequested.collectAsState()
    val consentRequested by viewModel.consentRequested.collectAsState()
    
    // Consent Flow Launcher
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentComplete(result.resultCode == Activity.RESULT_OK)
    }
    
    LaunchedEffect(signInRequested) {
        if (signInRequested) {
            if (activity != null) {
                viewModel.onSignInReady(activity)
            } else {
                viewModel.cancelSignIn()
                if (!activityErrorShown) {
                    snackbarHostState.showSnackbar("Cannot sign in: Activity not available")
                    activityErrorShown = true
                }
            }
        }
    }
    
    LaunchedEffect(sessionRestoreRequested) {
        if (sessionRestoreRequested && activity != null) {
            viewModel.onSessionRestoreReady(activity)
        }
    }
    
    // Handle Drive Consent Flow
    LaunchedEffect(consentRequested) {
        if (consentRequested) {
            val pendingIntent = viewModel.getDriveConsentPendingIntent()
            if (pendingIntent != null) {
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    consentLauncher.launch(intentSenderRequest)
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Failed to open consent screen: ${e.message}")
                    viewModel.onConsentComplete(false)
                }
            } else {
                // No pending intent means consent isn't actually needed
                viewModel.onConsentComplete(true)
            }
        }
    }

    // Success Handling - Finish onboarding on successful restore
    LaunchedEffect(importExportResult) {
        when (val result = importExportResult) {
             is ImportExportResult.Success -> {
                 safeOnFinish() // Auto-finish on success
                 viewModel.clearImportExportResult()
             }
             is ImportExportResult.Error -> {
                 snackbarHostState.showSnackbar("Restore failed: ${result.message}")
                 viewModel.clearImportExportResult()
             }
             else -> {}
        }
    }
    
    LaunchedEffect(driveOperation) {
        when (val op = driveOperation) {
            is DriveOperationState.Success -> viewModel.clearDriveOperation()
            is DriveOperationState.Error -> {
                snackbarHostState.showSnackbar("Drive error: ${op.message}")
                viewModel.clearDriveOperation()
            }
            else -> {}
        }
    }

    // Local Backup Picker
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.startImport(it) }
    }


    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        me.avinas.tempo.ui.components.DeepOceanBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
    
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.02f)))
                    
                    // Top Bar with Back Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
    
                    Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.01f)))
                    
                    Text(
                        text = "Import & Restore",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    
                    Text(
                        text = "Import from other services or restore your backup.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = rememberScreenHeightPercentage(0.01f))
                    )
        
                    Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))

                    // Section: Import
                    Text(
                        text = "Import",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        textAlign = TextAlign.Start
                    )

                    // Last.fm Import Card
                    LastFmImportCard(
                        uiState = lastFmUiState,
                        importProgress = lastFmImportProgress,
                        onUsernameSubmit = lastFmViewModel::discoverUser,
                        onSelectTier = lastFmViewModel::startImportDirect,
                        onCancel = lastFmViewModel::reset,
                        onCancelImport = lastFmViewModel::cancelImport,
                        onClearError = lastFmViewModel::clearError,
                        onFinishImport = {
                            lastFmViewModel.reset()
                            safeOnFinish()
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Spotify JSON Import Card
                    SpotifyJsonImportCard(
                        uiState = spotifyJsonImportUiState,
                        importState = spotifyJsonImportState,
                        onImport = { uris ->
                            spotifyJsonImportViewModel.importFiles(context, uris)
                        },
                        onReset = {
                            spotifyJsonImportViewModel.resetState()
                        },
                        onFinish = {
                            spotifyJsonImportViewModel.resetState()
                            safeOnFinish()
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // YouTube Music Import Card
                    YouTubeMusicImportCard(
                        uiState = youTubeMusicImportUiState,
                        importState = youTubeMusicImportState,
                        onImport = { uris ->
                            youTubeMusicImportViewModel.importFiles(context, uris)
                        },
                        onReset = {
                            youTubeMusicImportViewModel.resetState()
                        },
                        onFinish = {
                            youTubeMusicImportViewModel.resetState()
                            safeOnFinish()
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Section: Restore
                    Text(
                        text = "Restore",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        textAlign = TextAlign.Start
                    )
        
                    // Option 1: Google Drive
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        variant = GlassCardVariant.LowProminence,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                modifier = Modifier
                                    .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
                                    .background(TempoInfo.copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Cloud,
                                        contentDescription = null,
                                        tint = TempoInfo
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                Text(
                                    text = "Google Drive",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
        
                            HorizontalDivider(color = Divider)
        
                            if (!isSignedIn) {
                                Box(
                                    modifier = Modifier
                                        .clickable { viewModel.requestSignIn() }
                                        .padding(16.dp)
                                        .fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Connect Google Account",
                                        color = TempoInfo,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            } else {
                                 if (driveOperation is DriveOperationState.Loading || driveOperation is DriveOperationState.SigningIn) {
                                     Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                         CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                     }
                                 } else if (driveBackups.isNotEmpty()) {
                                     Column(
                                         modifier = Modifier.padding(bottom = 16.dp)
                                     ) {
                                         driveBackups.forEachIndexed { index, backup ->
                                             Row(
                                                 modifier = Modifier
                                                     .fillMaxWidth()
                                                     .clickable { viewModel.startDriveRestore(backup) }
                                                     .padding(horizontal = 16.dp, vertical = 12.dp),
                                                 verticalAlignment = Alignment.CenterVertically
                                             ) {
                                                 Column(modifier = Modifier.weight(1f)) {
                                                     Text(
                                                        text = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(backup.createdAt)),
                                                        color = TextPrimary,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontSize = adaptiveTextUnitByCategory(16.sp, 15.sp, 14.sp)
                                                     )
                                                     Text(
                                                        text = formatBytes(backup.sizeBytes) + (backup.deviceName?.let { " • $it" } ?: ""),
                                                        color = TextTertiary,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontSize = adaptiveTextUnitByCategory(14.sp, 13.sp, 12.sp)
                                                     )
                                                 }
                                                 Icon(
                                                     Icons.Default.CloudDownload,
                                                     contentDescription = "Restore",
                                                     tint = TempoPrimary
                                                 )
                                             }
                                             if (index < driveBackups.lastIndex) {
                                                 HorizontalDivider(color = TempoSurfaceSunken)
                                             }
                                         }
                                     }
                                 } else {
                                     Text(
                                         text = "No backups found",
                                         color = TextTertiary,
                                         modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
                                         style = MaterialTheme.typography.bodyMedium
                                     )
                                 }
                            }
                        }
                    }
        
                    Spacer(modifier = Modifier.height(adaptiveSizeByCategory(16.dp, 14.dp, 12.dp)))
        
                    // Option 2: Local Backup
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                            },
                        variant = GlassCardVariant.LowProminence,
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
                                    .background(Divider, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = TextPrimary
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(16.dp))
                            
                            Column {
                                Text(
                                    text = "Restore from File",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Select a .zip backup file",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextTertiary
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.04f)))
                }
    
                // Spotify Import Reminder Banner
                var showSpotifyReminder by remember { mutableStateOf(true) }
                if (showSpotifyReminder) {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = adaptiveSizeByCategory(24.dp, 20.dp, 16.dp))
                            .padding(bottom = 12.dp),
                        variant = GlassCardVariant.LowProminence,
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = SpotifyGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "You can import Spotify data later from Settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { showSpotifyReminder = false },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
    
                // Start Fresh Button (Pinned Footer)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = adaptiveSizeByCategory(24.dp, 20.dp, 16.dp))
                        .padding(bottom = rememberScreenHeightPercentage(0.03f))
                ) {
                    Button(
                        onClick = safeOnFinish,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(scaledSize(54.dp, 0.85f, 1.1f)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Divider,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "Start Fresh",
                            fontSize = adaptiveTextUnitByCategory(16.sp, 15.sp, 14.sp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
    
    // --- Dialogs ---

    // Progress Dialog
    importExportProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (progress.phase.contains("Import")) "Restoring..." else "Processing...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(progress.phase)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (progress.isIndeterminate || progress.total <= 0) {
                        CircularProgressIndicator()
                    } else {
                        LinearProgressIndicator(
                            progress = { (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) }
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Drive Downloading Dialog
    if (driveOperation is DriveOperationState.Downloading || driveOperation is DriveOperationState.Restoring) {
         AlertDialog(
            onDismissRequest = { },
            title = { Text("Restoring from Cloud...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val downloadingState = driveOperation as? DriveOperationState.Downloading
                    if (downloadingState != null) {
                        val currentProgress = downloadingState.progress
                        LinearProgressIndicator(progress = { currentProgress })
                    } else {
                         CircularProgressIndicator()
                    }
                }
            },
            confirmButton = {}
        )
    }
    
    // Local Conflict Resolution
    conflictDialogUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("Restore Options") },
            text = { Text("How should we handle data conflicts?") },
            confirmButton = {
                TextButton(onClick = { viewModel.importData(uri, ImportConflictStrategy.REPLACE) }) {
                    Text("Replace Everything")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.cancelImport() }) {
                        Text("Cancel")
                    }
                    TextButton(onClick = { viewModel.importData(uri, ImportConflictStrategy.SKIP) }) {
                        Text("Skip Duplicates")
                    }
                }
            }
        )
    }


    // Drive Restore Confirmation
    driveRestoreDialog?.let { backup ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDriveRestore() },
            title = { Text("Restore this backup?") },
            text = { 
                Column {
                    Text("Date: ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(backup.createdAt))}")
                    Text("Size: ${formatBytes(backup.sizeBytes)}")
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.restoreFromDrive(backup, ImportConflictStrategy.REPLACE) }) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDriveRestore() }) {
                    Text("Cancel")
                }
            }
        )
    }
    
}

/**
 * Last.fm Import Card for onboarding
 * Compact card that handles the full Last.fm import flow inline
 */
@Composable
fun LastFmImportCard(
    uiState: LastFmUiState,
    importProgress: LastFmImportService.ImportProgress,
    onUsernameSubmit: (String) -> Unit,
    onSelectTier: (LastFmImportService.TierConfig) -> Unit,
    onCancel: () -> Unit,
    onCancelImport: () -> Unit,
    onClearError: () -> Unit,
    onFinishImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lastFmPurple = TempoPrimary // Last.fm red
    var username by remember { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(false) }
    
    // Reset username when state is reset (e.g., when going back from tier selection)
    LaunchedEffect(uiState.discoveryResult, uiState.showTierSelection) {
        if (uiState.discoveryResult == null && !uiState.showTierSelection && !uiState.isImporting) {
            username = ""
        }
    }
    
    // Auto-expand when there's discovery result or import in progress
    LaunchedEffect(uiState.discoveryResult, uiState.isImporting, uiState.importResult) {
        if (uiState.discoveryResult != null || uiState.isImporting || uiState.importResult != null) {
            isExpanded = true
        }
    }
    
    // Handle successful import - don't auto-finish, let user click Continue
    // This avoids race conditions with the button click
    
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        variant = GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column {
            // Header row - always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        if (!uiState.isImporting && !uiState.isLoading) {
                            isExpanded = !isExpanded 
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
                        .background(lastFmPurple.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lastfm),
                        contentDescription = "Last.fm",
                        tint = lastFmPurple,
                        modifier = Modifier
                            .size(24.dp)
                            .offset(y = (-1).dp) // Visual correction
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Last.fm",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Import years of listening history",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
                
                // Expand/collapse indicator
                if (!uiState.isImporting && !uiState.isLoading) {
                    Icon(
                        imageVector = if (isExpanded) 
                            Icons.Default.KeyboardArrowUp 
                        else 
                            Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = TextTertiary
                    )
                }
            }
            
            // Expandable content
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(color = Divider)
                    
                    when {
                        // Import complete
                        uiState.importResult != null -> {
                            LastFmImportComplete(
                                result = uiState.importResult!!,
                                onDone = onFinishImport
                            )
                        }
                        
                        // Import in progress
                        uiState.isImporting -> {
                            LastFmImportProgress(
                                progress = importProgress,
                                onCancel = onCancelImport
                            )
                        }
                        
                        // Tier selection
                        uiState.showTierSelection && uiState.discoveryResult != null -> {
                            LastFmTierSelection(
                                discovery = uiState.discoveryResult!!,
                                onSelectTier = onSelectTier,
                                onBack = onCancel
                            )
                        }
                        
                        // Loading/discovering
                        uiState.isLoading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = lastFmPurple,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Discovering your account...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                        
                        // Username input (default state)
                        else -> {
                            LastFmUsernameInput(
                                username = username,
                                onUsernameChange = { newValue ->
                                    username = newValue
                                    if (uiState.error != null) onClearError()
                                },
                                error = uiState.error,
                                onSubmit = { onUsernameSubmit(username.trim()) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LastFmUsernameInput(
    username: String,
    onUsernameChange: (String) -> Unit,
    error: String?,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Last.fm Username") },
            placeholder = { Text("Enter your username") },
            singleLine = true,
            isError = error != null,
            supportingText = if (error != null) {
                { Text(error, color = MaterialTheme.colorScheme.error) }
            } else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = TempoPrimary,
                unfocusedBorderColor = TextTertiary,
                focusedLabelColor = TempoPrimary,
                unfocusedLabelColor = TextSecondary,
                cursorColor = TempoPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Button(
            onClick = onSubmit,
            enabled = username.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = TempoPrimary,
                disabledContainerColor = TempoPrimary.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Connect", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LastFmTierSelection(
    discovery: LastFmImportService.DiscoveryResult,
    onSelectTier: (LastFmImportService.TierConfig) -> Unit,
    onBack: () -> Unit
) {
    val numberFormat = remember { java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()) }
    
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            text = "Welcome, ${discovery.username}!",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "${numberFormat.format(discovery.totalScrobbles)} total scrobbles",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Your complete history is imported.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
        Text(
            text = "Choose which tracks power your leaderboards:",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Tier options - using new Quick/Standard/Deep system
        LastFmTierOption(
            name = "Quick",
            description = "Recent 3 months in leaderboards",
            icon = "⚡",
            isRecommended = false,
            onClick = { onSelectTier(LastFmImportService.Companion.Tiers.QUICK) }
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        LastFmTierOption(
            name = "Standard", 
            description = "Last year + top tracks in leaderboards",
            icon = "⭐",
            isRecommended = true, // Standard is recommended for most users
            onClick = { onSelectTier(LastFmImportService.Companion.Tiers.STANDARD) }
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        LastFmTierOption(
            name = "Deep",
            description = "Last 2 years + more tracks in leaderboards",
            icon = "📊",
            isRecommended = false,
            onClick = { onSelectTier(LastFmImportService.Companion.Tiers.DEEP) }
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Explanation text - user-friendly
        Text(
            text = "💡 All your tracks are saved. This just affects chart speed.",
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(
                text = "Use different account",
                color = TextTertiary
            )
        }
    }
}

@Composable
private fun LastFmTierOption(
    name: String,
    description: String,
    icon: String,
    isRecommended: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isRecommended) TempoPrimary.copy(alpha = 0.15f) else TempoSurfaceSunken,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, style = MaterialTheme.typography.titleMedium)
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
        
        if (isRecommended) {
            Box(
                modifier = Modifier
                    .background(
                        color = TempoPrimary.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Recommended",
                    style = MaterialTheme.typography.labelSmall,
                    color = TempoPrimary
                )
            }
        }
    }
}

@Composable
private fun LastFmImportProgress(
    progress: LastFmImportService.ImportProgress,
    onCancel: () -> Unit
) {
    val numberFormat = remember { java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()) }
    val lastFmRed = TempoPrimary
    
    // Extract progress details including tier info
    val progressData = when (progress) {
        is LastFmImportService.ImportProgress.Importing -> {
            val percent = if (progress.total > 0) ((progress.current * 100) / progress.total).toInt() else 0
            ProgressData(
                percent = percent, 
                phase = progress.phase, 
                current = progress.current, 
                total = progress.total, 
                eventsCreated = progress.eventsCreated, 
                archived = progress.archived,
                isEverythingTier = progress.tierName == "EVERYTHING"
            )
        }
        is LastFmImportService.ImportProgress.Discovering -> ProgressData(0, "Discovering", 0, 0, 0, 0, false)
        is LastFmImportService.ImportProgress.Processing -> ProgressData(0, "Processing", 0, 0, 0, 0, false)
        else -> ProgressData(0, "Preparing", 0, 0, 0, 0, false)
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Circular progress with percentage
        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { (progressData.percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize(),
                color = lastFmRed,
                trackColor = Divider,
                strokeWidth = 6.dp
            )
            
            Text(
                text = "${progressData.percent}%",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Phase indicator
        Text(
            text = progressData.phase,
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        
        // Progress text
        if (progressData.total > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${numberFormat.format(progressData.current)} of ${numberFormat.format(progressData.total)} scrobbles",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This may take a few minutes",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Live stats row - show different labels based on tier
        if (progressData.isEverythingTier) {
            // Full import: just show total imported
            ImportStatItem(
                label = "Imported",
                value = numberFormat.format(progressData.eventsCreated),
                color = TempoSuccess
            )
        } else {
            // Tiered import: show active vs archived breakdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ImportStatItem(
                    label = "Active",
                    value = numberFormat.format(progressData.eventsCreated),
                    color = TempoSuccess // Green for active tracks
                )
                ImportStatItem(
                    label = "Archived",
                    value = numberFormat.format(progressData.archived),
                    color = TextTertiary // Gray for archived
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Cancel button
        TextButton(onClick = onCancel) {
            Text(
                text = "Cancel",
                color = TextTertiary
            )
        }
    }
}

/** Data class for progress extraction */
private data class ProgressData(
    val percent: Int,
    val phase: String,
    val current: Long,
    val total: Long,
    val eventsCreated: Long,
    val archived: Long,
    val isEverythingTier: Boolean
)

@Composable
private fun ImportStatItem(
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary
        )
    }
}

@Composable
private fun LastFmImportComplete(
    result: LastFmImportService.ImportResult,
    onDone: () -> Unit
) {
    val numberFormat = remember { java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault()) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = TempoSuccess,
            modifier = Modifier.size(48.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = "Import Complete!",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "${numberFormat.format(result.activeSetCount + result.archivedCount)} scrobbles imported",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(
                containerColor = TempoPrimary
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Continue", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SpotifyJsonImportCard(
    uiState: SpotifyJsonImportUiState,
    importState: SpotifyJsonImportService.ImportState,
    onImport: (List<Uri>) -> Unit,
    onReset: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spotifyGreen = SpotifyGreen
    var isExpanded by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        selectedUris = uris
        if (uris.isNotEmpty()) {
            isExpanded = true
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is SpotifyJsonImportUiState.Importing || uiState is SpotifyJsonImportUiState.Completed) {
            isExpanded = true
        }
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        variant = GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (uiState !is SpotifyJsonImportUiState.Importing) {
                            isExpanded = !isExpanded
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
                        .background(spotifyGreen.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.spotify),
                        contentDescription = null,
                        modifier = Modifier.size(adaptiveSizeByCategory(28.dp, 26.dp, 24.dp))
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Spotify Data Export",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Import from JSON files",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }

                if (uiState !is SpotifyJsonImportUiState.Importing) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = TextTertiary
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    when (uiState) {
                        is SpotifyJsonImportUiState.Idle -> {
                            Text(
                                text = buildAnnotatedString {
                                    append("Get your data from ")
                                    withLink(
                                        LinkAnnotation.Url(
                                            url = "https://spotify.com/account/privacy",
                                            styles = TextLinkStyles(style = SpanStyle(color = SpotifyGreen, textDecoration = TextDecoration.Underline))
                                        )
                                    ) {
                                        append("spotify.com/account/privacy")
                                    }
                                    append(", then select the JSON files. You can also do this later from Settings.")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    filePickerLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Select JSON Files")
                            }

                            if (selectedUris.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "${selectedUris.size} file(s) selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = spotifyGreen
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { onImport(selectedUris) },
                                    colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("Start Import", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        is SpotifyJsonImportUiState.Importing -> {
                            val (message, progress) = when (importState) {
                                is SpotifyJsonImportService.ImportState.Parsing -> {
                                    "Parsing ${importState.fileName}..." to
                                        (importState.filesProcessed.toFloat() / importState.totalFiles.coerceAtLeast(1))
                                }
                                is SpotifyJsonImportService.ImportState.Importing -> {
                                    "Importing ${importState.current}/${importState.total}" to
                                        (importState.current.toFloat() / importState.total.coerceAtLeast(1))
                                }
                                else -> "Preparing..." to 0f
                            }

                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = spotifyGreen,
                                trackColor = Divider
                            )
                        }

                        is SpotifyJsonImportUiState.Completed -> {
                            val result = uiState.result
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = TempoSuccess,
                                modifier = Modifier.size(40.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Import Complete!",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "${result.tracksImported} tracks, ${result.eventsCreated} events",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = onFinish,
                                colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Continue", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        is SpotifyJsonImportUiState.Error -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = TempoError,
                                modifier = Modifier.size(40.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = uiState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onReset,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Retry")
                                }
                                Button(
                                    onClick = {
                                        selectedUris = emptyList()
                                        onReset()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = spotifyGreen),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Select Files")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun YouTubeMusicImportCard(
    uiState: YouTubeMusicImportUiState,
    importState: YouTubeMusicImportService.ImportState,
    onImport: (List<Uri>) -> Unit,
    onReset: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val youTubeRed = TempoError
    var isExpanded by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        selectedUris = uris
        if (uris.isNotEmpty()) {
            isExpanded = true
            onImport(uris)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is YouTubeMusicImportUiState.Importing || uiState is YouTubeMusicImportUiState.Completed) {
            isExpanded = true
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is YouTubeMusicImportUiState.Completed) {
            kotlinx.coroutines.delay(2000)
            onFinish()
        }
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        variant = GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (uiState !is YouTubeMusicImportUiState.Importing) {
                            isExpanded = !isExpanded
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(adaptiveSizeByCategory(48.dp, 44.dp, 40.dp))
                        .background(youTubeRed.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = youTubeRed,
                        modifier = Modifier.size(adaptiveSizeByCategory(28.dp, 26.dp, 24.dp))
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "YouTube Music Takeout",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Import from YouTube Takeout (ZIP or JSON)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }

                if (uiState !is YouTubeMusicImportUiState.Importing) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = TextTertiary
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    when (uiState) {
                        is YouTubeMusicImportUiState.Idle -> {
                            Text(
                                text = buildAnnotatedString {
                                    append("Get your data from ")
                                    withLink(
                                        LinkAnnotation.Url(
                                            url = "https://takeout.google.com",
                                            styles = TextLinkStyles(style = SpanStyle(color = youTubeRed, textDecoration = TextDecoration.Underline))
                                        )
                                    ) {
                                        append("takeout.google.com")
                                    }
                                    append(", deselect all → select only \"YouTube and YouTube Music\" → only \"history\" → JSON format. Download the ZIP and select it here directly. You can also do this later from Settings.")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = {
                                    filePickerLauncher.launch(arrayOf("application/zip", "application/x-zip", "application/json", "text/plain", "*/*"))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = youTubeRed),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Select File")
                            }
                        }

                        is YouTubeMusicImportUiState.Importing -> {
                            val (message, progress) = when (importState) {
                                is YouTubeMusicImportService.ImportState.Parsing -> {
                                    "Parsing ${importState.fileName}..." to
                                        (importState.filesProcessed.toFloat() / importState.totalFiles.coerceAtLeast(1))
                                }
                                is YouTubeMusicImportService.ImportState.Importing -> {
                                    "Importing ${importState.current}/${importState.total}" to
                                        (importState.current.toFloat() / importState.total.coerceAtLeast(1))
                                }
                                else -> "Preparing..." to 0f
                            }

                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = youTubeRed,
                                trackColor = Divider
                            )
                        }

                        is YouTubeMusicImportUiState.Completed -> {
                            val result = uiState.result
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = TempoSuccess,
                                modifier = Modifier.size(40.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Import Complete!",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "${result.tracksImported} tracks, ${result.eventsCreated} events",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Continuing automatically...",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }

                        is YouTubeMusicImportUiState.Error -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = TempoError,
                                modifier = Modifier.size(40.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = uiState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onReset,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Retry")
                                }
                                Button(
                                    onClick = {
                                        selectedUris = emptyList()
                                        onReset()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = youTubeRed),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Select Files")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
