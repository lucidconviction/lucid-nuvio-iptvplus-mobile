package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.CatchupInfo
import com.nuvio.app.features.livetv.model.DrmInfo
import com.nuvio.app.features.livetv.model.LiveChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SavedChannelsTest {
    private val full = LiveChannel(
        key = "pl-1|news|News HD",
        sourceId = "pl-1",
        sourceName = "Main playlist",
        name = "News HD",
        tvgId = "news.id",
        tvgName = "News",
        logo = "https://example.test/news.png",
        thumbnail = "https://example.test/news-wide.jpg",
        groupId = "pl-1::News",
        group = "News",
        number = 12,
        url = "https://example.test/live/news.m3u8",
        headers = mapOf("User-Agent" to "Test Agent", "X-Odd" to "a\tb\nc\\d"),
        catchup = CatchupInfo(type = "append", source = "?utc=", days = 7, correctionHours = 1.5),
        drm = DrmInfo(
            scheme = "widevine",
            license = "https://example.test/license",
            licenseHeaders = mapOf("Authorization" to "Bearer token"),
            manifestType = "mpd",
        ),
    )

    private val minimal = LiveChannel(
        key = "pl-2|sports|Sports",
        sourceId = "pl-2",
        sourceName = "Backup",
        name = "Sports",
        tvgId = null,
        tvgName = null,
        logo = null,
        groupId = "Sports",
        group = "Sports",
        number = 3,
        url = "https://example.test/sports.ts",
    )

    private fun roundTrip(channels: List<LiveChannel>, stamp: String = "stamp-1"): SavedChannels.Saved? = runBlocking {
        val chunks = ArrayList<String>()
        SavedChannels.write(stamp, channels) { chunks += it }
        SavedChannels.read(chunks.asSequence().flatMap { it.lineSequence() })
    }

    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        val saved = roundTrip(listOf(full, minimal))
        assertEquals("stamp-1", saved?.stamp)
        assertEquals(listOf(full, minimal), saved?.channels)
    }

    @Test
    fun anotherVersionIsIgnored() {
        val saved = runBlocking {
            val chunks = ArrayList<String>()
            SavedChannels.write("stamp", listOf(minimal)) { chunks += it }
            chunks[0] = "V\t999\n"
            SavedChannels.read(chunks.asSequence().flatMap { it.lineSequence() })
        }
        assertNull(saved)
    }

    @Test
    fun aBrokenChannelLineIsIgnored() {
        val saved = runBlocking {
            val chunks = ArrayList<String>()
            SavedChannels.write("stamp", listOf(minimal)) { chunks += it }
            chunks += "C\ttoo\tfew\n"
            SavedChannels.read(chunks.asSequence().flatMap { it.lineSequence() })
        }
        assertNull(saved)
    }
}
