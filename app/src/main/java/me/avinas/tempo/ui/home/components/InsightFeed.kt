package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.*
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import me.avinas.tempo.data.stats.InsightCardData
import me.avinas.tempo.data.stats.InsightType
import me.avinas.tempo.data.stats.InsightPayload
import me.avinas.tempo.data.stats.DiscoveryTrend
import me.avinas.tempo.data.stats.HourlyDistribution
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.ContentScale
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.FrostedIconButton
import me.avinas.tempo.ui.theme.*


@Composable
fun ConstellationWeb(
    insights: List<InsightCardData>,
    selectedType: InsightType?,
    onTypeSelected: (InsightType?) -> Unit,
    tempoBpm: Float = 100f,
    modifier: Modifier = Modifier
) {
    val categories = remember(insights) {
        insights.map { it.type }.distinct().take(6)
    }
    
    if (categories.isEmpty()) return

    val orbitAngle = 0f
    val pulseAnim = 1f
    val packetFraction = 0f

    // Snap offsets for interactive drag snapping
    val nodeOffsets = remember(categories) {
        categories.associateWith { Animatable(Offset.Zero, Offset.VectorConverter) }
    }

    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        val angles = remember(categories.size) {
            categories.mapIndexed { index, _ ->
                (index * (2f * Math.PI) / categories.size)
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val w = maxWidth
            val h = maxHeight
            val cx = w / 2
            val cy = h / 2
            val radius = (minOf(w, h) / 2) * 0.65f
            val localDensity = androidx.compose.ui.platform.LocalDensity.current
            val nodeRadiusPx = remember(localDensity) { with(localDensity) { 24.dp.toPx() } }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val cwPx = size.width
                val chPx = size.height
                val cxPx = cwPx / 2
                val cyPx = chPx / 2
                val radiusPx = (minOf(cwPx, chPx) / 2) * 0.65f

                // Web lines
                for (r in listOf(0.4f, 0.75f, 1.0f)) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.02f),
                        radius = radiusPx * r,
                        center = Offset(cxPx, cyPx),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }

                // Draw central throb Vibe Core
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(InsightDanceability.copy(alpha = 0.35f), Color.Transparent),
                        center = Offset(cxPx, cyPx),
                        radius = 32.dp.toPx() * pulseAnim
                    ),
                    center = Offset(cxPx, cyPx),
                    radius = 32.dp.toPx() * pulseAnim
                )
                drawCircle(
                    color = Color.White,
                    radius = 8.dp.toPx(),
                    center = Offset(cxPx, cyPx)
                )
                drawCircle(
                    color = InsightDanceability,
                    radius = 5.dp.toPx(),
                    center = Offset(cxPx, cyPx)
                )

                // Render dynamic elastic connectors
                val resolvedPositions = categories.mapIndexed { index, type ->
                    val angle = angles[index] + orbitAngle.toDouble()
                    val baseOffset = Offset(
                        x = (cxPx + radiusPx * kotlin.math.cos(angle)).toFloat(),
                        y = (cyPx + radiusPx * kotlin.math.sin(angle)).toFloat()
                    )
                    val dragOffsetPx = Offset(
                        x = nodeOffsets[type]?.value?.x?.dp?.toPx() ?: 0f,
                        y = nodeOffsets[type]?.value?.y?.dp?.toPx() ?: 0f
                    )
                    baseOffset + dragOffsetPx
                }

                for (i in resolvedPositions.indices) {
                    val p1 = resolvedPositions[i]
                    val p2 = resolvedPositions[(i + 1) % resolvedPositions.size]
                    
                    drawLine(
                        color = InsightDanceability.copy(alpha = 0.18f),
                        start = p1,
                        end = p2,
                        strokeWidth = 1.5.dp.toPx()
                    )

                    // Draw radial spokes from center Vibe Core to nodes
                    val isSelectedNode = selectedType == categories[i]
                    val connectorColor = if (isSelectedNode) {
                        val nodeColor = when(categories[i]) {
                            InsightType.MOOD -> me.avinas.tempo.ui.theme.InsightMood
                            InsightType.PEAK_TIME -> me.avinas.tempo.ui.theme.InsightPeakTime
                            InsightType.BINGE -> me.avinas.tempo.ui.theme.InsightBinge
                            InsightType.DISCOVERY -> me.avinas.tempo.ui.theme.InsightDiscovery
                            InsightType.ENERGY -> me.avinas.tempo.ui.theme.InsightEnergy
                            InsightType.DANCEABILITY -> me.avinas.tempo.ui.theme.InsightDanceability
                            InsightType.TEMPO -> me.avinas.tempo.ui.theme.InsightTempo
                            InsightType.ACOUSTICNESS -> me.avinas.tempo.ui.theme.InsightAcousticness
                            InsightType.STREAK -> me.avinas.tempo.ui.theme.InsightStreak
                            InsightType.GENRE -> me.avinas.tempo.ui.theme.InsightGenre
                            InsightType.ENGAGEMENT -> me.avinas.tempo.ui.theme.InsightEngagement
                            else -> Color.Gray
                        }
                        nodeColor.copy(alpha = 0.4f)
                    } else {
                        Color.White.copy(alpha = 0.04f)
                    }
                    val spokeWidth = if (isSelectedNode) 2.dp.toPx() else 1.dp.toPx()

                    drawLine(
                        color = connectorColor,
                        start = Offset(cxPx, cyPx),
                        end = p1,
                        strokeWidth = spokeWidth
                    )
                }

                // If a node is selected, render traveling packet
                selectedType?.let { type ->
                    val selIndex = categories.indexOf(type)
                    if (selIndex != -1 && selIndex < resolvedPositions.size) {
                        val targetPos = resolvedPositions[selIndex]
                        val startPos = Offset(cxPx, cyPx)
                        val packetX = startPos.x + (targetPos.x - startPos.x) * packetFraction
                        val packetY = startPos.y + (targetPos.y - startPos.y) * packetFraction
                        val packetPos = Offset(packetX, packetY)

                        val nodeColor = when(type) {
                            InsightType.MOOD -> me.avinas.tempo.ui.theme.InsightMood
                            InsightType.PEAK_TIME -> me.avinas.tempo.ui.theme.InsightPeakTime
                            InsightType.BINGE -> me.avinas.tempo.ui.theme.InsightBinge
                            InsightType.DISCOVERY -> me.avinas.tempo.ui.theme.InsightDiscovery
                            InsightType.ENERGY -> me.avinas.tempo.ui.theme.InsightEnergy
                            InsightType.DANCEABILITY -> me.avinas.tempo.ui.theme.InsightDanceability
                            InsightType.TEMPO -> me.avinas.tempo.ui.theme.InsightTempo
                            InsightType.ACOUSTICNESS -> me.avinas.tempo.ui.theme.InsightAcousticness
                            InsightType.STREAK -> me.avinas.tempo.ui.theme.InsightStreak
                            InsightType.GENRE -> me.avinas.tempo.ui.theme.InsightGenre
                            InsightType.ENGAGEMENT -> me.avinas.tempo.ui.theme.InsightEngagement
                            else -> Color.Gray
                        }

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(nodeColor.copy(alpha = 0.8f), Color.Transparent),
                                center = packetPos,
                                radius = 14.dp.toPx()
                            ),
                            center = packetPos,
                            radius = 14.dp.toPx()
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 3.5.dp.toPx(),
                            center = packetPos
                        )
                    }
                }
            }

            // Interactive node targets placed at matching coordinates
            categories.forEachIndexed { index, type ->
                val angle = angles[index] + orbitAngle.toDouble()
                val nodeCenterX = cx + radius * kotlin.math.cos(angle).toFloat()
                val nodeCenterY = cy + radius * kotlin.math.sin(angle).toFloat()

                val dragOffset = nodeOffsets[type]?.value ?: Offset.Zero
                val isSelected = selectedType == type

                val nodeColor = when(type) {
                    InsightType.MOOD -> me.avinas.tempo.ui.theme.InsightMood
                    InsightType.PEAK_TIME -> me.avinas.tempo.ui.theme.InsightPeakTime
                    InsightType.BINGE -> me.avinas.tempo.ui.theme.InsightBinge
                    InsightType.DISCOVERY -> me.avinas.tempo.ui.theme.InsightDiscovery
                    InsightType.ENERGY -> me.avinas.tempo.ui.theme.InsightEnergy
                    InsightType.DANCEABILITY -> me.avinas.tempo.ui.theme.InsightDanceability
                    InsightType.TEMPO -> me.avinas.tempo.ui.theme.InsightTempo
                    InsightType.ACOUSTICNESS -> me.avinas.tempo.ui.theme.InsightAcousticness
                    InsightType.STREAK -> me.avinas.tempo.ui.theme.InsightStreak
                    InsightType.GENRE -> me.avinas.tempo.ui.theme.InsightGenre
                    InsightType.ENGAGEMENT -> me.avinas.tempo.ui.theme.InsightEngagement
                    else -> Color.Gray
                }

                Column(
                    modifier = Modifier
                        .offset(
                            x = nodeCenterX - 40.dp + dragOffset.x.dp,
                            y = nodeCenterY - 36.dp + dragOffset.y.dp
                        )
                        .width(80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = type.name.replace("_", " "),
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = if (isSelected) Color.White else nodeColor.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(nodeColor.copy(alpha = if (isSelected) 0.4f else 0.15f), Color.Transparent),
                                    radius = nodeRadiusPx
                                )
                            )
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) Color.White else nodeColor.copy(alpha = 0.5f),
                                shape = CircleShape
                            )
                            .pointerInput(type) {
                                detectDragGestures(
                                    onDragStart = {
                                        onTypeSelected(type)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        coroutineScope.launch {
                                            val newX = dragOffset.x + dragAmount.x.toDp().value
                                            val newY = dragOffset.y + dragAmount.y.toDp().value
                                            nodeOffsets[type]?.snapTo(Offset(newX, newY))
                                        }
                                    },
                                    onDragEnd = {
                                        coroutineScope.launch {
                                            nodeOffsets[type]?.animateTo(
                                                targetValue = Offset.Zero,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                    stiffness = Spring.StiffnessMedium
                                                )
                                            )
                                        }
                                    },
                                    onDragCancel = {
                                        coroutineScope.launch {
                                            nodeOffsets[type]?.animateTo(
                                                targetValue = Offset.Zero,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                    stiffness = Spring.StiffnessMedium
                                                )
                                            )
                                        }
                                    }
                                )
                            }
                            .clickable {
                                if (selectedType == type) onTypeSelected(null) else onTypeSelected(type)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val icon = when(type) {
                            InsightType.MOOD -> Icons.Filled.Face
                            InsightType.PEAK_TIME -> Icons.Filled.DateRange
                            InsightType.BINGE -> Icons.Filled.Bolt
                            InsightType.DISCOVERY -> Icons.Filled.Celebration
                            InsightType.ENERGY -> Icons.Filled.Bolt
                            InsightType.DANCEABILITY -> Icons.Filled.Celebration
                            InsightType.TEMPO -> Icons.Filled.Speed
                            InsightType.ACOUSTICNESS -> Icons.Filled.Piano
                            InsightType.STREAK -> Icons.Filled.LocalFireDepartment
                            InsightType.GENRE -> Icons.AutoMirrored.Filled.QueueMusic
                            InsightType.ENGAGEMENT -> Icons.Filled.Favorite
                            else -> Icons.Filled.Settings
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = type.name,
                            tint = if (isSelected) Color.White else nodeColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Text(
            text = if (selectedType == null) stringResource(R.string.constellation_hint) else stringResource(R.string.constellation_active, selectedType.name.replace("_", " ")),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            letterSpacing = 1.2.sp
        )
    }
}



@Composable
fun InsightFeed(
    insights: List<InsightCardData>,
    onNavigateToTrack: (Long) -> Unit,
    onNavigateToArtist: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val nonGamification = remember(insights) {
        insights.filter { it.payload !is InsightPayload.GamificationProgress }
    }

    var selectedType by remember(nonGamification) {
        mutableStateOf<InsightType?>(nonGamification.firstOrNull()?.type)
    }

    val selectedInsight = remember(nonGamification, selectedType) {
        nonGamification.find { it.type == selectedType }
    }

    val avgTempo = remember(nonGamification) {
        val tempoInsight = nonGamification.find { it.type == InsightType.TEMPO }
        val payload = tempoInsight?.payload
        if (payload is InsightPayload.TempoValue) payload.bpm else 100f
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ConstellationWeb(
            insights = nonGamification,
            selectedType = selectedType,
            onTypeSelected = { selectedType = it },
            tempoBpm = avgTempo
        )

        Crossfade(
            targetState = selectedInsight,
            animationSpec = tween(450, easing = FastOutSlowInEasing),
            label = "deck_transition"
        ) { insight ->
            if (insight != null) {
                InsightCard(
                    insight = insight,
                    selectedType = selectedType,
                    onCloseClick = { selectedType = null },
                    onClick = {},
                    onNavigateToArtist = onNavigateToArtist,
                    onNavigateToTrack = onNavigateToTrack
                )
            }
        }
    }
}

@Composable
fun VibeHeader(
    energy: Float,
    valence: Float,
    userName: String,
    profileImagePath: String? = null,
    isNewUser: Boolean = false,
    userLevel: Int? = null,
    levelProgress: Float = 0f,
    levelTitle: String? = null,
    isGamificationEnabled: Boolean = true,
    onLevelClick: () -> Unit = {},
    onSettingsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
    ) {
        val avatarSize = 48.dp
        val rowPaddingH = 14.dp
        val rowPaddingV = 10.dp
        val itemSpacing = 12.dp
        val nameTextStyle = MaterialTheme.typography.titleMedium
        val levelNumStyle = MaterialTheme.typography.titleSmall
        val titleStyle = MaterialTheme.typography.labelMedium

        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Premium Opaque Card for Profile - Minimalist Pill Edition
            Surface(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickable(
                        enabled = isGamificationEnabled,
                        onClick = onLevelClick
                    )
                    .defaultMinSize(minHeight = if (!levelTitle.isNullOrBlank()) 72.dp else 64.dp)
                    .shadow(
                        elevation = 10.dp,
                        shape = RoundedCornerShape(100.dp),
                        spotColor = TempoDarkSurfaceSunken,
                        ambientColor = TempoDarkSurfaceSunken.copy(alpha = 0.8f)
                    )
                    .border(width = 1.dp, color = me.avinas.tempo.ui.theme.PillBorder, shape = RoundedCornerShape(100.dp)),
                color = me.avinas.tempo.ui.theme.PillSurface,
                shape = RoundedCornerShape(100.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = rowPaddingH, vertical = rowPaddingV),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                ) {
                    // Left: Adaptive Level Ring + Avatar
                    Box(
                        modifier = Modifier.size(avatarSize),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isGamificationEnabled && userLevel != null) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokeWidth = 3.dp.toPx()
                                val radius = (size.minDimension - strokeWidth) / 2
                                val topLeft = Offset(
                                    (size.width - radius * 2) / 2,
                                    (size.height - radius * 2) / 2
                                )
                                val arcSize = Size(radius * 2, radius * 2)
                                
                                drawArc(
                                    color = me.avinas.tempo.ui.theme.PillInnerSurface,
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        listOf(
                                            me.avinas.tempo.ui.theme.LevelRingSweepStart,
                                            me.avinas.tempo.ui.theme.LevelRingSweepMid,
                                            me.avinas.tempo.ui.theme.LevelRingSweepEnd,
                                            me.avinas.tempo.ui.theme.LevelRingSweepStart
                                        )
                                    ),
                                    startAngle = -90f,
                                    sweepAngle = 360f * levelProgress,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }
                        }
                        
                        // Avatar image or text letter inside (with a small margin so it sits inside the ring)
                        val innerAvatarSize = if (isGamificationEnabled && userLevel != null) avatarSize - 8.dp else avatarSize
                        Box(
                            modifier = Modifier
                                .size(innerAvatarSize)
                                .clip(CircleShape)
                                .background(me.avinas.tempo.ui.theme.PillInnerSurface)
                                .border(1.dp, me.avinas.tempo.ui.theme.PillBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (profileImagePath.isNullOrBlank()) {
                                Text(
                                    text = userName.firstOrNull()?.toString()?.uppercase() ?: "U",
                                    style = levelNumStyle.copy(lineHeight = levelNumStyle.fontSize * 1.1),
                                    fontWeight = FontWeight.Black,
                                    color = me.avinas.tempo.ui.theme.PillTextPrimary,
                                    maxLines = 1
                                )
                            } else {
                                CachedAsyncImage(
                                    imageUrl = profileImagePath,
                                    contentDescription = "Profile image",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .widthIn(min = 100.dp, max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(if (levelTitle.isNullOrBlank()) 0.dp else 2.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ResponsiveText(
                                text = userName,
                                style = nameTextStyle.copy(lineHeight = nameTextStyle.fontSize * 1.1f),
                                modifier = Modifier,
                                fontWeight = FontWeight.Bold,
                                color = me.avinas.tempo.ui.theme.PillTextPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isGamificationEnabled) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = TempoPop.copy(alpha = 0.08f),
                                    tonalElevation = 0.dp,
                                    shadowElevation = 0.dp
                                ) {
                                    Text(
                                        text = "LVL ${userLevel ?: 0}",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Black,
                                        color = TempoPop,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        if (isGamificationEnabled && !levelTitle.isNullOrBlank()) {
                            ResponsiveText(
                                text = levelTitle.uppercase(),
                                style = titleStyle.copy(letterSpacing = 0.9.sp, lineHeight = titleStyle.fontSize * 1.15f),
                                color = InsightBinge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (onSettingsClick != null) {
                FrostedIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.home_settings),
                    onClick = onSettingsClick
                )
            }
        }
    }
}

/**
 * Visualizes audio valence and energy metrics with animated wave bars and level gauges.
 */
@Composable
private fun MoodVisualizer(valence: Float, energy: Float, color: Color) {
    val safeEnergy = energy.coerceIn(0f, 1f)
    val safeValence = valence.coerceIn(0f, 1f)
    val energyPercent = (safeEnergy * 100).toInt()
    val vibePercent = (safeValence * 100).toInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Living Soundwave: 16 dynamic wave bars whose amplitude and motion reflect energy
        val barCount = 16
        val infiniteTransition = rememberInfiniteTransition(label = "mood_soundwave")
        val baseSpeed = (1200 - (safeEnergy * 600)).toInt().coerceAtLeast(350)
        val animations = (0 until barCount).map { index ->
            val factor = kotlin.math.sin(index * Math.PI / (barCount - 1)).toFloat().coerceAtLeast(0.25f)
            val duration = (baseSpeed + (index * 45) % 300).coerceAtLeast(200)
            infiniteTransition.animateFloat(
                initialValue = 0.15f * factor,
                targetValue = (0.35f + safeEnergy * 0.65f) * factor,
                animationSpec = infiniteRepeatable(
                    animation = tween(duration, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "mood_bar_$index"
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(TempoDarkSurfaceSunken.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            animations.forEach { anim ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleY = anim.value.coerceIn(0.1f, 1f)
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                        }
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(color, color.copy(alpha = 0.25f))
                            ),
                            shape = RoundedCornerShape(100.dp)
                        )
                )
            }
        }

        // Calibrated Dual-Metric Meters (Energy & Vibe Positivity) - Two responsive columns
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Energy Balance Cell
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(0.6.dp, GlassBorderSoft, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ENERGY",
                        style = KickerSmall,
                        color = TextTertiary
                    )
                    Text(
                        text = "$energyPercent%",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = color
                    )
                }
                // Calibration track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(safeEnergy)
                            .fillMaxHeight()
                            .background(color, RoundedCornerShape(100.dp))
                    )
                }
            }

            // Vibe Positivity Cell
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(0.6.dp, GlassBorderSoft, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (safeValence >= 0.5f) "UPBEAT" else "MELLOW",
                        style = KickerSmall,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$vibePercent%",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = DisplayFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary
                    )
                }
                // Calibration track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(safeValence)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), Color.White)),
                                RoundedCornerShape(100.dp)
                            )
                    )
                }
            }
        }
    }
}

/**
 * 24-hour listening curve area chart highlighting peak listening hour.
 * Generates a bell-curve falloff around the peak if distribution data is sparse.
 */
@Composable
private fun PeakTimeVisualizer(peakHour: Int, hourlyDistribution: List<HourlyDistribution>, color: Color) {
    val safePeak = peakHour.coerceIn(0, 23)
    val formattedPeak = remember(safePeak) {
        when {
            safePeak == 0 -> "12 AM"
            safePeak < 12 -> "$safePeak AM"
            safePeak == 12 -> "12 PM"
            else -> "${safePeak - 12} PM"
        }
    }

    // Populate a 24-point array (hours 0..23) so the curve spans the entire day.
    // If sparse (<2 non-zero points), generates a natural Gaussian falloff around safePeak
    val hourlyPlays = remember(hourlyDistribution, safePeak) {
        val array = FloatArray(24) { 0f }
        hourlyDistribution.forEach { item ->
            if (item.hour in 0..23) {
                array[item.hour] = item.playCount.toFloat()
            }
        }
        val nonZero = array.count { it > 0f }
        if (nonZero < 2) {
            for (h in 0..23) {
                val diff = kotlin.math.min(kotlin.math.abs(h - safePeak), 24 - kotlin.math.abs(h - safePeak))
                val bell = kotlin.math.exp(-(diff * diff) / 20.0).toFloat()
                array[h] = 0.08f + 0.92f * bell
            }
        }
        array
    }
    val maxHourlyPlay = remember(hourlyPlays) {
        hourlyPlays.maxOrNull()?.coerceAtLeast(0.01f) ?: 1f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Smooth Cubic Bézier Curve with Gradient Fill & Glowing Peak Point
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val pointsCount = 24
                val stepX = w / (pointsCount - 1)

                val path = Path()
                val fillPath = Path()

                for (i in 0 until pointsCount) {
                    val x = i * stepX
                    val ratio = (hourlyPlays[i] / maxHourlyPlay).coerceIn(0f, 1f)
                    val y = h - (ratio * h * 0.72f) - (h * 0.08f)

                    if (i == 0) {
                        path.moveTo(x, y)
                        fillPath.moveTo(x, h)
                        fillPath.lineTo(x, y)
                    } else {
                        val prevX = (i - 1) * stepX
                        val prevRatio = (hourlyPlays[i - 1] / maxHourlyPlay).coerceIn(0f, 1f)
                        val prevY = h - (prevRatio * h * 0.72f) - (h * 0.08f)

                        val controlX1 = prevX + (stepX / 2f)
                        val controlY1 = prevY
                        val controlX2 = prevX + (stepX / 2f)
                        val controlY2 = y

                        path.cubicTo(controlX1, controlY1, controlX2, controlY2, x, y)
                        fillPath.cubicTo(controlX1, controlY1, controlX2, controlY2, x, y)
                    }

                    if (i == pointsCount - 1) {
                        fillPath.lineTo(x, h)
                        fillPath.close()
                    }
                }

                // Area gradient fill below the curve
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = 0.35f), Color.Transparent),
                        startY = 0f,
                        endY = h
                    )
                )

                // Smooth curve stroke
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                // Vertical dashed guide line from x-axis to the peak point
                val peakX = safePeak * stepX
                val peakRatio = (hourlyPlays[safePeak] / maxHourlyPlay).coerceIn(0f, 1f)
                val peakY = h - (peakRatio * h * 0.72f) - (h * 0.08f)

                drawLine(
                    color = color.copy(alpha = 0.5f),
                    start = Offset(peakX, h),
                    end = Offset(peakX, peakY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                )

                // Glowing concentric peak coordinate point
                drawCircle(
                    color = color.copy(alpha = 0.30f),
                    radius = 11.dp.toPx(),
                    center = Offset(peakX, peakY)
                )
                drawCircle(
                    color = color,
                    radius = 5.dp.toPx(),
                    center = Offset(peakX, peakY)
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.5.dp.toPx(),
                    center = Offset(peakX, peakY)
                )
            }
        }

        // Timeline markers & Peak Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("12 AM", style = KickerSmall, color = TextTertiary)
            Text("6 AM", style = KickerSmall, color = TextTertiary)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.12f))
                    .border(0.6.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
            ) {
                Box(modifier = Modifier.size(4.dp).background(color, CircleShape))
                Text("PEAK // $formattedPeak", style = KickerSmall, color = color)
            }
            Text("6 PM", style = KickerSmall, color = TextTertiary)
            Text("11 PM", style = KickerSmall, color = TextTertiary)
        }
    }
}

/**
 * Visualizes continuous playback sessions with a single artist, showing play count and session duration.
 */
@Composable
private fun BingeVisualizer(
    artist: String,
    playCount: Int,
    durationMs: Long,
    color: Color,
    onArtistClick: ((String) -> Unit)? = null
) {
    val safeArtist = artist.ifBlank { "Top Artist" }
    val safeCount = playCount.coerceAtLeast(1)
    val minutes = (durationMs / 1000 / 60).coerceAtLeast(0)
    val hours = minutes / 60
    val remainingMin = minutes % 60
    val timeFormatted = remember(durationMs) {
        if (durationMs <= 0L) {
            "Active Session"
        } else if (hours > 0) {
            "${hours}h ${remainingMin}m"
        } else {
            "${minutes}m"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Artist Identity Header Row with responsive text truncation
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.18f))
                        .border(1.dp, color.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Face,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = safeArtist,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onArtistClick != null && artist.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(color.copy(alpha = 0.12f))
                        .border(0.6.dp, color.copy(alpha = 0.3f), RoundedCornerShape(100.dp))
                        .clickable { onArtistClick(artist) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "EXPLORE",
                        style = KickerSmall,
                        color = color
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(8.dp)
                    )
                }
            }
        }

        // Live Rhythmic Playback Wave
        val barCount = 18
        val infiniteTransition = rememberInfiniteTransition(label = "binge_playback")
        val animations = (0 until barCount).map { index ->
            val duration = 400 + (index * 55) % 360
            infiniteTransition.animateFloat(
                initialValue = 0.12f,
                targetValue = 0.95f,
                animationSpec = infiniteRepeatable(
                    animation = tween(duration, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$index"
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(TempoDarkSurfaceSunken.copy(alpha = 0.4f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            animations.forEach { anim ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleY = anim.value
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(color, color.copy(alpha = 0.25f))
                            ),
                            shape = RoundedCornerShape(100.dp)
                        )
                )
            }
        }

        // Dual Telemetry Cards Row (Plays in a row + Session length)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(0.6.dp, GlassBorderSoft, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "STREAK PLAYS",
                    style = KickerSmall,
                    color = TextTertiary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$safeCount IN A ROW",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.03f))
                    .border(0.6.dp, GlassBorderSoft, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "SESSION TIME",
                    style = KickerSmall,
                    color = TextTertiary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Visualizes artist discoveries, rendering a trajectory curve for historical trends
 * or a radar view when trend points are limited.
 */
@Composable
private fun DiscoveryVisualizer(newArtistsCount: Int, trends: List<DiscoveryTrend>, color: Color) {
    val safeCount = newArtistsCount.coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Discovery Telemetry Headline
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "+$safeCount",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp
                    ),
                    color = color
                )
                Text(
                    text = "NEW ARTISTS",
                    style = KickerSmall,
                    color = TextPrimary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(color.copy(alpha = 0.12f))
                    .border(0.6.dp, color.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Box(modifier = Modifier.size(4.dp).background(color, CircleShape))
                Text(
                    text = "EXPANDING CATALOG",
                    style = KickerSmall,
                    color = color
                )
            }
        }

        if (trends.size >= 2) {
            // Trend curve when historical data exists
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val pointsCount = trends.size
                    val maxDiscoveries = trends.maxOfOrNull { it.new_artists_count }?.coerceAtLeast(1) ?: 1
                    val stepX = w / (pointsCount - 1)

                    val path = Path()
                    val fillPath = Path()

                    trends.forEachIndexed { index, trend ->
                        val x = index * stepX
                        val ratio = trend.new_artists_count.toFloat() / maxDiscoveries
                        val y = h - (ratio * h * 0.7f) - (h * 0.08f)

                        if (index == 0) {
                            path.moveTo(x, y)
                            fillPath.moveTo(x, h)
                            fillPath.lineTo(x, y)
                        } else {
                            val prevX = (index - 1) * stepX
                            val prevRatio = trends[index - 1].new_artists_count.toFloat() / maxDiscoveries
                            val prevY = h - (prevRatio * h * 0.7f) - (h * 0.08f)

                            val controlX1 = prevX + (stepX / 2f)
                            val controlY1 = prevY
                            val controlX2 = prevX + (stepX / 2f)
                            val controlY2 = y

                            path.cubicTo(controlX1, controlY1, controlX2, controlY2, x, y)
                            fillPath.cubicTo(controlX1, controlY1, controlX2, controlY2, x, y)
                        }

                        if (index == pointsCount - 1) {
                            fillPath.lineTo(x, h)
                            fillPath.close()
                        }
                    }

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(color.copy(alpha = 0.28f), Color.Transparent),
                            startY = 0f,
                            endY = h
                        )
                    )

                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )

                    trends.forEachIndexed { index, trend ->
                        val x = index * stepX
                        val ratio = trend.new_artists_count.toFloat() / maxDiscoveries
                        val y = h - (ratio * h * 0.7f) - (h * 0.08f)

                        drawCircle(
                            color = Color.White,
                            radius = 2.5.dp.toPx(),
                            center = Offset(x, y)
                        )
                        drawCircle(
                            color = color,
                            radius = 4.dp.toPx(),
                            center = Offset(x, y),
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            }

            // Recent months labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val displayTrends = if (trends.size > 5) trends.takeLast(5) else trends
                displayTrends.forEach { trend ->
                    val displayMonth = try {
                        val parts = trend.month.split("-")
                        val monthInt = parts[1].toInt()
                        java.time.Month.of(monthInt).getDisplayName(
                            java.time.format.TextStyle.SHORT,
                            java.util.Locale.ENGLISH
                        ).uppercase()
                    } catch (e: Exception) {
                        trend.month.takeLast(3).uppercase()
                    }
                    Text(
                        text = displayMonth,
                        style = KickerSmall,
                        color = TextTertiary
                    )
                }
            }
        } else {
            // Discovery Intake Constellation Fallback (Guaranteed never blank for 0 or 1 data point)
            val infiniteTransition = rememberInfiniteTransition(label = "discovery_radar")
            val orbitAngle by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(8000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "orbit"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(TempoDarkSurfaceSunken.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2
                    val cy = size.height / 2
                    val maxR = size.height * 0.42f

                    // Concentric radar orbits
                    drawCircle(
                        color = Color.White.copy(alpha = 0.05f),
                        radius = maxR * 0.5f,
                        center = Offset(cx, cy),
                        style = Stroke(1.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.08f),
                        radius = maxR,
                        center = Offset(cx, cy),
                        style = Stroke(1.dp.toPx())
                    )

                    // Orbiting discovery spark nodes
                    val nodeCount = 4
                    for (i in 0 until nodeCount) {
                        val angleRad = Math.toRadians((orbitAngle + i * (360.0 / nodeCount)).toDouble())
                        val r = maxR * (0.6f + (i % 2) * 0.35f)
                        val nx = (cx + r * kotlin.math.cos(angleRad)).toFloat()
                        val ny = (cy + r * kotlin.math.sin(angleRad)).toFloat()

                        drawCircle(
                            color = color.copy(alpha = 0.35f),
                            radius = 6.dp.toPx(),
                            center = Offset(nx, ny)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 2.5.dp.toPx(),
                            center = Offset(nx, ny)
                        )
                    }

                    // Center radar core
                    drawCircle(
                        color = color,
                        radius = 4.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }

                Text(
                    text = "Actively mapping new sounds to your library",
                    style = KickerSmall,
                    color = TextTertiary,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                )
            }
        }
    }
}

/**
 * Visualizes rhythm and beats per minute with an animated pulse wave and tempo label.
 */
@Composable
private fun TempoBPMVisualizer(bpm: Float, color: Color) {
    val safeBpm = bpm.coerceIn(40f, 240f)
    val cycleMs = (60_000 / safeBpm).toInt().coerceIn(250, 1500)
    val infiniteTransition = rememberInfiniteTransition(label = "metronome")
    val ringPulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(cycleMs / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringPulse"
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(cycleMs / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    val tempoTag = remember(safeBpm) {
        when {
            safeBpm >= 140 -> "FAST // PRESTO"
            safeBpm >= 120 -> "UPBEAT // ALLEGRO"
            safeBpm >= 100 -> "GROOVE // MODERATO"
            safeBpm >= 80 -> "WALKING // ANDANTE"
            else -> "RELAXED // ADAGIO"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.size(52.dp),
            contentAlignment = Alignment.Center
        ) {
            // Expanding sonic ripple
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .graphicsLayer {
                        scaleX = ringPulse
                        scaleY = ringPulse
                        this.alpha = ringAlpha
                    }
                    .background(color, CircleShape)
            )
            // Center vinyl pip
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(TempoDarkSurfaceSunken)
                    .border(2.dp, color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(Color.White, CircleShape)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${safeBpm.toInt()}",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    ),
                    color = TextPrimary
                )
                Text(
                    text = "BPM",
                    style = KickerSmall,
                    color = color,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Box(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = tempoTag,
                        style = TextStyle(
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = stringResource(R.string.tempo_bpm_hint),
                style = KickerSmall,
                color = TextTertiary,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 6. Streak Visualizer:
 * Visualizes daily listening consistency with flame animation and 7-dot weekly habit matrix.
 */
@Composable
private fun StreakVisualizer(days: Int) {
    val safeDays = days.coerceAtLeast(1)
    val infiniteTransition = rememberInfiniteTransition(label = "flame")
    val flameScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flameScale"
    )

    val fireColor1 = InsightStreak
    val fireColor2 = InsightEnergy
    val fireColor3 = TempoWarningBright

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Canvas(
            modifier = Modifier
                .size(46.dp)
                .graphicsLayer {
                    scaleX = flameScale
                    scaleY = flameScale
                }
        ) {
            val w = size.width
            val h = size.height

            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.05f)
                cubicTo(w * 0.72f, h * 0.28f, w * 0.95f, h * 0.55f, w * 0.8f, h * 0.8f)
                cubicTo(w * 0.7f, h * 0.95f, w * 0.3f, h * 0.95f, w * 0.2f, h * 0.8f)
                cubicTo(w * 0.05f, h * 0.55f, w * 0.28f, h * 0.28f, w * 0.5f, h * 0.05f)
                close()
            }

            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(fireColor3, fireColor1, fireColor2),
                    startY = 0f,
                    endY = h
                )
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$safeDays DAYS IN A ROW",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = fireColor1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 7-Dot Habit Momentum Matrix (Past 7 days)
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filledDots = safeDays.coerceIn(1, 7)
                for (i in 1..7) {
                    val isFilled = i <= filledDots
                    Box(
                        modifier = Modifier
                            .size(width = 12.dp, height = 5.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .background(
                                if (isFilled) fireColor1 else Color.White.copy(alpha = 0.12f)
                            )
                    )
                }
            }

            Text(
                text = stringResource(R.string.streak_hint),
                style = KickerSmall,
                color = TextTertiary,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Renders the top music genre alongside a simulated audio frequency spectrum.
 */
@Composable
private fun GenreVisualizer(genre: String, color: Color) {
    val safeGenre = genre.ifBlank { "Eclectic Sound" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PRIMARY SOUND PROFILE",
                style = KickerSmall,
                color = TextTertiary
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(modifier = Modifier.size(4.dp).background(color, CircleShape))
                Text(
                    text = "HEAVY ROTATION",
                    style = KickerSmall,
                    color = color
                )
            }
        }

        // Genre Headline with clean ellipsis protection
        Text(
            text = safeGenre.uppercase(java.util.Locale.getDefault()),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = DisplayFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 1.0.sp
            ),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Frequency Spectrum Wave
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TempoDarkSurfaceSunken.copy(alpha = 0.4f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.5.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val bars = 20
            for (i in 0 until bars) {
                val heightFraction = (0.25f + 0.75f * kotlin.math.sin(i * Math.PI / (bars - 1)).toFloat()).coerceIn(0.2f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(heightFraction)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(color, color.copy(alpha = 0.25f))
                            ),
                            shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
                        )
                )
            }
        }
    }
}

/**
 * Horizontal gauge displaying track audio attributes such as energy or danceability.
 */
@Composable
private fun FeatureGaugeVisualizer(value: Float, color: Color, label: String) {
    val safeValue = value.coerceIn(0f, 1f)
    val animValue = remember { Animatable(0f) }
    LaunchedEffect(safeValue) {
        animValue.animateTo(
            targetValue = safeValue,
            animationSpec = tween(900, easing = FastOutSlowInEasing)
        )
    }

    val percentage = (safeValue * 100).toInt()
    val levelDescription = when {
        safeValue >= 0.75f -> "HIGH INTENSITY"
        safeValue >= 0.45f -> "MODERATE"
        else -> "SUBTLE"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(java.util.Locale.getDefault()),
                style = KickerSmall,
                color = TextTertiary
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = levelDescription,
                    style = KickerSmall,
                    color = color
                )
                Text(
                    text = "$percentage%",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextPrimary
                )
            }
        }

        // Calibrated horizontal track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(100.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animValue.value)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(color.copy(alpha = 0.5f), color)
                        ),
                        RoundedCornerShape(100.dp)
                    )
            )
        }

        // Calibration scale ticks
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("0% LOW", style = TextStyle(fontFamily = AppFontFamily, fontSize = 8.5.sp), color = TextTertiary.copy(alpha = 0.6f))
            Text("50% MODERATE", style = TextStyle(fontFamily = AppFontFamily, fontSize = 8.5.sp), color = TextTertiary.copy(alpha = 0.6f))
            Text("100% HIGH", style = TextStyle(fontFamily = AppFontFamily, fontSize = 8.5.sp), color = TextTertiary.copy(alpha = 0.6f))
        }
    }
}

/**
 * Circular progress ring displaying listener engagement or completion rates.
 */
@Composable
private fun EngagementVisualizer(value: Float, color: Color) {
    val isSkipRate = value < 0f
    val absValue = kotlin.math.abs(value).coerceIn(0f, 1f)
    val percentage = (absValue * 100).toInt()
    val animValue = remember { Animatable(0f) }
    LaunchedEffect(value) {
        animValue.animateTo(
            targetValue = absValue,
            animationSpec = tween(1100, easing = FastOutSlowInEasing)
        )
    }

    val archetypeTitle = if (isSkipRate) "CURATOR MODE" else "COMPLETIONIST"
    val archetypeSub = if (isSkipRate) "Skip Rate Focus" else "Track Completion"
    val descriptor = if (isSkipRate) {
        "You skip $percentage% of songs to find the exact vibe you want."
    } else {
        "You finish $percentage% of the songs you start. A true album listener."
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // High-Craft Elevated Circular Ring Gauge
        Box(
            modifier = Modifier.size(86.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val strokeWidthPx = 6.5.dp.toPx()
                val radius = (w - strokeWidthPx) / 2f
                val cx = w / 2f
                val cy = h / 2f

                // Track ring
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f),
                    radius = radius,
                    style = Stroke(width = strokeWidthPx)
                )

                // Animated Sweep Arc
                val sweep = animValue.value * 360f
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(color.copy(alpha = 0.45f), color, color)
                    ),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(strokeWidthPx / 2, strokeWidthPx / 2),
                    size = Size(w - strokeWidthPx, h - strokeWidthPx),
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                )

                // Glowing Tip Pip at the end of the arc
                if (animValue.value > 0.02f) {
                    val endAngleDeg = -90f + sweep
                    val endAngleRad = Math.toRadians(endAngleDeg.toDouble())
                    val tipX = (cx + radius * kotlin.math.cos(endAngleRad)).toFloat()
                    val tipY = (cy + radius * kotlin.math.sin(endAngleRad)).toFloat()

                    drawCircle(
                        color = color.copy(alpha = 0.35f),
                        radius = 6.dp.toPx(),
                        center = Offset(tipX, tipY)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 2.5.dp.toPx(),
                        center = Offset(tipX, tipY)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "$percentage%",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = TextPrimary
                )
            }
        }

        // Contextual Archetype Narrative
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(color))
                Text(
                    text = archetypeTitle,
                    style = KickerSmall,
                    color = color
                )
            }
            Text(
                text = archetypeSub,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                ),
                color = TextPrimary
            )
            Text(
                text = descriptor,
                style = KickerSmall,
                color = TextSecondary,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Animated waveform fallback for cards without a specific visualizer.
 */
@Composable
private fun FallbackVisualizer(color: Color) {
    val barCount = 14
    val infiniteTransition = rememberInfiniteTransition(label = "ambient_wave")
    val animations = (0 until barCount).map { index ->
        val duration = 600 + (index * 60) % 400
        infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(
                animation = tween(duration, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "amb_$index"
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(TempoDarkSurfaceSunken.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        animations.forEach { anim ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        scaleY = anim.value
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }
                    .background(
                        brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.6f), color.copy(alpha = 0.15f))),
                        shape = RoundedCornerShape(100.dp)
                    )
            )
        }
    }
}

@Composable
fun InsightCard(
    insight: InsightCardData,
    onCloseClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    selectedType: InsightType? = null,
    onNavigateToArtist: ((String) -> Unit)? = null,
    onNavigateToTrack: ((Long) -> Unit)? = null
) {
    val (icon, color) = when(insight.type) {
        InsightType.MOOD -> Icons.Filled.Face to me.avinas.tempo.ui.theme.InsightMood
        InsightType.PEAK_TIME -> Icons.Filled.DateRange to me.avinas.tempo.ui.theme.InsightPeakTime
        InsightType.BINGE -> Icons.Filled.Bolt to me.avinas.tempo.ui.theme.InsightBinge
        InsightType.DISCOVERY -> Icons.Filled.Celebration to me.avinas.tempo.ui.theme.InsightDiscovery
        InsightType.ENERGY -> Icons.Filled.Bolt to me.avinas.tempo.ui.theme.InsightEnergy
        InsightType.DANCEABILITY -> Icons.Filled.Celebration to me.avinas.tempo.ui.theme.InsightDanceability
        InsightType.TEMPO -> Icons.Filled.Speed to me.avinas.tempo.ui.theme.InsightTempo
        InsightType.ACOUSTICNESS -> Icons.Filled.Piano to me.avinas.tempo.ui.theme.InsightAcousticness
        InsightType.STREAK -> Icons.Filled.LocalFireDepartment to me.avinas.tempo.ui.theme.InsightStreak
        InsightType.GENRE -> Icons.AutoMirrored.Filled.QueueMusic to me.avinas.tempo.ui.theme.InsightGenre
        InsightType.ENGAGEMENT -> Icons.Filled.Favorite to me.avinas.tempo.ui.theme.InsightEngagement
        InsightType.RATE_APP -> Icons.Filled.Star to GoldPrimary
        else -> Icons.Filled.Settings to Color.Gray
    }

    val rawCategory = when(insight.type) {
        InsightType.MOOD -> stringResource(R.string.insight_category_mood)
        InsightType.PEAK_TIME -> stringResource(R.string.insight_category_peak_time)
        InsightType.BINGE -> stringResource(R.string.insight_category_binge)
        InsightType.DISCOVERY -> stringResource(R.string.insight_category_discovery)
        InsightType.ENERGY -> stringResource(R.string.insight_category_energy)
        InsightType.DANCEABILITY -> stringResource(R.string.insight_category_danceability)
        InsightType.TEMPO -> stringResource(R.string.insight_category_tempo)
        InsightType.ACOUSTICNESS -> stringResource(R.string.insight_category_acousticness)
        InsightType.STREAK -> stringResource(R.string.insight_category_streak)
        InsightType.GENRE -> stringResource(R.string.insight_category_genre)
        InsightType.ENGAGEMENT -> stringResource(R.string.insight_category_engagement)
        else -> stringResource(R.string.insight_category_default)
    }

    // Clean category prefix: extract specific label after "//" if present, so it never overflows
    val categoryPrefix = remember(rawCategory) {
        if (rawCategory.contains("//")) {
            rawCategory.substringAfter("//").trim().uppercase(java.util.Locale.getDefault())
        } else {
            rawCategory.uppercase(java.util.Locale.getDefault())
        }
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .premiumClickable(onClick = onClick, pressedScale = 0.985f),
        accentColor = color,
        accentStrength = 0.08f,
        variant = GlassCardVariant.Obsidian,
        shape = RoundedCornerShape(22.dp),
        borderColor = GlassBorderSoft,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Masthead row: Icon Emblem + Kicker + Frosted close button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Circular icon emblem with ambient accent tint
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(TempoDarkSurfaceSunken)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(color.copy(alpha = 0.22f), Color.Transparent)
                            )
                        )
                        .border(1.dp, color.copy(alpha = 0.28f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(19.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                        Text(
                            text = categoryPrefix,
                            style = KickerSmall,
                            color = color,
                            letterSpacing = 1.0.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (onCloseClick != null) {
                    FrostedIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = stringResource(R.string.insight_close),
                        onClick = onCloseClick,
                        iconTint = TextSecondary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Editorial Serif Headline
            Text(
                text = insight.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                    lineHeight = 26.sp
                ),
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                softWrap = true
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Context Narrative
            Text(
                text = insight.description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 20.sp
                ),
                color = TextSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                softWrap = true
            )

            // Visualizer content
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.025f))
                    .border(0.6.dp, GlassBorderSoft, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                when (val payload = insight.payload) {
                    is InsightPayload.MoodData -> {
                        MoodVisualizer(valence = payload.valence, energy = payload.energy, color = color)
                    }
                    is InsightPayload.PeakTimeData -> {
                        PeakTimeVisualizer(peakHour = payload.peakHour, hourlyDistribution = payload.hourlyDistribution, color = color)
                    }
                    is InsightPayload.BingeData -> {
                        BingeVisualizer(
                            artist = payload.artist,
                            playCount = payload.playCount,
                            durationMs = payload.durationMs,
                            color = color,
                            onArtistClick = onNavigateToArtist
                        )
                    }
                    is InsightPayload.DiscoveryData -> {
                        DiscoveryVisualizer(
                            newArtistsCount = payload.newArtistsCount,
                            trends = payload.trends,
                            color = color
                        )
                    }
                    is InsightPayload.StreakData -> {
                        StreakVisualizer(days = payload.days)
                    }
                    is InsightPayload.GenreData -> {
                        GenreVisualizer(genre = payload.genre, color = color)
                    }
                    is InsightPayload.FeatureValue -> {
                        if (payload.type == InsightType.ENGAGEMENT) {
                            EngagementVisualizer(value = payload.value, color = color)
                        } else {
                            val label = when(payload.type) {
                                InsightType.ENERGY -> "Energy"
                                InsightType.DANCEABILITY -> "Danceability"
                                InsightType.ACOUSTICNESS -> "Acousticness"
                                else -> "Feature Balance"
                            }
                            FeatureGaugeVisualizer(value = payload.value, color = color, label = label)
                        }
                    }
                    is InsightPayload.TempoValue -> {
                        TempoBPMVisualizer(bpm = payload.bpm, color = color)
                    }
                    else -> {
                        FallbackVisualizer(color = color)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResponsiveText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
    softWrap: Boolean = false,
    overflow: TextOverflow = TextOverflow.Clip
) {
    var fontSize by remember(text, style.fontSize, maxLines, softWrap) { mutableStateOf(style.fontSize) }
    var readyToDraw by remember(text, style.fontSize, maxLines, softWrap) { mutableStateOf(false) }

    Text(
        text = text,
        modifier = modifier.graphicsLayer { 
            alpha = if (readyToDraw) 1f else 0f 
        },
        style = style.copy(fontSize = fontSize),
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = overflow,
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.hasVisualOverflow && fontSize.value > 12f) {
                fontSize = (fontSize.value * 0.9f).sp
            } else {
                readyToDraw = true
            }
        }
    )
}

