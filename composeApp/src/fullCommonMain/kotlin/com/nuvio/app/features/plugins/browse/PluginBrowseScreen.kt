package com.nuvio.app.features.plugins.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.TrailerPlaybackMode
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioInfoBadge
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.SkeletonPoster
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.features.details.MetaCompany
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.details.components.ExpandableDescription
import com.nuvio.app.features.details.components.TrailerPlayerPopup
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.plugins.PluginBrowseRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginRepositoryType
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingLibraryProvider
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginSubtitleResult
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.plugins.external.PluginBrowseActor
import com.nuvio.app.features.plugins.external.PluginBrowseCategory
import com.nuvio.app.features.plugins.external.PluginBrowseDetail
import com.nuvio.app.features.plugins.external.PluginBrowseTrailer
import com.nuvio.app.features.plugins.external.toBrowseStreamItem
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.player.PlayerProviderFilterRow
import com.nuvio.app.features.player.PlayerStreamList
import com.nuvio.app.features.tmdb.TmdbMetadataService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.plugins.external.PluginBrowseEpisode
import com.nuvio.app.features.plugins.external.PluginBrowseItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.plugins_browse_detail_failed
import nuvio.composeapp.generated.resources.plugins_browse_episodes
import nuvio.composeapp.generated.resources.plugins_browse_coming_soon
import nuvio.composeapp.generated.resources.plugins_browse_trailer_number
import nuvio.composeapp.generated.resources.plugins_browse_minutes
import nuvio.composeapp.generated.resources.plugins_browse_rating
import nuvio.composeapp.generated.resources.plugins_browse_status
import nuvio.composeapp.generated.resources.plugins_browse_runtime
import nuvio.composeapp.generated.resources.plugins_browse_genres
import nuvio.composeapp.generated.resources.plugins_browse_more_like_this
import nuvio.composeapp.generated.resources.plugins_browse_trailers
import nuvio.composeapp.generated.resources.plugins_browse_cast
import nuvio.composeapp.generated.resources.plugins_browse_details
import nuvio.composeapp.generated.resources.plugins_browse_content_rating
import nuvio.composeapp.generated.resources.plugins_browse_episodes_count
import nuvio.composeapp.generated.resources.plugins_browse_extension_label
import nuvio.composeapp.generated.resources.plugins_browse_latest
import nuvio.composeapp.generated.resources.plugins_browse_latest_empty
import nuvio.composeapp.generated.resources.plugins_browse_no_catalog_title
import nuvio.composeapp.generated.resources.plugins_browse_latest_failed
import nuvio.composeapp.generated.resources.plugins_browse_live
import nuvio.composeapp.generated.resources.plugins_browse_movie
import nuvio.composeapp.generated.resources.plugins_browse_no_results
import nuvio.composeapp.generated.resources.plugins_browse_no_streams
import nuvio.composeapp.generated.resources.plugins_browse_provider_hint
import nuvio.composeapp.generated.resources.plugins_browse_results_for
import nuvio.composeapp.generated.resources.plugins_browse_categories_count
import nuvio.composeapp.generated.resources.plugins_browse_search
import nuvio.composeapp.generated.resources.plugins_browse_search_failed
import nuvio.composeapp.generated.resources.plugins_browse_search_placeholder
import nuvio.composeapp.generated.resources.plugins_browse_season
import nuvio.composeapp.generated.resources.plugins_browse_series
import nuvio.composeapp.generated.resources.plugins_browse_sources
import nuvio.composeapp.generated.resources.plugins_browse_streams
import nuvio.composeapp.generated.resources.plugins_browse_streams_failed
import nuvio.composeapp.generated.resources.plugins_production
import nuvio.composeapp.generated.resources.plugins_save
import nuvio.composeapp.generated.resources.plugins_saved
import nuvio.composeapp.generated.resources.plugins_sources_title
import nuvio.composeapp.generated.resources.plugins_sources_this_extension
import nuvio.composeapp.generated.resources.plugins_sources_other_extensions
import nuvio.composeapp.generated.resources.plugins_sources_addons
import nuvio.composeapp.generated.resources.plugins_sources_loading
import nuvio.composeapp.generated.resources.plugins_sources_empty
import nuvio.composeapp.generated.resources.plugins_sources_hold
import nuvio.composeapp.generated.resources.plugins_browse_trailer_unavailable
import nuvio.composeapp.generated.resources.plugins_provider_version
import org.jetbrains.compose.resources.stringResource

/**
 * CloudStream's own catalog, presented like a native Nuvio catalog: a poster grid with
 * provider search, then a hero-led detail page with episodes and sources.
 */
@Composable
internal fun PluginBrowseScreen(
    scraperId: String,
    scraperName: String,
    initialItem: PluginBrowseItem? = null,
    onBack: () -> Unit,
    onOpenPerson: (PluginBrowseActor) -> Unit = {},
    onOpenMeta: (MetaPreview) -> Unit = {},
    onPlaySource: (
        providerName: String,
        providerAddonId: String,
        item: PluginBrowseItem,
        detail: PluginBrowseDetail,
        episode: PluginBrowseEpisode?,
        result: PluginRuntimeResult,
    ) -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loadingDetail by remember { mutableStateOf(false) }
    var loadingStreams by remember { mutableStateOf(false) }
    var loadingLatest by remember { mutableStateOf(false) }
    var latestMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    val detailListState = rememberLazyListState()
    val stateKey = remember(scraperId) {
        "${PluginBrowseRepository.currentProfileId()}:$scraperId"
    }
    val savedState = remember(stateKey) { PluginBrowseStateStore.get(stateKey) }
    var mode by remember { mutableStateOf(savedState.mode) }
    var query by rememberSaveable { mutableStateOf(savedState.query) }
    var results by remember { mutableStateOf(savedState.results) }
    var latestItems by remember { mutableStateOf(savedState.latestItems) }
    var latestHasNext by remember { mutableStateOf(savedState.latestHasNext) }
    var latestPage by remember { mutableStateOf(savedState.latestPage) }
    var latestAttempted by remember { mutableStateOf(savedState.latestAttempted) }
    var latestCategories by remember { mutableStateOf(savedState.latestCategories) }
    var selectedCategoryKey by remember { mutableStateOf(savedState.selectedCategoryKey) }
    var detailItem by remember { mutableStateOf(savedState.detailItem) }
    var detail by remember { mutableStateOf(savedState.detail) }
    var selectedEpisode by remember { mutableStateOf(savedState.selectedEpisode) }
    var streams by remember { mutableStateOf(savedState.streams) }
    SideEffect {
        PluginBrowseStateStore.put(
            key = stateKey,
            state = PluginBrowseStateStore.State(
                mode = mode,
                query = query,
                results = results,
                latestItems = latestItems,
                latestHasNext = latestHasNext,
                latestPage = latestPage,
                latestAttempted = latestAttempted,
                latestCategories = latestCategories,
                selectedCategoryKey = selectedCategoryKey,
                detailItem = detailItem,
                detail = detail,
                selectedEpisode = selectedEpisode,
                streams = streams,
            ),
        )
    }

    val pluginState by PluginRepository.uiState.collectAsStateWithLifecycle()
    val provider = remember(pluginState.scrapers, scraperId) {
        pluginState.scrapers.firstOrNull { it.id == scraperId }
    }
    val noStreamsText = stringResource(Res.string.plugins_browse_no_streams)
    val detailFailedText = stringResource(Res.string.plugins_browse_detail_failed)
    val noResultsText = stringResource(Res.string.plugins_browse_no_results)
    val searchFailedText = stringResource(Res.string.plugins_browse_search_failed)
    val streamsFailedText = stringResource(Res.string.plugins_browse_streams_failed)
    val latestEmptyText = stringResource(Res.string.plugins_browse_latest_empty)
    val latestFailedText = stringResource(Res.string.plugins_browse_latest_failed)
    val selectedCategory = latestCategories.firstOrNull { it.key == selectedCategoryKey }


    fun closeDetail() {
        detail = null
        detailItem = null
        selectedEpisode = null
        streams = emptyList()
        errorMessage = null
    }

    fun openDetail(item: PluginBrowseItem) {
        detailItem = item
        detail = null
        loadingDetail = true
        selectedEpisode = null
        streams = emptyList()
        errorMessage = null
        scope.launch { detailListState.scrollToItem(0) }
        scope.launch {
            PluginBrowseRepository.loadDetail(scraperId, item)
                .onSuccess { loaded ->
                    detail = loaded
                    if (loaded == null) {
                        errorMessage = detailFailedText
                    } else if (loaded.isSeries) {
                        selectedEpisode = loaded.episodes.firstOrNull()
                    }
                }
                .onFailure { error ->
                    errorMessage = error.message ?: detailFailedText
                }
            loadingDetail = false
        }
    }

    fun runSearch() {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            mode = PluginBrowseMode.Latest
            errorMessage = null
            return
        }
        mode = PluginBrowseMode.Search
        searching = true
        errorMessage = null
        detail = null
        detailItem = null
        streams = emptyList()
        results = emptyList()
        focusManager.clearFocus()
        scope.launch {
            PluginBrowseRepository.search(scraperId, trimmed)
                .onSuccess { found ->
                    results = found
                    if (found.isEmpty()) {
                        errorMessage = noResultsText
                    }
                }
                .onFailure { error ->
                    errorMessage = error.message ?: searchFailedText
                }
            searching = false
        }
    }

    fun loadLatest(reset: Boolean, category: PluginBrowseCategory? = selectedCategory) {
        if (loadingLatest) return
        val page = if (reset || latestPage <= 0) 1 else latestPage + 1
        loadingLatest = true
        latestMessage = null
        if (reset) {
            latestItems = emptyList()
            latestHasNext = false
        }
        scope.launch {
            PluginBrowseRepository.latest(scraperId, page, category)
                .onSuccess { feed ->
                    latestAttempted = true
                    if (feed == null) {
                        if (reset) {
                            latestItems = emptyList()
                            latestMessage = latestEmptyText
                        } else {
                            latestHasNext = false
                        }
                    } else {
                        if (feed.categories.isNotEmpty()) {
                            latestCategories = feed.categories
                        }
                        latestItems = if (reset) {
                            feed.items
                        } else {
                            (latestItems + feed.items).distinctBy { it.apiIndex to it.url }
                        }
                        latestHasNext = feed.hasNext
                        latestPage = page
                        // The loader may have used a different tab because "Latest" was
                        // empty. Keep the chips and later pages on that same tab.
                        if (reset && category == null) {
                            feed.activeCategory?.let { active ->
                                selectedCategoryKey = active.key
                            }
                        }
                        if (latestItems.isEmpty()) {
                            latestMessage = latestEmptyText
                        }
                    }
                }
                .onFailure { error ->
                    latestAttempted = true
                    latestMessage = error.message ?: latestFailedText
                }
            loadingLatest = false
        }
    }

    fun selectCategory(category: PluginBrowseCategory?) {
        if (loadingLatest) return
        if (selectedCategoryKey == category?.key && latestAttempted) return
        selectedCategoryKey = category?.key
        latestAttempted = false
        latestPage = 0
        latestHasNext = false
        latestMessage = null
        loadLatest(reset = true, category = category)
    }

    LaunchedEffect(stateKey, initialItem?.url) {
        if (initialItem != null) {
            if (detailItem?.url != initialItem.url) {
                openDetail(initialItem)
            }
        } else if (!latestAttempted) {
            loadLatest(reset = true)
        }
    }

    LaunchedEffect(gridState, mode, latestHasNext, loadingLatest, latestItems.size, selectedCategory?.key) {
        if (mode != PluginBrowseMode.Latest || !latestHasNext || loadingLatest) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
            .distinctUntilChanged()
            .collect { lastVisible ->
                val total = gridState.layoutInfo.totalItemsCount
                if (lastVisible >= total - 4) loadLatest(reset = false, category = selectedCategory)
            }
    }

    val showDetail = detail != null || (loadingDetail && detailItem != null)
    if (showDetail) {
        val currentDetail = detail
        if (currentDetail == null) {
            val item = detailItem
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.nuvio.colors.background),
            ) {
                PluginBrowseHeader(
                    title = item?.name ?: scraperName,
                    subtitle = null,
                    provider = provider,
                    onBack = ::closeDetail,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    NuvioLoadingIndicator()
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                PluginDetailContent(
                    item = detailItem,
                    detail = currentDetail,
                    provider = provider,
                    scraperId = scraperId,
                    scraperName = scraperName,
                    selectedEpisode = selectedEpisode,
                    listState = detailListState,
                    onBack = ::closeDetail,
                    onSelectEpisode = { episode -> selectedEpisode = episode },
                    onOpenRecommendation = { recommendation -> openDetail(recommendation) },
                    onOpenPerson = onOpenPerson,
                    onOpenMeta = onOpenMeta,
                    onPlaySource = onPlaySource,
                )
            }
        }
        return
    }

    val displayItems = if (mode == PluginBrowseMode.Search) results else latestItems
    val emptyText = if (mode == PluginBrowseMode.Search) {
        errorMessage ?: noResultsText
    } else {
        latestMessage ?: latestEmptyText
    }
    val emptyTitle = if (mode == PluginBrowseMode.Search) {
        stringResource(Res.string.plugins_browse_search)
    } else {
        stringResource(Res.string.plugins_browse_no_catalog_title)
    }
    val isEmpty = displayItems.isEmpty() && !searching && !loadingLatest
    val isBusyFirstLoad = displayItems.isEmpty() && (searching || loadingLatest)
    val categoryChips = latestCategories
    val heroItems = remember(latestItems) { latestItems.take(10) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.nuvio.colors.background),
    ) {
        val columns = when {
            maxWidth >= 840.dp -> 5
            maxWidth >= 600.dp -> 4
            else -> 3
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = nuvioSafeBottomPadding(28.dp)),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(
                key = "plugin_catalog_hero",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                if (heroItems.isEmpty()) {
                    PluginCatalogHero(
                        title = scraperName,
                        provider = provider,
                        categoryCount = latestCategories.size,
                    )
                } else {
                    PluginCatalogHeroCarousel(
                        items = heroItems,
                        provider = provider,
                        onOpenItem = { item -> openDetail(item) },
                    )
                }
            }
            item(
                key = "plugin_catalog_controls",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    PluginSearchField(
                        query = query,
                        searching = searching,
                        onQueryChange = { value ->
                            query = value
                            errorMessage = null
                            if (value.isBlank() && mode == PluginBrowseMode.Search) {
                                mode = PluginBrowseMode.Latest
                                if (!latestAttempted) loadLatest(reset = true)
                            }
                        },
                        onSearch = ::runSearch,
                        onClear = {
                            query = ""
                            mode = PluginBrowseMode.Latest
                            errorMessage = null
                            if (!latestAttempted) loadLatest(reset = true)
                        },
                    )
                    if (mode == PluginBrowseMode.Latest && categoryChips.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            categoryChips.forEach { category ->
                                Chip(
                                    label = category.label,
                                    selected = selectedCategoryKey == category.key,
                                    onClick = { selectCategory(category) },
                                )
                            }
                        }
                    }
                    errorMessage?.takeIf { displayItems.isNotEmpty() }?.let { message ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (isBusyFirstLoad) {
                items(columns * 2) {
                    SkeletonPoster(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        cornerRadius = 12.dp,
                        showLabels = true,
                    )
                }
            } else if (isEmpty) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = emptyTitle,
                        message = emptyText,
                        actionLabel = if (mode == PluginBrowseMode.Latest) {
                            stringResource(Res.string.action_retry)
                        } else {
                            null
                        },
                        onActionClick = if (mode == PluginBrowseMode.Latest) {
                            { loadLatest(reset = true) }
                        } else {
                            null
                        },
                    )
                }
            } else {
                items(
                    items = displayItems,
                    key = { item -> "${item.apiIndex}:${item.url}" },
                ) { item ->
                    Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                        PluginPosterTile(
                            item = item,
                            onClick = { openDetail(item) },
                        )
                    }
                }
                if (searching || loadingLatest) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            NuvioLoadingIndicator(modifier = Modifier.size(22.dp))
                        }
                    }
                } else if (mode == PluginBrowseMode.Latest && latestMessage != null && displayItems.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = latestMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.nuvio.colors.textMuted,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
        NuvioBackButton(
            onClick = {
                PluginBrowseStateStore.clear(stateKey)
                onBack()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 8.dp),
            containerColor = Color.Black,
            contentColor = Color.White,
        )
    }
}

@Composable
private fun PluginCatalogHeroCarousel(
    items: List<PluginBrowseItem>,
    provider: PluginScraper?,
    onOpenItem: (PluginBrowseItem) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    LaunchedEffect(items.size) {
        if (pagerState.currentPage >= items.size) pagerState.scrollToPage(0)
    }
    LaunchedEffect(items.size, pagerState) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(5_000L)
            pagerState.animateScrollToPage((pagerState.currentPage + 1) % items.size)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(330.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items[page]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onOpenItem(item) },
            ) {
                if (!item.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = item.posterUrl,
                        contentDescription = item.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.06f
                                scaleY = 1.06f
                            },
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.Center,
                    )
                } else {
                    GeneratedDetailArtwork(
                        name = item.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.nuvio.colors.accent,
                        ) {
                            Text(
                                text = "TOP ${page + 1}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = MaterialTheme.nuvio.colors.onAccent,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            )
                        }
                        Text(
                            text = provider?.name?.uppercase().orEmpty(),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = pluginMetaLine(item.type, item.year),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == pagerState.currentPage) 6.dp else 4.dp)
                        .clip(CircleShape)
                        .background(
                            Color.White.copy(
                                alpha = if (index == pagerState.currentPage) 0.92f else 0.42f,
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun PluginCatalogHero(
    title: String,
    provider: PluginScraper?,
    categoryCount: Int,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val logo = provider?.logo?.takeIf { it.isNotBlank() }
    val typeLine = provider?.supportedTypes.orEmpty().joinToString(" | ")
    val versionLabel = provider?.version?.takeIf { it.isNotBlank() }?.let {
        stringResource(Res.string.plugins_provider_version, it)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        tokens.colors.accent.copy(alpha = 0.65f),
                        tokens.colors.surfaceElevated,
                        tokens.colors.background,
                    ),
                ),
            ),
    ) {
        if (logo != null) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.12f
                        scaleY = 1.12f
                        alpha = 0.82f
                    }
                    .blur(18.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Extension,
                contentDescription = null,
                tint = tokens.colors.textPrimary.copy(alpha = 0.10f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp)
                    .size(110.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (logo != null) {
                    AsyncImage(
                        model = logo,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(Res.string.plugins_browse_extension_label).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 0.8.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = Color.White.copy(alpha = 0.82f),
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = provider?.description?.takeIf { it.isNotBlank() }
                    ?: stringResource(Res.string.plugins_browse_provider_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (typeLine.isNotBlank()) HeroPill(typeLine)
                if (versionLabel != null) HeroPill(versionLabel)
                if (categoryCount > 1) {
                    HeroPill(
                        stringResource(
                            Res.string.plugins_browse_categories_count,
                            categoryCount,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.42f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun PluginBrowseHeader(
    title: String,
    subtitle: String?,
    provider: PluginScraper?,
    onBack: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioBackButton(
            onClick = onBack,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        if (!provider?.logo.isNullOrBlank()) {
            AsyncImage(
                model = provider?.logo,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PluginSearchField(
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    NuvioInputField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(Res.string.plugins_browse_search_placeholder),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (query.isNotBlank()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = MaterialTheme.nuvio.colors.textMuted,
                        )
                    }
                }
                IconButton(
                    onClick = onSearch,
                    enabled = query.isNotBlank() && !searching,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(Res.string.plugins_browse_search),
                        tint = if (query.isNotBlank()) {
                            MaterialTheme.nuvio.colors.accent
                        } else {
                            MaterialTheme.nuvio.colors.textMuted
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun PluginPosterTile(
    item: PluginBrowseItem,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(shape)
                .background(tokens.colors.surface)
                .nuvioCardDepth(
                    shape = shape,
                    surface = NuvioCardDepthSurface.Posters,
                ),
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Extension,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp),
                )
            }
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = pluginMetaLine(item.type, item.year),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class PluginResolvedSource(
    val providerName: String,
    val providerAddonId: String,
    val result: PluginRuntimeResult,
    val isCurrent: Boolean,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PluginDetailContent(
    item: PluginBrowseItem?,
    detail: PluginBrowseDetail,
    provider: PluginScraper?,
    scraperId: String,
    scraperName: String,
    selectedEpisode: PluginBrowseEpisode?,
    listState: LazyListState,
    onBack: () -> Unit,
    onSelectEpisode: (PluginBrowseEpisode) -> Unit,
    onOpenRecommendation: (PluginBrowseItem) -> Unit,
    onOpenPerson: (PluginBrowseActor) -> Unit,
    onOpenMeta: (MetaPreview) -> Unit,
    onPlaySource: (
        providerName: String,
        providerAddonId: String,
        item: PluginBrowseItem,
        detail: PluginBrowseDetail,
        episode: PluginBrowseEpisode?,
        result: PluginRuntimeResult,
    ) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val trailerScope = rememberCoroutineScope()
    val contentItem = item ?: PluginBrowseItem(
        apiIndex = detail.apiIndex,
        apiName = scraperName,
        name = detail.name,
        url = "",
        posterUrl = detail.posterUrl,
        type = if (detail.isSeries) "tv" else "movie",
        year = null,
    )
    val inAppTrailer = AppFeaturePolicy.trailerPlaybackMode == TrailerPlaybackMode.IN_APP
    val trailerUnavailableText = stringResource(Res.string.plugins_browse_trailer_unavailable)
    val noSourcesText = stringResource(Res.string.plugins_sources_empty)
    var tmdbId by remember(detail.name) { mutableStateOf<Int?>(null) }
    var tmdbMeta by remember(detail.name) { mutableStateOf<MetaDetails?>(null) }
    var savedInLibrary by remember(detail.name) { mutableStateOf(false) }
    var showSaveOptions by remember { mutableStateOf(false) }
    LaunchedEffect(detail.name, detail.isSeries, item?.year) {
        val id = TmdbMetadataService.searchTitleId(
            name = detail.name,
            isSeries = detail.isSeries,
            year = item?.year,
        )
        tmdbId = id
        tmdbMeta = if (id != null) {
            runCatching {
                TmdbMetadataService.fetchStandaloneMeta(
                    type = if (detail.isSeries) "tv" else "movie",
                    id = "tmdb:$id",
                    settings = TmdbSettingsRepository.snapshot(),
                )
            }.getOrNull()
        } else {
            null
        }
        savedInLibrary = if (id != null) {
            runCatching {
                LibraryRepository.isSaved(
                    id = "tmdb:$id",
                    type = if (detail.isSeries) "series" else "movie",
                )
            }.getOrDefault(false)
        } else {
            false
        }
    }
    val allTrailers = remember(detail.trailers, tmdbMeta) {
        val fromTmdb = tmdbMeta?.trailers.orEmpty()
            .filter { trailer ->
                trailer.site.equals("YouTube", ignoreCase = true) &&
                    trailer.key.isNotBlank() &&
                    (trailer.type.equals("Trailer", ignoreCase = true) ||
                        trailer.type.equals("Teaser", ignoreCase = true))
            }
            .map { PluginBrowseTrailer("https://www.youtube.com/watch?v=${it.key}") }
            .distinctBy { it.url }
        if (fromTmdb.isNotEmpty()) fromTmdb else detail.trailers
    }
    var selectedTrailer by remember(detail.name) { mutableStateOf<PluginBrowseTrailer?>(null) }
    var trailerSource by remember(detail.name) { mutableStateOf<TrailerPlaybackSource?>(null) }
    var trailerLoading by remember(detail.name) { mutableStateOf(false) }
    var trailerError by remember(detail.name) { mutableStateOf<String?>(null) }
    var resolvingPlay by remember(detail.name) { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var sourcesLoading by remember { mutableStateOf(false) }
    var selectedSourceFilter by remember { mutableStateOf<String?>(null) }
    var sourcesRequested by remember(detail.name) { mutableStateOf(false) }
    var currentSources by remember(detail.name) { mutableStateOf<List<PluginResolvedSource>>(emptyList()) }
    var otherSources by remember(detail.name) { mutableStateOf<List<PluginResolvedSource>>(emptyList()) }
    var addonSources by remember(detail.name) { mutableStateOf<List<PluginResolvedSource>>(emptyList()) }
    val seasonGroups = remember(detail.episodes) {
        detail.episodes
            .groupBy { it.season }
            .toList()
            .sortedBy { (season, _) -> season ?: Int.MAX_VALUE }
            .map { (season, episodes) -> EpisodeSeasonGroup(season, episodes) }
    }
    var expandedSeasons by remember(detail.name) {
        mutableStateOf<Set<Int?>>(
            seasonGroups.firstOrNull()?.season?.let { setOf(it) } ?: emptySet(),
        )
    }
    val detailRows = buildList {
        if (detail.tags.isNotEmpty()) {
            add(stringResource(Res.string.plugins_browse_genres) to detail.tags.joinToString(", "))
        }
        detail.durationMinutes?.takeIf { it > 0 }?.let { minutes ->
            add(
                stringResource(Res.string.plugins_browse_runtime) to
                    stringResource(Res.string.plugins_browse_minutes, minutes),
            )
        }
        detail.contentRating?.takeIf { it.isNotBlank() }?.let { rating ->
            add(stringResource(Res.string.plugins_browse_content_rating) to rating)
        }
        detail.rating?.takeIf { it > 0 }?.let { rating ->
            add(stringResource(Res.string.plugins_browse_rating) to "★ $rating")
        }
        detail.status?.takeIf { it.isNotBlank() }?.let { status ->
            add(stringResource(Res.string.plugins_browse_status) to status.replaceFirstChar { it.uppercase() })
        }
        if (detail.comingSoon) {
            add(
                stringResource(Res.string.plugins_browse_status) to
                    stringResource(Res.string.plugins_browse_coming_soon),
            )
        }
        tmdbMeta?.director?.takeIf { it.isNotEmpty() }?.let { directors ->
            add("Director" to directors.joinToString(", "))
        }
        tmdbMeta?.writer?.takeIf { it.isNotEmpty() }?.let { writers ->
            add("Writer" to writers.joinToString(", "))
        }
        tmdbMeta?.country?.takeIf { it.isNotBlank() }?.let { add("Country" to it) }
        tmdbMeta?.language?.takeIf { it.isNotBlank() }?.let { add("Language" to it) }
        tmdbMeta?.collectionName?.takeIf { it.isNotBlank() }?.let { add("Collection" to it) }
    }

    fun openTrailer(trailer: PluginBrowseTrailer) {
        val isYouTube = trailer.url.contains("youtube.com", ignoreCase = true) ||
            trailer.url.contains("youtu.be", ignoreCase = true)
        if (!inAppTrailer) {
            runCatching { uriHandler.openUri(trailer.url) }
            return
        }
        if (isDirectVideoUrl(trailer.url)) {
            selectedTrailer = trailer
            trailerSource = TrailerPlaybackSource(trailer.url, headers = trailer.headers)
            trailerError = null
            trailerLoading = false
            return
        }
        if (!isYouTube) {
            runCatching { uriHandler.openUri(trailer.url) }
            return
        }
        selectedTrailer = trailer
        trailerSource = null
        trailerError = null
        trailerLoading = true
        trailerScope.launch {
            val source = runCatching {
                TrailerPlaybackResolver.resolveFromYouTubeUrl(trailer.url)
            }.getOrNull()
            trailerSource = source
            trailerError = if (source == null) trailerUnavailableText else null
            trailerLoading = false
        }
    }

    suspend fun loadCurrentSources(): List<PluginResolvedSource> {
        val data = if (detail.isSeries) selectedEpisode?.data else detail.movieData
        if (data.isNullOrBlank()) return emptyList()
        return PluginBrowseRepository.loadStreams(
            scraperId = scraperId,
            detail = detail,
            season = selectedEpisode?.season,
            episode = selectedEpisode?.episode,
            data = data,
            timeoutMs = 20_000L,
        ).getOrElse { emptyList() }
            .map {
                PluginResolvedSource(
                    providerName = "CloudStream · $scraperName",
                    providerAddonId = "plugin:$scraperId",
                    result = it,
                    isCurrent = true,
                )
            }
    }

    suspend fun loadOtherSources(): List<PluginResolvedSource> {
        val extensions = PluginRepository.uiState.value.scrapers
            .filter { scraper ->
                scraper.manifestEnabled &&
                    scraper.id != scraperId &&
                    scraper.type == PluginRepositoryType.EXTERNAL_DEX
            }
            .take(6)
        if (extensions.isEmpty()) return emptyList()
        return coroutineScope {
            extensions.map { extension ->
                async {
                    val matches = withTimeoutOrNull(8_000L) {
                        runCatching {
                            PluginRepository.browseSearch(extension.id, detail.name)
                        }.getOrNull()
                    }.orEmpty()
                    val match = matches.firstOrNull { it.name.equals(detail.name, ignoreCase = true) }
                        ?: matches.firstOrNull()
                        ?: return@async emptyList()
                    val links = withTimeoutOrNull(18_000L) {
                        runCatching {
                            PluginRepository.browseStreams(
                                scraperId = extension.id,
                                apiIndex = match.apiIndex,
                                url = match.url,
                                season = null,
                                episode = null,
                                timeoutMs = 12_000L,
                            )
                        }.getOrNull()
                    }.orEmpty()
                    links.take(3).map {
                        PluginResolvedSource(
                            providerName = "CloudStream · ${extension.name}",
                            providerAddonId = "plugin:${extension.id}",
                            result = it,
                            isCurrent = false,
                        )
                    }
                }
            }.awaitAll().flatten()
        }
    }

    suspend fun loadAddonSources(): List<PluginResolvedSource> {
        val tmdbId = withTimeoutOrNull(8_000L) {
            TmdbMetadataService.searchTitleId(
                name = detail.name,
                isSeries = detail.isSeries,
                year = item?.year,
            )
        } ?: return emptyList()
        val type = if (detail.isSeries) "tv" else "movie"
        val streams = withTimeoutOrNull(20_000L) {
            runCatching {
                StreamsRepository.resolveStreamCandidates(
                    type = type,
                    videoId = tmdbId.toString(),
                    parentMetaId = tmdbId.toString(),
                    season = selectedEpisode?.season,
                    episode = selectedEpisode?.episode,
                    timeoutMs = 15_000L,
                )
            }.getOrNull()
        }.orEmpty()
        return streams
            .filter { it.playableDirectUrl != null }
            .take(5)
            .map {
                PluginResolvedSource(
                    providerName = "Add-on · ${it.addonName}",
                    providerAddonId = it.addonId.ifBlank { "addon:${it.addonName}" },
                    result = it.toPluginResult(),
                    isCurrent = false,
                )
            }
    }

    fun play(source: PluginResolvedSource) {
        showSources = false
        onPlaySource(
            source.providerName,
            source.providerAddonId,
            contentItem,
            detail,
            selectedEpisode,
            source.result,
        )
    }

    fun playAutomatically() {
        if (resolvingPlay) return
        resolvingPlay = true
        scope.launch {
            val chosen = currentSources.firstOrNull()
                ?: loadCurrentSources().firstOrNull()
                ?: otherSources.firstOrNull()
                ?: loadOtherSources().firstOrNull()
                ?: addonSources.firstOrNull()
                ?: loadAddonSources().firstOrNull()
            if (chosen == null) {
                NuvioToastController.show(noSourcesText)
            } else {
                play(chosen)
            }
            resolvingPlay = false
        }
    }

    fun openSources() {
        showSources = true
        if (sourcesRequested) return
        sourcesRequested = true
        sourcesLoading = true
        scope.launch {
            coroutineScope {
                val current = async { loadCurrentSources() }
                val other = async { loadOtherSources() }
                val addons = async { loadAddonSources() }
                currentSources = current.await()
                otherSources = other.await()
                addonSources = addons.await()
            }
            sourcesLoading = false
        }
    }

    fun libraryItemForSave(): LibraryItem? {
        val meta = tmdbMeta
        val id = tmdbId
        if (meta == null || id == null) {
            NuvioToastController.show("No TMDB match for this title.")
            return null
        }
        return meta.toLibraryItem(0L).copy(
            id = "tmdb:$id",
            type = if (detail.isSeries) "series" else "movie",
            tmdbId = id,
        )
    }

    fun saveTo(target: TrackingLibraryProvider?) {
        val item = libraryItemForSave() ?: return
        showSaveOptions = false
        scope.launch {
            val ok = runCatching {
                if (target == null) {
                    LibraryRepository.toggleLocalSaved(item)
                    true
                } else {
                    val desired = target.toggledDefaultMembership(target.membership(item))
                    target.applyMembership(
                        profileId = ProfileRepository.activeProfileId,
                        item = item,
                        desiredMembership = desired,
                    )
                    true
                }
            }.getOrDefault(false)
            if (!ok) {
                NuvioToastController.show("Could not save this title.")
            } else {
                savedInLibrary = LibraryRepository.isSaved(item.id, item.type)
                NuvioToastController.show(
                    if (savedInLibrary) "Saved" else "Removed from library",
                )
            }
        }
    }

    val trailer = allTrailers.firstOrNull()
    val allResolvedSources = listOf(currentSources, otherSources, addonSources).flatten()
    val sourceByKey = allResolvedSources.associateBy { source ->
        "${source.providerAddonId}|${source.result.url}"
    }
    val sourceGroups = remember(currentSources, otherSources, addonSources, scraperId, scraperName) {
        buildList {
            if (currentSources.isNotEmpty()) {
                add(
                    AddonStreamGroup(
                        addonName = "CloudStream · $scraperName",
                        addonId = "plugin:$scraperId",
                        streams = currentSources.map { it.toStreamItem() },
                    ),
                )
            }
            otherSources.groupBy { it.providerAddonId to it.providerName }.forEach { (key, group) ->
                add(
                    AddonStreamGroup(
                        addonName = key.second,
                        addonId = key.first,
                        streams = group.map { it.toStreamItem() },
                    ),
                )
            }
            addonSources.groupBy { it.providerAddonId to it.providerName }.forEach { (key, group) ->
                add(
                    AddonStreamGroup(
                        addonName = key.second,
                        addonId = key.first,
                        streams = group.map { it.toStreamItem() },
                    ),
                )
            }
        }
    }
    val sourceUiState = StreamsUiState(
        groups = sourceGroups,
        selectedFilter = selectedSourceFilter,
        isAnyLoading = sourcesLoading,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background),
        contentPadding = PaddingValues(bottom = nuvioSafeBottomPadding(32.dp)),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "hero") {
            PluginDetailHero(
                item = item,
                detail = detail,
                provider = provider,
                onBack = onBack,
            )
        }

        item(key = "play") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(tokens.shapes.button)
                        .combinedClickable(
                            enabled = !resolvingPlay,
                            onClick = { playAutomatically() },
                            onLongClick = { openSources() },
                        ),
                    shape = tokens.shapes.button,
                    color = tokens.colors.accent,
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (resolvingPlay) {
                            NuvioLoadingIndicator(
                                size = 20.dp,
                                color = tokens.colors.onAccent,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = tokens.colors.onAccent,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(Res.string.action_play),
                                style = MaterialTheme.typography.titleMedium,
                                color = tokens.colors.onAccent,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(Res.string.plugins_sources_hold),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                )
                if (detail.isSeries && selectedEpisode != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = episodeLabel(selectedEpisode),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(tokens.shapes.button)
                            .clickable { showSaveOptions = true },
                        shape = tokens.shapes.button,
                        color = if (savedInLibrary) {
                            tokens.colors.accent.copy(alpha = 0.16f)
                        } else {
                            tokens.colors.surfaceCard
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bookmark,
                                contentDescription = null,
                                tint = if (savedInLibrary) tokens.colors.accent else tokens.colors.textMuted,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    if (savedInLibrary) Res.string.plugins_saved else Res.string.plugins_save,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (savedInLibrary) tokens.colors.accent else tokens.colors.textPrimary,
                            )
                        }
                    }
                    if (trailer != null) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(tokens.shapes.button)
                                .clickable { openTrailer(trailer) },
                            shape = tokens.shapes.button,
                            color = tokens.colors.surfaceCard,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = tokens.colors.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(Res.string.plugins_browse_trailers),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = tokens.colors.textPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (detail.isSeries) {
            item(key = "episodes_header") {
                SectionHeader(
                    title = stringResource(Res.string.plugins_browse_episodes),
                    detail = stringResource(
                        Res.string.plugins_browse_episodes_count,
                        detail.episodes.size,
                    ),
                    topPadding = 12.dp,
                )
            }
            if (detail.episodes.isEmpty()) {
                item(key = "episodes_empty") {
                    MissingSectionText(stringResource(Res.string.plugins_browse_no_streams))
                }
            } else {
                seasonGroups.forEach { group ->
                    val expanded = group.season in expandedSeasons
                    item(key = "season_${group.season}") {
                        SeasonDropdownHeader(
                            season = group.season,
                            count = group.episodes.size,
                            expanded = expanded,
                            onClick = {
                                expandedSeasons = if (expanded) {
                                    expandedSeasons - group.season
                                } else {
                                    expandedSeasons + group.season
                                }
                            },
                        )
                    }
                    if (expanded) {
                        itemsIndexed(
                            items = group.episodes,
                            key = { index, episode ->
                                "${group.season}:${index}:${episode.season}:${episode.episode}:${episode.name}"
                            },
                        ) { _, episode ->
                            EpisodeRow(
                                episode = episode,
                                selected = episode == selectedEpisode,
                                onClick = { onSelectEpisode(episode) },
                            )
                        }
                    }
                }
            }
        }

        if (!detail.description.isNullOrBlank()) {
            item(key = "overview") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
                    ExpandableDescription(
                        text = detail.description.orEmpty(),
                        collapsedMaxLines = 6,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textSecondary,
                    )
                }
            }
        }

        if (detailRows.isNotEmpty()) {
            item(key = "details_header") {
                SectionHeader(
                    title = stringResource(Res.string.plugins_browse_details),
                    topPadding = 24.dp,
                )
            }
            item(key = "details") {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    detailRows.forEach { (label, value) ->
                        DetailInfoRow(label = label, value = value)
                    }
                }
            }
        }

        if (detail.actors.isNotEmpty()) {
            item(key = "cast_header") {
                SectionHeader(
                    title = stringResource(Res.string.plugins_browse_cast),
                    topPadding = 24.dp,
                )
            }
            item(key = "cast") {
                PluginCastRow(
                    actors = detail.actors,
                    onActorClick = onOpenPerson,
                )
            }
        }

        if (tmdbMeta?.productionCompanies?.isNotEmpty() == true) {
            item(key = "production_header") {
                SectionHeader(
                    title = stringResource(Res.string.plugins_production),
                    topPadding = 24.dp,
                )
            }
            item(key = "production") {
                PluginProductionRow(companies = tmdbMeta?.productionCompanies.orEmpty())
            }
        }

        if (allTrailers.isNotEmpty()) {
            item(key = "trailers_header") {
                SectionHeader(
                    title = stringResource(Res.string.plugins_browse_trailers),
                    topPadding = 24.dp,
                )
            }
            item(key = "trailers_grid") {
                PluginTrailerThumbnailGrid(
                    trailers = allTrailers,
                    onTrailerClick = { openTrailer(it) },
                )
            }
        }

        val tmdbMoreLikeThis = tmdbMeta?.moreLikeThis.orEmpty()
        if (tmdbMoreLikeThis.isNotEmpty()) {
            item(key = "recommendations_header") {
                SectionHeader(
                    title = stringResource(Res.string.plugins_browse_more_like_this),
                    topPadding = 24.dp,
                )
            }
            item(key = "recommendations") {
                PluginTmdbRecommendationRow(
                    items = tmdbMoreLikeThis,
                    onClick = onOpenMeta,
                )
            }
        } else if (detail.recommendations.isNotEmpty()) {
            item(key = "recommendations_header") {
                SectionHeader(
                    title = stringResource(Res.string.plugins_browse_more_like_this),
                    topPadding = 24.dp,
                )
            }
            item(key = "recommendations") {
                PluginRecommendationRow(
                    items = detail.recommendations,
                    onClick = onOpenRecommendation,
                )
            }
        }
    }

    if (showSources) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.colors.background),
        ) {
            NuvioScreenHeader(
                title = stringResource(Res.string.plugins_sources_title),
                onBack = { showSources = false },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    text = if (detail.isSeries && selectedEpisode != null) {
                        episodeLabel(selectedEpisode)
                    } else {
                        detail.name
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(12.dp))
                PlayerProviderFilterRow(
                    streamsUiState = sourceUiState,
                    onFilterSelected = { selectedSourceFilter = it },
                    onRefresh = {
                        sourcesRequested = false
                        scope.launch {
                            sourcesLoading = true
                            val current = loadCurrentSources()
                            val other = loadOtherSources()
                            val addons = loadAddonSources()
                            currentSources = current
                            otherSources = other
                            addonSources = addons
                            sourcesLoading = false
                        }
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                PlayerStreamList(
                    streamsUiState = sourceUiState,
                    onStreamSelected = { stream ->
                        val source = sourceByKey["${stream.addonId}|${stream.url}"]
                        if (source != null) {
                            play(source)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        PlatformBackHandler(enabled = showSources) {
            showSources = false
        }
    }

    if (showSaveOptions) {
        DialogSurface(
            onDismissRequest = { showSaveOptions = false },
            title = "Save to",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SaveTargetRow(
                    title = "Nuvio Library",
                    subtitle = "Local library",
                    onClick = { saveTo(null) },
                )
                val providers = remember(showSaveOptions) {
                    TrackingProviderRegistry.connectedLibraryProviders()
                }
                providers.forEach { provider ->
                    SaveTargetRow(
                        title = provider.providerId.displayName,
                        subtitle = "Connected tracker",
                        onClick = { saveTo(provider) },
                    )
                }
            }
        }
    }

    TrailerPlayerPopup(
        visible = selectedTrailer != null,
        trailerTitle = stringResource(Res.string.plugins_browse_trailers),
        trailerType = stringResource(Res.string.plugins_browse_trailers),
        contentTitle = detail.name,
        playbackSource = trailerSource,
        isLoading = trailerLoading,
        errorMessage = trailerError,
        onDismiss = {
            trailerLoading = false
            trailerSource = null
            trailerError = null
            selectedTrailer = null
        },
        onRetry = selectedTrailer?.let { trailerItem -> { openTrailer(trailerItem) } },
        onOpenExternal = selectedTrailer?.let { trailerItem ->
            { runCatching { uriHandler.openUri(trailerItem.url) } }
        },
    )
}

@Composable
private fun SourcesGroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.nuvio.colors.textMuted,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SourceSheetRow(
    source: PluginResolvedSource,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = tokens.shapes.compactCard
    val subtitle = listOfNotNull(
        source.providerName.takeIf { !source.isCurrent },
        source.result.quality?.takeIf { it.isNotBlank() },
        source.result.size?.takeIf { it.isNotBlank() },
        source.result.language?.takeIf { it.isNotBlank() },
    ).joinToString("  ·  ")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = tokens.colors.surfaceCard,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.result.name ?: source.result.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun PluginResolvedSource.toStreamItem(): StreamItem =
    result.toBrowseStreamItem(
        providerName = providerName,
        providerId = providerAddonId,
    )

private fun StreamItem.toPluginResult(): PluginRuntimeResult = PluginRuntimeResult(
    title = name ?: title ?: streamLabel,
    name = name,
    url = url.orEmpty(),
    quality = null,
    size = null,
    language = null,
    provider = addonName,
    type = streamType,
    headers = behaviorHints.proxyHeaders?.request,
    infoHash = infoHash,
    subtitles = externalSubtitles
        .map { PluginSubtitleResult(it.url, it.language, it.name, it.headers) }
        .ifEmpty { null },
)

@Composable
private fun DetailInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PluginCastRow(
    actors: List<PluginBrowseActor>,
    onActorClick: (PluginBrowseActor) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = actors.size) { index ->
            val actor = actors[index]
            Column(
                modifier = Modifier
                    .width(84.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onActorClick(actor) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(tokens.colors.surfaceCard),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!actor.imageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = actor.imageUrl,
                            contentDescription = actor.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = actor.name.take(2).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.colors.textMuted,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = actor.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                actor.role?.takeIf { it.isNotBlank() }?.let { role ->
                    Text(
                        text = role,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginProductionRow(companies: List<MetaCompany>) {
    val tokens = MaterialTheme.nuvio
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(count = companies.size) { index ->
            val company = companies[index]
            Surface(
                shape = tokens.shapes.compactCard,
                color = tokens.colors.surfaceCard,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.colors.surfaceElevated),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!company.logo.isNullOrBlank()) {
                            AsyncImage(
                                model = company.logo,
                                contentDescription = company.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                text = company.name.take(2).uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = tokens.colors.textMuted,
                            )
                        }
                    }
                    Text(
                        text = company.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginTmdbRecommendationRow(
    items: List<MetaPreview>,
    onClick: (MetaPreview) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = items.size) { index ->
            val item = items[index]
            Column(
                modifier = Modifier
                    .width(116.dp)
                    .clickable { onClick(item) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.68f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.nuvio.colors.surface),
                ) {
                    if (!item.poster.isNullOrBlank()) {
                        AsyncImage(
                            model = item.poster,
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.nuvio.colors.textMuted,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(24.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.releaseInfo?.takeIf { it.isNotBlank() }?.let { info ->
                    Text(
                        text = info,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.nuvio.colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveTargetRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .clickable(onClick = onClick),
        shape = tokens.shapes.compactCard,
        color = tokens.colors.surfaceCard,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun PluginTrailerThumbnailGrid(
    trailers: List<PluginBrowseTrailer>,
    onTrailerClick: (PluginBrowseTrailer) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        trailers.chunked(3).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEachIndexed { columnIndex, trailer ->
                    PluginTrailerThumbnail(
                        trailer = trailer,
                        index = rowIndex * 3 + columnIndex,
                        modifier = Modifier.weight(1f),
                        onClick = { onTrailerClick(trailer) },
                    )
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PluginTrailerThumbnail(
    trailer: PluginBrowseTrailer,
    index: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val thumbnail = youtubeVideoKey(trailer.url)?.let { key ->
        "https://img.youtube.com/vi/$key/hqdefault.jpg"
    }
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.colors.surfaceElevated)
            .clickable(onClick = onClick),
    ) {
        if (thumbnail != null) {
            AsyncImage(
                model = thumbnail,
                contentDescription = "Trailer ${index + 1}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.4f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.50f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = "Trailer ${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
        )
    }
}

private fun youtubeVideoKey(url: String): String? {
    val patterns = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{6,})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{6,})"""),
        Regex("""/embed/([A-Za-z0-9_-]{6,})"""),
    )
    return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.getOrNull(1) }
}

@Composable
private fun PluginTrailerRow(index: Int, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val shape = tokens.shapes.compactCard
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = tokens.colors.surfaceCard,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(Res.string.plugins_browse_trailer_number, index + 1),
                style = MaterialTheme.typography.titleSmall,
                color = tokens.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PluginRecommendationRow(
    items: List<PluginBrowseItem>,
    onClick: (PluginBrowseItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = items.size) { index ->
            val item = items[index]
            Column(
                modifier = Modifier
                    .width(116.dp)
                    .clickable { onClick(item) },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.68f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.nuvio.colors.surface),
                ) {
                    if (!item.posterUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = item.posterUrl,
                            contentDescription = item.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.nuvio.colors.textMuted,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(24.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.nuvio.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluginMetaLine(item.type, item.year),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.nuvio.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class EpisodeSeasonGroup(
    val season: Int?,
    val episodes: List<PluginBrowseEpisode>,
)

@Composable
private fun SeasonDropdownHeader(
    season: Int?,
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = tokens.shapes.compactCard
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = tokens.colors.surfaceCard,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = season?.let {
                        stringResource(Res.string.plugins_browse_season, it)
                    } ?: stringResource(Res.string.plugins_browse_episodes),
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.colors.textPrimary,
                )
                Text(
                    text = stringResource(Res.string.plugins_browse_episodes_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun GeneratedDetailArtwork(
    name: String,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    tokens.colors.accent.copy(alpha = 0.62f),
                    tokens.colors.surfaceElevated,
                    tokens.colors.background,
                ),
            ),
        ),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            tokens.colors.accent.copy(alpha = 0.30f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = tokens.colors.textPrimary.copy(alpha = 0.10f),
            textAlign = TextAlign.End,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.68f)
                .padding(top = 96.dp, end = 16.dp),
        )
    }
}

@Composable
private fun PluginDetailHero(
    item: PluginBrowseItem?,
    detail: PluginBrowseDetail,
    provider: PluginScraper?,
    onBack: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val name = detail.name
    val poster = detail.posterUrl ?: item?.posterUrl
    val backdrop = detail.backgroundUrl
    val providerName = provider?.name ?: item?.apiName ?: "CloudStream"
    val type = detail.isSeries
    val year = item?.year
    val episodeCount = detail.episodes.size
    val contentLogo = detail.logoUrl?.takeIf { it.isNotBlank() }
    var logoFailed by remember(detail.logoUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp),
    ) {
        val heroArtwork = backdrop?.takeIf { it.isNotBlank() }
        if (heroArtwork != null && heroArtwork != poster) {
            AsyncImage(
                model = heroArtwork,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = 1.06f
                        scaleY = 1.06f
                    },
                contentScale = ContentScale.Crop,
            )
        } else {
            GeneratedDetailArtwork(
                name = name,
                modifier = Modifier.matchParentSize(),
            )
        }
        NuvioBackButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 8.dp),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 104.dp, height = 156.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.colors.surface),
            ) {
                if (!poster.isNullOrBlank()) {
                    AsyncImage(
                        model = poster,
                        contentDescription = name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Extension,
                        contentDescription = null,
                        tint = tokens.colors.textMuted,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!provider?.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = provider?.logo,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Text(
                        text = providerName.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 0.8.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = tokens.colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (contentLogo != null && !logoFailed) {
                    AsyncImage(
                        model = contentLogo,
                        contentDescription = name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .height(54.dp),
                        onError = { logoFailed = true },
                    )
                } else {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = tokens.colors.textPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioInfoBadge(
                        text = stringResource(
                            if (type) Res.string.plugins_browse_series else Res.string.plugins_browse_movie,
                        ),
                    )
                    year?.takeIf { it > 0 }?.let { releaseYear ->
                        NuvioInfoBadge(text = releaseYear.toString())
                    }
                    if (type && episodeCount > 0) {
                        NuvioInfoBadge(
                            text = stringResource(
                                Res.string.plugins_browse_episodes_count,
                                episodeCount,
                            ),
                        )
                    }
                    detail.rating?.takeIf { it > 0 }?.let { rating ->
                        NuvioInfoBadge(text = "★ $rating")
                    }
                    detail.contentRating?.takeIf { it.isNotBlank() }?.let { rating ->
                        NuvioInfoBadge(text = rating)
                    }
                    detail.durationMinutes?.takeIf { it > 0 }?.let { minutes ->
                        NuvioInfoBadge(
                            text = stringResource(Res.string.plugins_browse_minutes, minutes),
                        )
                    }
                    detail.status?.takeIf { it.isNotBlank() }?.let { status ->
                        NuvioInfoBadge(text = status.replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    detail: String? = null,
    topPadding: Dp = 24.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.nuvio.colors.textPrimary,
        )
        detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: PluginBrowseEpisode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = tokens.shapes.compactCard
    val meta = buildList {
        add(episodeLabel(episode))
        episode.runtimeMinutes?.takeIf { it > 0 }?.let { minutes ->
            add(stringResource(Res.string.plugins_browse_minutes, minutes))
        }
    }.joinToString(" • ")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) tokens.colors.accent.copy(alpha = 0.14f) else tokens.colors.surfaceCard,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!episode.posterUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .size(width = 96.dp, height = 56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.colors.surfaceElevated),
                ) {
                    AsyncImage(
                        model = episode.posterUrl,
                        contentDescription = episode.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.48f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) tokens.colors.accent else tokens.colors.surfaceElevated,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = if (selected) tokens.colors.onAccent else tokens.colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.name.ifBlank { episodeLabel(episode) },
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) tokens.colors.accent else tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginStreamRow(
    stream: PluginRuntimeResult,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val shape = tokens.shapes.compactCard
    val subtitle = listOfNotNull(
        stream.quality?.takeIf { it.isNotBlank() },
        stream.size?.takeIf { it.isNotBlank() },
        stream.language?.takeIf { it.isNotBlank() },
    ).joinToString(" • ")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = tokens.colors.surfaceCard,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stream.name ?: stream.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            stream.seeders?.takeIf { it > 0 }?.let { seeders ->
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "↑ $seeders",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                )
            }
        }
    }
}

@Composable
private fun StreamSkeletonRow(index: Int) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = tokens.shapes.compactCard,
        color = tokens.colors.surfaceCard,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.surfaceElevated),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.48f else 0.62f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.colors.surfaceElevated),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.32f)
                        .height(11.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.colors.surfaceElevated),
                )
            }
        }
    }
}

@Composable
private fun MissingSectionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.nuvio.colors.textMuted,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun pluginMetaLine(type: String, year: Int?): String {
    val typeLabel = when (type.lowercase()) {
        "movie" -> "Movie"
        "series", "tv" -> "Series"
        "live" -> "Live"
        else -> type.replaceFirstChar { it.uppercase() }
    }
    return listOfNotNull(typeLabel, year?.takeIf { it > 0 }?.toString()).joinToString(" • ")
}

private fun episodeLabel(episode: PluginBrowseEpisode): String {
    val prefix = when {
        episode.season != null && episode.episode != null -> "S${episode.season}E${episode.episode}"
        episode.episode != null -> "Episode ${episode.episode}"
        else -> null
    }
    val name = episode.name.takeIf { it.isNotBlank() }
    if (prefix == null) return name.orEmpty()
    if (name == null || name.equals(prefix, ignoreCase = true)) return prefix
    return "$prefix • $name"
}

/** Direct media links an extension can expose instead of a YouTube watch page. */
private fun isDirectVideoUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".mp4") || path.endsWith(".m3u8") ||
        path.endsWith(".webm") || path.endsWith(".mkv")
}
