package me.avinas.tempo.ui.spotlight.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoBackground
import me.avinas.tempo.ui.theme.TempoCyan
import me.avinas.tempo.ui.theme.TempoErrorDeep
import me.avinas.tempo.ui.theme.TempoInfoSoft
import me.avinas.tempo.ui.theme.TempoPrimaryMuted
import me.avinas.tempo.ui.theme.TempoSurfaceDialog
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.theme.TempoSurfaceSunken

/**
 * Bottom sheet for selecting canvas backgrounds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundPickerSheet(
    selectedBackground: CanvasBackground,
    onBackgroundSelected: (CanvasBackground) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TempoSurfaceSunken.copy(alpha = 0.95f),
        contentColor = Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Choose Background",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Gradient section
            Text(
                text = "GRADIENTS",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                letterSpacing = 2.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = CanvasBackground.gradients,
                    key = { it.id }
                ) { bg ->
                    BackgroundThumbnail(
                        background = bg,
                        isSelected = selectedBackground.id == bg.id,
                        onClick = { onBackgroundSelected(bg) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Solid section
            Text(
                text = "SOLID COLORS",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                letterSpacing = 2.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = CanvasBackground.solids,
                    key = { it.id }
                ) { bg ->
                    BackgroundThumbnail(
                        background = bg,
                        isSelected = selectedBackground.id == bg.id,
                        onClick = { onBackgroundSelected(bg) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BackgroundThumbnail(
    background: CanvasBackground,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgModifier = when (background.id) {
        "holographic" -> {
            Modifier.background(
                Brush.sweepGradient(
                    listOf(
                        Color(0xFFF9A8D4),
                        TempoAccent,
                        TempoCyan,
                        Color(0xFFF0ABFC),
                        Color(0xFFF9A8D4)
                    )
                )
            )
        }
        "midnight_grain" -> {
            Modifier.background(
                Brush.radialGradient(
                    colors = listOf(TempoSurfaceDialog, Color(0xFF020617)),
                    radius = 200f
                )
            )
        }
        "sunset_blur" -> {
            Modifier.background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF0D5C4A), TempoErrorDeep, TempoWarning)
                )
            )
        }
        "electric_void" -> {
            Modifier.background(
                Brush.radialGradient(
                    colors = listOf(TempoPrimaryMuted, Color(0xFF1D4ED8), TempoBackground),
                    radius = 150f
                )
            )
        }
        "neo_mint" -> {
            Modifier.background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F766E), Color(0xFF2DD4BF), Color(0xFF064E3B))
                )
            )
        }
        else -> {
            if (background.isGradient && background.colors != null) {
                Modifier.background(Brush.verticalGradient(background.colors))
            } else {
                Modifier.background(background.solidColor ?: Color.Black)
            }
        }
    }
    
    GlassCard(
        modifier = Modifier
            .size(70.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        variant = if (isSelected) GlassCardVariant.HighProminence else GlassCardVariant.LowProminence,
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(bgModifier),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .border(2.dp, TempoInfoSoft, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(TempoInfoSoft, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            } else {
                Text(
                    text = background.name.first().toString(),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
