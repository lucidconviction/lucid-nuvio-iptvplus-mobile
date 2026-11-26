package com.nuvio.app.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nuvio.app.features.player.AndroidPlayerNowPlayingController
import com.nuvio.app.features.player.PlayerNowPlayingInfo
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One app-wide music player. Audio-only by default; video mode plays the YouTube music video
 * inline through the same ExoPlayer. Leaving the Music tab does not stop it, and the now-playing
 * controller mirrors it to the notification bar.
 */
object MusicPlayer {
    private const val CROSSFADE_MS = 10_000L
    private const val CROSSFADE_PRELOAD_MS = 20_000L

    enum class RepeatMode { Off, All, One }

    data class State(
        val track: MusicTrack? = null,
        val queue: List<MusicTrack> = emptyList(),
        val index: Int = 0,
        val playing: Boolean = false,
        val loading: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val shuffle: Boolean = false,
        val repeat: RepeatMode = RepeatMode.Off,
        val videoMode: Boolean = false,
        val streamLabel: String = "",
        val error: String? = null,
    ) {
        val hasNext: Boolean
            get() = queue.isNotEmpty() &&
                (index < queue.lastIndex || repeat == RepeatMode.All || shuffle)
        val hasPrevious: Boolean
            get() = queue.isNotEmpty() && (index > 0 || positionMs > 3_000L)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var nextPlayer: ExoPlayer? = null
    private var nowPlaying: AndroidPlayerNowPlayingController? = null
    private var loadJob: Job? = null
    private var playToken = 0
    private var originalQueue: List<MusicTrack> = emptyList()
    private var pendingSeekMs: Long? = null

    private data class NextTrack(
        val track: MusicTrack,
        val queue: List<MusicTrack>,
        val index: Int,
    )

    private var crossfadeNext: MusicTrack? = null
    private var crossfadeNextQueue: List<MusicTrack> = emptyList()
    private var crossfadeNextIndex: Int = -1
    private var crossfadeLoadJob: Job? = null
    private var crossfadeReady = false
    private var crossfading = false
    private var crossfadeStartedAt = 0L
    private var crossfadeDurationMs = CROSSFADE_MS

    /** The ExoPlayer behind the page, for the inline video surface. */
    val exoPlayer: ExoPlayer? get() = player

    fun init(context: Context) {
        appContext = context.applicationContext
        MusicLibrary.init(context)
        if (player != null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            createPlayer()
        } else {
            main.post { if (player == null) createPlayer() }
        }
    }

    private fun createPlayer() {
        val context = appContext ?: return
        val exo = buildPlayer()
        exo.addListener(listener)
        player = exo
        nextPlayer = buildPlayer().also { it.addListener(nextListener) }
        nowPlaying = AndroidPlayerNowPlayingController(
            context,
            AndroidPlayerNowPlayingController.PlaybackControls(
                play = { play() },
                pause = { pause() },
                seekTo = { seekTo(it) },
                seekBy = { seekTo((exo.currentPosition + it).coerceAtLeast(0L)) },
            ),
        )
        scope.launch {
            while (isActive) {
                syncSnapshot()
                delay(500L)
            }
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val context = appContext ?: error("MusicPlayer not initialised")
        return ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(playing = isPlaying || crossfading)
            syncSnapshot()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(
                loading = playbackState == Player.STATE_BUFFERING,
                durationMs = player?.duration?.takeIf { it > 0L } ?: _state.value.durationMs,
            )
            if (playbackState == Player.STATE_ENDED) {
                if (crossfading) return
                if (_state.value.repeat == RepeatMode.One) {
                    player?.seekTo(0L)
                    player?.play()
                } else {
                    next(auto = true)
                }
            }
            if (playbackState == Player.STATE_READY) {
                pendingSeekMs?.let { at ->
                    pendingSeekMs = null
                    player?.seekTo(at)
                }
            }
            syncSnapshot()
        }

        override fun onPlayerError(error: PlaybackException) {
            cancelCrossfade()
            _state.value = _state.value.copy(
                loading = false,
                playing = false,
                error = "Couldn't play this track.",
            )
            syncSnapshot()
        }
    }

    /** Preloads the next track and reports when it is ready for the ten-second overlap. */
    private val nextListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && crossfadeNext != null) {
                crossfadeReady = true
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            crossfadeReady = false
        }
    }

    fun play(track: MusicTrack, queue: List<MusicTrack>? = null) {
        val context = appContext ?: return
        init(context)
        cancelCrossfade()
        player?.volume = 1f
        loadJob?.cancel()
        playToken += 1
        val token = playToken
        val sourceQueue = (queue ?: listOf(track)).ifEmpty { listOf(track) }
        originalQueue = sourceQueue
        val ordered = if (_state.value.shuffle) {
            listOf(track) + sourceQueue.filterNot { it.id == track.id }.shuffled()
        } else {
            sourceQueue
        }
        val index = ordered.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        _state.value = _state.value.copy(
            track = track,
            queue = ordered,
            index = index,
            positionMs = 0L,
            durationMs = track.durationSeconds * 1_000L,
            loading = true,
            error = null,
            videoMode = MusicLibrary.videoMode.value,
        )
        MusicLibrary.addRecent(track)
        nowPlaying?.updateMetadata(
            PlayerNowPlayingInfo(
                title = track.title,
                subtitle = track.artist,
                artworkUrl = track.coverUrl.takeIf { it.isNotBlank() },
            ),
        )
        loadJob = scope.launch {
            val source = if (MusicLibrary.videoMode.value) {
                MusicSourceResolver.resolveVideo(track)
            } else {
                MusicSourceResolver.resolveAudio(track)
            }
            if (token != playToken) return@launch
            if (source == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    playing = false,
                    error = "No playable source found for this track.",
                )
                return@launch
            }
            startSource(source, token)
        }
    }

    private fun startSource(source: MusicStream, token: Int) {
        val exo = player ?: return
        if (token != playToken) return
        val headers = source.headers
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource)
        val item = MediaItem.Builder()
            .setUri(source.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(_state.value.track?.title)
                    .setArtist(_state.value.track?.artist)
                    .build(),
            )
            .build()
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !_state.value.videoMode)
            .build()
        exo.setMediaSource(mediaSourceFactory.createMediaSource(item))
        pendingSeekMs?.let { at ->
            pendingSeekMs = null
            exo.seekTo(at)
        }
        exo.volume = 1f
        _state.value = _state.value.copy(loading = true, streamLabel = source.label)
        exo.prepare()
        exo.play()
        syncSnapshot()
    }

    fun toggle() {
        val exo = player ?: return
        if (exo.isPlaying) pause() else play()
    }

    fun play() {
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_IDLE) {
            _state.value.track?.let { play(it, _state.value.queue) }
        } else {
            exo.play()
        }
    }

    fun pause() {
        cancelCrossfade()
        player?.pause()
    }

    fun next(auto: Boolean = false) {
        val current = _state.value
        if (current.queue.isNotEmpty()) {
            val nextIndex = when {
                current.shuffle -> {
                    if (current.queue.size <= 1) current.index
                    else current.queue.indices.filter { it != current.index }.random()
                }
                current.index < current.queue.lastIndex -> current.index + 1
                current.repeat == RepeatMode.All -> 0
                else -> -1
            }
            if (nextIndex >= 0) {
                play(current.queue[nextIndex], current.queue)
                return
            }
        }
        // No list left: keep the music going with a liked song, then recently played.
        val currentId = current.track?.id
        val fallback = (MusicLibrary.liked.value.filterNot { it.id == currentId })
            .ifEmpty { MusicLibrary.recent.value.filterNot { it.id == currentId } }
        if (fallback.isNotEmpty()) {
            play(fallback.first(), fallback)
            return
        }
        if (auto || current.track != null) {
            _state.value = current.copy(playing = false)
            nowPlaying?.clear()
        }
    }

    fun previous() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        if (current.positionMs > 3_000L) {
            seekTo(0L)
            return
        }
        val prevIndex = if (current.index > 0) current.index - 1 else current.queue.lastIndex
        play(current.queue[prevIndex], current.queue)
    }

    fun seekTo(positionMs: Long) {
        val exo = player ?: return
        cancelCrossfade()
        val target = positionMs.coerceAtLeast(0L)
        if (exo.playbackState == Player.STATE_IDLE) {
            pendingSeekMs = target
        } else {
            exo.seekTo(target)
        }
        _state.value = _state.value.copy(positionMs = target)
        syncSnapshot()
    }

    fun setShuffle(enabled: Boolean) {
        val current = _state.value
        if (current.shuffle == enabled) return
        val track = current.track
        val queue = if (enabled) {
            if (track == null) current.queue
            else listOf(track) + current.queue.filterNot { it.id == track.id }.shuffled()
        } else {
            if (track == null) current.queue
            else {
                val ordered = originalQueue.ifEmpty { current.queue }
                listOf(track) + ordered.filterNot { it.id == track.id }
            }
        }
        val index = if (track == null) current.index else queue.indexOfFirst { it.id == track.id }
        _state.value = current.copy(shuffle = enabled, queue = queue, index = index.coerceAtLeast(0))
    }

    fun cycleRepeat() {
        val next = when (_state.value.repeat) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        _state.value = _state.value.copy(repeat = next)
    }

    fun setVideoMode(enabled: Boolean) {
        MusicLibrary.setVideoMode(enabled)
        val current = _state.value
        val track = current.track ?: return
        if (current.videoMode == enabled) return
        pendingSeekMs = player?.currentPosition?.takeIf { it > 0L } ?: current.positionMs
        _state.value = current.copy(videoMode = enabled)
        play(track, current.queue.ifEmpty { listOf(track) })
    }

    /**
     * Resolves the following queue item in the last twenty seconds so both tracks can overlap
     * for the final ten seconds. If the next source is not ready in time the normal end-of-track
     * advance still runs.
     */
    private fun prepareCrossfadeIfNeeded() {
        val exo = player ?: return
        val state = _state.value
        if (crossfading || crossfadeNext != null || crossfadeLoadJob != null) return
        if (!exo.isPlaying || state.repeat == RepeatMode.One) return
        val duration = runCatching { exo.duration }.getOrDefault(0L)
        if (duration <= 0L) return
        if (duration - exo.currentPosition > CROSSFADE_PRELOAD_MS) return
        val candidate = determineNext(state) ?: return

        crossfadeNext = candidate.track
        crossfadeNextQueue = candidate.queue
        crossfadeNextIndex = candidate.index
        crossfadeReady = false
        val token = playToken
        val videoMode = state.videoMode
        crossfadeLoadJob = scope.launch {
            val source = if (videoMode) {
                MusicSourceResolver.resolveVideo(candidate.track)
            } else {
                MusicSourceResolver.resolveAudio(candidate.track)
            }
            if (token != playToken) return@launch
            val target = nextPlayer ?: return@launch
            if (source == null) return@launch
            val dataSource = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(source.headers)
                .setAllowCrossProtocolRedirects(true)
            val item = MediaItem.Builder()
                .setUri(source.url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(candidate.track.title)
                        .setArtist(candidate.track.artist)
                        .build(),
                )
                .build()
            target.trackSelectionParameters = target.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            target.volume = 0f
            target.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(item))
            target.prepare()
        }
    }

    private fun updateCrossfadeProgress() {
        val exo = player ?: return
        if (!crossfading) {
            if (crossfadeNext == null || !crossfadeReady || !exo.isPlaying) return
            val duration = runCatching { exo.duration }.getOrDefault(0L)
            if (duration <= 0L) return
            val remaining = duration - exo.currentPosition
            if (remaining > CROSSFADE_MS) return
            crossfading = true
            crossfadeStartedAt = SystemClock.elapsedRealtime()
            crossfadeDurationMs = remaining.coerceIn(1_000L, CROSSFADE_MS)
            nextPlayer?.let {
                it.volume = 0f
                it.play()
            }
        }

        val elapsed = SystemClock.elapsedRealtime() - crossfadeStartedAt
        val fraction = (elapsed.toFloat() / crossfadeDurationMs.toFloat()).coerceIn(0f, 1f)
        exo.volume = 1f - fraction
        nextPlayer?.volume = fraction
        if (fraction >= 1f) commitCrossfade()
    }

    private fun commitCrossfade() {
        val old = player ?: return
        val next = nextPlayer ?: return
        val track = crossfadeNext ?: return
        val queue = crossfadeNextQueue
        val index = crossfadeNextIndex
        val videoMode = _state.value.videoMode

        old.removeListener(listener)
        next.removeListener(nextListener)
        next.addListener(listener)
        old.addListener(nextListener)
        old.pause()
        old.clearMediaItems()
        old.volume = 1f
        next.volume = 1f
        next.trackSelectionParameters = next.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !videoMode)
            .build()

        player = next
        nextPlayer = old
        crossfading = false
        crossfadeReady = false
        crossfadeNext = null
        crossfadeNextQueue = emptyList()
        crossfadeNextIndex = -1
        crossfadeLoadJob = null
        crossfadeStartedAt = 0L

        next.play()
        _state.value = _state.value.copy(
            track = track,
            queue = queue,
            index = index,
            positionMs = next.currentPosition.coerceAtLeast(0L),
            durationMs = next.duration.takeIf { it > 0L } ?: track.durationSeconds * 1_000L,
            playing = true,
            loading = next.playbackState == Player.STATE_BUFFERING,
            error = null,
            videoMode = videoMode,
        )
        MusicLibrary.addRecent(track)
        nowPlaying?.updateMetadata(
            PlayerNowPlayingInfo(
                title = track.title,
                subtitle = track.artist,
                artworkUrl = track.coverUrl.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun determineNext(current: State): NextTrack? {
        if (current.queue.isNotEmpty()) {
            val nextIndex = when {
                current.shuffle -> {
                    if (current.queue.size <= 1) current.index
                    else current.queue.indices.filter { it != current.index }.random()
                }
                current.index < current.queue.lastIndex -> current.index + 1
                current.repeat == RepeatMode.All -> 0
                else -> -1
            }
            if (nextIndex >= 0) {
                return NextTrack(current.queue[nextIndex], current.queue, nextIndex)
            }
        }
        val currentId = current.track?.id
        val fallback = MusicLibrary.liked.value.filterNot { it.id == currentId }
            .ifEmpty { MusicLibrary.recent.value.filterNot { it.id == currentId } }
        return fallback.firstOrNull()?.let { NextTrack(it, fallback, 0) }
    }

    private fun cancelCrossfade() {
        crossfadeLoadJob?.cancel()
        crossfadeLoadJob = null
        crossfadeNext = null
        crossfadeNextQueue = emptyList()
        crossfadeNextIndex = -1
        crossfadeReady = false
        crossfading = false
        crossfadeStartedAt = 0L
        player?.volume = 1f
        nextPlayer?.let {
            it.pause()
            it.volume = 1f
            it.clearMediaItems()
        }
    }

    private fun syncSnapshot() {
        val exo = player ?: return
        if (_state.value.track == null) return
        prepareCrossfadeIfNeeded()
        updateCrossfadeProgress()
        val active = player ?: return
        val position = runCatching { active.currentPosition }.getOrDefault(0L)
        val duration = runCatching { active.duration }.getOrDefault(0L)
        val snapshot = PlayerPlaybackSnapshot(
            isLoading = active.playbackState == Player.STATE_BUFFERING,
            isPlaying = active.isPlaying || crossfading,
            isEnded = active.playbackState == Player.STATE_ENDED,
            durationMs = duration.coerceAtLeast(0L),
            positionMs = position.coerceAtLeast(0L),
        )
        _state.value = _state.value.copy(
            playing = snapshot.isPlaying,
            loading = snapshot.isLoading,
            positionMs = snapshot.positionMs,
            durationMs = if (snapshot.durationMs > 0L) snapshot.durationMs else _state.value.durationMs,
        )
        nowPlaying?.syncPlayback(snapshot)
    }
}
