package me.avinas.tempo.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.TempoWarning

@Composable
fun XiaomiGuidancePopup(
    onDismiss: () -> Unit,
    onConfigure: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) { visible = true }

    fun dismissAnimated(action: () -> Unit) {
        visible = false
        coroutineScope.launch {
            delay(200)
            action()
        }
    }

    Dialog(
        onDismissRequest = { dismissAnimated(onDismiss) },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(180))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = visible,
                    enter = scaleIn(
                        initialScale = 0.92f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ) + fadeIn(animationSpec = tween(250)),
                    exit = scaleOut(targetScale = 0.92f, animationSpec = tween(180)) +
                            fadeOut(animationSpec = tween(180))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(horizontal = 24.dp)
                    ) {
                        TempoDialogSurface {
                            Spacer(modifier = Modifier.height(8.dp))

                            TempoDialogIcon(
                                icon = Icons.Default.Warning,
                                tint = TempoWarning,
                                size = 56
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            TempoDialogTitle(
                                text = stringResource(R.string.xiaomi_guidance_title)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            TempoDialogBody(
                                text = stringResource(R.string.xiaomi_guidance_desc)
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            TempoDialogPrimaryButton(
                                text = stringResource(R.string.xiaomi_guidance_configure),
                                onClick = { dismissAnimated(onConfigure) },
                                icon = Icons.Default.Settings
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            TempoDialogSecondaryButton(
                                text = stringResource(R.string.xiaomi_guidance_later),
                                onClick = { dismissAnimated(onDismiss) }
                            )
                        }
                    }
                }
            }
        }
    }
}
