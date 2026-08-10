package me.avinas.tempo.ui.navigation

import me.avinas.tempo.ui.theme.TempoBackground
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoSurface
import me.avinas.tempo.ui.theme.Divider
import me.avinas.tempo.ui.theme.TextPrimary
import me.avinas.tempo.ui.theme.TextTertiary

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(TempoSurface)
            .border(
                width = 1.dp,
                color = Divider,
                shape = RoundedCornerShape(36.dp)
            )
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TempoNavItem(
                selected = currentDestination?.hierarchy?.any { it.route == Screen.Home.route } == true,
                onClick = onNavigateToHome,
                icon = Icons.Default.Home,
                unselectedIcon = Icons.Outlined.Home,
                label = "Home"
            )
            
            TempoNavItem(
                selected = currentDestination?.hierarchy?.any { it.route == Screen.Stats.route } == true,
                onClick = onNavigateToStats,
                icon = Icons.Default.BarChart,
                unselectedIcon = Icons.Outlined.BarChart,
                label = "Stats"
            )
            
            TempoNavItem(
                selected = currentDestination?.hierarchy?.any { it.route == Screen.History.route } == true,
                onClick = onNavigateToHistory,
                icon = Icons.Default.History,
                unselectedIcon = Icons.Outlined.History,
                label = "History"
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
    
    val selectedColor = TextPrimary
    val unselectedColor = TextTertiary
    
    val iconColor by animateColorAsState(
        targetValue = if (selected) selectedColor else unselectedColor,
        label = "iconColor"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.1f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(12.dp)
            .scale(scale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) icon else unselectedIcon,
            contentDescription = label,
            tint = iconColor,
            modifier = Modifier.size(26.dp)
        )
        
        if (selected) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(4.dp)
                    .background(TempoPrimary, CircleShape)
            )
        }
    }
}
