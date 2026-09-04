package me.avinas.tempo.ui.onboarding

import me.avinas.tempo.ui.theme.TempoDarkBackground

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.theme.TempoRed
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TextOnAccent
import me.avinas.tempo.ui.utils.adaptiveSizeByCategory
import me.avinas.tempo.ui.utils.adaptiveTextUnitByCategory
import me.avinas.tempo.ui.utils.isSmallScreen
import me.avinas.tempo.ui.utils.rememberScreenHeightPercentage
import me.avinas.tempo.ui.utils.scaledSize
import me.avinas.tempo.ui.utils.rememberClampedHeightPercentage
import androidx.compose.ui.res.stringResource

@Composable
fun BatteryOptimizationScreen(
    onOptimize: () -> Unit,
    onSkip: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isOptimized by remember { mutableStateOf(false) }

    // Check optimization status
    LaunchedEffect(Unit) {
        isOptimized = isBatteryOptimizationDisabled(context)
        if (isOptimized) {
            onOptimize() // Auto-proceed if already done
        }
    }

    // Re-check when returning from settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (isBatteryOptimizationDisabled(context)) {
                    isOptimized = true
                    onOptimize()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    me.avinas.tempo.ui.components.DeepOceanBackground(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = adaptiveSizeByCategory(24.dp, 20.dp, 16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top flexible spacer
            Spacer(modifier = Modifier.weight(0.15f))
            
            // Hero Illustration enclosed in a square card matching Tempo aesthetic
            val heroArtCardSize = rememberClampedHeightPercentage(0.22f, 130.dp, 190.dp)
            GlassCard(
                modifier = Modifier.size(heroArtCardSize),
                shape = RoundedCornerShape(24.dp),
                backgroundColor = Color.White.copy(alpha = 0.06f),
                contentPadding = PaddingValues(0.dp),
                contentAlignment = Alignment.BottomCenter,
                fillMaxWidth = false
            ) {
                Image(
                    painter = painterResource(id = R.drawable.battery_vector),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.BottomCenter,
                    modifier = Modifier.size(heroArtCardSize)
                )
            }

            // Proportional spacing after hero
            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.045f)))

            Text(
                text = stringResource(R.string.battery_title),
                style = if (isSmallScreen()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White,
                fontSize = adaptiveTextUnitByCategory(24.sp, 22.sp, 20.sp),
                lineHeight = adaptiveTextUnitByCategory(32.sp, 28.sp, 26.sp)
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.02f)))

            Text(
                text = stringResource(R.string.battery_description),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f),
                lineHeight = adaptiveTextUnitByCategory(24.sp, 22.sp, 20.sp),
                fontSize = adaptiveTextUnitByCategory(16.sp, 15.sp, 14.sp)
            )

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.015f)))

            Text(
                text = "Uses <1% battery daily • No continuous polling",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = adaptiveTextUnitByCategory(13.sp, 12.sp, 11.sp)
            )
            // Flexible spacer between content and buttons
            Spacer(modifier = Modifier.weight(0.2f))

            Button(
                onClick = {
                    requestBatteryOptimizationExemption(context)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(scaledSize(54.dp, 0.85f, 1.1f)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TempoPrimary,
                    contentColor = TextOnAccent
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 4.dp
                )
            ) {
                Text(
                    text = stringResource(R.string.battery_optimize),
                    fontSize = adaptiveTextUnitByCategory(18.sp, 17.sp, 16.sp),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.015f)))

            TextButton(onClick = onSkip) {
                Text(
                    text = stringResource(R.string.battery_skip),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            
            // Bottom padding - proportional to screen
            Spacer(modifier = Modifier.height(rememberScreenHeightPercentage(0.03f)))
        }
    }
}

private fun isBatteryOptimizationDisabled(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestBatteryOptimizationExemption(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:${context.packageName}")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e2: Exception) {
            // Last resort
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${context.packageName}")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }
}
