package me.avinas.tempo.ui.spotlight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.data.stats.TimeRange
import me.avinas.tempo.ui.components.CachedAsyncImage
import java.util.Locale
import me.avinas.tempo.ui.theme.TempoPrimary
import me.avinas.tempo.ui.theme.TempoWarningBright

// =====================================================================
// CONCLUSION — the finale: poetry, hero stat, recap, closing line
// =====================================================================

private val ConclusionTeal = TempoPrimary
private val ConclusionAmber = TempoWarningBright

@Composable
fun ConclusionPage(page: SpotlightStoryPage.Conclusion) {
    val context = LocalContext.current
    val titleText = remember(page.timeRange) {
        SpotlightPoetry.getHeading(context, page.timeRange)
    }
    val animatedMinutes = rememberStoryCountUp(
        targetValue = page.totalMinutes.toFloat(),
        delayMillis = 400,
        durationMillis = 1800
    )

    StoryPageScaffold(
        label = null,
        background = {
            // Warm parting gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                ConclusionTeal.copy(alpha = 0.08f),
                                Color.Transparent,
                                ConclusionTeal.copy(alpha = 0.06f)
                            )
                        )
                    )
            )
        }
    ) {
        val dimens = LocalSpotlightDimens.current

        // Emotional heading + callback
        EnterAnimation(delay = StoryTiming.Header) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = (dimens.textHeadline.value * 1.05f).sp,
                        fontWeight = FontWeight.Black
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(dimens.spacerSmall))
                Text(
                    text = page.conversationalText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = dimens.textBody,
                        fontStyle = FontStyle.Italic
                    ),
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Hero stat: total listening
        EnterAnimation(delay = StoryTiming.Hero) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = String.format(Locale.US, "%,d", animatedMinutes.toInt()),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = (dimens.textDisplay.value * 0.8f).sp,
                        fontWeight = FontWeight.Black
                    ),
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.spotlight_conclusion_min),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = dimens.textLabel),
                    color = Color.White.copy(alpha = 0.7f),
                    letterSpacing = (1f * dimens.scale).sp
                )
            }
        }
        // Personality recap
        EnterAnimation(delay = StoryTiming.Content) {
            val (personalityIcon, personalityColor) = getPersonalityAssets(page.personalityType)
            StoryGlassCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = ConclusionTeal.copy(alpha = 0.10f),
                borderColor = ConclusionTeal.copy(alpha = 0.25f),
                contentPadding = PaddingValues(14.dp * dimens.scale),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = personalityIcon,
                        contentDescription = null,
                        tint = personalityColor,
                        modifier = Modifier.size(32.dp * dimens.scale)
                    )
                    Spacer(modifier = Modifier.width(12.dp * dimens.scale))
                    Column {
                        Text(
                            text = stringResource(R.string.spotlight_listening_personality),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Text(
                            text = page.personalityType,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = (dimens.textBody.value * 1.1f).sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Top artists & songs recap
        EnterAnimation(delay = StoryTiming.ContentSub) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp * dimens.scale)
            ) {
                ConclusionListCard(
                    label = stringResource(R.string.spotlight_conclusion_top_artists),
                    labelColor = ConclusionTeal,
                    entries = page.topArtists.take(5).map { it.name to it.imageUrl },
                    entryShape = CircleShape,
                    accent = ConclusionTeal,
                    modifier = Modifier.weight(1f)
                )
                ConclusionListCard(
                    label = stringResource(R.string.spotlight_conclusion_top_songs),
                    labelColor = ConclusionAmber,
                    entries = page.topSongs.take(5).map { it.title to it.imageUrl },
                    entryShape = RoundedCornerShape(3.dp * dimens.scale),
                    accent = ConclusionAmber,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Closing line
        EnterAnimation(delay = StoryTiming.Footer) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.3f)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.3f))
                )
                Spacer(modifier = Modifier.height(14.dp * dimens.scale))
                val closingResId = when (page.timeRange) {
                    TimeRange.THIS_WEEK -> R.string.spotlight_conclusion_closing_week
                    TimeRange.THIS_MONTH -> R.string.spotlight_conclusion_closing_month
                    TimeRange.ALL_TIME -> R.string.spotlight_conclusion_closing_all_time
                    else -> R.string.spotlight_conclusion_closing
                }
                Text(
                    text = stringResource(closingResId),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = dimens.textBody,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
@Composable
private fun ConclusionListCard(
    label: String,
    labelColor: Color,
    entries: List<Pair<String, String?>>,
    entryShape: Shape,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val dimens = LocalSpotlightDimens.current
    StoryGlassCard(
        modifier = modifier,
        backgroundColor = accent.copy(alpha = 0.08f),
        borderColor = accent.copy(alpha = 0.20f),
        contentPadding = PaddingValues(12.dp * dimens.scale),
        contentAlignment = Alignment.TopStart
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = dimens.textLabel),
                color = labelColor,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp * dimens.scale))
            entries.forEach { (name, imageUrl) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 3.dp * dimens.scale)
                ) {
                    CachedAsyncImage(
                        imageUrl = imageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp * dimens.scale)
                            .clip(entryShape),
                        contentScale = ContentScale.Crop,
                        allowHardware = false
                    )
                    Spacer(modifier = Modifier.width(6.dp * dimens.scale))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = (dimens.textLabel.value * 0.9f).sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}