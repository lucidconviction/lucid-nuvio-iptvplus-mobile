package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginBadgePresentationTest {
    @Test
    fun pluginStreamCanReceiveBadgeRules() {
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/badges.json",
                    filters = listOf(
                        StreamBadgeFilter(
                            id = "uhd",
                            name = "4K",
                            pattern = "2160p",
                            imageURL = "https://example.com/4k.png",
                        ),
                    ),
                ),
            ),
        )
        val group = AddonStreamGroup(
            addonName = "Example CloudStream",
            addonId = "plugin:example",
            streams = listOf(
                StreamItem(
                    name = "Movie.2024.2160p.HDR",
                    title = "Movie",
                    url = "https://example.com/video.m3u8",
                    addonName = "Example CloudStream",
                    addonId = "plugin:example",
                ),
            ),
        )

        val presented = StreamBadgePresentation.apply(listOf(group), rules)
        val badges = presented.single().streams.single().badges
        assertEquals(1, badges.size)
        assertEquals("4K", badges.single().name)
    }

    @Test
    fun noBadgeRulesLeavesPluginGroupUntouched() {
        val group = AddonStreamGroup(
            addonName = "Example",
            addonId = "plugin:example",
            streams = listOf(
                StreamItem(
                    name = "Movie.2160p",
                    url = "https://example.com/video.m3u8",
                    addonName = "Example",
                    addonId = "plugin:example",
                ),
            ),
        )
        val presented = StreamBadgePresentation.apply(listOf(group), StreamBadgeRules())
        assertTrue(presented.single().streams.single().badges.isEmpty())
    }
}
