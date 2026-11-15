package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.sports.ChannelMatch
import com.nuvio.app.features.livetv.sports.sportsAliveChannelKeys
import kotlin.test.Test
import kotlin.test.assertEquals

/** Sports stream validation: only channels whose probe answered are presented. */
class SportsStreamValidationTest {

    private fun match(key: String, url: String) = ChannelMatch(
        channel = LiveChannel(
            key = key,
            sourceId = "pl",
            sourceName = "Playlist",
            name = key,
            tvgId = null,
            tvgName = null,
            logo = null,
            groupId = "sports",
            group = "Sports",
            number = 0,
            url = url,
        ),
        score = 100,
        how = "test",
        program = null,
        quality = null,
        catchup = false,
    )

    @Test
    fun onlyChannelsWithAnAnswerAreAlive() {
        val matches = listOf(
            match("a", "https://cdn.example/a.m3u8"),
            match("b", "https://cdn.example/b.m3u8"),
            match("c", "https://cdn.example/c.m3u8"),
        )
        val outcomes = mapOf(
            "https://cdn.example/a.m3u8" to true,
            "https://cdn.example/b.m3u8" to false,
            "https://cdn.example/c.m3u8" to false,
        )
        assertEquals(setOf("a"), sportsAliveChannelKeys(matches, outcomes))
    }

    @Test
    fun duplicateUrlsShareTheVerdict() {
        val matches = listOf(
            match("fhd", "https://cdn.example/same.m3u8"),
            match("hd", "https://cdn.example/same.m3u8"),
            match("dead", "https://cdn.example/dead.m3u8"),
        )
        val outcomes = mapOf(
            "https://cdn.example/same.m3u8" to true,
            "https://cdn.example/dead.m3u8" to false,
        )
        assertEquals(setOf("fhd", "hd"), sportsAliveChannelKeys(matches, outcomes))
    }

    @Test
    fun unknownUrlsAreHidden() {
        val matches = listOf(match("unknown", "https://cdn.example/unknown.m3u8"))
        assertEquals(emptySet(), sportsAliveChannelKeys(matches, emptyMap()))
    }
}
