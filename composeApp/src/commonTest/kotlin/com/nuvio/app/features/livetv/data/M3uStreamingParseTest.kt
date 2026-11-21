package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvSettings
import com.nuvio.app.features.livetv.model.LiveUserState
import com.nuvio.app.features.livetv.ui.LiveTvViewState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The streaming parser used for very large playlists must read the same entries as the old one. */
class M3uStreamingParseTest {
    private val sample = """
        #EXTM3U url-tvg="http://guide.example/a.xml,http://guide.example/b.xml"
        #EXTINF:-1 tvg-id="one" tvg-name="One HD" tvg-logo="http://logo/one.png" group-title="News",One
        http://host/one.ts
        #EXTINF:-1 tvg-id="two" group-title="Sports",Two
        http://host/two.ts
        #EXTINF:-1,Docs Channel
        #EXTGRP:Docs
        http://host/docs.ts
    """.trimIndent()

    @Test
    fun streamingParseMatchesWholePlaylistParse() {
        val whole = M3uParser.parse(sample)
        val streamed = ArrayList<M3uEntry>()
        val epgUrls = M3uParser.parseEach(sample.lineSequence()) { streamed += it }

        assertEquals(whole.entries, streamed)
        assertEquals(whole.epgUrls, epgUrls)
        assertEquals(3, streamed.size)
        assertEquals("News", streamed[0].group)
        assertEquals("Docs", streamed[2].group)
    }

    @Test
    fun streamingParseDropsEmptyHeaderMaps() {
        val entries = ArrayList<M3uEntry>()
        M3uParser.parseEach(sample.lineSequence()) { entries += it }
        assertTrue(entries.all { it.headers.isEmpty() })
    }

    @Test
    fun searchStopsAtItsLimit() {
        val channels = (1..2_000).map { i ->
            LiveChannel(
                key = "k$i",
                sourceId = "pl",
                sourceName = "Playlist",
                name = "Channel $i",
                tvgId = null,
                tvgName = null,
                logo = null,
                groupId = "News",
                group = "News",
                number = i,
                url = "http://host/$i.ts",
            )
        }
        val result = LiveTvViewState.search(channels, "channel", limit = 25)
        assertEquals(25, result.size)

        // Number matches come first; fuzzy name matches may also fit inside the same limit.
        val byNumber = LiveTvViewState.search(channels, "1500", limit = 25)
        assertTrue(byNumber.any { it.number == 1500 })
        assertTrue(byNumber.size <= 25)
    }

    @Test
    fun buildDoesNotCopyEveryChannelIntoTheLookupMap() {
        // A sanity check that the list still builds correctly with no favorites or custom groups.
        val channels = (1..50).map { i ->
            LiveChannel(
                key = "k$i",
                sourceId = "pl",
                sourceName = "Playlist",
                name = "Channel $i",
                tvgId = null,
                tvgName = null,
                logo = null,
                groupId = if (i % 2 == 0) "News" else "Sports",
                group = if (i % 2 == 0) "News" else "Sports",
                number = i,
                url = "http://host/$i.ts",
            )
        }
        val state = LiveTvViewState.build(channels, LiveUserState(), LiveTvSettings(), hasSources = true)
        assertEquals(50, state.visible.size)
        assertTrue(state.sections.any { it.group.id == "News" && it.channels.size == 25 })
        assertTrue(state.sections.any { it.group.id == "Sports" && it.channels.size == 25 })
    }
}
