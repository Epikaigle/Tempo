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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.theme.*

/* Hallmark · QuickStatsRow — two figures, shared baseline */
@Composable
fun QuickStatsRow(
    topArtistName: String?,
    topArtistImage: String?,
    topTrackName: String?,
    topTrackImage: String?,
    onArtistClick: (() -> Unit)? = null,
    onTrackClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onArtistClick != null && topArtistName != null)
                        Modifier.premiumClickable(onClick = onArtistClick, pressedScale = 0.97f)
                    else Modifier
                )
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(GlassFrostSoft)
                    .border(1.dp, GlassBorderSoft, CircleShape)
            ) {
                CachedAsyncImage(
                    imageUrl = topArtistImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, null, tint = Color.White.copy(alpha = 0.22f), modifier = Modifier.size(28.dp))
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = topArtistName ?: "—",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.home_top_artist),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                letterSpacing = 0.3.sp,
                maxLines = 1
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onTrackClick != null && topTrackName != null)
                        Modifier.premiumClickable(onClick = onTrackClick, pressedScale = 0.97f)
                    else Modifier
                )
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassFrostSoft)
                    .border(1.dp, GlassBorderSoft, RoundedCornerShape(16.dp))
            ) {
                CachedAsyncImage(
                    imageUrl = topTrackImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.22f), modifier = Modifier.size(28.dp))
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = topTrackName ?: "—",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.home_on_repeat),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                letterSpacing = 0.3.sp,
                maxLines = 1
            )
        }
    }
}
