package com.nuvio.app.features.player

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.plugins.external.PluginBrowseDetail
import com.nuvio.app.features.plugins.external.PluginBrowseEpisode
import com.nuvio.app.features.plugins.external.PluginBrowseItem

/**
 * The episode queue for a CloudStream extension series playing in the main player.
 *
 * The regular player resolves the next episode from MetaDetails. Extension catalog items have
 * synthetic ids and no MetaDetails, so this session supplies the episode list and the series
 * URL the extension needs to resolve the next episode's links.
 */
internal data class PluginPlaybackSession(
    val seriesId: String,
    val scraperId: String,
    val scraperName: String,
    val providerName: String,
    val providerId: String,
    val item: PluginBrowseItem,
    val detail: PluginBrowseDetail,
) {
    val episodes: List<PluginBrowseEpisode>
        get() = detail.episodes
            .filter { it.season != null && it.episode != null }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

    fun videoId(season: Int, episode: Int): String = "$seriesId:$season:$episode"

    fun episode(season: Int, episode: Int): PluginBrowseEpisode? =
        episodes.firstOrNull { it.season == season && it.episode == episode }

    fun metaVideos(): List<MetaVideo> = episodes.map { episode ->
        val season = episode.season ?: 0
        val number = episode.episode ?: 0
        MetaVideo(
            id = videoId(season, number),
            title = episode.name.ifBlank { "Episode $number" },
            thumbnail = episode.posterUrl ?: detail.posterUrl ?: item.posterUrl,
            season = season,
            episode = number,
            overview = episode.description,
            runtime = episode.runtimeMinutes,
        )
    }
}

/**
 * Recently played extension series, keyed by the synthetic series id used as the player's
 * parent meta id. The queue is small because a session carries the full episode list.
 */
internal object PluginPlaybackQueue {
    private const val MAX_SESSIONS = 2
    private val sessions = LinkedHashMap<String, PluginPlaybackSession>()

    fun put(session: PluginPlaybackSession) {
        sessions.remove(session.seriesId)
        sessions[session.seriesId] = session
        while (sessions.size > MAX_SESSIONS) {
            val oldest = sessions.keys.firstOrNull() ?: break
            sessions.remove(oldest)
        }
    }

    fun get(seriesId: String): PluginPlaybackSession? = sessions[seriesId]

    fun remove(seriesId: String) {
        sessions.remove(seriesId)
    }

    fun clear() {
        sessions.clear()
    }
}
