package com.nuvio.app.features.catalog

import kotlin.test.Test
import kotlin.test.assertTrue

class CatalogUrlTest {

    @Test
    fun requiredCatalogExtrasAreIncludedAndReservedNamesCannotBeOverridden() {
        val url = buildCatalogUrl(
            manifestUrl = "https://example.com/manifest.json",
            type = "series",
            catalogId = "last",
            genre = "Drama",
            search = null,
            skip = 100,
            extras = mapOf(
                "lastVideoIds" to "tt1,tt2",
                "genre" to "Ignored",
                "skip" to "999",
                "empty" to "",
            ),
        )
        assertTrue(url.contains("genre=Drama"))
        assertTrue(url.contains("skip=100"))
        assertTrue(url.contains("lastVideoIds=tt1%2Ctt2"))
        assertTrue(!url.contains("Ignored"))
        assertTrue(!url.contains("skip=999"))
        assertTrue(!url.contains("empty="))
    }
}
