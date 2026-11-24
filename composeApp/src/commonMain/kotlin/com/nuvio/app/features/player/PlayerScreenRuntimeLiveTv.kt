package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.player.LiveTvPlayback
import com.nuvio.app.features.livetv.player.VirtualChannelPlayback
import com.nuvio.app.features.livetv.ui.LiveTvPlayerOverlay
import com.nuvio.app.features.livetv.ui.LiveTvViewState
import com.nuvio.app.features.livetv.virtual.VirtualChannelRepository
import com.nuvio.app.features.livetv.virtual.VirtualChannels
import kotlinx.coroutines.launch

/** Whether a Live TV channel (or a catch-up replay of one) is playing. */
internal val PlayerScreenRuntime.isLiveTv: Boolean
    get() = LiveTvPlayback.owns(activeProviderAddonId)

/** Whether what's playing came from the Live TV section: live, catch-up, a recording or On Demand. */
internal val PlayerScreenRuntime.isLiveTvContent: Boolean
    get() = activeFromLiveTvSection || LiveTvPlayback.ownsContent(activeProviderAddonId)

/** The title for the controls: the channel now on screen, which changes as you switch channels. */
internal fun PlayerScreenRuntime.liveTitleOr(fallback: String): String =
    activeStreamTitle.takeIf { isLiveTv && it.isNotBlank() } ?: fallback

/** The logo for the pause screen: the channel now on screen. */
internal fun PlayerScreenRuntime.liveLogoOr(fallback: String?): String? {
    if (!isLiveTv) return fallback
    val key = activeVideoId ?: return fallback
    return LiveTvViewState.list.value.visible.firstOrNull { it.key == key }?.logo ?: fallback
}

/** Changes channel in place (the channel panel or a swipe), keeping the player open. */
internal fun PlayerScreenRuntime.switchLiveChannel(channel: LiveChannel, nowTitle: String?) {
    if (!VirtualChannels.isVirtualKey(channel.key) &&
        channel.key == activeVideoId && LiveTvPlayback.owns(activeProviderAddonId) && errorMessage == null
    ) return
    if (VirtualChannels.isVirtualKey(channel.key)) {
        // Lucid TV: resolve the item on now in the background, then swap it in. The last channel
        // picked wins when the viewer zaps quickly.
        addonCatalogPlayJob?.cancel()
        addonCatalogPlayJob = null
        virtualSwitchJob?.cancel()
        // Show the incoming channel's own name/artwork while the item resolves, like a tuner.
        streamTransition = PlayerStreamTransition(
            artwork = channelTransitionArtwork(channel),
            logo = channel.logo?.takeIf { it.isNotBlank() },
            title = channel.name,
        )
        virtualSwitchJob = scope.launch {
            val outcome = VirtualChannelPlayback.resolve(channel, profileId)
            val resolved = outcome.resolved
            if (resolved == null) {
                com.nuvio.app.core.ui.NuvioToastController.show(
                    outcome.message ?: "This Lucid TV channel is offline right now"
                )
                return@launch
            }
            val tune = resolved.tune
            flushWatchProgress()
            credentialRefreshJob?.cancel()
            errorMessage = null
            externalSubtitles = emptyList()
            activeSourceAudioUrl = null
            activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(resolved.stream.behaviorHints.proxyHeaders?.response)
            // switchToSource adopts the stream's own add-on id and the previous position; Lucid TV
            // puts its own live identity and the join offset back right afterwards.
            activeSeasonNumber = tune.season
            activeEpisodeNumber = tune.episode
            switchToSource(resolved.stream, saveForReuse = false)
            activeStreamTitle = channel.name
            activeStreamSubtitle = tune.title
            activePauseDescription = tune.item.description
            activeProviderName = channel.sourceName
            activeProviderAddonId = LiveTvPlayback.PROVIDER_ID
            activeVideoId = channel.key
            activeEpisodeTitle = tune.episodeTitle
            activeEpisodeThumbnail = tune.item.poster
            activeInitialPositionMs = tune.startOffsetMs
            activeInitialProgressFraction = null
            streamTransition = PlayerStreamTransition(
                artwork = tune.item.backdrop?.takeIf { it.isNotBlank() }
                    ?: tune.item.poster?.takeIf { it.isNotBlank() }
                    ?: channelTransitionArtwork(channel),
                logo = channel.logo?.takeIf { it.isNotBlank() },
                title = channel.name,
            )
        }
        return
    }
    // A channel switch always wins over a slow add-on catalog lookup.
    addonCatalogPlayJob?.cancel()
    addonCatalogPlayJob = null
    virtualSwitchJob?.cancel()
    virtualSwitchJob = null
    activeFromLiveTvSection = true
    val req = LiveTvPlayback.switchTo(channel)
    flushWatchProgress()
    credentialRefreshJob?.cancel()
    errorMessage = null
    externalSubtitles = emptyList()
    activeSourceUrl = req.url
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(req.headers)
    activeSourceResponseHeaders = sanitizePlaybackResponseHeaders(null)
    activeStreamType = req.streamType
    activeSourceIdentityKey = "url:${req.url}"
    activeSourceFilename = null
    activeStreamFilename = sourceFilenameFromUrl(req.url)
    activeStreamTitle = channel.name
    activeStreamSubtitle = nowTitle ?: channel.group
    activePauseDescription = nowTitle
    activeProviderName = channel.sourceName
    activeProviderAddonId = LiveTvPlayback.PROVIDER_ID
    activeVideoId = channel.key
    activeInitialPositionMs = 0L
    activeInitialProgressFraction = null
    // Show the incoming channel's own artwork while it tunes in, even if the previous source
    // never finished loading (the artwork effect below keeps it in sync afterwards).
    streamTransition = PlayerStreamTransition(
        artwork = channelTransitionArtwork(channel),
        logo = channel.logo?.takeIf { it.isNotBlank() },
        title = channel.name,
    )
}

/**
 * The incoming channel's own artwork: its thumbnail first, then what's on now, then its logo.
 * Never falls back to the previous source's backdrop/poster, which would show the wrong channel
 * during the tuning transition.
 */
private fun channelTransitionArtwork(channel: LiveChannel): String? {
    if (channel.thumbnail?.isNotBlank() == true) return channel.thumbnail
    val now = LiveTvClock.nowMs()
    val programIcon = LiveTvRepository.programs.value[channel.key]
        ?.firstOrNull { now >= it.startMs && now < it.stopMs }
        ?.icon
        ?.takeIf { it.isNotBlank() }
    return programIcon ?: channel.logo?.takeIf { it.isNotBlank() }
}

/** The artwork/logo/title of the source being tuned in, used by the stream transition overlay. */
internal fun PlayerScreenRuntime.incomingStreamTransition(): PlayerStreamTransition {
    val channel = if (isLiveTv) {
        val key = activeVideoId
        key?.let { channelKey ->
            LiveTvViewState.list.value.visible.firstOrNull { channel -> channel.key == channelKey }
                // A channel just switched to inside the player may not be in the filtered list yet.
                ?: LiveTvPlayback.lastSwitchedChannel?.takeIf { it.key == channelKey }
        }
    } else {
        null
    }
    val channelArtwork = channel?.let(::channelTransitionArtwork)
    return PlayerStreamTransition(
        // A non-live switch (a new source for the same title) can keep using the player's own
        // artwork; a live channel must only ever show its own.
        artwork = if (channel != null) channelArtwork else background ?: poster,
        logo = channel?.logo?.takeIf { it.isNotBlank() } ?: if (channel == null) logo else null,
        title = channel?.name ?: activeStreamTitle.takeIf { it.isNotBlank() } ?: title,
    )
}

/** The channel panel and channel swiping, over a playing Live TV channel. */
@Composable
internal fun PlayerScreenRuntime.RenderLiveTvChannels() {
    if (!isLiveTvContent) return
    val isInPip = rememberIsInPictureInPicture()
    // Lucid TV is a broadcast: warm the current item and the next two, then tune at the boundary.
    LaunchedEffect(activeVideoId, isLiveTv) {
        val key = activeVideoId ?: return@LaunchedEffect
        if (!isLiveTv || !VirtualChannels.isVirtualKey(key)) return@LaunchedEffect
        scope.launch { VirtualChannelPlayback.prefetchCurrent(key) }
        var prefetchedSlotStart = Long.MIN_VALUE
        while (true) {
            val now = LiveTvClock.nowMs()
            val tune = com.nuvio.app.features.livetv.virtual.VirtualChannelRepository.tune(key, now)
            if (tune == null) {
                kotlinx.coroutines.delay(15_000L)
                continue
            }
            // Resolve the next two items shortly before the slot changes.
            if (now >= tune.slotEndMs - 30_000L && tune.slotStartMs != prefetchedSlotStart) {
                prefetchedSlotStart = tune.slotStartMs
                scope.launch { VirtualChannelPlayback.prefetch(key, now) }
            }
            if (now >= tune.slotEndMs) {
                if (!playbackSnapshot.isPlaying && !playbackSnapshot.isEnded) {
                    kotlinx.coroutines.delay(1_000L)
                    continue
                }
                val channel = LiveTvViewState.list.value.visible.firstOrNull { it.key == key } ?: break
                prefetchedSlotStart = Long.MIN_VALUE
                switchLiveChannel(channel, tune.title)
                kotlinx.coroutines.delay(1_000L)
                continue
            }
            // Wake at the prefetch point or the boundary, not on a fixed 15-second tick.
            val nextWake = when {
                now < tune.slotEndMs - 30_000L -> tune.slotEndMs - 30_000L - now
                else -> tune.slotEndMs - now
            }
            kotlinx.coroutines.delay(nextWake.coerceIn(1_000L, 15_000L))
        }
    }
    // A file that ends before the guide says it should (a shorter encode, or a dead source that
    // stopped) moves the queue to the next programme instead of leaving a finished film on screen.
    LaunchedEffect(activeVideoId, isLiveTv, playbackSnapshot.isEnded) {
        val key = activeVideoId ?: return@LaunchedEffect
        if (!isLiveTv || !playbackSnapshot.isEnded || !VirtualChannels.isVirtualKey(key)) return@LaunchedEffect
        val now = LiveTvClock.nowMs()
        val tune = VirtualChannelRepository.tune(key, now) ?: return@LaunchedEffect
        if (tune.relay || now >= tune.slotEndMs - 2_000L) return@LaunchedEffect
        if (!VirtualChannelRepository.advanceAfter(key, tune.item.id)) return@LaunchedEffect
        val channel = LiveTvViewState.list.value.visible.firstOrNull { it.key == key } ?: return@LaunchedEffect
        switchLiveChannel(channel, tune.title)
    }
    LiveTvPlayerOverlay(
        currentKey = activeVideoId,
        controlsVisible = controlsVisible,
        enabled = !isInPip && !playerControlsLocked,
        // Only a live channel changes on a swipe; On Demand and recordings keep their gestures.
        swipeEnabled = isLiveTv && !isInPip && !playerControlsLocked,
        playing = playbackSnapshot.isPlaying,
        onPanelOpened = { controlsVisible = false },
        onSwitch = { channel, nowTitle -> switchLiveChannel(channel, nowTitle) },
        onAddToMultiView = { channel -> args.onAddChannelToMultiView?.invoke(channel) },
        onOpenAddonCatalog = { manifestUrl, catalog -> args.onOpenAddonCatalog?.invoke(manifestUrl, catalog) },
        onPlayAddonCatalogItem = { item, addon, onFinished ->
            playAddonCatalogItem(item, addon, onFinished)
        },
        onPlayPluginCatalogItem = { scraper, item, onFinished ->
            playPluginCatalogItem(scraper, item, onFinished)
        },
    )
}
