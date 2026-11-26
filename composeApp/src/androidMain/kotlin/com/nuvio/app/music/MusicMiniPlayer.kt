package com.nuvio.app.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val MINI_MUSIC_CONTROLS_TIMEOUT_MS = 3_000L
private const val MINI_MUSIC_MIN_WIDTH_DP = 300f
private const val MINI_MUSIC_BOTTOM_CLEARANCE_DP = 84

/**
 * One floating unit: the music video (when video mode is on) sits directly on top of the
 * player bar at the same width. Tap and hold the video or the bar to move the whole card;
 * drag the bottom grip (or the video's corner handle) to resize it. Playback lives in
 * [MusicPlayer]'s single ExoPlayer, so going back to the Music tab continues at the same
 * timestamp.
 */
@Composable
fun MusicMiniPlayer(
    modifier: Modifier = Modifier,
    onOpenMusic: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val state by MusicPlayer.state.collectAsStateWithLifecycle()
    val track = state.track ?: return
    // The floating overlay is only for active playback. Paused or finished music stays in
    // the Music tab instead of hovering over the rest of the app.
    if (!state.playing && !state.loading) return
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(interaction) {
        controlsVisible = true
        delay(MINI_MUSIC_CONTROLS_TIMEOUT_MS)
        controlsVisible = false
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = MINI_MUSIC_BOTTOM_CLEARANCE_DP.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val maxWidthDp = maxWidth.value
        var unitWidthDp by remember(maxWidthDp) { mutableFloatStateOf(maxWidthDp) }
        var offsetXPx by remember { mutableFloatStateOf(0f) }
        var offsetYPx by remember { mutableFloatStateOf(0f) }
        var cardHeightPx by remember { mutableIntStateOf(0) }
        val widthDp = unitWidthDp.coerceIn(MINI_MUSIC_MIN_WIDTH_DP, maxWidthDp)
        val maxShiftPx = with(density) {
            ((maxWidth - widthDp.dp).coerceAtLeast(0.dp) / 2).toPx()
        }
        val statusBarPx = with(density) {
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
        }
        val maxUpPx = with(density) { maxHeight.toPx() } - cardHeightPx - statusBarPx
        val shownY = offsetYPx.coerceIn(-maxUpPx.coerceAtLeast(0f), 0f)

        // Reads the latest width without restarting the gesture on every resize frame.
        val latestWidthDp = rememberUpdatedState(widthDp)
        val resizeModifier = Modifier.pointerInput(maxWidthDp) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val startWidthDp = latestWidthDp.value
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val deltaDp = (change.position.x - down.position.x) / density.density
                    unitWidthDp = (startWidthDp + deltaDp)
                        .coerceIn(MINI_MUSIC_MIN_WIDTH_DP, maxWidthDp)
                    change.consume()
                }
            }
        }

        // Tap and hold anywhere on the card, then drag to move it. The child buttons keep
        // their normal tap behaviour; a drag only starts after the long-press timeout.
        val moveBy: (Offset) -> Unit = { delta ->
            offsetXPx = (offsetXPx + delta.x).coerceIn(-maxShiftPx, maxShiftPx)
            offsetYPx = (offsetYPx + delta.y).coerceIn(-maxUpPx.coerceAtLeast(0f), 0f)
        }
        val longPressHaptic = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }

        Surface(
            modifier = Modifier
                .width(widthDp.dp)
                .offset {
                    IntOffset(
                        offsetXPx.coerceIn(-maxShiftPx, maxShiftPx).roundToInt(),
                        shownY.roundToInt(),
                    )
                }
                .onSizeChanged { cardHeightPx = it.height },
            shape = RoundedCornerShape(18.dp),
            color = tokens.colors.surfaceElevated.copy(alpha = 0.97f),
            shadowElevation = 12.dp,
        ) {
            Box {
                Column {
                    if (state.videoMode) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(Color.Black),
                        ) {
                            AndroidView(
                                factory = { context ->
                                    PlayerView(context).apply {
                                        useController = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                                        setBackgroundColor(android.graphics.Color.BLACK)
                                    }
                                },
                                update = { view -> view.player = MusicPlayer.exoPlayer },
                                onRelease = { view -> view.player = null },
                                modifier = Modifier.fillMaxSize(),
                            )
                            // Long-press drag layer over the video. Controls are drawn after it.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(maxShiftPx, maxUpPx) {
                                        detectLongPressDrag(
                                            onLongPress = longPressHaptic,
                                            onDrag = moveBy,
                                        )
                                    },
                            )
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.48f))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.DragIndicator,
                                    contentDescription = "Hold to move music video",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(14.dp),
                                )
                                Text("Video", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(
                                onClick = { MusicPlayer.setVideoMode(false); interaction += 1 },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f)),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Switch to audio",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .then(resizeModifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.OpenInFull,
                                    contentDescription = "Resize music video",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(maxShiftPx, maxUpPx) {
                                detectLongPressDrag(
                                    onLongPress = longPressHaptic,
                                    onDrag = moveBy,
                                )
                            }
                            .clickable { interaction += 1 }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AsyncImage(
                            model = track.coverUrl,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp))
                                .background(tokens.colors.surface),
                            contentScale = ContentScale.Crop,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                color = tokens.colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                track.artist,
                                color = tokens.colors.textMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { MusicPlayer.previous(); interaction += 1 }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Filled.SkipPrevious,
                                        contentDescription = "Previous",
                                        tint = tokens.colors.textPrimary,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                                IconButton(onClick = { MusicPlayer.toggle(); interaction += 1 }, modifier = Modifier.size(36.dp)) {
                                    if (state.loading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(19.dp),
                                            color = tokens.colors.accent,
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                            contentDescription = if (state.playing) "Pause" else "Play",
                                            tint = tokens.colors.textPrimary,
                                            modifier = Modifier.size(25.dp),
                                        )
                                    }
                                }
                                IconButton(onClick = { MusicPlayer.next(); interaction += 1 }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Filled.SkipNext,
                                        contentDescription = "Next",
                                        tint = tokens.colors.textPrimary,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { MusicPlayer.setVideoMode(!state.videoMode); interaction += 1 },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                if (state.videoMode) Icons.Filled.GraphicEq else Icons.Filled.Videocam,
                                contentDescription = if (state.videoMode) "Switch to audio" else "Switch to video",
                                tint = if (state.videoMode) tokens.colors.accent else tokens.colors.textMuted,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        IconButton(
                            onClick = onOpenMusic,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(tokens.colors.accent.copy(alpha = 0.16f)),
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = "Open Music",
                                tint = tokens.colors.accent,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
                }

                // Always-visible resize grip. Works in both audio and video mode.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 1.dp)
                        .size(width = 64.dp, height = 22.dp)
                        .semantics { contentDescription = "Resize mini player" }
                        .then(resizeModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 26.dp, height = 3.dp)
                            .clip(CircleShape)
                            .background(tokens.colors.textMuted.copy(alpha = 0.55f)),
                    )
                }
            }
        }
    }
}

/**
 * Starts a drag on the whole mini player only after a long press. A normal tap, or a
 * movement beyond touch slop before the long-press timeout, is left to children and parents
 * so buttons and page scrolling keep working.
 */
private suspend fun PointerInputScope.detectLongPressDrag(
    onLongPress: () -> Unit,
    onDrag: (Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var cancelled = false
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    cancelled = true
                    return@withTimeoutOrNull
                }
                if (change.positionChange().getDistance() > viewConfiguration.touchSlop) {
                    cancelled = true
                    return@withTimeoutOrNull
                }
            }
        }
        if (cancelled) return@awaitEachGesture

        onLongPress()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val delta = change.positionChange()
            if (delta != Offset.Zero) {
                onDrag(delta)
                change.consume()
            }
        }
    }
}
