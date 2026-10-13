package me.avinas.tempo.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.data.drive.DriveHistorySyncManager
import me.avinas.tempo.data.drive.DriveHistorySyncResult
import me.avinas.tempo.data.drive.DriveHistorySyncSettings
import me.avinas.tempo.data.drive.DriveHistorySyncSettingsManager
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.components.SettingsSwitch
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.worker.DriveHistorySyncWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

@HiltViewModel
class DriveHistorySyncViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsManager: DriveHistorySyncSettingsManager,
    private val syncManager: DriveHistorySyncManager,
    private val applicationScope: CoroutineScope
) : ViewModel() {
    val settings: StateFlow<DriveHistorySyncSettings> = settingsManager.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        DriveHistorySyncSettings()
    )

    private val _operation = MutableStateFlow<DriveHistoryUiOperation>(DriveHistoryUiOperation.Idle)
    val operation: StateFlow<DriveHistoryUiOperation> = _operation.asStateFlow()
    private val operationRunning = AtomicBoolean(false)

    fun setEnabled(enabled: Boolean) {
        launchExclusive {
            if (enabled) {
                if (!syncManager.enableSync()) {
                    return@launchExclusive DriveHistoryUiOperation.Error(
                        context.getString(R.string.drive_auth_required)
                    )
                }
                DriveHistorySyncWorker.schedule(context)
                syncResultToUi(syncManager.syncNow())
            } else {
                syncManager.disableSync()
                DriveHistorySyncWorker.cancel(context)
                DriveHistoryUiOperation.Idle
            }
        }
    }

    fun syncNow() {
        launchExclusive {
            syncResultToUi(syncManager.syncNow())
        }
    }

    fun restoreFullHistory() {
        launchExclusive {
            syncResultToUi(syncManager.restoreFullHistory())
        }
    }

    fun shareOlderLocalHistory() {
        launchExclusive {
            when (val result = syncManager.shareOlderLocalHistory()) {
                is DriveHistorySyncResult.Success -> syncResultToUi(syncManager.syncNow())
                else -> syncResultToUi(result)
            }
        }
    }

    fun deleteCloudHistory() {
        launchExclusive {
            try {
                val deleted = syncManager.deleteCloudHistoryAndReset()
                DriveHistorySyncWorker.cancel(context)
                DriveHistoryUiOperation.Success(
                    context.getString(R.string.drive_deleted_success, deleted)
                )
            } catch (error: Exception) {
                // Account-boundary protection can deliberately turn sync off
                // before refusing to delete from an unexpected Google account.
                if (!settingsManager.settings.first().enabled) {
                    DriveHistorySyncWorker.cancel(context)
                }
                throw error
            }
        }
    }

    fun clearOperation() {
        if (!operationRunning.get()) _operation.value = DriveHistoryUiOperation.Idle
    }

    private fun launchExclusive(block: suspend () -> DriveHistoryUiOperation) {
        if (!operationRunning.compareAndSet(false, true)) return
        _operation.value = DriveHistoryUiOperation.Running
        applicationScope.launch {
            _operation.value = try {
                block()
            } catch (e: Exception) {
                DriveHistoryUiOperation.Error(e.message ?: context.getString(R.string.drive_operation_failed))
            } finally {
                operationRunning.set(false)
            }
        }
    }

    private fun syncResultToUi(result: DriveHistorySyncResult): DriveHistoryUiOperation = when (result) {
        DriveHistorySyncResult.Disabled -> DriveHistoryUiOperation.Idle
        is DriveHistorySyncResult.RemoteDisabled -> {
            DriveHistorySyncWorker.cancel(context)
            DriveHistoryUiOperation.Error(result.message)
        }
        is DriveHistorySyncResult.Success -> DriveHistoryUiOperation.Success(
            context.getString(R.string.drive_sync_success, result.uploaded, result.imported)
        )
        is DriveHistorySyncResult.Error -> DriveHistoryUiOperation.Error(result.message)
    }
}

sealed class DriveHistoryUiOperation {
    data object Idle : DriveHistoryUiOperation()
    data object Running : DriveHistoryUiOperation()
    data class Success(val message: String) : DriveHistoryUiOperation()
    data class Error(val message: String) : DriveHistoryUiOperation()
}

@Composable
fun DriveHistorySyncSection(
    isGoogleSignedIn: Boolean,
    onRequestGoogleSignIn: () -> Unit,
    viewModel: DriveHistorySyncViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val operation by viewModel.operation.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmShare by remember { mutableStateOf(false) }

    val running = operation is DriveHistoryUiOperation.Running

    Text(
        text = stringResource(R.string.drive_heading),
        style = MaterialTheme.typography.titleSmall,
        color = TempoRed,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        variant = GlassCardVariant.LowProminence
    ) {
        Column {
            SettingsSwitch(
                title = stringResource(R.string.drive_toggle_title),
                subtitle = stringResource(R.string.drive_toggle_subtitle),
                checked = settings.enabled,
                onCheckedChange = viewModel::setEnabled,
                enabled = isGoogleSignedIn && !running
            )
            if (!isGoogleSignedIn) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.drive_signin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                    Button(
                        onClick = onRequestGoogleSignIn,
                        enabled = !running,
                        modifier = Modifier.padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary)
                    ) {
                        Text(stringResource(R.string.drive_signin_action))
                    }
                }
            }
            if (running && !settings.enabled) {
                Row(modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.drive_connecting),
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            if (settings.enabled && isGoogleSignedIn) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.drive_status_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                            Text(
                                text = historySyncSubtitle(settings),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                        Button(
                            onClick = viewModel::syncNow,
                            enabled = !running,
                            colors = ButtonDefaults.buttonColors(containerColor = TempoPrimary)
                        ) {
                            if (running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                            Text(stringResource(R.string.drive_sync_now))
                        }
                    }

                    OutlinedButton(
                        onClick = viewModel::restoreFullHistory,
                        enabled = !running,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.drive_restore))
                    }
                    Text(
                        stringResource(R.string.drive_restore_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    OutlinedButton(
                        onClick = { confirmShare = true },
                        enabled = !running,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.drive_share))
                    }
                    Text(
                        stringResource(R.string.drive_share_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 4.dp)
                    )

                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Row(modifier = Modifier.padding(16.dp)) {
                    OutlinedButton(onClick = { confirmDelete = true }, enabled = !running) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                        Text(stringResource(R.string.drive_delete))
                    }
                }
            }

            // Keep feedback visible even when sync has just been disabled.
            when (val op = operation) {
                is DriveHistoryUiOperation.Success -> Text(
                    text = op.message,
                    modifier = Modifier.padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
                is DriveHistoryUiOperation.Error -> Text(
                    text = op.message,
                    modifier = Modifier.padding(16.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                    style = MaterialTheme.typography.bodySmall,
                    color = TempoRed
                )
                else -> Unit
            }
        }
    }

    if (confirmShare) {
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text(stringResource(R.string.drive_share_confirm_title)) },
            text = {
                Text(stringResource(R.string.drive_share_confirm_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmShare = false
                    viewModel.shareOlderLocalHistory()
                }) { Text(stringResource(R.string.drive_share_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmShare = false }) { Text(stringResource(R.string.drive_cancel)) }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.CloudSync, contentDescription = null, tint = TempoRed) },
            title = { Text(stringResource(R.string.drive_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.drive_delete_confirm_body)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteCloudHistory()
                }) {
                    Text(stringResource(R.string.drive_delete_confirm), color = TempoRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.drive_cancel)) }
            }
        )
    }
}

@Composable
private fun historySyncSubtitle(settings: DriveHistorySyncSettings): String {
    val last = settings.lastSyncTime?.let {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(it))
    } ?: stringResource(R.string.drive_never)
    return when {
        settings.lastMessage != null -> stringResource(R.string.drive_last_message, last, settings.lastMessage)
        settings.lastSyncTime != null ->
            stringResource(R.string.drive_last_counts, last, settings.lastUploaded, settings.lastImported)
        else -> stringResource(R.string.drive_schedule_hint)
    }
}
