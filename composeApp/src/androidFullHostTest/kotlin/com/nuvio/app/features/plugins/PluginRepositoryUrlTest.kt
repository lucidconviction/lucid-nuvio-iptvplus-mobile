package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginRepositoryUrlTest {
    @Test
    fun cloudStreamRepoLinksSurviveSync() {
        assertEquals(
            "https://example.test/repo.json",
            ensurePluginManifestUrl("https://example.test/repo.json"),
        )
        assertEquals(
            "https://example.test/builds/plugins.json?x=1",
            ensurePluginManifestUrl("https://example.test/builds/plugins.json?x=1"),
        )
        assertEquals(
            "https://example.test/repo/manifest.json",
            ensurePluginManifestUrl("https://example.test/repo"),
        )
    }

    @Test
    fun earlierDoubleManifestSuffixIsRemoved() {
        assertEquals(
            "https://example.test/repo.json",
            healPluginRepositoryUrl("https://example.test/repo.json/manifest.json"),
        )
        assertEquals(
            "https://example.test/repo.json?branch=main",
            healPluginRepositoryUrl("https://example.test/repo.json/manifest.json?branch=main"),
        )
        assertEquals(
            "https://example.test/manifest.json",
            healPluginRepositoryUrl("https://example.test/manifest.json"),
        )
    }

    @Test
    fun normalNuvioManifestIsUntouched() {
        assertEquals(
            "https://example.test/manifest.json",
            ensurePluginManifestUrl("https://example.test/manifest.json"),
        )
        assertEquals(
            "https://example.test/repo/manifest.json",
            ensurePluginManifestUrl("https://example.test/repo"),
        )
    }
}
