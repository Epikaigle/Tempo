package me.avinas.tempo.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import me.avinas.tempo.R
import me.avinas.tempo.utils.ShareUtils

/** Fixed design resolution for the share card. Both the hidden capture source and the
 * visible preview are laid out at this size so the captured bitmap is WYSIWYG with the
 * preview regardless of device density or screen aspect. */
private val CardDesignWidth = 360.dp
private val CardDesignHeight = 640.dp

/**
 * Generic Dialog to preview content and share it as an image.
 */
@Composable
fun SharePreviewDialog(
    onDismiss: () -> Unit,
    contentToShare: @Composable (theme: ShareTheme) -> Unit
) {
    val context = LocalContext.current
    val captureController = rememberCaptureController()
    val coroutineScope = rememberCoroutineScope()
    var isSharing by remember { mutableStateOf(false) }
    var theme by remember { mutableStateOf(ShareTheme.MIDNIGHT) }
    val shareFailedText = stringResource(R.string.share_failed)

    LaunchedEffect(Unit) {
        captureController.capturedBitmap.collect { bitmap ->
            isSharing = true
            val success = ShareUtils.shareBitmap(context, bitmap)
            isSharing = false
            if (!success) {
                Toast.makeText(context, shareFailedText, Toast.LENGTH_SHORT).show()
            } else {
                onDismiss() // Close dialog on successful share launch
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false, // Full screen width
            decorFitsSystemWindows = false
        )
    ) {
        // Root Container
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // 1. Hidden Capture Source (High Quality, WYSIWYG)
            // Rendered at the SAME fixed design size as the preview so the captured
            // bitmap's element proportions match exactly what the user sees, regardless
            // of device density or screen aspect. Invisible (alpha 0f) but laid out for
            // capture. requiredSize ignores parent constraints, so this stays stable in
            // portrait, landscape, and tablet modes.
            Box(
                modifier = Modifier
                    .requiredSize(CardDesignWidth, CardDesignHeight)
                    .alpha(0f),
                contentAlignment = Alignment.Center
            ) {
                CaptureWrapper(
                    controller = captureController,
                    modifier = Modifier.fillMaxSize()
                ) {
                    contentToShare(theme)
                }
            }

            // 2. Dark Overlay Background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
            )

            // 3. Visible UI (Preview + Controls)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(bottom = 16.dp) // Clean spacing with systemBarsPadding
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.details_share_preview),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                Color.White.copy(alpha = 0.12f),
                                androidx.compose.foundation.shape.CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.share_close),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Responsive Preview card using BoxWithConstraints.
                // We layout the card at its designed 360dp x 640dp resolution, and scale it
                // using graphicsLayer to fit within the available screen area.
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val scaleX = maxWidth / CardDesignWidth
                    val scaleY = maxHeight / CardDesignHeight
                    val scale = minOf(scaleX, scaleY).coerceAtMost(1f)
                    
                    val scaledWidth = CardDesignWidth * scale
                    val scaledHeight = CardDesignHeight * scale
                    val cardShape = RoundedCornerShape((20 * scale).dp)
                    
                    Box(
                        modifier = Modifier
                            .size(scaledWidth, scaledHeight)
                            .shadow(24.dp, cardShape, spotColor = Color.Black.copy(alpha = 0.7f), ambientColor = Color.Black.copy(alpha = 0.4f))
                            .clip(cardShape)
                            .border(1.dp, Color.White.copy(alpha = 0.18f), cardShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .requiredSize(CardDesignWidth, CardDesignHeight)
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                                )
                        ) {
                            contentToShare(theme)
                        }
                    }
                }

                // Theme picker with selected theme name indicator
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${stringResource(R.string.share_theme_label).uppercase()} • ${theme.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f),
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ShareTheme.entries.forEach { t ->
                            ThemeSwatch(
                                theme = t,
                                selected = theme == t,
                                onClick = { theme = t }
                            )
                        }
                    }
                }

                // Share Button
                Button(
                    onClick = {
                        if (!isSharing) {
                            captureController.capture()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(26.dp)
                ) {
                    if (isSharing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.spotlight_share_instagram),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
