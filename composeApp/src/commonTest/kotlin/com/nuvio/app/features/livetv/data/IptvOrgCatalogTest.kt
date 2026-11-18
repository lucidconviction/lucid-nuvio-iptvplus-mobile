package com.nuvio.app.features.livetv.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IptvOrgCatalogTest {

    @Test
    fun countriesBecomeM3uUrlsAndAllStaysFirst() {
        val lists = IptvOrgCatalog.parseCountries(
            """[
              {"name":"United States","code":"US","flag":"🇺🇸"},
              {"name":"Albania","code":"AL","flag":"🇦🇱"}
            ]"""
        )

        assertEquals("ALL", lists.first().code)
        assertEquals("https://iptv-org.github.io/iptv/index.m3u", lists.first().url)
        assertEquals("Albania", lists[1].name)
        assertEquals("🇦🇱", lists[1].flag)
        assertEquals("https://iptv-org.github.io/iptv/countries/al.m3u", lists[1].url)
        assertEquals("United States", lists[2].name)
        assertEquals("https://iptv-org.github.io/iptv/countries/us.m3u", lists[2].url)
    }

    @Test
    fun categoriesKeepTheirIdAndDescription() {
        val lists = IptvOrgCatalog.parseCategories(
            """[{"id":"news","name":"News","description":"Programming with news"}]"""
        )

        assertEquals(1, lists.size)
        assertEquals("news", lists[0].code)
        assertEquals("Programming with news", lists[0].subtitle)
        assertEquals("https://iptv-org.github.io/iptv/categories/news.m3u", lists[0].url)
    }

    @Test
    fun regionsUseTheLowercaseCode() {
        val lists = IptvOrgCatalog.parseRegions("""[{"code":"AFR","name":"Africa"}]""")

        assertEquals("AFR", lists[0].code)
        assertEquals("https://iptv-org.github.io/iptv/regions/afr.m3u", lists[0].url)
    }

    @Test
    fun blankEntriesAreSkipped() {
        assertEquals(
            2,
            IptvOrgCatalog.parseCountries("""[{"name":"","code":"US"},{"name":"Albania","code":"AL"}]""").size,
        )
        assertTrue(IptvOrgCatalog.parseCategories("[]").isEmpty())
        assertTrue(IptvOrgCatalog.parseRegions("[]").isEmpty())
    }

    @Test
    fun duplicateMatchingIgnoresCaseAndTrailingSlashes() {
        assertEquals(
            "https://iptv-org.github.io/iptv/countries/us.m3u",
            normalizeIptvOrgUrl(" HTTPS://iptv-org.github.io/iptv/countries/US.m3u/ "),
        )
    }
}
