package me.avinas.tempo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import me.avinas.tempo.ui.theme.*

/**
 * Cohesive menu picker design tokens and components for Tempo.
 *
 * Replaces raw, unstyled [DropdownMenu] across all screens with a unified,
 * dark studio glassmorphic surface:
 * - 16dp rounded geometry
 * - Studio elevated gradient surface (TempoSurfaceRaised -> TempoSurfaceDialog)
 * - Hairline glass border (GlassBorderSoft)
 * - Tactile menu item touch targets with dedicated icon tiles and subtitle support
 * - Native selection indicator with [TempoIcons.Check]
 */
object TempoMenuTokens {
    val Radius = 16.dp
    val Shape = RoundedCornerShape(Radius)
    val BorderWidth = 1.dp
    val ShadowElevation = 20.dp
    val MinWidth = 200.dp
}

@Composable
fun TempoDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = offset,
        properties = properties,
        shape = TempoMenuTokens.Shape,
        containerColor = TempoSurfaceDialog,
        tonalElevation = 0.dp,
        shadowElevation = TempoMenuTokens.ShadowElevation,
        border = BorderStroke(TempoMenuTokens.BorderWidth, GlassBorderSoft),
        modifier = modifier
            .widthIn(min = TempoMenuTokens.MinWidth)
            .padding(vertical = 4.dp),
        content = content
    )
}

/**
 * Editorial menu item with icon badge, typography hierarchy, and selection indicator.
 */
@Composable
fun TempoDropdownMenuItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingIconTint: Color? = null,
    isSelected: Boolean = false,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    trailingText: String? = null
) {
    val primaryColor = when {
        !enabled -> TextQuaternary
        isDestructive -> TempoError
        isSelected -> TempoPrimary
        else -> TextPrimary
    }

    val iconColor = when {
        !enabled -> TextQuaternary
        isDestructive -> TempoError
        leadingIconTint != null -> leadingIconTint
        isSelected -> TempoPrimary
        else -> TextSecondary
    }

    val iconBgColor = when {
        isDestructive -> TempoError.copy(alpha = 0.08f)
        isSelected -> TempoPrimary.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.04f)
    }

    DropdownMenuItem(
        text = {
            Column(
                modifier = Modifier.padding(vertical = if (subtitle != null) 4.dp else 2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = primaryColor
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
        },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = if (leadingIcon != null) {
            {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconBgColor)
                        .border(
                            0.5.dp,
                            if (isSelected) TempoPrimary.copy(alpha = 0.18f) else GlassBorderSoft,
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        } else null,
        trailingIcon = {
            when {
                isSelected -> {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(TempoPrimary.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = TempoIcons.Check,
                            contentDescription = null,
                            tint = TempoPrimary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                trailingText != null -> {
                    Text(
                        text = trailingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
        },
        colors = MenuDefaults.itemColors(
            textColor = primaryColor,
            leadingIconColor = iconColor,
            trailingIconColor = if (isSelected) TempoPrimary else TextTertiary,
            disabledTextColor = TextQuaternary,
            disabledLeadingIconColor = TextQuaternary,
            disabledTrailingIconColor = TextQuaternary
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = modifier
    )
}

/**
 * Section kicker for categorizing menu options.
 */
@Composable
fun TempoMenuKicker(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextTertiary
) {
    Text(
        text = text.uppercase(),
        style = KickerSmall,
        color = color,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * Subtle hairline separator within menu surfaces.
 */
@Composable
fun TempoMenuDivider(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(1.dp)
            .background(GlassBorderSoft)
    )
}
