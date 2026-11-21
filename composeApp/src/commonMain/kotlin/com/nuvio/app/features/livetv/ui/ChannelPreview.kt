package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.player.LiveTvPlayback
import com.nuvio.app.features.livetv.player.VirtualChannelPlayback
import com.nuvio.app.features.livetv.sync.LiveTvDriveHttp
import com.nuvio.app.features.livetv.virtual.VirtualChannels
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pStreamRequest
import com.nuvio.app.features.p2p.P2pStreamingEngine
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.resolveAutoPlaybackEngine
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.YouTubeStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * "PIP Ch Preview": off by default. When on, picking a live channel or an add-on stream shows it
 * in the floating preview instead of opening the full player. Remembered on this device.
 */
internal object ChannelPreviewSettings {
    // A fresh key: the renamed "PIP Mode/Ch Preview" starts Off even on devices that had the
    // earlier preview switch on.
    private const val KEY = "pip_mode_ch_preview_enabled"

    private val _enabled = MutableStateFlow(
        runCatching { LiveTvDriveHttp.getBoolean(KEY, false) }.getOrDefault(false),
    )
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        runCatching { LiveTvDriveHttp.putBoolean(KEY, enabled) }
    }
}

/** What the preview is playing: a live channel, or an add-on catalog item's stream. */
internal sealed interface ChannelPreviewSource {
    val key: String
    val title: String
    val subtitle: String?
    val logo: String?
    val artwork: String?

    data class Live(
        val channel: LiveChannel,
        val nowTitle: String?,
    ) : ChannelPreviewSource {
        override val key: String = "live:${channel.key}"
        override val title: String = channel.name
        override val subtitle: String? = nowTitle ?: channel.group
        override val logo: String? = channel.logo
        override val artwork: String? = channel.thumbnail?.takeIf { it.isNotBlank() } ?: channel.logo
    }

    data class Addon(
        val item: MetaPreview,
        val videoId: String,
        val season: Int?,
        val episode: Int?,
    ) : ChannelPreviewSource {
        override val key: String = "addon:${item.type}:${item.id}:$videoId:$season:$episode"
        override val title: String = item.name
        override val subtitle: String? = if (season != null && episode != null) {
            "S" + season.toString().padStart(2, '0') + " E" + episode.toString().padStart(2, '0')
        } else {
            item.type
        }
        override val logo: String? = item.logo
        override val artwork: String? = item.banner?.takeIf { it.isNotBlank() } ?: item.poster
    }

    /** An already-built player launch: On Demand, a recording, or a web media stream. */
    data class Player(val launch: com.nuvio.app.features.player.PlayerLaunch) : ChannelPreviewSource {
        override val key: String = "player:${launch.videoId ?: launch.sourceUrl}:${launch.title}"
        override val title: String = launch.title
        override val subtitle: String? = launch.streamSubtitle
        override val logo: String? = launch.logo
        override val artwork: String? = launch.background?.takeIf { it.isNotBlank() }
            ?: launch.poster?.takeIf { it.isNotBlank() }
            ?: launch.logo
    }
}

/** One floating preview window: what it plays, where it sits, and whether it takes touch. */
internal data class ChannelPreviewPane(
    val id: Long,
    val source: ChannelPreviewSource,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val locked: Boolean = false,
    val muted: Boolean = false,
    /** The viewer moved or resized this pane, so automatic tiling leaves it alone. */
    val manual: Boolean = false,
    /** Where playback was when the pane was minimized; restored when it is reopened. */
    val resumePositionMs: Long = 0L,
    /** Changes on every minimize/restore so the player applies the resume position once. */
    val resumeToken: Long = 0L,
)

/**
 * Every floating preview, held above navigation so they float over every screen. A tap with
 * PIP on reuses the newest pane; "Play in PIP" and add-on long-press add another (up to 4
 * upright / 9 on a tablet).
 */
internal object ChannelPreviewRepository {
    private val _panes = MutableStateFlow<List<ChannelPreviewPane>>(emptyList())
    val panes: StateFlow<List<ChannelPreviewPane>> = _panes.asStateFlow()

    /** Windows folded away into the floating bubble, with the timestamp they were stopped at. */
    private val _minimized = MutableStateFlow<List<ChannelPreviewPane>>(emptyList())
    val minimized: StateFlow<List<ChannelPreviewPane>> = _minimized.asStateFlow()

    private var nextId = 1L

    /** Normal play with PIP on: reuse the newest window instead of stacking one per tap. */
    fun show(source: ChannelPreviewSource) {
        val list = _panes.value
        if (list.isEmpty()) {
            add(source)
            return
        }
        val newest = list.last()
        _panes.value = list.dropLast(1).map { it.copy(muted = true) } +
            newest.copy(
                source = source,
                muted = false,
                resumePositionMs = 0L,
                resumeToken = newest.resumeToken + 1,
            )
    }

    fun show(channel: LiveChannel, nowTitle: String?) = show(ChannelPreviewSource.Live(channel, nowTitle))

    /** Explicit "Play in PIP": opens another window. */
    fun add(source: ChannelPreviewSource) {
        val list = _panes.value
        if (list.any { it.source.key == source.key }) {
            // Already open: bring the existing one to the front and give it audio.
            _panes.value = list.map { it.copy(muted = it.source.key != source.key) }
            return
        }
        val pane = ChannelPreviewPane(id = nextId++, source = source, muted = false)
        _panes.value = list.map { it.copy(muted = true) } + pane
    }

    fun add(channel: LiveChannel, nowTitle: String?) = add(ChannelPreviewSource.Live(channel, nowTitle))

    /** Folds a window into the circular bubble, remembering where its playback stopped. */
    fun minimize(id: Long, positionMs: Long) {
        val pane = _panes.value.firstOrNull { it.id == id } ?: return
        _panes.value = _panes.value.filterNot { it.id == id }
        _minimized.value = _minimized.value.filterNot { it.id == id } +
            pane.copy(
                resumePositionMs = positionMs.coerceAtLeast(0L),
                resumeToken = pane.resumeToken + 1,
                muted = true,
                locked = false,
            )
    }

    /** Reopens a minimized window at its old position/size and playback timestamp. */
    fun restore(id: Long) {
        val pane = _minimized.value.firstOrNull { it.id == id } ?: return
        _minimized.value = _minimized.value.filterNot { it.id == id }
        val existing = _panes.value.filterNot { it.id == id }
        _panes.value = existing.map { it.copy(muted = true) } +
            pane.copy(
                muted = false,
                locked = false,
                manual = true,
                resumeToken = pane.resumeToken + 1,
            )
    }

    fun removeMinimized(id: Long) {
        _minimized.value = _minimized.value.filterNot { it.id == id }
    }

    fun remove(id: Long) {
        _panes.value = _panes.value.filterNot { it.id == id }
        _minimized.value = _minimized.value.filterNot { it.id == id }
    }

    /** The touched window moves to the top, so overlapping PiPs stack naturally. */
    fun bringToFront(id: Long) {
        val list = _panes.value
        if (list.lastOrNull()?.id == id) return
        val pane = list.firstOrNull { it.id == id } ?: return
        _panes.value = list.filterNot { it.id == id } + pane
    }

    fun setBounds(id: Long, x: Float, y: Float, width: Float, height: Float, manual: Boolean) {
        _panes.value = _panes.value.map { pane ->
            if (pane.id == id) {
                pane.copy(x = x, y = y, width = width, height = height, manual = manual || pane.manual)
            } else {
                pane
            }
        }
    }

    fun setLocked(id: Long, locked: Boolean) {
        _panes.value = _panes.value.map { if (it.id == id) it.copy(locked = locked) else it }
    }

    fun setMuted(id: Long, muted: Boolean) {
        _panes.value = _panes.value.map { if (it.id == id) it.copy(muted = muted) else it }
    }

    fun trimTo(maxPanes: Int) {
        val list = _panes.value
        if (list.size > maxPanes) _panes.value = list.takeLast(maxPanes)
    }

    fun hide() {
        _panes.value = emptyList()
        _minimized.value = emptyList()
    }
}

/** One playable link, normalised from either Live TV, Lucid TV or an add-on stream. */
private data class PreviewPlayback(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val audioUrl: String? = null,
)

/** The PIP Ch Preview row, sized and styled like the All Playlists picker below it. */
@Composable
internal fun ChannelPreviewToggle(modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val enabled by ChannelPreviewSettings.enabled.collectAsStateWithLifecycle()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(tokens.colors.surface)
            .clickable { ChannelPreviewSettings.setEnabled(!enabled) }
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Icon(
            Icons.Filled.PictureInPictureAlt,
            contentDescription = null,
            tint = if (enabled) tokens.colors.accent else tokens.colors.textMuted,
            modifier = Modifier.size(NuvioTokens.Icon.md),
        )
        Text(
            text = "PIP Mode/Ch Preview",
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (enabled) "On" else "Off",
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) tokens.colors.accent else tokens.colors.textMuted,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .size(width = 34.dp, height = 20.dp)
                .clip(CircleShape)
                .background(if (enabled) tokens.colors.accent else tokens.colors.textMuted.copy(alpha = 0.35f)),
        ) {
            Box(
                modifier = Modifier
                    .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(2.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/**
 * Hosts every floating preview above navigation. The first opens as the large card; adding
 * another tiles them, up to 4 upright and 9 on a tablet. Each window can be moved (tap and
 * hold), resized and locked on its own.
 */
@Composable
internal fun ChannelPreviewHost(
    onExpand: (ChannelPreviewPane) -> Unit,
    modifier: Modifier = Modifier,
) {
    val panes by ChannelPreviewRepository.panes.collectAsStateWithLifecycle()
    val minimized by ChannelPreviewRepository.minimized.collectAsStateWithLifecycle()
    if (panes.isEmpty() && minimized.isEmpty()) return
    var folderOpen by remember { mutableStateOf(false) }
    LaunchedEffect(minimized.isEmpty()) {
        if (minimized.isEmpty()) folderOpen = false
    }
    PlatformBackHandler(enabled = folderOpen || panes.isNotEmpty()) {
        if (folderOpen) {
            folderOpen = false
        } else {
            panes.lastOrNull()?.let { ChannelPreviewRepository.remove(it.id) }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val marginPx = with(density) { 12.dp.toPx() }
        val gapPx = with(density) { 8.dp.toPx() }
        val topPx = with(density) { 64.dp.toPx() }
        val maxWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val maxHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val minWidthPx = minOf(with(density) { 150.dp.toPx() }, maxWidthPx)
        val minHeightPx = minOf(with(density) { 100.dp.toPx() }, maxHeightPx)
        val tablet = maxWidth >= 600.dp
        val maxPanes = if (tablet) 9 else 4
        val bubbleRadiusPx = with(density) { 28.dp.toPx() }
        var folderX by remember {
            mutableFloatStateOf(maxWidthPx - bubbleRadiusPx - with(density) { 16.dp.toPx() })
        }
        var folderY by remember {
            mutableFloatStateOf(maxHeightPx - bubbleRadiusPx - with(density) { 120.dp.toPx() })
        }
        LaunchedEffect(maxWidthPx, maxHeightPx) {
            folderX = folderX.coerceIn(bubbleRadiusPx, (maxWidthPx - bubbleRadiusPx).coerceAtLeast(bubbleRadiusPx))
            folderY = folderY.coerceIn(bubbleRadiusPx, (maxHeightPx - bubbleRadiusPx).coerceAtLeast(bubbleRadiusPx))
        }

        LaunchedEffect(panes.size, maxWidthPx, maxHeightPx, tablet) {
            ChannelPreviewRepository.trimTo(maxPanes)
            val list = ChannelPreviewRepository.panes.value
            if (list.isEmpty()) return@LaunchedEffect
            val columns = if (tablet) 3 else 2
            val count = list.size
            val grid = count > 1
            val rows = ((count + columns - 1) / columns).coerceAtLeast(1)
            val largeWidth = (maxWidthPx - marginPx * 2f).coerceIn(minWidthPx, maxWidthPx)
            val largeHeight = with(density) { 300.dp.toPx() }
                .coerceIn(minHeightPx, (maxHeightPx - topPx - marginPx).coerceAtLeast(minHeightPx))
            val cellWidth = (
                (maxWidthPx - marginPx * 2f - gapPx * (columns - 1)) / columns
                ).coerceIn(minWidthPx, maxWidthPx)
            val cellHeight = (
                (maxHeightPx * 0.62f - gapPx * (rows - 1)) / rows
                ).coerceIn(minHeightPx, with(density) { 230.dp.toPx() }.coerceAtLeast(minHeightPx))
            var slot = 0
            list.forEach { pane ->
                if (pane.manual) {
                    // Keep a viewer-placed window on screen after rotation or a window resize.
                    val width = pane.width.coerceIn(minWidthPx, maxWidthPx)
                    val height = pane.height.coerceIn(minHeightPx, maxHeightPx)
                    ChannelPreviewRepository.setBounds(
                        id = pane.id,
                        x = pane.x.coerceIn(0f, (maxWidthPx - width).coerceAtLeast(0f)),
                        y = pane.y.coerceIn(0f, (maxHeightPx - height).coerceAtLeast(0f)),
                        width = width,
                        height = height,
                        manual = true,
                    )
                    return@forEach
                }
                if (!grid) {
                    ChannelPreviewRepository.setBounds(
                        id = pane.id,
                        x = if (tablet) marginPx else ((maxWidthPx - largeWidth) / 2f).coerceAtLeast(0f),
                        y = topPx,
                        width = largeWidth,
                        height = largeHeight,
                        manual = false,
                    )
                } else {
                    val column = slot % columns
                    val row = slot / columns
                    slot++
                    ChannelPreviewRepository.setBounds(
                        id = pane.id,
                        x = marginPx + column * (cellWidth + gapPx),
                        y = topPx + row * (cellHeight + gapPx),
                        width = cellWidth,
                        height = cellHeight,
                        manual = false,
                    )
                }
            }
        }

        panes.forEach { pane ->
            if (pane.width <= 0f || pane.height <= 0f) return@forEach
            key(pane.id) {
                ChannelPreviewWindow(
                    pane = pane,
                    onToggleLock = { ChannelPreviewRepository.setLocked(pane.id, !pane.locked) },
                    onExpand = { if (!pane.locked) onExpand(pane) },
                    onClose = { ChannelPreviewRepository.remove(pane.id) },
                    onMinimize = { positionMs -> ChannelPreviewRepository.minimize(pane.id, positionMs) },
                    onMoveBy = { dx, dy ->
                        if (!pane.locked) {
                            ChannelPreviewRepository.setBounds(
                                id = pane.id,
                                x = (pane.x + dx).coerceIn(0f, (maxWidthPx - pane.width).coerceAtLeast(0f)),
                                y = (pane.y + dy).coerceIn(0f, (maxHeightPx - pane.height).coerceAtLeast(0f)),
                                width = pane.width,
                                height = pane.height,
                                manual = true,
                            )
                        }
                    },
                    onResizeBy = { dx, dy ->
                        if (!pane.locked) {
                            val nextWidth = (pane.width + dx).coerceIn(minWidthPx, maxWidthPx)
                            val nextHeight = (pane.height + dy).coerceIn(minHeightPx, maxHeightPx)
                            ChannelPreviewRepository.setBounds(
                                id = pane.id,
                                x = pane.x.coerceIn(0f, (maxWidthPx - nextWidth).coerceAtLeast(0f)),
                                y = pane.y.coerceIn(0f, (maxHeightPx - nextHeight).coerceAtLeast(0f)),
                                width = nextWidth,
                                height = nextHeight,
                                manual = true,
                            )
                        }
                    },
                    onMuteChange = { ChannelPreviewRepository.setMuted(pane.id, it) },
                    modifier = Modifier
                        .offset { IntOffset(pane.x.roundToInt(), pane.y.roundToInt()) }
                        .width(with(density) { pane.width.toDp() })
                        .height(with(density) { pane.height.toDp() }),
                )
            }
        }

        if (folderOpen) {
            // A dialog is its own window, so the minimized items are reliably tappable above
            // the app and the active PiPs.
            Dialog(
                onDismissRequest = { folderOpen = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xF21A1A1A))
                            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Minimized PiPs",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                        minimized.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { pane ->
                                    MinimizedPaneButton(pane) {
                                        folderOpen = false
                                        ChannelPreviewRepository.restore(pane.id)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (minimized.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (folderX - bubbleRadiusPx).roundToInt(),
                            (folderY - bubbleRadiusPx).roundToInt(),
                        )
                    }
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.82f))
                    .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                    .clickable { folderOpen = !folderOpen }
                    .pointerInput(maxWidthPx, maxHeightPx) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            folderX = (folderX + drag.x).coerceIn(
                                bubbleRadiusPx,
                                (maxWidthPx - bubbleRadiusPx).coerceAtLeast(bubbleRadiusPx),
                            )
                            folderY = (folderY + drag.y).coerceIn(
                                bubbleRadiusPx,
                                (maxHeightPx - bubbleRadiusPx).coerceAtLeast(bubbleRadiusPx),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = "Minimized PiPs",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.nuvio.colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = minimized.size.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** One folded-away window in the circular folder. */
@Composable
private fun MinimizedPaneButton(
    pane: ChannelPreviewPane,
    onClick: () -> Unit,
) {
    val image = pane.source.artwork ?: pane.source.logo
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFF2A2A2A))
            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = pane.source.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
            )
        } else {
            Text(
                text = pane.source.title.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ChannelPreviewWindow(
    pane: ChannelPreviewPane,
    onToggleLock: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    onMinimize: (positionMs: Long) -> Unit,
    onMoveBy: (dxPx: Float, dyPx: Float) -> Unit,
    onResizeBy: (dxPx: Float, dyPx: Float) -> Unit,
    onMuteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val screenActive = LocalScreenActive.current
    val source = pane.source
    val locked = pane.locked
    var playback by remember(source.key) { mutableStateOf<PreviewPlayback?>(null) }
    var error by remember(source.key) { mutableStateOf<String?>(null) }
    var snapshot by remember(source.key) { mutableStateOf(PlayerPlaybackSnapshot()) }
    var controller by remember(source.key) { mutableStateOf<PlayerEngineController?>(null) }
    var volume by remember { mutableFloatStateOf(1f) }
    var muted by remember(pane.id) { mutableStateOf(pane.muted) }
    var touchCount by remember { mutableIntStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    var p2pStarted by remember { mutableStateOf(false) }
    val started = snapshot.positionMs > 0L || snapshot.isPlaying
    val playerSettings by PlayerSettingsRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { PlayerSettingsRepository.ensureLoaded() }
    val previewEngine = remember(source.key, playerSettings.androidPlaybackEngine) {
        when (source) {
            is ChannelPreviewSource.Player -> resolveAutoPlaybackEngine(
                playbackEngineSetting = playerSettings.androidPlaybackEngine,
                videoId = source.launch.videoId,
                streamFilename = source.launch.sourceFilename ?: source.launch.stream?.behaviorHints?.filename,
                streamName = source.launch.streamTitle,
                streamDescription = source.launch.streamSubtitle,
                contentTitle = source.launch.title,
                genres = emptyList(),
                country = null,
            )
            is ChannelPreviewSource.Addon -> resolveAutoPlaybackEngine(
                playbackEngineSetting = playerSettings.androidPlaybackEngine,
                videoId = source.videoId,
                streamFilename = null,
                streamName = source.item.name,
                streamDescription = source.item.description,
                contentTitle = source.item.name,
                genres = source.item.genres,
                country = null,
            )
            is ChannelPreviewSource.Live -> null
        }
    }

    DisposableEffect(pane.id) {
        onDispose {
            if (p2pStarted) runCatching { P2pStreamingEngine.stopStream() }
        }
    }
    fun interacted() {
        touchCount += 1
        ChannelPreviewRepository.bringToFront(pane.id)
    }
    val showControls = controlsVisible && !locked

    LaunchedEffect(pane.muted) {
        muted = pane.muted
        controller?.setMuted(muted)
    }
    LaunchedEffect(touchCount) {
        controlsVisible = true
        delay(PREVIEW_CONTROLS_TIMEOUT_MS)
        controlsVisible = false
    }

    // Keep the drag callbacks stable: a changing lambda key would cancel a gesture as soon as
    // touching the window triggers a recomposition.
    val moveByNow by rememberUpdatedState(onMoveBy)
    val resizeByNow by rememberUpdatedState(onResizeBy)

    LaunchedEffect(source.key) {
        if (p2pStarted) {
            runCatching { P2pStreamingEngine.stopStream() }
            p2pStarted = false
        }
        playback = null
        error = null
        controller = null
        snapshot = PlayerPlaybackSnapshot()
        runCatching {
            when (source) {
                is ChannelPreviewSource.Live -> {
                    if (VirtualChannels.isVirtualKey(source.channel.key)) {
                        val outcome = VirtualChannelPlayback.resolve(source.channel, ProfileRepository.activeProfileId)
                        val resolved = outcome.resolved
                        val direct = resolved?.stream?.playableDirectUrl
                        if (direct != null) {
                            val proxy = resolved.stream.behaviorHints.proxyHeaders
                            playback = PreviewPlayback(
                                url = direct,
                                headers = proxy?.request.orEmpty(),
                                responseHeaders = proxy?.response.orEmpty(),
                                streamType = resolved.stream.streamType,
                            )
                        } else if (resolved != null) {
                            error = "Tap to open this channel"
                        } else {
                            error = outcome.message ?: "This Lucid TV channel is offline right now"
                        }
                    } else {
                        val request = LiveTvPlayback.requestFor(source.channel)
                        playback = PreviewPlayback(
                            url = request.url,
                            headers = request.headers,
                            streamType = request.streamType,
                        )
                    }
                }
                is ChannelPreviewSource.Addon -> {
                    val resolved = resolveAddonPreview(source)
                    if (resolved != null) {
                        playback = resolved
                    } else {
                        error = "No preview stream. Tap to open the full player."
                    }
                }
                is ChannelPreviewSource.Player -> {
                    val launch = source.launch
                    val sentinel = launch.sourceUrl.startsWith("torrent://") || launch.sourceUrl.startsWith("torrent:")
                    if (sentinel) {
                        // Nuvio movies/series from torrent add-ons carry a sentinel URL. Bring up
                        // the same P2P stream the full player would, then hand the local URL over.
                        val hash = launch.torrentInfoHash ?: launch.stream?.p2pInfoHash
                        if (hash.isNullOrBlank()) {
                            error = "This torrent has no stream to preview. Tap to open the full player."
                        } else if (!P2pSettingsRepository.uiState.value.p2pEnabled) {
                            error = "P2P is off. Turn it on to preview this source, or tap to open the full player."
                        } else {
                            val localUrl = withTimeoutOrNull(40_000L) {
                                runCatching {
                                    P2pStreamingEngine.startStream(
                                        P2pStreamRequest(
                                            infoHash = hash,
                                            fileIdx = launch.torrentFileIdx ?: launch.stream?.p2pFileIdx,
                                            filename = launch.torrentFilename ?: launch.stream?.behaviorHints?.filename,
                                            trackers = launch.torrentTrackers.ifEmpty { launch.stream?.p2pTrackers.orEmpty() },
                                        ),
                                    )
                                }.getOrNull()
                            }
                            if (localUrl != null) {
                                p2pStarted = true
                                playback = PreviewPlayback(
                                    url = localUrl,
                                    streamType = launch.streamType,
                                    audioUrl = launch.sourceAudioUrl,
                                )
                            } else {
                                error = "This torrent didn't start in the preview. Tap to open the full player."
                            }
                        }
                    } else {
                        playback = PreviewPlayback(
                            url = launch.sourceUrl,
                            headers = launch.sourceHeaders,
                            responseHeaders = launch.sourceResponseHeaders,
                            streamType = launch.streamType,
                            audioUrl = launch.sourceAudioUrl,
                        )
                    }
                }
            }
        }.onFailure { failure ->
            error = failure.message?.takeIf { it.isNotBlank() } ?: "This stream didn't open"
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(NuvioTokens.Radius.md)),
    ) {
        // A fixed strip above the video. When the controls time out the strip stays black,
        // so showing them again never resizes or shifts the video.
        Box(
            modifier = Modifier.fillMaxWidth().height(44.dp).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (locked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Locked",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    SmallPreviewButton(
                        icon = Icons.Rounded.Lock,
                        description = "Unlock preview",
                        onClick = { interacted(); onToggleLock() },
                    )
                }
            } else if (showControls) {
                PreviewTopBar(
                    source = source,
                    onTouch = { interacted() },
                    onMoveBy = onMoveBy,
                    onToggleLock = onToggleLock,
                    onExpand = onExpand,
                    onClose = onClose,
                    onMinimize = { onMinimize(snapshot.positionMs) },
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            if (source.artwork != null) {
                AsyncImage(
                    model = source.artwork,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.Center,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.25f),
                                0.55f to Color.Black.copy(alpha = 0.55f),
                                1f to Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                )
            }

            playback?.let { play ->
                PlatformPlayerSurface(
                    sourceUrl = play.url,
                    sourceAudioUrl = play.audioUrl,
                    sourceHeaders = play.headers,
                    sourceResponseHeaders = play.responseHeaders,
                    streamType = play.streamType,
                    modifier = Modifier.fillMaxSize(),
                    playWhenReady = screenActive,
                    playbackEngine = previewEngine,
                    initialPositionMs = pane.resumePositionMs.takeIf { it > 0L },
                    initialPositionRequestKey = "pip:${pane.id}:${pane.resumeToken}",
                    // Crop-to-fill: the picture uses the whole video area instead of leaving
                    // black bars top and bottom when the window is not 16:9.
                    resizeMode = PlayerResizeMode.Zoom,
                    allowConcurrentAudio = true,
                    // Unlocked, Compose receives taps over the video for the controls. Locked,
                    // the video itself takes them so nothing reaches the list behind.
                    interceptTouches = locked,
                    onControllerReady = { ready ->
                        controller = ready
                        ready.setVolume(volume)
                        ready.setMuted(muted)
                    },
                    onSnapshot = { snapshot = it },
                    onError = { message -> if (!message.isNullOrBlank()) error = message },
                )
            }

            if (!locked) {
                // The video is the touch surface: a tap brings the controls back, a hold picks
                // the window up. The control bars live outside this box, so their buttons win.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { interacted() }
                        .pointerInput(locked) {
                            val resizeCornerPx = 56.dp.toPx()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val longPress = awaitLongPressOrCancellation(down.id)
                                if (longPress != null) {
                                    longPress.consume()
                                    interacted()
                                    // Holding the bottom-right corner resizes; anywhere else moves.
                                    val resizing = down.position.x >= size.width - resizeCornerPx &&
                                        down.position.y >= size.height - resizeCornerPx
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.positionChanged()) {
                                            val delta = change.positionChange()
                                            change.consume()
                                            if (resizing) resizeByNow(delta.x, delta.y) else moveByNow(delta.x, delta.y)
                                        }
                                        if (!change.pressed) {
                                            change.consume()
                                            break
                                        }
                                    }
                                } else if (currentEvent.changes.any { it.id == down.id && it.pressed }) {
                                    // A quick swipe must not scroll the list under the preview.
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.positionChanged()) change.consume()
                                        if (!change.pressed) break
                                    }
                                }
                            }
                        },
                )
            }

            if (showControls) {
                // A visual hint only: the drag itself is handled by the video touch layer.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Fullscreen,
                        contentDescription = "Hold and drag to resize",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }

            if (!started && error == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s10),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                ) {
                    if (source.logo != null) {
                        AsyncImage(
                            model = source.logo,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(width = 96.dp, height = 54.dp),
                        )
                    } else {
                        Text(
                            text = source.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "Tuning…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                }
            }

            error?.let { message ->
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable { onExpand() }
                        .padding(horizontal = NuvioTokens.Space.s10),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.92f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Tap to open",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Fixed strip below the video, for the same reason as the top strip.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(Color.Black)
                .then(
                    if (locked) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (showControls) {
                PreviewBottomBar(
                    snapshot = snapshot,
                    muted = muted,
                    onTouch = { interacted() },
                    onPlayPause = {
                        interacted()
                        if (snapshot.isPlaying) controller?.pause() else controller?.play()
                    },
                    onSeek = { fraction ->
                        interacted()
                        if (snapshot.durationMs > 0L) controller?.seekTo((snapshot.durationMs * fraction).toLong())
                    },
                    onMute = {
                        interacted()
                        muted = !muted
                        controller?.setMuted(muted)
                        onMuteChange(muted)
                    },
                    onVolumeDown = {
                        interacted()
                        volume = (volume - 0.1f).coerceIn(0f, 1f)
                        muted = volume <= 0f
                        controller?.setVolume(volume)
                        controller?.setMuted(muted)
                        onMuteChange(muted)
                    },
                    onVolumeUp = {
                        interacted()
                        volume = (volume + 0.1f).coerceIn(0f, 1f)
                        muted = volume <= 0f
                        controller?.setVolume(volume)
                        controller?.setMuted(muted)
                        onMuteChange(muted)
                    },
                    onResizeBy = onResizeBy,
                )
            }
        }
    }
}

@Composable
private fun PreviewTopBar(
    source: ChannelPreviewSource,
    onTouch: () -> Unit,
    onMoveBy: (dxPx: Float, dyPx: Float) -> Unit,
    onToggleLock: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
) {
    val moveByNow by rememberUpdatedState(onMoveBy)
    val touchNow by rememberUpdatedState(onTouch)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(start = NuvioTokens.Space.s6, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Minimize lives on the far left, away from Close on the right, so it cannot be hit
        // by mistake when reaching for the close button.
        SmallPreviewButton(
            icon = Icons.Rounded.Minimize,
            description = "Minimize preview",
            onClick = { onTouch(); onMinimize() },
        )
        // The title area is this window's move handle. It claims the touch on the way down,
        // so the scrollable list behind cannot steal the drag.
        Row(
            modifier = Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        touchNow()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.positionChanged()) {
                                val delta = change.positionChange()
                                change.consume()
                                moveByNow(delta.x, delta.y)
                            }
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.DragIndicator,
                    contentDescription = "Drag to move",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
                Text(
                    text = source.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                source.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        SmallPreviewButton(
            icon = Icons.Rounded.LockOpen,
            description = "Lock position",
            onClick = { onTouch(); onToggleLock() },
        )
        SmallPreviewButton(
            icon = Icons.Rounded.Fullscreen,
            description = "Open full player",
            onClick = { onTouch(); onExpand() },
        )
        SmallPreviewButton(
            icon = Icons.Rounded.Minimize,
            description = "Minimize preview",
            onClick = { onTouch(); onMinimize() },
        )
        SmallPreviewButton(
            icon = Icons.Filled.Close,
            description = "Close preview",
            onClick = onClose,
        )
    }
}

@Composable
private fun PreviewBottomBar(
    snapshot: PlayerPlaybackSnapshot,
    muted: Boolean,
    onTouch: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (fraction: Float) -> Unit,
    onMute: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onResizeBy: (dxPx: Float, dyPx: Float) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val resizeByNow by rememberUpdatedState(onResizeBy)
    val touchNow by rememberUpdatedState(onTouch)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val compact = maxWidth < 200.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(horizontal = NuvioTokens.Space.s6, vertical = 2.dp),
    ) {
        if (snapshot.durationMs > 0L) {
            PreviewProgressBar(
                progress = (snapshot.positionMs.toFloat() / snapshot.durationMs).coerceIn(0f, 1f),
                onSeek = onSeek,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(tokens.colors.danger))
                Text(
                    "LIVE",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f)),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallPreviewButton(
                icon = if (snapshot.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                description = if (snapshot.isPlaying) "Pause" else "Play",
                onClick = onPlayPause,
            )
            Text(
                text = if (snapshot.durationMs > 0L) formatPreviewTime(snapshot.positionMs) else "LIVE",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            SmallPreviewButton(
                icon = if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                description = if (muted) "Unmute" else "Mute",
                onClick = onMute,
            )
            if (!compact) {
                SmallPreviewButton(
                    icon = Icons.Rounded.VolumeDown,
                    description = "Volume down",
                    onClick = onVolumeDown,
                )
                SmallPreviewButton(
                    icon = Icons.Rounded.VolumeUp,
                    description = "Volume up",
                    onClick = onVolumeUp,
                )
            }
            // Keep a gap so the volume buttons' larger touch targets cannot steal this drag.
            Spacer(modifier = Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(topStart = NuvioTokens.Radius.md, bottomEnd = NuvioTokens.Radius.md))
                    .background(Color.White.copy(alpha = 0.08f))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Claim the gesture at once so the list behind cannot scroll instead.
                            down.consume()
                            touchNow()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.positionChanged()) {
                                    val delta = change.positionChange()
                                    change.consume()
                                    resizeByNow(delta.x, delta.y)
                                }
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Fullscreen,
                    contentDescription = "Drag to resize",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun SmallPreviewButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    // A plain Box rather than IconButton: Material's minimum touch target would otherwise
    // overlap the neighbouring buttons (and the resize corner) on a window this small.
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun PreviewProgressBar(
    progress: Float,
    onSeek: (fraction: Float) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onSeek((change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.28f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .clip(CircleShape)
                .background(tokens.colors.accent),
        )
        Box(
            modifier = Modifier
                .offset(x = (maxWidth * progress.coerceIn(0f, 1f)) - 4.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

private fun formatPreviewTime(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

/** Resolves an add-on item's stream the same way the player does, but only to a direct URL. */
private suspend fun resolveAddonPreview(source: ChannelPreviewSource.Addon): PreviewPlayback? =
    withContext(Dispatchers.Default) {
        val candidates = withTimeoutOrNull(PREVIEW_RESOLVE_TIMEOUT_MS) {
            runCatching {
                StreamsRepository.resolveStreamCandidates(
                    type = source.item.type,
                    videoId = source.videoId,
                    parentMetaId = source.item.id,
                    season = source.season,
                    episode = source.episode,
                    timeoutMs = 20_000L,
                )
            }.getOrNull()
        }.orEmpty()

        fun fromStream(stream: StreamItem): PreviewPlayback? {
            val url = stream.playableDirectUrl ?: return null
            val proxy = stream.behaviorHints.proxyHeaders
            return PreviewPlayback(
                url = url,
                headers = proxy?.request.orEmpty(),
                responseHeaders = proxy?.response.orEmpty(),
                streamType = stream.streamType,
            )
        }

        candidates.firstNotNullOfOrNull(::fromStream)?.let { return@withContext it }

        // A slower server/debrid/YouTube source is still worth a try for the preview.
        for (stream in candidates.take(8)) {
            if (stream.serverTarget != null || stream.needsServerPreparation) {
                val prepared = withTimeoutOrNull(PREVIEW_SOURCE_PREPARE_MS) {
                    runCatching { ServerPlayback.prepare(stream) }.getOrNull()
                }
                prepared?.playableDirectUrl?.let { url ->
                    val proxy = prepared.behaviorHints.proxyHeaders
                    return@withContext PreviewPlayback(
                        url = url,
                        headers = proxy?.request.orEmpty(),
                        responseHeaders = proxy?.response.orEmpty(),
                        streamType = prepared.streamType,
                    )
                }
            }
            if (stream.youTubeIdToResolve != null) {
                val resolved = withTimeoutOrNull(PREVIEW_SOURCE_PREPARE_MS) {
                    runCatching { YouTubeStreamResolver.Default.resolve(stream) }.getOrNull()
                }
                resolved?.playableDirectUrl?.let { url ->
                    val proxy = resolved.behaviorHints.proxyHeaders
                    return@withContext PreviewPlayback(
                        url = url,
                        headers = proxy?.request.orEmpty(),
                        responseHeaders = proxy?.response.orEmpty(),
                        streamType = resolved.streamType,
                    )
                }
            }
            if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
                val resolved = withTimeoutOrNull(PREVIEW_SOURCE_PREPARE_MS) {
                    runCatching {
                        DirectDebridPlaybackResolver.resolveToPlayableStream(stream, source.season, source.episode)
                    }.getOrNull()
                }
                val playable = (resolved as? DirectDebridPlayableResult.Success)?.stream
                playable?.let(::fromStream)?.let { return@withContext it }
            }
        }
        null
    }

private const val PREVIEW_CONTROLS_TIMEOUT_MS = 3_200L
private const val PREVIEW_RESOLVE_TIMEOUT_MS = 30_000L
private const val PREVIEW_SOURCE_PREPARE_MS = 10_000L
