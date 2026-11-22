package com.nuvio.app.features.plugins.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.features.plugins.PluginBrowseRepository
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.external.PluginBrowseDetail
import com.nuvio.app.features.plugins.external.PluginBrowseEpisode
import com.nuvio.app.features.plugins.external.PluginBrowseItem
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.plugins_browse_back
import nuvio.composeapp.generated.resources.plugins_browse_detail_failed
import nuvio.composeapp.generated.resources.plugins_browse_episodes
import nuvio.composeapp.generated.resources.plugins_browse_no_results
import nuvio.composeapp.generated.resources.plugins_browse_no_streams
import nuvio.composeapp.generated.resources.plugins_browse_search
import nuvio.composeapp.generated.resources.plugins_browse_search_failed
import nuvio.composeapp.generated.resources.plugins_browse_search_placeholder
import nuvio.composeapp.generated.resources.plugins_browse_select_episode
import nuvio.composeapp.generated.resources.plugins_browse_streams
import nuvio.composeapp.generated.resources.plugins_browse_streams_failed
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PluginBrowseScreen(
    scraperId: String,
    scraperName: String,
    onBack: () -> Unit,
    onPlay: (PluginBrowseItem, PluginBrowseDetail, PluginBrowseEpisode?, PluginRuntimeResult) -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loadingDetail by remember { mutableStateOf(false) }
    var loadingStreams by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val stateKey = remember(scraperId) {
        "${PluginBrowseRepository.currentProfileId()}:$scraperId"
    }
    val savedState = remember(stateKey) { PluginBrowseStateStore.get(stateKey) }
    var query by rememberSaveable { mutableStateOf(savedState.query) }
    var results by remember { mutableStateOf(savedState.results) }
    var detailItem by remember { mutableStateOf(savedState.detailItem) }
    var detail by remember { mutableStateOf(savedState.detail) }
    var selectedEpisode by remember { mutableStateOf(savedState.selectedEpisode) }
    var streams by remember { mutableStateOf(savedState.streams) }
    SideEffect {
        PluginBrowseStateStore.put(
            key = stateKey,
            state = PluginBrowseStateStore.State(
                query = query,
                results = results,
                detailItem = detailItem,
                detail = detail,
                selectedEpisode = selectedEpisode,
                streams = streams,
            ),
        )
    }
    val noStreamsText = stringResource(Res.string.plugins_browse_no_streams)
    val detailFailedText = stringResource(Res.string.plugins_browse_detail_failed)
    val noResultsText = stringResource(Res.string.plugins_browse_no_results)
    val searchFailedText = stringResource(Res.string.plugins_browse_search_failed)
    val streamsFailedText = stringResource(Res.string.plugins_browse_streams_failed)

    fun loadStreams(
        currentDetail: PluginBrowseDetail,
        episode: PluginBrowseEpisode?,
        data: String,
    ) {
        loadingStreams = true
        streams = emptyList()
        errorMessage = null
        scope.launch {
            PluginBrowseRepository.loadStreams(
                scraperId = scraperId,
                detail = currentDetail,
                season = episode?.season,
                episode = episode?.episode,
                data = data,
            ).onSuccess { loaded ->
                streams = loaded
            }.onFailure { error ->
                errorMessage = error.message ?: streamsFailedText
            }
            loadingStreams = false
        }
    }

    fun openDetail(item: PluginBrowseItem) {
        detailItem = item
        detail = null
        loadingDetail = true
        selectedEpisode = null
        streams = emptyList()
        errorMessage = null
        scope.launch {
            PluginBrowseRepository.loadDetail(scraperId, item)
                .onSuccess { loaded ->
                    detail = loaded
                    if (loaded == null) {
                        errorMessage = detailFailedText
                    } else if (!loaded.isSeries) {
                        loadStreams(loaded, null, loaded.movieData.orEmpty())
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
        if (trimmed.isEmpty()) return
        searching = true
        errorMessage = null
        detail = null
        detailItem = null
        streams = emptyList()
        results = emptyList()
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

    NuvioScreen(modifier = Modifier.fillMaxWidth()) {
        stickyHeader {
            NuvioScreenHeader(
                title = scraperName,
                onBack = {
                    PluginBrowseStateStore.clear(stateKey)
                    onBack()
                },
            )
        }

        item {
            NuvioSurfaceCard {
                NuvioInputField(
                    value = query,
                    onValueChange = {
                        query = it
                        errorMessage = null
                    },
                    placeholder = stringResource(Res.string.plugins_browse_search_placeholder),
                )
                Spacer(modifier = Modifier.height(10.dp))
                NuvioPrimaryButton(
                    text = stringResource(Res.string.plugins_browse_search),
                    enabled = query.isNotBlank() && !searching,
                    onClick = { runSearch() },
                )
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        val currentDetail = detail
        if (currentDetail != null) {
            item {
                NuvioPrimaryButton(
                    text = stringResource(Res.string.plugins_browse_back),
                    onClick = {
                        detail = null
                        detailItem = null
                        selectedEpisode = null
                        streams = emptyList()
                        errorMessage = null
                    },
                )
            }

            item {
                DetailHeader(
                    item = detailItem,
                    detail = currentDetail,
                )
            }

            if (currentDetail.isSeries) {
                item {
                    Text(
                        text = stringResource(Res.string.plugins_browse_episodes),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                if (currentDetail.episodes.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(Res.string.plugins_browse_select_episode),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(currentDetail.episodes) { episode ->
                        EpisodeCard(
                            episode = episode,
                            selected = episode == selectedEpisode,
                            onClick = {
                                selectedEpisode = episode
                                loadStreams(currentDetail, episode, episode.data)
                            },
                        )
                    }
                }
            }

            item {
                Text(
                    text = stringResource(Res.string.plugins_browse_streams),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            if (loadingStreams) {
                item { NuvioLoadingIndicator() }
            } else if (currentDetail.isSeries && selectedEpisode == null) {
                item {
                    Text(
                        text = stringResource(Res.string.plugins_browse_select_episode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (streams.isEmpty()) {
                item {
                    Text(
                        text = noStreamsText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(streams) { stream ->
                    StreamCard(
                        stream = stream,
                        onClick = {
                            val currentItem = detailItem
                            if (currentItem != null) {
                                onPlay(currentItem, currentDetail, selectedEpisode, stream)
                            }
                        },
                    )
                }
            }
        } else {
            if (loadingDetail || searching) {
                item { NuvioLoadingIndicator() }
            }
            items(results) { item ->
                ResultCard(item = item, onClick = { openDetail(item) })
            }
        }
    }
}

@Composable
private fun ResultCard(
    item: PluginBrowseItem,
    onClick: () -> Unit,
) {
    NuvioSurfaceCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 56.dp, height = 80.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(item.type, item.year?.toString()).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailHeader(
    item: PluginBrowseItem?,
    detail: PluginBrowseDetail,
) {
    NuvioSurfaceCard {
        Row(modifier = Modifier.fillMaxWidth()) {
            val poster = detail.posterUrl ?: item?.posterUrl
            if (!poster.isNullOrBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 72.dp, height = 104.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detail.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                detail.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: PluginBrowseEpisode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NuvioSurfaceCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = episodeLabel(episode),
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    text = "Selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StreamCard(
    stream: PluginRuntimeResult,
    onClick: () -> Unit,
) {
    val subtitle = listOfNotNull(
        stream.quality,
        stream.size,
        stream.language,
    ).joinToString(" • ")

    NuvioSurfaceCard(modifier = Modifier.clickable(onClick = onClick)) {
        Text(
            text = stream.name ?: stream.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun episodeLabel(episode: PluginBrowseEpisode): String {
    val prefix = when {
        episode.season != null && episode.episode != null -> "S${episode.season}E${episode.episode}"
        episode.episode != null -> "Episode ${episode.episode}"
        else -> null
    }
    return listOfNotNull(prefix, episode.name.takeIf { it.isNotBlank() }).joinToString(" • ")
}
