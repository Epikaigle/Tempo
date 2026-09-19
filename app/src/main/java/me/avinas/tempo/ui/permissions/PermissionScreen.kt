package me.avinas.tempo.ui.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.avinas.tempo.R
import me.avinas.tempo.service.MusicTrackingService
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.onboarding.AnimatedOpacity
import me.avinas.tempo.ui.onboarding.MockupStage
import me.avinas.tempo.ui.onboarding.NotificationAccessMockup
import me.avinas.tempo.ui.onboarding.OnboardingFooter
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.isSmallScreen

/**
 * Permission setup screen that guides users through granting Notification Listener access.
 */
@Composable
fun PermissionScreen(
    onPermissionGranted: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationListenerGranted by remember { mutableStateOf(false) }

    // Check permission states
    LaunchedEffect(Unit) {
        notificationListenerGranted = isNotificationListenerEnabled(context)
        if (notificationListenerGranted) {
            onPermissionGranted() // Auto-proceed if already granted
        }
    }

    // Re-check when window regains focus (user returns from settings)
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (isNotificationListenerEnabled(context)) {
                        notificationListenerGranted = true
                        onPermissionGranted()
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    me.avinas.tempo.ui.components.DeepOceanBackground(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top skip bar — keeps the footer to the shared button-only spec.
            val haptic = LocalHapticFeedback.current
            // ponytail: same rise+fade stagger as Battery — the two ask-screens
            // rhyme so the second ask feels expected, not heavier.
            val entered = remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { entered.value = true }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = adaptiveSizeByCategory(16.dp, 14.dp, 12.dp),
                        vertical = 4.dp
                    ),
                contentAlignment = Alignment.CenterEnd
            ) {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSkip()
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.perm_do_later),
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Scrollable explainer content
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = adaptiveSizeByCategory(24.dp, 20.dp, 16.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(12.dp))

                AnimatedOpacity(delay = 100, visible = entered.value) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.perm_one_needed),
                            style = if (isSmallScreen()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = Color.White,
                            fontSize = adaptiveTextUnitByCategory(24.sp, 22.sp, 20.sp),
                            lineHeight = adaptiveTextUnitByCategory(32.sp, 28.sp, 26.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.perm_how_tempo_sees),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = Color.White.copy(alpha = 0.7f),
                            lineHeight = adaptiveTextUnitByCategory(24.sp, 22.sp, 20.sp),
                            fontSize = adaptiveTextUnitByCategory(16.sp, 15.sp, 14.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(adaptiveSizeByCategory(24.dp, 20.dp, 16.dp)))

                // Staged preview of the exact system screen awaiting the
                // user — tapping it opens Settings, same as the button below.
                AnimatedOpacity(delay = 250, visible = entered.value) {
                    MockupStage(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            openNotificationListenerSettings(context)
                        },
                    ) {
                        NotificationAccessMockup()
                    }
                }

                Spacer(modifier = Modifier.height(adaptiveSizeByCategory(24.dp, 20.dp, 16.dp)))

                // Comparison row — one shared card so both sides align exactly.
                AnimatedOpacity(delay = 400, visible = entered.value) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            PermissionFactCard(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                icon = Icons.Default.Check,
                                accent = Color(0xFF22C55E),
                                title = stringResource(R.string.perm_music_only),
                                body = stringResource(R.string.perm_music_apps),
                                bodyColor = Color.White.copy(alpha = 0.9f),
                            )
                            PermissionFactCard(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                icon = Icons.Default.Close,
                                accent = Color(0xFFEF4444),
                                title = stringResource(R.string.perm_private_data),
                                body = stringResource(R.string.perm_we_ignore),
                                bodyColor = Color.White.copy(alpha = 0.6f),
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        // Reassurance lives with the explainer now — the footer is
                        // button-only so the CTA rect matches every other step.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.perm_runs_efficient),
                                textAlign = TextAlign.Center,
                                fontSize = 12.sp,
                                color = Color.Gray,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // Pinned CTA — same rect as every other step, never scrolled away.
            OnboardingFooter(
                text = stringResource(R.string.perm_enable_access),
                onClick = { openNotificationListenerSettings(context) }
            )
        }
    }
}

/**
 * One side of the music-vs-private comparison. Shared by both cards so
 * icon, label and body align exactly — centered text needs fillMaxWidth
 * to center every line, not just the text block.
 */
@Composable
private fun PermissionFactCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    accent: Color,
    title: String,
    body: String,
    bodyColor: Color,
) {
    GlassCard(
        modifier = modifier,
        backgroundColor = accent.copy(alpha = 0.1f),
        contentPadding = PaddingValues(adaptiveSizeByCategory(16.dp, 14.dp, 12.dp)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .background(accent.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = bodyColor,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun isNotificationListenerEnabled(context: Context): Boolean {
    val packageName = context.packageName
    val flat =
        Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        )

    if (flat.isNullOrEmpty()) return false

    val componentName = ComponentName(context, MusicTrackingService::class.java)
    return flat.contains(componentName.flattenToString()) || flat.contains(packageName)
}

private fun openNotificationListenerSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback to app settings
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.parse("package:${context.packageName}")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}
