package com.nuvio.app.features.livetv

import com.nuvio.app.features.livetv.data.LiveTvCodec
import com.nuvio.app.features.livetv.data.StalkerClient
import com.nuvio.app.features.livetv.model.PlaylistSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StalkerPortalTest {

    @Test
    fun `mac addresses are normalized from common formats`() {
        assertEquals("00:1A:79:AA:BB:CC", PlaylistSource.normalizeMac("00:1a:79:aa:bb:cc"))
        assertEquals("00:1A:79:AA:BB:CC", PlaylistSource.normalizeMac("00-1A-79-AA-BB-CC"))
        assertEquals("00:1A:79:AA:BB:CC", PlaylistSource.normalizeMac("001A79AABBCC"))
        assertTrue(PlaylistSource.isValidMac("00:1a:79:aa:bb:cc"))
        assertFalse(PlaylistSource.isValidMac("00:1a:79"))
    }

    @Test
    fun `portal addresses are cleaned to the server base`() {
        assertEquals(
            "http://example.test:8080/c",
            PlaylistSource.cleanStalkerPortal("example.test:8080/c/"),
        )
        assertEquals(
            "http://example.test:8080/c",
            PlaylistSource.cleanStalkerPortal("http://example.test:8080/c/server/load.php?type=stb"),
        )
        assertEquals(
            "https://example.test/stalker_portal/c",
            PlaylistSource.cleanStalkerPortal("https://example.test/stalker_portal/c/"),
        )
        assertEquals(
            "http://example.test:8080/c",
            PlaylistSource.cleanStalkerPortal("http://example.test:8080"),
        )
    }

    @Test
    fun `stalker source keeps its identity and uses an endpoint not a playlist url`() {
        val source = PlaylistSource(
            id = "portal",
            name = "Portal",
            url = "",
            stalkerPortal = "http://example.test/c/",
            stalkerMac = "00-1a-79-aa-bb-cc",
        )

        assertTrue(source.isStalker)
        assertFalse(source.isXtream)
        assertTrue(source.liveEnabled)
        assertEquals("", source.resolvedUrl())
        assertEquals("http://example.test/c/server/load.php", source.stalkerEndpoint())
        assertEquals("mac=00:1A:79:AA:BB:CC; stb_lang=en; timezone=UTC", source.stalkerCookie())
    }

    @Test
    fun `portal channels become an m3u with stream headers and guide`() = runTest {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            val body = when {
                url.contains("action=handshake") -> """{"js":{"token":"token-123"}}"""
                url.contains("action=get_profile") -> """{"js":{"timezone":"UTC"}}"""
                url.contains("action=get_genres") ->
                    """{"js":{"data":[{"id":"1","title":"News"}]}}"""
                url.contains("action=get_all_channels") ->
                    """{"js":{"data":[{"id":"ch1","name":"News One","cmd":"ffmpeg http://cdn.test/live/1.m3u8","tv_genre_id":"1","logo":"http://cdn.test/logo.png","xmltv_id":"news1","number":"5"}]}}"""
                url.contains("action=get_epg_info") ->
                    """{"js":{"data":{"ch1":[{"start_timestamp":"1700000000","stop_timestamp":"1700003600","name":"Morning News","descr":"Today"}]}}}"""
                else -> """{"js":{"data":[]}}"""
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = StalkerClient(HttpClient(engine))
        val source = PlaylistSource(
            id = "portal",
            name = "Portal",
            url = "",
            stalkerPortal = "http://example.test/c/",
            stalkerMac = "00:1A:79:AA:BB:CC",
        )

        val playlist = client.livePlaylistM3u(source)

        assertEquals(1, playlist.channels.size)
        assertTrue(playlist.m3u.contains("tvg-id=\"news1\""))
        assertTrue(playlist.m3u.contains("group-title=\"News\""))
        assertTrue(playlist.m3u.contains("http://cdn.test/live/1.m3u8"))
        assertTrue(playlist.m3u.contains("\"Cookie\":\"mac=00:1A:79:AA:BB:CC"))

        val guide = client.epgXml(source, playlist).orEmpty()
        assertTrue(guide.contains("<channel id=\"news1\">"))
        assertTrue(guide.contains("channel=\"news1\""))
        assertTrue(guide.contains("Morning News"))
    }

    @Test
    fun `stalker fields survive the playlist codec`() {
        val source = PlaylistSource(
            id = "portal",
            name = "Portal",
            url = "",
            stalkerPortal = "http://example.test/c",
            stalkerMac = "00:1A:79:AA:BB:CC",
            stalkerTimezone = "Europe/London",
        )

        val decoded = LiveTvCodec.decodePlaylists(LiveTvCodec.encodePlaylists(listOf(source)))

        assertEquals(listOf(source), decoded)
    }
}
