package me.avinas.tempo.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.avinas.tempo.ui.components.DeepOceanBackground
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.Divider
import me.avinas.tempo.ui.theme.TempoSuccess
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary
import me.avinas.tempo.ui.theme.TextTertiary
import me.avinas.tempo.utils.OemBackgroundHelper

/**
 * Background Protection Settings Screen.
 * 
 * Guides Xiaomi/MIUI/HyperOS users through required settings
 * to prevent the app from being killed in the background.
 * 
 * Based on https://dontkillmyapp.com/xiaomi recommendations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundProtectionScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Device info (static - doesn't change)
    val isXiaomi = remember { OemBackgroundHelper.isXiaomiDevice() }
    val osName = remember { OemBackgroundHelper.getOsDisplayName() ?: "Unknown" }
    
    // Autostart state - recompute when returning from settings
    var autostartState by remember { mutableStateOf(OemBackgroundHelper.getAutostartState(context)) }
    
    // Refresh autostart state when screen resumes (user may have changed settings)
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                autostartState = OemBackgroundHelper.getAutostartState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Background Protection", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        DeepOceanBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Device Status Card
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    variant = GlassCardVariant.LowProminence
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Detected Device",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                            Text(
                                text = osName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        
                        // Autostart status
                        val (statusIcon, statusColor, statusText) = when (autostartState) {
                            OemBackgroundHelper.AutostartState.ENABLED -> 
                                Triple(Icons.Default.CheckCircle, TempoSuccess, "Autostart ON")
                            OemBackgroundHelper.AutostartState.DISABLED -> 
                                Triple(Icons.Default.Warning, TempoPrimary, "Autostart OFF")
                            OemBackgroundHelper.AutostartState.UNKNOWN -> 
                                Triple(Icons.AutoMirrored.Filled.HelpOutline, TextTertiary, "Unknown")
                        }
                        
                        Column(horizontalAlignment = Alignment.End) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Warning banner
                if (autostartState == OemBackgroundHelper.AutostartState.DISABLED) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = TempoPrimary.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = TempoPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Autostart is disabled. Tempo may stop working in the background.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // Explanation
                Text(
                    text = "Configure Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Xiaomi devices aggressively kill background apps. Complete these steps to ensure Tempo works reliably.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Settings Steps
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                    variant = GlassCardVariant.LowProminence
                ) {
                    Column {
                        // Step 1: Autostart
                        SettingsStep(
                            stepNumber = 1,
                            title = "Enable Autostart",
                            description = "Allow Tempo to start automatically",
                            icon = Icons.Default.PlayArrow,
                            isCompleted = autostartState == OemBackgroundHelper.AutostartState.ENABLED,
                            onClick = { OemBackgroundHelper.openAutostartSettings(context) }
                        )
                        
                        HorizontalDivider(color = Divider)
                        
                        // Step 2: Battery Saver
                        SettingsStep(
                            stepNumber = 2,
                            title = "Battery Saver → No restrictions",
                            description = "Security > Battery > App Battery Saver",
                            icon = Icons.Default.BatteryFull,
                            onClick = { OemBackgroundHelper.openBatterySaverSettings(context) }
                        )
                        
                        HorizontalDivider(color = Divider)
                        
                        // Step 3: Lock in Recent Apps
                        SettingsStep(
                            stepNumber = 3,
                            title = "Lock App in Recent Apps",
                            description = "Open recents → drag Tempo down to lock it",
                            icon = Icons.Default.Lock,
                            showOpenButton = false
                        )
                        
                        HorizontalDivider(color = Divider)
                        
                        // Step 4: Boost Speed Lock
                        SettingsStep(
                            stepNumber = 4,
                            title = "Lock in Boost Speed",
                            description = "Security > Boost speed > Lock applications",
                            icon = Icons.Default.Speed,
                            onClick = { OemBackgroundHelper.openAppLockSettings(context) }
                        )
                        
                        HorizontalDivider(color = Divider)
                        
                        // Step 5: MIUI Optimizations (Advanced)
                        SettingsStep(
                            stepNumber = 5,
                            title = "Disable MIUI Optimizations",
                            description = "Developer Options (Advanced users)",
                            icon = Icons.Default.Code,
                            onClick = { OemBackgroundHelper.openDeveloperOptions(context) }
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Help Link
                OutlinedButton(
                    onClick = { OemBackgroundHelper.openDontKillMyAppPage(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("More help on dontkillmyapp.com")
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SettingsStep(
    stepNumber: Int,
    title: String,
    description: String,
    icon: ImageVector,
    isCompleted: Boolean = false,
    showOpenButton: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Step number circle
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (isCompleted) TempoSuccess else TempoPrimary.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isCompleted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Completed",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text(
                    text = stepNumber.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Icon
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Text content
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
        
        // Open button
        if (showOpenButton && onClick != null) {
            TextButton(
                onClick = onClick,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = TempoPrimary
                )
            ) {
                Text("Open")
            }
        }
    }
}
