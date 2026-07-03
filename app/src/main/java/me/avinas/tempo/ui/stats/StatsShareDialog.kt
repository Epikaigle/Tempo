package me.avinas.tempo.ui.stats

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.ListeningOverview
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.components.CaptureWrapper
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.rememberCaptureController
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.utils.ShareUtils

private val CardDesignWidth = 360.dp
private val CardDesignHeight = 640.dp

@Composable
fun StatsShareDialog(
    tab: StatsTab,
    timeRange: TimeRange,
    items: List<Any>,
    overview: ListeningOverview?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val captureController = rememberCaptureController()
    var isSharing by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(StatsShareConfig()) }

    LaunchedEffect(Unit) {
        captureController.capturedBitmap.collect { bitmap ->
            isSharing = true
            val success = ShareUtils.shareBitmap(context, bitmap)
            isSharing = false
            if (!success) {
                Toast.makeText(context, "Failed to share image", Toast.LENGTH_SHORT).show()
            } else {
                onDismiss()
            }
        }
    }

    val contentToShare: @Composable () -> Unit = {
        StatsShareCard(
            tab = tab,
            timeRange = timeRange,
            items = items,
            overview = overview,
            config = config,
            modifier = Modifier.fillMaxSize()
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 1. Hidden capture source — rendered at the SAME fixed design size as the
            //    preview to guarantee WYSIWYG. The bitmap is captured at the device's
            //    pixel density (e.g. 1080x1920 at 3x), perfectly matching the preview.
            Box(
                modifier = Modifier
                    .requiredSize(CardDesignWidth, CardDesignHeight)
                    .alpha(0f),
                contentAlignment = Alignment.Center
            ) {
                CaptureWrapper(controller = captureController, modifier = Modifier.fillMaxSize()) {
                    contentToShare()
                }
            }

            // 2. Dark overlay
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)))

            // 3. Visible UI
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().systemBarsPadding().padding(bottom = 24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.stats_share_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(Color.White.copy(alpha = 0.1f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.stats_share_close), tint = Color.White)
                    }
                }

                // Preview — laid out at the design size and scaled to fit
                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val scale = minOf(maxWidth / CardDesignWidth, maxHeight / CardDesignHeight).coerceAtMost(1f)
                    val scaledWidth = CardDesignWidth * scale
                    val scaledHeight = CardDesignHeight * scale
                    Box(modifier = Modifier.size(scaledWidth, scaledHeight), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .requiredSize(CardDesignWidth, CardDesignHeight)
                                .graphicsLayer(scaleX = scale, scaleY = scale, transformOrigin = TransformOrigin(0.5f, 0.5f))
                        ) {
                            contentToShare()
                        }
                    }
                }

                // Config controls
                ConfigPanel(
                    config = config,
                    onConfigChange = { config = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                )

                // Share button
                Button(
                    onClick = { if (!isSharing) captureController.capture() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    if (isSharing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.stats_share_button),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigPanel(
    config: StatsShareConfig,
    onConfigChange: (StatsShareConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier,
        backgroundColor = Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Row 1: Layout + Items
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConfigLabel(text = stringResource(R.string.stats_share_layout_label))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LayoutToggle(
                            icon = Icons.Default.ViewList,
                            contentDescription = stringResource(R.string.stats_share_layout_list),
                            selected = config.layout == StatsShareLayout.LIST,
                            onClick = { onConfigChange(config.copy(layout = StatsShareLayout.LIST)) }
                        )
                        LayoutToggle(
                            icon = Icons.Default.EmojiEvents,
                            contentDescription = stringResource(R.string.stats_share_layout_podium),
                            selected = config.layout == StatsShareLayout.PODIUM,
                            onClick = { onConfigChange(config.copy(layout = StatsShareLayout.PODIUM)) }
                        )
                        LayoutToggle(
                            icon = Icons.Default.GridView,
                            contentDescription = stringResource(R.string.stats_share_layout_grid),
                            selected = config.layout == StatsShareLayout.GRID,
                            onClick = { onConfigChange(config.copy(layout = StatsShareLayout.GRID)) }
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConfigLabel(text = stringResource(R.string.stats_share_count_label))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatsShareCount.entries.forEach { c ->
                            CountToggle(
                                text = c.count.toString(),
                                selected = config.count == c,
                                onClick = { onConfigChange(config.copy(count = c)) }
                            )
                        }
                    }
                }
            }
            // Row 2: Theme + Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConfigLabel(text = stringResource(R.string.stats_share_theme_label))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatsShareTheme.entries.forEach { t ->
                            ThemeSwatch(
                                theme = t,
                                selected = config.theme == t,
                                onClick = { onConfigChange(config.copy(theme = t)) }
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ConfigLabel(text = stringResource(R.string.stats_share_summary))
                    SummaryToggle(
                        enabled = config.showSummary,
                        onToggle = { onConfigChange(config.copy(showSummary = it)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = 0.55f),
        letterSpacing = 1.2.sp
    )
}

@Composable
private fun LayoutToggle(icon: ImageVector, contentDescription: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) TempoRed else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun CountToggle(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) TempoRed else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun ThemeSwatch(theme: StatsShareTheme, selected: Boolean, onClick: () -> Unit) {
    val palette = theme.palette
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(palette.gradient))
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.15f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(modifier = Modifier.size(6.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun SummaryToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) TempoRed else Color.White.copy(alpha = 0.08f))
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = if (enabled) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = stringResource(if (enabled) R.string.stats_share_on else R.string.stats_share_off),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.7f)
        )
    }
}
