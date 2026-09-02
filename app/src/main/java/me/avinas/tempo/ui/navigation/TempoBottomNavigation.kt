package me.avinas.tempo.ui.navigation

import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.TempoDarkSurface
import me.avinas.tempo.ui.theme.TempoDarkSurfaceElevated
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextSecondary

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy

@Composable
fun TempoBottomNavigation(
    currentDestination: NavDestination?,
    onNavigateToHome: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(36.dp),
                ambientColor = Color.Transparent,
                spotColor = Color.Black.copy(alpha = 0.35f)
            )
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(
                // Vertical gradient fill
                Brush.verticalGradient(
                    colors = listOf(TempoDarkSurfaceElevated, TempoDarkSurface)
                )
            )
            .drawBehind {
                // Top border highlight line
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent)
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, 1.dp.toPx())
                )
            }
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.15f), // Increased from 0.05f for visibility
                        Color.White.copy(alpha = 0.05f)  // Increased from 0.02f
                    )
                ),
                shape = RoundedCornerShape(36.dp)
            )
    ) {
        // Glassmorphism overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.02f))
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TempoNavItem(
                selected = currentDestination?.hierarchy?.any { it.route == Screen.Home.route } == true,
                onClick = onNavigateToHome,
                icon = Icons.Rounded.Home,
                unselectedIcon = Icons.Outlined.Home,
                label = stringResource(R.string.nav_home)
            )
            
            TempoNavItem(
                selected = currentDestination?.hierarchy?.any { it.route == Screen.Stats.route } == true,
                onClick = onNavigateToStats,
                icon = Icons.Rounded.Leaderboard,
                unselectedIcon = Icons.Outlined.Leaderboard,
                label = stringResource(R.string.nav_stats)
            )
            
            TempoNavItem(
                selected = currentDestination?.hierarchy?.any { it.route == Screen.History.route } == true,
                onClick = onNavigateToHistory,
                icon = Icons.Rounded.History,
                unselectedIcon = Icons.Outlined.History,
                label = stringResource(R.string.nav_history)
            )
        }
    }
}

@Composable
private fun TempoNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    unselectedIcon: ImageVector,
    label: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val isSelected = selected

    val selectedColor = TextPrimary
    val unselectedColor = TextSecondary

    val iconColor by animateColorAsState(
        targetValue = if (selected) selectedColor else unselectedColor,
        label = "iconColor"
    )

    // Separate selection bounce and touch-press animations
    val selectionScale by animateFloatAsState(
        targetValue = if (selected) 1.1f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "selectionScale"
    )
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "pressScale"
    )

    // Selection indicator dot alpha transition
    val dotAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(150),
        label = "dotAlpha"
    )

    Column(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // Scale press feedback instead of ripple
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .semantics {
                role = Role.Tab
                this.selected = isSelected
            }
            .padding(12.dp)
            .scale(selectionScale * pressScale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            // Radial glow behind active icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer { alpha = dotAlpha }
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                selectedColor.copy(alpha = 0.3f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Icon(
                imageVector = if (selected) icon else unselectedIcon,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(26.dp)
            )
        }

        // Selection indicator dot
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(4.dp)
                .graphicsLayer { alpha = dotAlpha }
                .background(selectedColor, CircleShape)
        )
    }
}
