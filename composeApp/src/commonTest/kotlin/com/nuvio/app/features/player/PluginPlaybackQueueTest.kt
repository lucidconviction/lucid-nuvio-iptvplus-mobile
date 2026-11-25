package com.nuvio.app.features.player

import com.nuvio.app.features.plugins.external.PluginBrowseDetail
import com.nuvio.app.features.plugins.external.PluginBrowseEpisode
import com.nuvio.app.features.plugins.external.PluginBrowseItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginPlaybackQueueTest {
    private val detail = PluginBrowseDetail(
        apiIndex = 0,
        name = "Show",
        posterUrl = "poster",
        description = null,
        isSeries = true,
        movieData = null,
        episodes = listOf(
            PluginBrowseEpisode(season = 2, episode = 3, name = "S2E3", data = "d3"),
            PluginBrowseEpisode(season = 1, episode = 1, name = "S1E1", data = "d1"),
            PluginBrowseEpisode(season = 1, episode = 2, name = "S1E2", data = "d2"),
            PluginBrowseEpisode(season = null, episode = 4, name = "special", data = "d4"),
            PluginBrowseEpisode(season = 2, episode = null, name = "special2", data = "d5"),
        ),
    )

    private val session = PluginPlaybackSession(
        seriesId = "series",
        scraperId = "scraper",
        scraperName = "Scraper",
        providerName = "CloudStream · Scraper",
        providerId = "plugin:scraper",
        item = PluginBrowseItem(
            apiIndex = 0,
            apiName = "Scraper",
            name = "Show",
            url = "https://example.com/show",
            posterUrl = "poster",
            type = "tv",
            year = 2024,
        ),
        detail = detail,
    )

    @Test
    fun `episode videos are sorted and use the player's season episode ids`() {
        val videos = session.metaVideos()

        assertEquals(listOf("series:1:1", "series:1:2", "series:2:3"), videos.map { it.id })
        assertEquals(listOf(1, 1, 2), videos.map { it.season })
        assertEquals(listOf(1, 2, 3), videos.map { it.episode })
    }

    @Test
    fun `only complete season episode pairs are in the next up queue`() {
        assertEquals("S1E2", session.episode(1, 2)?.name)
        assertEquals("d3", session.episode(2, 3)?.data)
        assertNull(session.episode(0, 4))
        assertNull(session.episode(2, 0))
    }
}
