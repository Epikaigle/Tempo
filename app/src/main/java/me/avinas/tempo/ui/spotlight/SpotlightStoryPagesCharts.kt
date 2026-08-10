package me.avinas.tempo.ui.spotlight

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.avinas.tempo.R
import me.avinas.tempo.ui.theme.TempoAccent
import me.avinas.tempo.ui.theme.TempoSuccessDeep
import me.avinas.tempo.ui.theme.TempoWarning
import me.avinas.tempo.ui.components.CachedAsyncImage
import me.avinas.tempo.ui.theme.TempoSuccessBright
import me.avinas.tempo.ui.theme.TempoWarningBright

// =====================================================================
// CHART STORY PAGES — Top Artist, Top Album, Top Track, Top Songs, Genres
// =====================================================================

@Composable
fun TopArtistPage(page: SpotlightStoryPage.TopArtist) {
    StoryPageScaffold(label = stringResource(R.string.spotlight_top_artist_label)) {
        val dimens = LocalSpotlightDimens.current
        // Keep the hero small when the full top-10 is shown
        val heroScale = if (page.topArtists.size > 5) 0.62f else 0.9f

        // 1. Hero (~46%)
        Column(
            modifier = Modifier
                .weight(0.46f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = StoryTiming.Hero) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StoryHeroImage(
                        imageUrl = page.topArtistImageUrl,
                        size = dimens.imageMain * heroScale,
                        glowColor = TempoAccent.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(dimens.spacerSmall))
                    Text(
                        text = page.topArtistName,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = (dimens.textHeadline.value * heroScale).sp
                        ),
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(dimens.spacerSmall))
                    StoryConversationalText(text = page.conversationalText)
                }
            }
        }

        // 2. Ranks 2-9 (~40%)
        Column(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val gridItems = page.topArtists.drop(1).take(8)
            gridItems.chunked(2).forEachIndexed { rowIndex, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowItems.forEachIndexed { colIndex, artist ->
                        Box(modifier = Modifier.weight(1f)) {
                            EnterAnimation(
                                delay = StoryTiming.stagger(rowIndex * 2 + colIndex)
                            ) {
                                StoryRankMiniCard(
                                    rank = artist.rank,
                                    imageUrl = artist.imageUrl,
                                    title = artist.name,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // 3. Rank 10 (~14%)
        Box(
            modifier = Modifier
                .weight(0.14f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val lastArtist = page.topArtists.drop(1).drop(8).firstOrNull()
            if (lastArtist != null) {
                EnterAnimation(delay = StoryTiming.ContentSub) {
                    StoryRankFooterRow(
                        rank = lastArtist.rank,
                        imageUrl = lastArtist.imageUrl,
                        title = lastArtist.name
                    )
                }
            }
        }
    }
}

// ── Top Album ───────────────────────────────────────────────────────

private val AlbumAmber = TempoWarningBright

@Composable
fun TopAlbumPage(page: SpotlightStoryPage.TopAlbum) {
    StoryPageScaffold(
        label = stringResource(R.string.spotlight_top_album_label),
        conversationalText = page.conversationalText,
        background = {
            if (page.albumArtUrl != null) {
                val dimens = LocalSpotlightDimens.current
                CachedAsyncImage(
                    imageUrl = page.albumArtUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(40.dp * dimens.scale)
                        .scale(1.3f),
                    contentScale = ContentScale.Crop,
                    allowHardware = false
                )
                // Darken so foreground content stays readable
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.55f),
                                    Color.Black.copy(alpha = 0.75f)
                                )
                            )
                        )
                )
            }
        }
    ) {
        val dimens = LocalSpotlightDimens.current

        // Hero artwork
        EnterAnimation(delay = StoryTiming.Hero) {
            StoryHeroImage(
                imageUrl = page.albumArtUrl,
                size = dimens.imageMain * 1.05f,
                shape = RoundedCornerShape(20.dp * dimens.scale),
                glowColor = Color.White.copy(alpha = 0.15f),
                borderWidth = 2.dp
            )
        }

        // Album + artist names
        EnterAnimation(delay = StoryTiming.HeroSub) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = page.albumName,
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textHeadline),
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = page.artistName,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = dimens.textTitle),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Stats: plays / time / tracks
        EnterAnimation(delay = StoryTiming.Content) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp * dimens.scale)
            ) {
                StoryStatTile(
                    value = page.playCount.toString(),
                    label = stringResource(R.string.spotlight_plays),
                    modifier = Modifier.weight(1f),
                    valueColor = AlbumAmber,
                    backgroundColor = AlbumAmber.copy(alpha = 0.08f),
                    borderColor = AlbumAmber.copy(alpha = 0.22f),
                    valueFontSize = dimens.textTitle
                )
                StoryStatTile(
                    value = formatStoryMinutes((page.totalTimeMs / 60_000).toInt()),
                    label = stringResource(R.string.spotlight_time_spent),
                    modifier = Modifier.weight(1f),
                    valueColor = AlbumAmber,
                    backgroundColor = AlbumAmber.copy(alpha = 0.08f),
                    borderColor = AlbumAmber.copy(alpha = 0.22f),
                    valueFontSize = dimens.textTitle
                )
                StoryStatTile(
                    value = page.uniqueTracksPlayed.toString(),
                    label = stringResource(R.string.spotlight_tracks),
                    modifier = Modifier.weight(1f),
                    valueColor = AlbumAmber,
                    backgroundColor = AlbumAmber.copy(alpha = 0.08f),
                    borderColor = AlbumAmber.copy(alpha = 0.22f),
                    valueFontSize = dimens.textTitle
                )
            }
        }
    }
}

// ── Top Track Setup (teaser) ────────────────────────────────────────

@Composable
fun TopTrackSetupPage(page: SpotlightStoryPage.TopTrackSetup) {
    StoryPageScaffold(
        label = stringResource(R.string.spotlight_top_track_setup_label),
        contentArrangement = Arrangement.Center
    ) {
        val dimens = LocalSpotlightDimens.current

        EnterAnimation(delay = StoryTiming.Hero) {
            Text(
                text = page.conversationalText,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = dimens.textHeadline),
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(dimens.spacerLarge))

        // Blurred artwork teaser — the reveal happens on the next page
        EnterAnimation(delay = StoryTiming.Content) {
            StoryHeroImage(
                imageUrl = page.topSongImageUrl,
                size = dimens.imageMain * 0.85f,
                shape = RoundedCornerShape(16.dp * dimens.scale),
                glowColor = Color.White.copy(alpha = 0.12f),
                blurRadius = 8.dp * dimens.scale
            )
        }
    }
}

// ── Top Songs ───────────────────────────────────────────────────────

private val SongsAmber = TempoWarning

@Composable
fun TopSongsPage(page: SpotlightStoryPage.TopSongs) {
    StoryPageScaffold(label = stringResource(R.string.spotlight_top_song_label)) {
        val dimens = LocalSpotlightDimens.current
        // Keep the hero small when the full top-10 is shown
        val heroScale = if (page.topSongs.size > 5) 0.62f else 0.9f

        // 1. Hero (~46%)
        Column(
            modifier = Modifier
                .weight(0.46f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            EnterAnimation(delay = StoryTiming.Hero) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StoryHeroImage(
                        imageUrl = page.topSongImageUrl,
                        size = dimens.imageMain * heroScale,
                        shape = RoundedCornerShape(24.dp * dimens.scale),
                        glowColor = SongsAmber.copy(alpha = 0.35f),
                        borderWidth = 1.dp
                    )
                    Spacer(modifier = Modifier.height(dimens.spacerSmall))
                    Text(
                        text = page.topSongTitle,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = (dimens.textHeadline.value * heroScale).sp
                        ),
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = page.topSongArtist,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = (dimens.textTitle.value * heroScale).sp
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(dimens.spacerSmall))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp * dimens.scale)) {
                        StoryChip(
                            text = "${page.playCount} ${stringResource(R.string.spotlight_plays)}",
                            icon = Icons.Default.MusicNote,
                            accentColor = SongsAmber
                        )
                        if (page.totalTimeMs > 0L) {
                            val totalMinutes = (page.totalTimeMs / 60_000).toInt()
                            val hours = totalMinutes / 60
                            val playtimeText = if (hours > 0) {
                                stringResource(R.string.spotlight_playtime_hours, hours, totalMinutes % 60)
                            } else {
                                stringResource(R.string.spotlight_playtime_minutes, totalMinutes)
                            }
                            StoryChip(
                                text = playtimeText,
                                accentColor = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }
        // 2. Ranks 2-9 (~40%)
        Column(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            val gridItems = page.topSongs.drop(1).take(8)
            gridItems.chunked(2).forEachIndexed { rowIndex, rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimens.gridSpacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowItems.forEachIndexed { colIndex, song ->
                        Box(modifier = Modifier.weight(1f)) {
                            EnterAnimation(
                                delay = StoryTiming.stagger(rowIndex * 2 + colIndex)
                            ) {
                                StoryRankMiniCard(
                                    rank = song.rank,
                                    imageUrl = song.imageUrl,
                                    title = song.title,
                                    subtitle = song.artist,
                                    imageShape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // 3. Rank 10 (~14%)
        Box(
            modifier = Modifier
                .weight(0.14f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val lastSong = page.topSongs.drop(1).drop(8).firstOrNull()
            if (lastSong != null) {
                EnterAnimation(delay = StoryTiming.ContentSub) {
                    StoryRankFooterRow(
                        rank = lastSong.rank,
                        imageUrl = lastSong.imageUrl,
                        title = lastSong.title,
                        subtitle = lastSong.artist,
                        imageShape = RoundedCornerShape(8.dp * dimens.scale)
                    )
                }
            }
        }
    }
}

// ── Top Genres (bubble universe) ────────────────────────────────────

private val GenreEmerald = TempoSuccessDeep
private val GenreMint = TempoSuccessBright

@Composable
fun TopGenresPage(page: SpotlightStoryPage.TopGenres) {
    StoryPageScaffold(
        label = stringResource(R.string.spotlight_top_genres_label),
        contentArrangement = Arrangement.Center
    ) {
        val dimens = LocalSpotlightDimens.current

        val floatTransition = rememberInfiniteTransition(label = "genreFloat")
        val mainFloat by floatTransition.animateFloat(
            initialValue = -10f,
            targetValue = 10f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "mainFloat"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            // Main genre bubble
            EnterAnimation(delay = StoryTiming.Hero) {
                Box(
                    modifier = Modifier
                        .offset(y = mainFloat.dp)
                        .size(dimens.bubbleMain)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    GenreEmerald.copy(alpha = 0.6f),
                                    GenreEmerald.copy(alpha = 0.2f)
                                )
                            ),
                            shape = CircleShape
                        )
                        .padding(24.dp * dimens.scale),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = page.topGenre,
                            style = MaterialTheme.typography.displaySmall.copy(fontSize = dimens.textHeadline),
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            lineHeight = dimens.textHeadline
                        )
                        Spacer(modifier = Modifier.height(dimens.spacerSmall))
                        StoryConversationalText(text = page.conversationalText)
                        Spacer(modifier = Modifier.height(8.dp * dimens.scale))
                        Text(
                            text = "${page.topGenrePercentage}%",
                            style = MaterialTheme.typography.headlineSmall.copy(fontSize = dimens.textTitle),
                            color = GenreMint,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            // Satellite genre bubbles
            val satellitePositions = listOf(
                Alignment.TopStart,
                Alignment.TopEnd,
                Alignment.BottomStart,
                Alignment.BottomEnd
            )
            page.genres.drop(1).take(4).forEachIndexed { index, genre ->
                val satelliteFloat by floatTransition.animateFloat(
                    initialValue = if (index % 2 == 0) 5f else -5f,
                    targetValue = if (index % 2 == 0) -5f else 5f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2500 + (index * 200), easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "satelliteFloat$index"
                )

                Box(
                    modifier = Modifier
                        .align(satellitePositions[index])
                        .offset(
                            x = if (index % 2 == 0) 20.dp * dimens.scale else -(20.dp * dimens.scale),
                            y = (if (index < 2) 20.dp * dimens.scale else -(20.dp * dimens.scale)) + satelliteFloat.dp
                        )
                ) {
                    EnterAnimation(delay = StoryTiming.stagger(index, base = StoryTiming.HeroSub, step = 120)) {
                        Box(
                            modifier = Modifier
                                .size(dimens.bubbleMain * 0.5f)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.15f),
                                            Color.White.copy(alpha = 0.05f)
                                        )
                                    ),
                                    shape = CircleShape
                                )
                                .padding(12.dp * dimens.scale),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = genre.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = dimens.textLabel),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${genre.percentage}%",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = (dimens.textLabel.value * 0.85f).sp
                                    ),
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
