package com.nuvio.app.features.plugins.browse

import androidx.compose.runtime.Composable
import com.nuvio.app.SettingsDestination
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.player.sanitizePlaybackResponseHeaders
import com.nuvio.app.features.plugins.external.toBrowseStreamItem
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.PluginBrowseRoute
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.plugins_browse_stream_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun PluginBrowseDestination(
    route: PluginBrowseRoute,
    navController: NuvioNavigator,
) {
    SettingsDestination(route, navController) { onBack ->
        val streamUnavailableText = stringResource(Res.string.plugins_browse_stream_unavailable)
        PluginBrowseScreen(
            scraperId = route.scraperId,
            scraperName = route.scraperName,
            onBack = onBack,
            onPlay = { item, detail, episode, result ->
                val providerName = "CloudStream · ${route.scraperName}"
                val stream = result.toBrowseStreamItem(
                    providerName = providerName,
                    providerId = "plugin:${route.scraperId}",
                )
                val directUrl = stream.playableDirectUrl
                if (directUrl == null) {
                    NuvioToastController.show(streamUnavailableText)
                    return@PluginBrowseScreen
                }

                val contentId = "pluginbrowse:${route.scraperId}:${item.apiIndex}:${item.url.hashCode()}"
                val contentType = if (detail.isSeries) "tv" else "movie"
                val contentKey = StreamLinkCacheRepository.contentKey(
                    type = contentType,
                    videoId = contentId,
                    parentMetaId = contentId,
                    season = episode?.season,
                    episode = episode?.episode,
                )
                val requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
                val responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response)
                StreamLinkCacheRepository.save(
                    contentKey = contentKey,
                    url = directUrl,
                    streamName = stream.streamLabel,
                    addonName = providerName,
                    addonId = "plugin:${route.scraperId}",
                    requestHeaders = requestHeaders,
                    responseHeaders = responseHeaders,
                    filename = stream.behaviorHints.filename,
                    videoSize = stream.behaviorHints.videoSize,
                    bingeGroup = stream.behaviorHints.bingeGroup,
                    streamType = stream.streamType,
                )
                val launch = PlayerLaunch(
                    profileId = ProfileRepository.activeProfileId,
                    title = item.name,
                    sourceUrl = directUrl,
                    sourceHeaders = requestHeaders,
                    sourceResponseHeaders = responseHeaders,
                    externalSubtitles = stream.externalSubtitles,
                    streamType = stream.streamType,
                    poster = item.posterUrl ?: detail.posterUrl,
                    background = item.posterUrl ?: detail.posterUrl,
                    seasonNumber = episode?.season,
                    episodeNumber = episode?.episode,
                    episodeTitle = episode?.name,
                    streamTitle = stream.streamLabel,
                    streamSubtitle = stream.streamSubtitle,
                    providerName = providerName,
                    providerAddonId = "plugin:${route.scraperId}",
                    contentType = contentType,
                    videoId = contentId,
                    parentMetaId = contentId,
                    parentMetaType = contentType,
                    bingeGroup = stream.behaviorHints.bingeGroup,
                    stream = stream,
                )
                val launchId = PlayerLaunchStore.put(launch)
                navController.navigate(PlayerRoute(launchId = launchId, title = launch.title)) {
                    popUpTo<PluginBrowseRoute> { inclusive = false }
                }
            },
        )
    }
}
