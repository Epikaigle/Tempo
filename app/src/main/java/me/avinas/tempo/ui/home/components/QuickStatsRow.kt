package me.avinas.tempo.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.components.GlassCard
import me.avinas.tempo.ui.components.GlassCardVariant
import me.avinas.tempo.ui.theme.*
import java.util.Locale
/* QuickStatsRow — two figures, shared baseline */
@Composable
fun QuickStatsRow(
    topArtistName: String?,
    topArtistImage: String?,
    topTrackName: String?,
    topTrackImage: String?,
    topArtistPlayCount: Int? = null,
    topTrackArtist: String? = null,
    topTrackPlayCount: Int? = null,
    onArtistClick: (() -> Unit)? = null,
    onTrackClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Top Artist Editorial Card
        GlassCard(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onArtistClick != null && topArtistName != null)
                        Modifier.premiumClickable(onClick = onArtistClick, pressedScale = 0.97f)
                    else Modifier
                ),
            variant = GlassCardVariant.QuietGlass,
            shape = RoundedCornerShape(20.dp),
            borderColor = GlassBorderSoft,
            borderWidth = 0.8.dp,
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Kicker row
                Text(
                    text = stringResource(R.string.home_top_artist).uppercase(Locale.getDefault()),
                    style = KickerSmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Circular Artist Avatar
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(TempoDarkSurfaceSunken)
                        .border(1.dp, GlassBorderMedium, CircleShape)
                ) {
                    CachedAsyncImage(
                        imageUrl = topArtistImage,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.22f),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = topArtistName ?: "—",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(2.dp))

                val artistSubtitle = if (topArtistPlayCount != null && topArtistPlayCount > 0) {
                    "$topArtistPlayCount plays"
                } else {
                    stringResource(R.string.details_rank_most_played)
                }

                Text(
                    text = artistSubtitle,
                    style = CaptionSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }

        // On Repeat Track Editorial Card
        GlassCard(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onTrackClick != null && topTrackName != null)
                        Modifier.premiumClickable(onClick = onTrackClick, pressedScale = 0.97f)
                    else Modifier
                ),
            variant = GlassCardVariant.QuietGlass,
            shape = RoundedCornerShape(20.dp),
            borderColor = GlassBorderSoft,
            borderWidth = 0.8.dp,
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Kicker row
                Text(
                    text = stringResource(R.string.home_on_repeat).uppercase(Locale.getDefault()),
                    style = KickerSmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Rounded Track Art
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(TempoDarkSurfaceSunken)
                        .border(1.dp, GlassBorderMedium, RoundedCornerShape(14.dp))
                ) {
                    CachedAsyncImage(
                        imageUrl = topTrackImage,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        placeholder = {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.22f),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = topTrackName ?: "—",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = DisplayFontFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp
                    ),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(2.dp))

                val trackSubtitle = if (!topTrackArtist.isNullOrBlank()) {
                    topTrackArtist
                } else if (topTrackPlayCount != null && topTrackPlayCount > 0) {
                    "$topTrackPlayCount plays"
                } else {
                    stringResource(R.string.home_on_repeat)
                }

                Text(
                    text = trackSubtitle,
                    style = CaptionSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
