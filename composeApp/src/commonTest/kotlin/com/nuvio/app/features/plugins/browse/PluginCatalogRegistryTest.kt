package com.nuvio.app.features.plugins.browse

import com.nuvio.app.features.plugins.external.PluginBrowseItem
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class PluginCatalogRegistryTest {
    private val item = PluginBrowseItem(
        apiIndex = 0,
        apiName = "Provider",
        name = "Latest video",
        url = "https://example.com/video/1",
        posterUrl = "https://example.com/poster.jpg",
        type = "movie",
        year = 2026,
    )

    @BeforeTest
    fun setUp() {
        PluginCatalogRegistry.clear()
    }

    @Test
    fun registersStableIdsForSameProviderItem() {
        val first = item.toCatalogMetaPreview("scraper-1", "Provider")
        val second = item.toCatalogMetaPreview("scraper-1", "Provider")

        assertEquals(first.id, second.id)
        assertEquals("Latest video", first.name)
        assertEquals("movie", first.type)
        assertEquals("https://example.com/poster.jpg", first.poster)
        assertEquals("2026", first.releaseInfo)

        val ref = PluginCatalogRegistry.resolve(first.id)
        assertEquals("scraper-1", ref?.scraperId)
        assertEquals("Provider", ref?.scraperName)
        assertEquals(item.url, ref?.url)
        assertEquals(0, ref?.apiIndex)
    }

    @Test
    fun isolatesItemsAcrossProviders() {
        val first = item.toCatalogMetaPreview("scraper-1", "Provider")
        val second = item.toCatalogMetaPreview("scraper-2", "Other")

        assertNotEquals(first.id, second.id)
        assertEquals("scraper-2", PluginCatalogRegistry.resolve(second.id)?.scraperId)
    }

    @Test
    fun unknownIdDoesNotResolve() {
        assertNull(PluginCatalogRegistry.resolve("plugin-item:missing"))
    }
}
