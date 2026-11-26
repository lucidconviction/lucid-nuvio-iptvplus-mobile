package com.nuvio.app.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.music.MusicHost
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay

private const val NOW_PLAYING_CONTROLS_TIMEOUT_MS = 3_000L

private enum class MusicTab { Home, Search, Playlists, Library }

private sealed interface MusicDetailRequest {
    val id: String
    data class Artist(override val id: String) : MusicDetailRequest
    data class Album(override val id: String) : MusicDetailRequest
    data class Playlist(override val id: String) : MusicDetailRequest
    data class UserPlaylist(override val id: String) : MusicDetailRequest
}

private data class MusicDetailState(
    val title: String = "",
    val subtitle: String = "",
    val coverUrl: String = "",
    val tracks: List<MusicTrack> = emptyList(),
    val albums: List<MusicAlbum> = emptyList(),
    val playlistId: String? = null,
    val loading: Boolean = true,
)

private sealed interface MusicViewMore {
    val title: String
    data class Tracks(override val title: String, val tracks: List<MusicTrack>) : MusicViewMore
    data class Artists(override val title: String, val artists: List<MusicArtist>) : MusicViewMore
    data class Albums(override val title: String, val albums: List<MusicAlbum>) : MusicViewMore
    data class Playlists(override val title: String, val playlists: List<MusicPlaylist>) : MusicViewMore
}

@Composable
fun MusicScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tokens = MaterialTheme.nuvio
    val playerState by MusicPlayer.state.collectAsStateWithLifecycle()
    val likedTracks by MusicLibrary.liked.collectAsStateWithLifecycle()
    val recentTracks by MusicLibrary.recent.collectAsStateWithLifecycle()
    val playlists by MusicLibrary.playlists.collectAsStateWithLifecycle()
    val videoMode by MusicLibrary.videoMode.collectAsStateWithLifecycle()
    val floating by MusicHost.floating.collectAsStateWithLifecycle()
    val floatingActive = floating && (playerState.playing || playerState.loading)
    val screenActive = LocalScreenActive.current

    var tab by rememberSaveable { mutableStateOf(MusicTab.Home) }
    var query by rememberSaveable { mutableStateOf("") }
    var home by remember { mutableStateOf<MusicHome?>(null) }
    var homeLoading by remember { mutableStateOf(true) }
    var homeError by remember { mutableStateOf<String?>(null) }
    var searchResults by remember { mutableStateOf<MusicSearchData?>(null) }
    var searchLoading by remember { mutableStateOf(false) }
    var detailRequest by remember { mutableStateOf<MusicDetailRequest?>(null) }
    var detail by remember { mutableStateOf<MusicDetailState?>(null) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var viewMore by remember { mutableStateOf<MusicViewMore?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var addTrackTarget by remember { mutableStateOf<MusicTrack?>(null) }
    var createPlaylistForTrack by remember { mutableStateOf<MusicTrack?>(null) }

    LaunchedEffect(screenActive) {
        if (
            screenActive &&
            !MusicHost.floating.value &&
            MusicPlayer.state.value.track != null &&
            MusicPlayer.state.value.videoMode
        ) {
            showNowPlaying = true
        }
    }

    LaunchedEffect(Unit) {
        MusicPlayer.init(context)
        home = runCatching { MusicApi.home() }.getOrNull()
        homeError = if (home == null) "Could not reach the music catalogue." else null
        homeLoading = false
    }

    LaunchedEffect(tab, query) {
        if (tab != MusicTab.Search || query.isBlank()) {
            searchResults = null
            return@LaunchedEffect
        }
        delay(350)
        searchLoading = true
        searchResults = MusicApi.search(query)
        searchLoading = false
    }

    LaunchedEffect(detailRequest) {
        val request = detailRequest ?: return@LaunchedEffect
        detail = MusicDetailState(loading = true)
        detail = when (request) {
            is MusicDetailRequest.Artist -> MusicApi.artist(request.id)?.let {
                MusicDetailState(
                    title = it.artist.name,
                    subtitle = "Artist",
                    coverUrl = it.artist.pictureUrl,
                    tracks = it.topTracks,
                    albums = it.albums,
                    loading = false,
                )
            }
            is MusicDetailRequest.Album -> MusicApi.album(request.id)?.let {
                MusicDetailState(
                    title = it.album.title,
                    subtitle = it.album.artistName,
                    coverUrl = it.album.coverUrl,
                    tracks = it.tracks,
                    loading = false,
                )
            }
            is MusicDetailRequest.Playlist -> MusicApi.playlist(request.id)?.let {
                MusicDetailState(
                    title = it.playlist.title,
                    subtitle = it.playlist.creator.ifBlank { "Playlist" },
                    coverUrl = it.playlist.coverUrl,
                    tracks = it.tracks,
                    loading = false,
                )
            }
            is MusicDetailRequest.UserPlaylist -> {
                val playlist = MusicLibrary.playlists.value.firstOrNull { it.id == request.id }
                if (playlist == null) {
                    MusicDetailState(title = "Playlist not found", loading = false)
                } else {
                    MusicDetailState(
                        title = playlist.title,
                        subtitle = "${playlist.tracks.size} songs",
                        coverUrl = playlist.tracks.firstOrNull()?.coverUrl.orEmpty(),
                        tracks = playlist.tracks,
                        playlistId = playlist.id,
                        loading = false,
                    )
                }
            }
        } ?: MusicDetailState(
            title = "Could not load this page",
            loading = false,
        )
    }

    BackHandler(enabled = detailRequest != null || showNowPlaying || viewMore != null) {
        when {
            showNowPlaying -> showNowPlaying = false
            detailRequest != null -> {
                detailRequest = null
                detail = null
            }
            viewMore != null -> viewMore = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1B1030), tokens.colors.background, Color.Black),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    bottom = if (LocalNuvioBottomNavigationOverlayPadding.current > 0.dp) {
                        nuvioSafeBottomPadding(NuvioTokens.Space.s8)
                    } else {
                        NuvioTokens.Space.s8
                    },
                ),
        ) {
            if (detailRequest == null && viewMore != null) {
                MusicViewMoreContent(
                    state = viewMore!!,
                    onBack = { viewMore = null },
                    onPlayTrack = { track, queue -> MusicPlayer.play(track, queue) },
                    onOpenArtist = { detailRequest = MusicDetailRequest.Artist(it.id) },
                    onOpenAlbum = { detailRequest = MusicDetailRequest.Album(it.id) },
                    onOpenPlaylist = { detailRequest = MusicDetailRequest.Playlist(it.id) },
                    onAddToPlaylist = { addTrackTarget = it },
                    modifier = Modifier.weight(1f),
                )
            } else if (detailRequest == null) {
                val headerArtists = home?.artists.orEmpty()
                if (tab == MusicTab.Home && headerArtists.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding(),
                    ) {
                        TopArtistCarousel(
                            artists = headerArtists.take(25),
                            onOpenArtist = { detailRequest = MusicDetailRequest.Artist(it.id) },
                        )
                    }
                } else {
                    NuvioScreenHeader(
                        title = "Music",
                        actions = {
                            IconButton(onClick = { tab = MusicTab.Search }) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = "Search music",
                                    tint = tokens.colors.textPrimary,
                                )
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s6),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                ) {
                    MusicTab.entries.forEach { item ->
                        Chip(
                            label = when (item) {
                                MusicTab.Home -> "Home"
                                MusicTab.Search -> "Search"
                                MusicTab.Playlists -> "Playlists"
                                MusicTab.Library -> "Liked"
                            },
                            selected = tab == item,
                            onClick = { tab = item },
                        )
                    }
                }

                when (tab) {
                    MusicTab.Home -> MusicHomeContent(
                        home = home,
                        loading = homeLoading,
                        error = homeError,
                        onPlayTrack = { track, queue -> MusicPlayer.play(track, queue) },
                        onOpenArtist = { detailRequest = MusicDetailRequest.Artist(it.id) },
                        onOpenAlbum = { detailRequest = MusicDetailRequest.Album(it.id) },
                        onOpenPlaylist = { detailRequest = MusicDetailRequest.Playlist(it.id) },
                        onAddToPlaylist = { addTrackTarget = it },
                        onViewMore = { viewMore = it },
                        modifier = Modifier.weight(1f),
                    )
                    MusicTab.Search -> MusicSearchContent(
                        query = query,
                        onQueryChange = { query = it },
                        results = searchResults,
                        loading = searchLoading,
                        onPlayTrack = { track, queue -> MusicPlayer.play(track, queue) },
                        onOpenArtist = { detailRequest = MusicDetailRequest.Artist(it.id) },
                        onOpenAlbum = { detailRequest = MusicDetailRequest.Album(it.id) },
                        onOpenPlaylist = { detailRequest = MusicDetailRequest.Playlist(it.id) },
                        onAddToPlaylist = { addTrackTarget = it },
                        modifier = Modifier.weight(1f),
                    )
                    MusicTab.Playlists -> MusicPlaylistsContent(
                        playlists = playlists,
                        onOpen = { detailRequest = MusicDetailRequest.UserPlaylist(it.id) },
                        onCreate = {
                            createPlaylistForTrack = null
                            newPlaylistName = ""
                            showCreatePlaylist = true
                        },
                        onPlay = { playlist ->
                            playlist.tracks.firstOrNull()?.let { MusicPlayer.play(it, playlist.tracks) }
                        },
                        onDelete = { MusicLibrary.deletePlaylist(it.id) },
                        modifier = Modifier.weight(1f),
                    )
                    MusicTab.Library -> MusicTrackList(
                        tracks = likedTracks.ifEmpty { recentTracks },
                        emptyText = "Songs you like and play will appear here.",
                        onPlayTrack = { track, queue -> MusicPlayer.play(track, queue) },
                        onAddToPlaylist = { addTrackTarget = it },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                MusicDetailContent(
                    state = detail,
                    onBack = {
                        detailRequest = null
                        detail = null
                    },
                    onPlayTrack = { track, queue -> MusicPlayer.play(track, queue) },
                    onOpenAlbum = { detailRequest = MusicDetailRequest.Album(it.id) },
                    onAddToPlaylist = { addTrackTarget = it },
                    onRemoveFromPlaylist = { playlistId, trackId ->
                        MusicLibrary.removeFromPlaylist(playlistId, trackId)
                        if (detail?.playlistId == playlistId) {
                            detail = detail?.copy(tracks = detail?.tracks.orEmpty().filterNot { it.id == trackId })
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            if (playerState.track != null && !showNowPlaying && !floatingActive) {
                MusicNowPlayingBar(
                    state = playerState,
                    onOpen = {
                        MusicHost.floating.value = false
                        showNowPlaying = true
                    },
                )
            }
        }

        if (showNowPlaying) {
            Dialog(
                onDismissRequest = { showNowPlaying = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                MusicNowPlayingScreen(
                    state = playerState,
                    liked = playerState.track?.let { track -> likedTracks.any { it.id == track.id } } == true,
                    onClose = { showNowPlaying = false },
                    onLike = { playerState.track?.let(MusicLibrary::toggleLike) },
                    onAddToPlaylist = { playerState.track?.let { addTrackTarget = it } },
                    onMultiwindow = {
                        MusicHost.floating.value = true
                        showNowPlaying = false
                    },
                    active = screenActive,
                )
            }
        }
    }

    if (showCreatePlaylist) {
        DialogSurface(
            onDismissRequest = {
                showCreatePlaylist = false
                createPlaylistForTrack = null
            },
            title = "New playlist",
        ) {
            NuvioInputField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                placeholder = "Playlist name",
            )
            DialogButtons {
                DialogButton(
                    text = "Cancel",
                    onClick = {
                        showCreatePlaylist = false
                        createPlaylistForTrack = null
                    },
                )
                DialogButton(
                    text = "Create",
                    style = DialogButtonStyle.Primary,
                    onClick = {
                        val created = MusicLibrary.createPlaylist(newPlaylistName)
                        if (created != null) {
                            createPlaylistForTrack?.let { track ->
                                MusicLibrary.addToPlaylist(created.id, track)
                                NuvioToastController.show("Added to ${created.title}")
                            }
                        }
                        showCreatePlaylist = false
                        createPlaylistForTrack = null
                        newPlaylistName = ""
                    },
                )
            }
        }
    }

    addTrackTarget?.let { track ->
        DialogSurface(
            onDismissRequest = { addTrackTarget = null },
            title = "Add to playlist",
        ) {
            if (playlists.isEmpty()) {
                Text(
                    "No playlists yet. Create one and this song will be added to it.",
                    color = MaterialTheme.nuvio.colors.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        Surface(
                            onClick = {
                                val added = MusicLibrary.addToPlaylist(playlist.id, track)
                                NuvioToastController.show(
                                    if (added) "Added to ${playlist.title}" else "Already in ${playlist.title}",
                                )
                                addTrackTarget = null
                            },
                            shape = MaterialTheme.nuvio.shapes.compactCard,
                            color = MaterialTheme.nuvio.colors.surfaceCard,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.nuvio.colors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        playlist.title,
                                        color = MaterialTheme.nuvio.colors.textPrimary,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${playlist.tracks.size} songs",
                                        color = MaterialTheme.nuvio.colors.textMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.nuvio.colors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
            DialogButtons {
                DialogButton(
                    text = "New playlist",
                    style = DialogButtonStyle.Primary,
                    onClick = {
                        createPlaylistForTrack = track
                        addTrackTarget = null
                        newPlaylistName = ""
                        showCreatePlaylist = true
                    },
                )
                DialogButton(text = "Cancel", onClick = { addTrackTarget = null })
            }
        }
    }
}

@Composable
private fun TopArtistCarousel(
    artists: List<MusicArtist>,
    onOpenArtist: (MusicArtist) -> Unit,
) {
    if (artists.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { artists.size })
    LaunchedEffect(artists.size) {
        if (artists.size <= 1) return@LaunchedEffect
        while (true) {
            delay(4_000L)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % artists.size)
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(238.dp)
            .padding(horizontal = NuvioTokens.Space.s16),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val artist = artists[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { onOpenArtist(artist) },
            ) {
                AsyncImage(
                    model = artist.pictureUrl,
                    contentDescription = artist.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.10f),
                                0.45f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.94f),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Text(
                        "TOP 25 ARTISTS",
                        color = MaterialTheme.nuvio.colors.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        artist.name,
                        color = Color.White,
                        fontSize = 30.sp,
                        lineHeight = 33.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Tap to open artist",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(artists.size) { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == pagerState.currentPage) 5.dp else 3.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == pagerState.currentPage) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = 0.45f)
                            },
                        ),
                )
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.42f),
        ) {
            Text(
                "${pagerState.currentPage + 1} / ${artists.size}",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MusicHomeContent(
    home: MusicHome?,
    loading: Boolean,
    error: String?,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onOpenArtist: (MusicArtist) -> Unit,
    onOpenAlbum: (MusicAlbum) -> Unit,
    onOpenPlaylist: (MusicPlaylist) -> Unit,
    onAddToPlaylist: (MusicTrack) -> Unit,
    onViewMore: (MusicViewMore) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    var rotation by remember { mutableIntStateOf(0) }
    LaunchedEffect(home) {
        while (true) {
            delay(30_000L)
            rotation += 1
        }
    }
    val topTracks = home?.sections?.get("Top 25 right now").orEmpty()
    val hero = topTracks.getOrNull(rotation % topTracks.size.coerceAtLeast(1)) ?: home?.hero
    when {
        loading && home == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = tokens.colors.accent)
        }
        home == null -> Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(error ?: "No music found.", color = tokens.colors.textMuted)
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24),
        ) {
            // 1. Top 25 tracks first, directly below the tabs (artist header is above them).
            if (topTracks.isNotEmpty() || hero != null) {
                item(key = "top25") {
                    MusicSectionHeader("Top 25 right now", onViewMore = {
                        onViewMore(MusicViewMore.Tracks("Top 25 right now", topTracks))
                    })
                    if (topTracks.isNotEmpty()) {
                        val offset = if (topTracks.size > 1) rotation % topTracks.size else 0
                        val shown = topTracks.drop(offset) + topTracks.take(offset)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                        ) {
                            items(shown, key = { "top-${it.id}" }) { track ->
                                MusicTrackCard(
                                    track = track,
                                    onClick = { onPlayTrack(track, shown) },
                                    onAdd = { onAddToPlaylist(track) },
                                )
                            }
                        }
                    }
                }
            }

            // 2. Artists.
            if (home.artists.isNotEmpty()) {
                item(key = "artists") {
                    MusicSectionHeader("Artists", onViewMore = {
                        onViewMore(MusicViewMore.Artists("Artists", home.artists))
                    })
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                    ) {
                        items(home.artists, key = { "ar-${it.id}" }) { artist ->
                            Column(
                                modifier = Modifier.width(104.dp).clickable { onOpenArtist(artist) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AsyncImage(
                                    model = artist.pictureUrl,
                                    contentDescription = artist.name,
                                    modifier = Modifier.size(92.dp).clip(CircleShape).background(tokens.colors.surfaceElevated),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    artist.name,
                                    color = tokens.colors.textPrimary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            // 3. New releases.
            if (home.albums.isNotEmpty()) {
                item(key = "albums") {
                    MusicSectionHeader("New releases", onViewMore = {
                        onViewMore(MusicViewMore.Albums("New releases", home.albums))
                    })
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    ) {
                        items(home.albums, key = { "al-${it.id}" }) { album ->
                            MusicArtworkCard(
                                title = album.title,
                                subtitle = album.artistName,
                                imageUrl = album.coverUrl,
                                onClick = { onOpenAlbum(album) },
                            )
                        }
                    }
                }
            }

            // 4. Playlists.
            if (home.playlists.isNotEmpty()) {
                item(key = "playlists") {
                    MusicSectionHeader("Playlists", onViewMore = {
                        onViewMore(MusicViewMore.Playlists("Playlists", home.playlists))
                    })
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    ) {
                        items(home.playlists, key = { "pl-${it.id}" }) { playlist ->
                            MusicArtworkCard(
                                title = playlist.title,
                                subtitle = playlist.creator,
                                imageUrl = playlist.coverUrl,
                                onClick = { onOpenPlaylist(playlist) },
                            )
                        }
                    }
                }
            }

            // 5. Everything else (genre shelves).
            home.sections
                .filterKeys { it != "Top 25 right now" }
                .forEach { (title, tracks) ->
                    item(key = "section-$title") {
                        MusicSectionHeader(title, onViewMore = {
                            onViewMore(MusicViewMore.Tracks(title, tracks))
                        })
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                        ) {
                            items(tracks, key = { "$title-${it.id}" }) { track ->
                                MusicTrackCard(
                                    track = track,
                                    onClick = { onPlayTrack(track, tracks) },
                                    onAdd = { onAddToPlaylist(track) },
                                )
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun MusicSearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    results: MusicSearchData?,
    loading: Boolean,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onOpenArtist: (MusicArtist) -> Unit,
    onOpenAlbum: (MusicAlbum) -> Unit,
    onOpenPlaylist: (MusicPlaylist) -> Unit,
    onAddToPlaylist: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.fillMaxSize()) {
        NuvioInputField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Songs, artists, albums…",
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions.Default,
        )
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.colors.accent, modifier = Modifier.size(28.dp))
            }
        }
        val data = results
        if (data == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Search Deezer's catalogue", color = tokens.colors.textMuted)
            }
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24),
        ) {
            if (data.tracks.isNotEmpty()) {
                item(key = "search-tracks-header") { MusicSectionHeader("Songs") }
                items(data.tracks, key = { "st-${it.id}" }) { track ->
                    MusicTrackRow(
                        track = track,
                        queue = data.tracks,
                        onClick = { onPlayTrack(track, data.tracks) },
                        onAdd = { onAddToPlaylist(track) },
                    )
                }
            }
            if (data.artists.isNotEmpty()) {
                item(key = "search-artists-header") { MusicSectionHeader("Artists") }
                item(key = "search-artists") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                    ) {
                        items(data.artists, key = { "sa-${it.id}" }) { artist ->
                            Column(
                                modifier = Modifier.width(104.dp).clickable { onOpenArtist(artist) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AsyncImage(
                                    model = artist.pictureUrl,
                                    contentDescription = artist.name,
                                    modifier = Modifier.size(92.dp).clip(CircleShape).background(tokens.colors.surfaceElevated),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    artist.name,
                                    color = tokens.colors.textPrimary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            if (data.albums.isNotEmpty()) {
                item(key = "search-albums-header") { MusicSectionHeader("Albums") }
                item(key = "search-albums") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    ) {
                        items(data.albums, key = { "sal-${it.id}" }) { album ->
                            MusicArtworkCard(
                                title = album.title,
                                subtitle = album.artistName,
                                imageUrl = album.coverUrl,
                                onClick = { onOpenAlbum(album) },
                            )
                        }
                    }
                }
            }
            if (data.playlists.isNotEmpty()) {
                item(key = "search-playlists-header") { MusicSectionHeader("Playlists") }
                item(key = "search-playlists") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    ) {
                        items(data.playlists, key = { "spl-${it.id}" }) { playlist ->
                            MusicArtworkCard(
                                title = playlist.title,
                                subtitle = playlist.creator,
                                imageUrl = playlist.coverUrl,
                                onClick = { onOpenPlaylist(playlist) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicDetailContent(
    state: MusicDetailState?,
    onBack: () -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onOpenAlbum: (MusicAlbum) -> Unit,
    onAddToPlaylist: (MusicTrack) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = tokens.colors.textPrimary)
            }
            Text(
                text = state?.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state == null || state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.colors.accent)
            }
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24),
        ) {
            item(key = "detail-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
                ) {
                    AsyncImage(
                        model = state.coverUrl,
                        contentDescription = state.title,
                        modifier = Modifier.size(112.dp).clip(RoundedCornerShape(18.dp))
                            .background(tokens.colors.surfaceElevated),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.title,
                            color = tokens.colors.textPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.subtitle,
                            color = tokens.colors.textMuted,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(10.dp))
                        if (state.tracks.isNotEmpty()) {
                            Surface(
                                onClick = { onPlayTrack(state.tracks.first(), state.tracks) },
                                shape = RoundedCornerShape(50),
                                color = tokens.colors.accent,
                                contentColor = tokens.colors.onAccent,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("Play", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
            if (state.albums.isNotEmpty()) {
                item(key = "detail-albums-header") { MusicSectionHeader("Albums") }
                item(key = "detail-albums") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    ) {
                        items(state.albums, key = { "da-${it.id}" }) { album ->
                            MusicArtworkCard(
                                title = album.title,
                                subtitle = album.artistName,
                                imageUrl = album.coverUrl,
                                onClick = { onOpenAlbum(album) },
                            )
                        }
                    }
                }
            }
            item(key = "detail-tracks-header") { MusicSectionHeader("Songs") }
            items(state.tracks, key = { "dt-${it.id}" }) { track ->
                MusicTrackRow(
                    track = track,
                    queue = state.tracks,
                    onClick = { onPlayTrack(track, state.tracks) },
                    onAdd = if (state.playlistId == null) {
                        { onAddToPlaylist(track) }
                    } else {
                        null
                    },
                    onRemove = state.playlistId?.let { playlistId ->
                        { onRemoveFromPlaylist(playlistId, track.id) }
                    },
                )
            }
        }
    }
}

@Composable
private fun MusicModePill(video: Boolean, onChange: (Boolean) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Surface(
        onClick = { onChange(!video) },
        shape = CircleShape,
        color = tokens.colors.surfaceElevated,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                if (video) Icons.Filled.Videocam else Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = tokens.colors.accent,
                modifier = Modifier.size(15.dp),
            )
            Text(
                if (video) "Video" else "Audio",
                color = tokens.colors.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun MusicPlaylistsContent(
    playlists: List<UserMusicPlaylist>,
    onOpen: (UserMusicPlaylist) -> Unit,
    onCreate: () -> Unit,
    onPlay: (UserMusicPlaylist) -> Unit,
    onDelete: (UserMusicPlaylist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Your playlists",
                color = tokens.colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Surface(
                onClick = onCreate,
                shape = RoundedCornerShape(50),
                color = tokens.colors.accent.copy(alpha = 0.16f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(17.dp))
                    Text("New", color = tokens.colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (playlists.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No playlists yet. Create one, then add songs with the playlist button.",
                    color = tokens.colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    Surface(
                        onClick = { onOpen(playlist) },
                        modifier = Modifier.fillMaxWidth().padding(
                            horizontal = NuvioTokens.Space.s16,
                            vertical = 4.dp,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        color = tokens.colors.surfaceElevated.copy(alpha = 0.72f),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AsyncImage(
                                model = playlist.tracks.firstOrNull()?.coverUrl,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp))
                                    .background(tokens.colors.surface),
                                contentScale = ContentScale.Crop,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    playlist.title,
                                    color = tokens.colors.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${playlist.tracks.size} songs",
                                    color = tokens.colors.textMuted,
                                    fontSize = 11.sp,
                                )
                            }
                            IconButton(onClick = { onPlay(playlist) }, enabled = playlist.tracks.isNotEmpty()) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Play playlist",
                                    tint = if (playlist.tracks.isEmpty()) tokens.colors.textDisabled else tokens.colors.accent,
                                )
                            }
                            IconButton(onClick = { onDelete(playlist) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete playlist",
                                    tint = tokens.colors.textMuted,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicViewMoreContent(
    state: MusicViewMore,
    onBack: () -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onOpenArtist: (MusicArtist) -> Unit,
    onOpenAlbum: (MusicAlbum) -> Unit,
    onOpenPlaylist: (MusicPlaylist) -> Unit,
    onAddToPlaylist: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = tokens.colors.textPrimary)
            }
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when (state) {
            is MusicViewMore.Tracks -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24),
                ) {
                    items(state.tracks, key = { "vm-${it.id}" }) { track ->
                        MusicTrackRow(
                            track = track,
                            queue = state.tracks,
                            onClick = { onPlayTrack(track, state.tracks) },
                            onAdd = { onAddToPlaylist(track) },
                        )
                    }
                }
            }

            is MusicViewMore.Artists -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24),
                ) {
                    items(state.artists, key = { "vma-${it.id}" }) { artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenArtist(artist) }
                                .padding(horizontal = NuvioTokens.Space.s16, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AsyncImage(
                                model = artist.pictureUrl,
                                contentDescription = artist.name,
                                modifier = Modifier.size(54.dp).clip(CircleShape)
                                    .background(tokens.colors.surfaceElevated),
                                contentScale = ContentScale.Crop,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    artist.name,
                                    color = tokens.colors.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "Artist",
                                    color = tokens.colors.textMuted,
                                    fontSize = 11.sp,
                                )
                            }
                            Icon(
                                Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = tokens.colors.textMuted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            is MusicViewMore.Albums -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = NuvioTokens.Space.s16,
                        end = NuvioTokens.Space.s16,
                        bottom = NuvioTokens.Space.s24,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s14),
                ) {
                    gridItems(state.albums, key = { "vmal-${it.id}" }) { album ->
                        Column(modifier = Modifier.fillMaxWidth().clickable { onOpenAlbum(album) }) {
                            AsyncImage(
                                model = album.coverUrl,
                                contentDescription = album.title,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(tokens.colors.surfaceElevated),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                album.title,
                                color = tokens.colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                album.artistName,
                                color = tokens.colors.textMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            is MusicViewMore.Playlists -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = NuvioTokens.Space.s16,
                        end = NuvioTokens.Space.s16,
                        bottom = NuvioTokens.Space.s24,
                    ),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                ) {
                    items(state.playlists, key = { "vmpl-${it.id}" }) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPlaylist(playlist) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AsyncImage(
                                model = playlist.coverUrl,
                                contentDescription = playlist.title,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                                    .background(tokens.colors.surfaceElevated),
                                contentScale = ContentScale.Crop,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    playlist.title,
                                    color = tokens.colors.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        playlist.creator.takeIf { it.isNotBlank() },
                                        playlist.trackCount.takeIf { it > 0 }?.let { "$it songs" },
                                    ).joinToString(" · ").ifBlank { "Playlist" },
                                    color = tokens.colors.textMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = tokens.colors.textMuted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicTrackList(
    tracks: List<MusicTrack>,
    emptyText: String,
    onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
    onAddToPlaylist: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    if (tracks.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, color = tokens.colors.textMuted, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NuvioTokens.Space.s24)) {
        items(tracks, key = { "lib-${it.id}" }) { track ->
            MusicTrackRow(
                track = track,
                queue = tracks,
                onClick = { onPlayTrack(track, tracks) },
                onAdd = { onAddToPlaylist(track) },
            )
        }
    }
}

@Composable
private fun MusicTrackRow(
    track: MusicTrack,
    queue: List<MusicTrack>,
    onClick: () -> Unit,
    onAdd: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val player by MusicPlayer.state.collectAsStateWithLifecycle()
    val current = player.track?.id == track.id
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTokens.Space.s16, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        AsyncImage(
            model = track.coverUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                .background(tokens.colors.surfaceElevated),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (current) tokens.colors.accent else tokens.colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                color = tokens.colors.textMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(track.formattedDuration, color = tokens.colors.textMuted, fontSize = 11.sp)
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove from playlist",
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(19.dp),
                )
            }
        } else if (onAdd != null) {
            IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.PlaylistAdd,
                    contentDescription = "Add to playlist",
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Icon(
            if (current && player.playing) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = if (current) tokens.colors.accent else tokens.colors.textMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun MusicHero(track: MusicTrack, onPlay: () -> Unit, onAdd: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        AsyncImage(
            model = track.coverUrl,
            contentDescription = track.title,
            modifier = Modifier.size(132.dp).clip(RoundedCornerShape(20.dp))
                .background(tokens.colors.surfaceElevated),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f)) {
            Text("TRENDING NOW", color = tokens.colors.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                track.title,
                color = tokens.colors.textPrimary,
                fontSize = 24.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(track.artist, color = tokens.colors.textMuted, fontSize = 13.sp, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onPlay,
                    shape = CircleShape,
                    color = tokens.colors.accent,
                    contentColor = tokens.colors.onAccent,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play ${track.title}",
                        modifier = Modifier.padding(10.dp).size(24.dp),
                    )
                }
                IconButton(onClick = onAdd) {
                    Icon(
                        Icons.Filled.PlaylistAdd,
                        contentDescription = "Add ${track.title} to playlist",
                        tint = tokens.colors.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicSectionHeader(
    title: String,
    onViewMore: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = NuvioTokens.Space.s16,
                end = NuvioTokens.Space.s16,
                top = NuvioTokens.Space.s14,
                bottom = NuvioTokens.Space.s6,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = tokens.colors.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
        if (onViewMore != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onViewMore)
                    .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "View more",
                    color = tokens.colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun MusicTrackCard(track: MusicTrack, onClick: () -> Unit, onAdd: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = Modifier.width(140.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(140.dp)) {
            AsyncImage(
                model = track.coverUrl,
                contentDescription = track.title,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .background(tokens.colors.surfaceElevated),
                contentScale = ContentScale.Crop,
            )
            IconButton(
                onClick = onAdd,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Icon(
                    Icons.Filled.PlaylistAdd,
                    contentDescription = "Add to playlist",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            track.title,
            color = tokens.colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artist,
            color = tokens.colors.textMuted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MusicArtworkCard(
    title: String,
    subtitle: String,
    imageUrl: String,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = Modifier.width(140.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = imageUrl,
            contentDescription = title,
            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(16.dp))
                .background(tokens.colors.surfaceElevated),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            color = tokens.colors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            color = tokens.colors.textMuted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MusicNowPlayingBar(
    state: MusicPlayer.State,
    onOpen: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val track = state.track ?: return
    Surface(
        modifier = Modifier.fillMaxWidth().padding(
            start = NuvioTokens.Space.s8,
            end = NuvioTokens.Space.s8,
            bottom = NuvioTokens.Space.s6,
        ).clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        color = tokens.colors.surfaceElevated.copy(alpha = 0.96f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
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
            IconButton(onClick = { MusicPlayer.previous() }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = tokens.colors.textPrimary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { MusicPlayer.toggle() }, modifier = Modifier.size(38.dp)) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = tokens.colors.accent, strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        tint = tokens.colors.textPrimary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            IconButton(onClick = { MusicPlayer.next() }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = tokens.colors.textPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun MusicNowPlayingScreen(
    state: MusicPlayer.State,
    liked: Boolean,
    onClose: () -> Unit,
    onLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onMultiwindow: () -> Unit,
    active: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val track = state.track ?: return
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }

    LaunchedEffect(active) {
        if (!active) fullscreen = false
    }

    LaunchedEffect(interaction, fullscreen) {
        controlsVisible = true
        delay(NOW_PLAYING_CONTROLS_TIMEOUT_MS)
        controlsVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF2A155C), Color(0xFF0B0B10), Color.Black),
                ),
            )
            .clickable { interaction += 1 },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close player", tint = Color.White)
                    }
                    Text(
                        "Now playing",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (state.videoMode) {
                    if (active && !fullscreen) {
                        MusicVideoSurface(modifier = Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(22.dp)))
                    } else {
                        Box(Modifier.fillMaxSize().background(Color.Black))
                    }
                    if (controlsVisible) {
                        Row(
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.playing || state.loading) {
                                Surface(
                                    onClick = onMultiwindow,
                                    shape = CircleShape,
                                    color = Color.Black.copy(alpha = 0.55f),
                                ) {
                                    Icon(
                                        Icons.Filled.PictureInPictureAlt,
                                        contentDescription = "Multiwindow",
                                        tint = Color.White,
                                        modifier = Modifier.padding(8.dp).size(22.dp),
                                    )
                                }
                            }
                            Surface(
                                onClick = { fullscreen = true },
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.55f),
                            ) {
                                Icon(
                                    Icons.Filled.Fullscreen,
                                    contentDescription = "Fullscreen",
                                    tint = Color.White,
                                    modifier = Modifier.padding(8.dp).size(22.dp),
                                )
                            }
                        }
                    }
                } else {
                    AsyncImage(
                        model = track.coverUrl,
                        contentDescription = track.title,
                        modifier = Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF181820)),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                color = Color.White,
                                fontSize = 21.sp,
                                lineHeight = 24.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                track.artist,
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = onAddToPlaylist) {
                            Icon(
                                Icons.Filled.PlaylistAdd,
                                contentDescription = "Add to playlist",
                                tint = Color.White.copy(alpha = 0.75f),
                            )
                        }
                        IconButton(onClick = { onLike() }) {
                            Icon(
                                if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Like",
                                tint = if (liked) tokens.colors.accent else Color.White.copy(alpha = 0.75f),
                            )
                        }
                    }
                    val duration = state.durationMs.coerceAtLeast(0L)
                    Slider(
                        value = if (duration > 0L) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = {
                            MusicPlayer.seekTo((it * duration).toLong())
                            interaction += 1
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(state.positionMs), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        Text(formatTime(duration), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { MusicPlayer.setShuffle(!state.shuffle); interaction += 1 }) {
                            Icon(
                                Icons.Filled.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (state.shuffle) tokens.colors.accent else Color.White.copy(alpha = 0.55f),
                            )
                        }
                        IconButton(onClick = { MusicPlayer.previous(); interaction += 1 }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                        IconButton(onClick = { MusicPlayer.toggle(); interaction += 1 }, modifier = Modifier.size(58.dp)) {
                            if (state.loading) {
                                CircularProgressIndicator(Modifier.size(30.dp), color = tokens.colors.accent, strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (state.playing) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        }
                        IconButton(onClick = { MusicPlayer.next(); interaction += 1 }) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                        IconButton(onClick = { MusicPlayer.cycleRepeat(); interaction += 1 }) {
                            Icon(
                                if (state.repeat == MusicPlayer.RepeatMode.One) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                contentDescription = "Repeat",
                                tint = if (state.repeat != MusicPlayer.RepeatMode.Off) tokens.colors.accent else Color.White.copy(alpha = 0.55f),
                            )
                        }
                    }
                    Text(
                        state.error ?: state.streamLabel,
                        color = if (state.error != null) tokens.colors.danger else Color.White.copy(alpha = 0.45f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 56.dp),
        ) {
            MusicModePill(
                video = state.videoMode,
                onChange = {
                    MusicPlayer.setVideoMode(it)
                    interaction += 1
                },
            )
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                MusicVideoSurface(Modifier.fillMaxSize())
                IconButton(
                    onClick = { fullscreen = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Exit fullscreen", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun MusicVideoSurface(modifier: Modifier = Modifier) {
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
        modifier = modifier.background(Color.Black),
    )
}

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val minutes = total / 60L
    val seconds = total % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
