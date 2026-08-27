package me.avinas.tempo.ui.home.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.components.GlassCard

import me.avinas.tempo.ui.components.TrendLine
import me.avinas.tempo.ui.theme.*
import androidx.compose.ui.res.stringResource
import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.premiumClickable

@Composable
fun HeroCard(
    userName: String,
    listeningTime: String,
    periodLabel: String,
    timeChangePercent: Double,
    trendData: List<Float>,
    selectedRange: TimeRange,
    dailyLabels: List<String> = emptyList(),
    modifier: Modifier = Modifier
) {
    var scrubbingTime by remember { mutableStateOf<String?>(null) }
    var scrubbingLabel by remember { mutableStateOf<String?>(null) }

    // Reset scrubbing state when time range changes
    LaunchedEffect(selectedRange) {
        scrubbingTime = null
        scrubbingLabel = null
    }

    val greeting = remember(userName) {
        me.avinas.tempo.utils.TempoCopyEngine.getHeroGreeting(userName)
    }
    val greetingStyle = when {
        greeting.length <= 20 -> MaterialTheme.typography.headlineSmall
        greeting.length <= 30 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Greeting
                Text(
                    text = greeting,
                    style = greetingStyle,
                    color = TextPrimary.copy(alpha = 0.92f),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                // Hero Time Display + period context
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = scrubbingTime ?: listeningTime,
                        style = MaterialTheme.typography.displayMedium,
                        color = TextPrimary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    if (scrubbingLabel != null) {
                        Text(
                            text = "• $scrubbingLabel",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else if (periodLabel.isNotBlank()) {
                        Text(
                            text = periodLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = TextTertiary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }

                val isPositive = timeChangePercent >= 0
                val percentString = "${if (isPositive) "+" else ""}${timeChangePercent.toInt()}%"
                val comparisonColor = if (isPositive) TempoSuccessBright else TempoWarningBright
                val arrowIcon = if (isPositive) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(comparisonColor.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = arrowIcon,
                                contentDescription = null,
                                tint = comparisonColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = percentString,
                                style = MaterialTheme.typography.labelMedium,
                                color = comparisonColor,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = me.avinas.tempo.utils.TempoCopyEngine.getHeroSubtitle(timeChangePercent, selectedRange),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }

            
            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
            ) {
                if (dailyLabels.isNotEmpty() && trendData.size == dailyLabels.size) {
                    InteractiveTrendLine(
                        dataPoints = trendData,
                        labels = dailyLabels,
                        modifier = Modifier.fillMaxSize(),
                        lineColor = me.avinas.tempo.ui.theme.TempoSecondary,
                        fillColor = me.avinas.tempo.ui.theme.TempoSecondary.copy(alpha = 0.2f),
                        strokeWidth = 3.dp,
                        formatValue = { value ->
                            val minutes = value.toLong()
                            val hours = minutes / 60
                            val mins = minutes % 60
                            if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                        },
                        onValueSelected = { value, label ->
                            val minutes = value.toLong()
                            val hours = minutes / 60
                            val mins = minutes % 60
                            scrubbingTime = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                            scrubbingLabel = label
                        },
                        onSelectionCleared = {
                            scrubbingTime = null
                            scrubbingLabel = null
                        }
                    )
                } else {
                    TrendLine(
                        dataPoints = trendData,
                        modifier = Modifier.fillMaxSize(),
                        lineColor = me.avinas.tempo.ui.theme.TempoSecondary,
                        fillColor = me.avinas.tempo.ui.theme.TempoSecondary.copy(alpha = 0.2f),
                        strokeWidth = 3.dp // Thicker line
                    )
                }
            }
        }
    }
}


private val SpotlightAccent = me.avinas.tempo.ui.theme.TempoPrimary

@Composable
private fun SpotlightRing(
    albumArtUrl: String?,
    viewed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(62.dp)
            .premiumClickable(onClick = onClick, pressedScale = 0.97f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val outerStroke = 1.5.dp.toPx()
            val inset = outerStroke / 2
            val d = size.minDimension - outerStroke
            val tl = Offset(inset, inset)
            val sz = androidx.compose.ui.geometry.Size(d, d)
            if (!viewed) {
                drawArc(
                    color = Color.White.copy(alpha = 0.10f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = outerStroke),
                    topLeft = tl,
                    size = sz
                )
                drawArc(
                    color = SpotlightAccent,
                    startAngle = -90f,
                    sweepAngle = 298f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = outerStroke,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    ),
                    topLeft = tl,
                    size = sz
                )
            } else {
                drawArc(
                    color = Color.White.copy(alpha = 0.09f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                    topLeft = tl,
                    size = sz
                )
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            if (!albumArtUrl.isNullOrBlank()) {
                CachedAsyncImage(
                    imageUrl = albumArtUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetSizeDp = 88
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = SpotlightAccent,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (!viewed) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(SpotlightAccent)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                            radius = 14.dp.value * 2
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
fun SpotlightStoryCard(
    onClick: () -> Unit,
    onRingClick: () -> Unit,
    albumArtUrl: String? = null,
    storyAvailable: Boolean = true,
    storyViewed: Boolean = false,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth().premiumClickable(onClick = onClick, pressedScale = 0.98f),
        accentColor = me.avinas.tempo.ui.theme.TempoPrimary,
        accentStrength = 0.06f,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (storyAvailable) {
                SpotlightRing(albumArtUrl = albumArtUrl, viewed = storyViewed, onClick = onRingClick)
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.home_spotlight_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.home_spotlight_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun WeekInReviewGrid(
    topArtistName: String?,
    topArtistImage: String?,
    topTrackName: String?,
    topTrackImage: String?,
    totalHours: String,
    newDiscoveries: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_week_review),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
        )
        
        // Grid layout using Rows and Columns
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Artist
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = me.avinas.tempo.ui.theme.NeonRed.copy(alpha = 0.12f), // Restored x-factor
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CachedAsyncImage(
                        imageUrl = topArtistImage,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.05f)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = me.avinas.tempo.utils.TempoCopyEngine.getTopArtistCopy(topArtistName),
                        style = MaterialTheme.typography.bodySmall, // Smaller for longer text
                        color = me.avinas.tempo.ui.theme.NeonRed,
                        maxLines = 1
                    )
                    Text(
                        text = topArtistName ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
            
            // Top Track
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = me.avinas.tempo.ui.theme.ElectricBlue.copy(alpha = 0.12f), // Restored x-factor
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CachedAsyncImage(
                        imageUrl = topTrackImage,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.05f)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = me.avinas.tempo.utils.TempoCopyEngine.getTopTrackCopy(topTrackName),
                        style = MaterialTheme.typography.bodySmall,
                        color = me.avinas.tempo.ui.theme.ElectricBlue,
                        maxLines = 1
                    )
                    Text(
                        text = topTrackName ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Total Hours
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = me.avinas.tempo.ui.theme.GoldenAmber.copy(alpha = 0.1f),
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(me.avinas.tempo.ui.theme.GoldenAmber.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = me.avinas.tempo.ui.theme.GoldenAmber,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.home_listen_time),
                        style = MaterialTheme.typography.labelMedium,
                        color = me.avinas.tempo.ui.theme.GoldenAmber
                    )
                    Text(
                        text = totalHours,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
            
            // Discoveries
            GlassCard(
                modifier = Modifier.weight(1f),
                backgroundColor = InsightDanceability.copy(alpha = 0.1f),
                variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(InsightDanceability.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = null,
                            tint = InsightDanceability,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.home_new_finds),
                        style = MaterialTheme.typography.labelMedium,
                        color = InsightDanceability
                    )
                    Text(
                        text = "$newDiscoveries",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun DiscoverySection(
    discoveryStats: me.avinas.tempo.data.stats.DiscoveryStats?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_ready_discover),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
        )
        
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val newArtists = discoveryStats?.newArtistsCount ?: 0
            val newTracks = discoveryStats?.newTracksCount ?: 0
            val varietyScore = ((discoveryStats?.varietyScore ?: 0.0) * 10).toInt()
            
            DiscoveryCard(
                title = stringResource(R.string.home_found_artists, newArtists),
                subtitle = stringResource(R.string.home_expand_horizon),
                icon = Icons.Default.Explore,
                color = TempoSky,
                backgroundColor = TempoInfo.copy(alpha = 0.2f)
            )
            
            DiscoveryCard(
                title = stringResource(R.string.home_discovered_tracks, newTracks),
                subtitle = stringResource(R.string.home_fresh_beats),
                icon = Icons.Default.History,
                color = TempoError,
                backgroundColor = TempoErrorDeep.copy(alpha = 0.2f)
            )
            
            DiscoveryCard(
                title = stringResource(R.string.home_variety_score, varietyScore),
                subtitle = stringResource(R.string.home_how_unique),
                icon = Icons.Default.Fingerprint,
                color = InsightMood,
                backgroundColor = InsightMood.copy(alpha = 0.2f)
            )
        }
    }
}

@Composable
fun DiscoveryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    backgroundColor: Color
) {
    GlassCard(
        modifier = Modifier.width(200.dp),
        backgroundColor = backgroundColor,
        variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(color.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun HabitInsights(
    insights: List<HabitInsightData>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.home_habits_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
        )
        
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            insights.forEach { insight ->
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = insight.gradient.first().copy(alpha = 0.2f),
                    variant = me.avinas.tempo.ui.components.GlassCardVariant.LowProminence
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(insight.iconBgColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = insight.icon,
                                contentDescription = null,
                                tint = insight.iconColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column {
                            Text(
                                text = insight.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                            Text(
                                text = insight.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

data class HabitInsightData(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconColor: Color,
    val iconBgColor: Color,
    val gradient: List<Color>
)
