package com.nuvio.app.features.plugins.external

import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.streams.normalizeStreamType

/**
 * One result returned by a CloudStream provider's own search. The browse screen
 * uses this instead of the normal TMDB-title matching path.
 */
internal data class PluginBrowseItem(
    val apiIndex: Int,
    val apiName: String,
    val name: String,
    val url: String,
    val posterUrl: String?,
    val type: String,
    val year: Int?,
)

internal data class PluginBrowseEpisode(
    val season: Int?,
    val episode: Int?,
    val name: String,
    val data: String,
)

internal data class PluginBrowseDetail(
    val apiIndex: Int,
    val name: String,
    val posterUrl: String?,
    val description: String?,
    val isSeries: Boolean,
    val movieData: String?,
    val episodes: List<PluginBrowseEpisode>,
)

/** One page of a provider's own home/latest feed. */
internal data class PluginLatestPage(
    val items: List<PluginBrowseItem>,
    val hasNext: Boolean,
)

/** Converts a provider result into the app's stream card model for the browse screen. */
internal fun PluginRuntimeResult.toBrowseStreamItem(
    providerName: String,
    providerId: String,
): StreamItem {
    val subtitleParts = listOfNotNull(
        quality?.takeIf { it.isNotBlank() },
        size?.takeIf { it.isNotBlank() },
        language?.takeIf { it.isNotBlank() },
    )
    val requestHeaders = headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val name = key.trim()
            val headerValue = value.trim()
            if (name.isBlank() || headerValue.isBlank() || name.equals("Range", ignoreCase = true)) {
                null
            } else {
                name to headerValue
            }
        }
        .toMap()

    return StreamItem(
        name = name ?: title,
        description = subtitleParts.joinToString(" • ").ifBlank { null },
        url = url,
        infoHash = infoHash,
        sourceName = providerName,
        addonName = providerName,
        addonId = providerId,
        streamType = normalizeStreamType(type),
        behaviorHints = if (requestHeaders.isEmpty()) {
            StreamBehaviorHints()
        } else {
            StreamBehaviorHints(
                notWebReady = true,
                proxyHeaders = StreamProxyHeaders(request = requestHeaders),
            )
        },
        externalSubtitles = subtitles?.map { subtitle ->
            StreamSubtitle(
                url = subtitle.url,
                language = subtitle.language,
                name = subtitle.name,
                headers = subtitle.headers,
            )
        } ?: emptyList(),
    )
}
