package com.nuvio.app.features.player

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.livetv.ui.quickchannels.QuickStreamChecker
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.plugins.external.PluginBrowseItem
import com.nuvio.app.features.plugins.external.toBrowseStreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val PluginCatalogStreamTimeoutMs = 60_000L

/**
 * Plays a CloudStream catalog item without leaving the player: the current channel keeps
 * playing while the extension resolves its links, then the player switches in place. Used by
 * the extension folders in the Live TV player panel.
 */
internal fun PlayerScreenRuntime.playPluginCatalogItem(
    scraper: PluginScraper,
    item: PluginBrowseItem,
    onFinished: (Boolean) -> Unit = {},
) {
    addonCatalogPlayJob?.cancel()
    addonCatalogPlayJob = scope.launch {
        var started = false
        val startingVideoId = activeVideoId
        try {
            val results: List<PluginRuntimeResult> = withTimeoutOrNull(PluginCatalogStreamTimeoutMs) {
                runCatching {
                    PluginRepository.browseStreams(
                        scraperId = scraper.id,
                        apiIndex = item.apiIndex,
                        url = item.url,
                        season = null,
                        episode = null,
                        timeoutMs = 45_000L,
                    )
                }.getOrDefault(emptyList())
            } ?: emptyList()
            if (activeVideoId != startingVideoId) return@launch

            val providerName = "CloudStream · ${scraper.name}"
            val providerId = "plugin:${scraper.id}"
            val stream = results
                .asSequence()
                .filter { it.url.isNotBlank() }
                .sortedWith(
                    compareByDescending<PluginRuntimeResult> { pluginQualityRank(it.quality) }
                        .thenByDescending { it.seeders ?: 0 },
                )
                .mapNotNull { result ->
                    result.toBrowseStreamItem(providerName = providerName, providerId = providerId)
                        .takeIf { it.playableDirectUrl != null }
                }
                .firstOrNull { candidate ->
                    val url = candidate.playableDirectUrl ?: return@firstOrNull false
                    runCatching {
                        QuickStreamChecker.isHlsManifestUsable(
                            url = url,
                            headers = candidate.behaviorHints.proxyHeaders?.request.orEmpty(),
                        )
                    }.getOrDefault(true)
                }
                ?: results.firstNotNullOfOrNull { result ->
                    result.toBrowseStreamItem(providerName = providerName, providerId = providerId)
                        .takeIf { it.playableDirectUrl != null }
                }

            if (stream == null) {
                NuvioToastController.show("No streams found for ${item.name}")
                return@launch
            }

            val contentId = "pluginlive:${scraper.id}:${item.apiIndex}:${item.url.hashCode()}"
            flushWatchProgress()
            playbackSnapshot = PlayerPlaybackSnapshot()
            args = args.copy(
                title = item.name,
                logo = null,
                poster = item.posterUrl,
                background = item.posterUrl,
                contentType = "movie",
                videoId = contentId,
                parentMetaId = contentId,
                parentMetaType = "movie",
                providerName = providerName,
                providerAddonId = providerId,
                fromLiveTvSection = true,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                episodeThumbnail = null,
                initialPositionMs = 0L,
                initialProgressFraction = null,
                torrentInfoHash = null,
                torrentFileIdx = null,
                torrentFilename = null,
                torrentTrackers = emptyList(),
                sourceFilename = null,
                stream = null,
                streamTitle = item.name,
                streamSubtitle = null,
                pauseDescription = null,
                initialBingeGroup = null,
            )
            activeFromLiveTvSection = true
            activeVideoId = contentId
            activeSeasonNumber = null
            activeEpisodeNumber = null
            activeEpisodeTitle = null
            activeEpisodeThumbnail = null
            activeStreamTitle = item.name
            activeStreamSubtitle = null
            activePauseDescription = null
            activeProviderName = providerName
            activeProviderAddonId = providerId
            activeInitialPositionMs = 0L
            activeInitialProgressFraction = null
            externalSubtitles = emptyList()
            errorMessage = null
            streamTransition = PlayerStreamTransition(
                artwork = item.posterUrl,
                logo = null,
                title = item.name,
            )
            switchToSource(stream)
            started = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NuvioToastController.show(e.message ?: "Could not play ${item.name}")
        } finally {
            onFinished(started)
        }
    }
}

private fun pluginQualityRank(quality: String?): Int {
    val value = quality.orEmpty().lowercase()
    return when {
        "2160" in value || "4k" in value -> 5
        "1440" in value -> 4
        "1080" in value || "fhd" in value -> 3
        "720" in value || "hd" in value -> 2
        "480" in value || "sd" in value -> 1
        else -> 0
    }
}
