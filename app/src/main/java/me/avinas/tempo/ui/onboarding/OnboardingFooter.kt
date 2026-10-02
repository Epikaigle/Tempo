package me.avinas.tempo.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize

/**
 * The one onboarding CTA spec — taken from WelcomeScreen.
 * Pinned bottom block: nav-bar padding comes from the screen's outer
 * container (never from here), so the button rect is identical on every
 * step and always clears the transparent 3-button nav. Nothing goes
 * below the button; secondary actions live in the top bar.
 */
@Composable
fun OnboardingFooter(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = adaptiveSizeByCategory(24.dp, 22.dp, 20.dp)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ponytail: press is 1:1 with the finger — scale + light haptic, same
        // idiom as premiumClickable / bottom nav. Elevation-only felt dead.
        val haptic = LocalHapticFeedback.current
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val pressScale by animateFloatAsState(
            if (pressed) 0.97f else 1f,
            tween(120, easing = FastOutSlowInEasing),
            label = "footer_press"
        )
        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
            enabled = enabled,
            interactionSource = interaction,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .height(scaledSize(54.dp, 0.85f, 1.1f)),
            colors = ButtonDefaults.buttonColors(
                containerColor = TempoPrimary,
                contentColor = TextOnAccent
            ),
            shape = RoundedCornerShape(18.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 8.dp,
                pressedElevation = 4.dp
            )
        ) {
            Text(
                text = text,
                fontSize = adaptiveTextUnitByCategory(18.sp, 17.sp, 16.sp),
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))
    }
}
