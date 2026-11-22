package com.nuvio.app.features.plugins.external

import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginSubtitleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBrowseModelsTest {
    @Test
    fun mapsProviderResultToStreamCard() {
        val item = PluginRuntimeResult(
            title = "Fallback title",
            name = null,
            url = "https://cdn.example/movie.mp4",
            quality = "1080p",
            size = "1.2 GB",
            language = "English",
            subtitles = listOf(
                PluginSubtitleResult(
                    url = "https://cdn.example/subs.vtt",
                    language = "en",
                    name = "English",
                ),
            ),
        ).toBrowseStreamItem(
            providerName = "CloudStream · Example",
            providerId = "plugin:example",
        )

        assertEquals("Fallback title", item.name)
        assertEquals("https://cdn.example/movie.mp4", item.url)
        assertEquals("CloudStream · Example", item.addonName)
        assertEquals("plugin:example", item.addonId)
        assertEquals("1080p • 1.2 GB • English", item.description)
        assertEquals("https://cdn.example/subs.vtt", item.externalSubtitles.single().url)
        assertEquals("English", item.externalSubtitles.single().name)
    }

    @Test
    fun keepsRequestHeadersExceptRangeAndFlagsWebUnready() {
        val item = PluginRuntimeResult(
            title = "Header test",
            url = "https://cdn.example/stream.m3u8",
            headers = mapOf(
                "Referer" to "https://provider.example/",
                "User-Agent" to "Mozilla/5.0",
                "Range" to "bytes=0-",
                " " to "ignored",
            ),
        ).toBrowseStreamItem(
            providerName = "CloudStream · Example",
            providerId = "plugin:example",
        )

        val hints = item.behaviorHints
        assertTrue(hints.notWebReady)
        assertEquals(
            mapOf(
                "Referer" to "https://provider.example/",
                "User-Agent" to "Mozilla/5.0",
            ),
            hints.proxyHeaders?.request,
        )
    }

    @Test
    fun omitsBehaviorHintsWhenNoHeaders() {
        val item = PluginRuntimeResult(
            title = "No headers",
            url = "https://cdn.example/plain.mp4",
        ).toBrowseStreamItem(
            providerName = "CloudStream · Example",
            providerId = "plugin:example",
        )

        assertNull(item.behaviorHints.proxyHeaders)
        assertTrue(item.externalSubtitles.isEmpty())
    }
}
