package com.nuvio.app.features.plugins.browse

import com.nuvio.app.features.plugins.external.PluginBrowseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBrowseStateStoreTest {
    @Test
    fun defaultsToLatest() {
        val state = PluginBrowseStateStore.get("profile:test")

        assertEquals(PluginBrowseMode.Latest, state.mode)
        assertTrue(state.latestItems.isEmpty())
        assertTrue(state.results.isEmpty())
    }

    @Test
    fun restoresLatestFeedPerKey() {
        val item = PluginBrowseItem(
            apiIndex = 0,
            apiName = "Provider",
            name = "Latest video",
            url = "https://example.com/video",
            posterUrl = null,
            type = "movie",
            year = null,
        )
        PluginBrowseStateStore.put(
            key = "1:scraper",
            state = PluginBrowseStateStore.State(
                mode = PluginBrowseMode.Latest,
                latestItems = listOf(item),
                latestPage = 2,
                latestHasNext = true,
                latestAttempted = true,
            ),
        )

        val restored = PluginBrowseStateStore.get("1:scraper")
        assertEquals(listOf(item), restored.latestItems)
        assertEquals(2, restored.latestPage)
        assertTrue(restored.latestHasNext)
        assertTrue(restored.latestAttempted)

        assertTrue(PluginBrowseStateStore.get("2:scraper").latestItems.isEmpty())
        PluginBrowseStateStore.clear("1:scraper")
        assertTrue(PluginBrowseStateStore.get("1:scraper").latestItems.isEmpty())
    }
}
