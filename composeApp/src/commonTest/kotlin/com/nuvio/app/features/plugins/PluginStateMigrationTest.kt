package com.nuvio.app.features.plugins

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginStateMigrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun oldStoredScraperDefaultsToJavaScript() {
        val stored = json.decodeFromString<StoredPluginScraper>(
            """
            {
              "id": "https://example.com/manifest.json:one",
              "repositoryUrl": "https://example.com/manifest.json",
              "name": "One",
              "description": "",
              "version": "1.0.0",
              "filename": "one.js",
              "supportedTypes": ["movie"],
              "enabled": true,
              "manifestEnabled": true
            }
            """.trimIndent(),
        )

        assertEquals(PluginRepositoryType.NUVIO_JS, stored.type)
        val restored = stored.restorePluginScraper { "console.log('ok')" }
        assertNotNull(restored)
        assertEquals(PluginRepositoryType.NUVIO_JS, restored!!.scraper.type)
        assertEquals("console.log('ok')", restored.scraper.code)
    }

    @Test
    fun dexScraperRestoresWithoutJavaScriptCode() {
        val stored = StoredPluginScraper(
            id = "https://example.com/repo:provider",
            repositoryUrl = "https://example.com/repo",
            name = "Provider",
            description = "",
            version = "3",
            filename = "https://example.com/provider.cs3",
            supportedTypes = listOf("movie", "tv"),
            enabled = true,
            manifestEnabled = true,
            type = PluginRepositoryType.EXTERNAL_DEX,
            extensionHash = "sha256-abc",
        )

        val restored = stored.restorePluginScraper { error("must not load JS for a DEX scraper") }
        assertNotNull(restored)
        assertEquals(PluginRepositoryType.EXTERNAL_DEX, restored.scraper.type)
        assertEquals("", restored.scraper.code)
        assertEquals("sha256-abc", restored.scraper.extensionHash)
        assertTrue(!restored.requiresMigration)
    }
}
