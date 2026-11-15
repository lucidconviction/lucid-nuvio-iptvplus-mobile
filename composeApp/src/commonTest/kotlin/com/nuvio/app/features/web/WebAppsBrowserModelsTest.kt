package com.nuvio.app.features.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure Web-Apps helpers: SSRF guard and source presentation. */
class WebAppsBrowserModelsTest {

    @Test
    fun privateAndLoopbackHostsAreBlocked() {
        listOf(
            "localhost", "127.0.0.1", "10.0.0.5", "192.168.1.20",
            "172.16.0.1", "172.31.255.255", "169.254.1.1", "printer.local", "::1",
        ).forEach { assertTrue(isPrivateWebHost(it), "expected private: $it") }
    }

    @Test
    fun publicHostsAreAllowed() {
        listOf(
            "example.com", "cdn.example.com", "172.32.0.1", "8.8.8.8", "sub.domain.co.uk",
        ).forEach { assertFalse(isPrivateWebHost(it), "expected public: $it") }
    }

    @Test
    fun qualityLabelsPreferHeight() {
        fun source(width: Int, height: Int) = WebMediaSource(
            url = "https://cdn.example/master.m3u8",
            pageUrl = "https://example.com/watch",
            title = "Example",
            width = width,
            height = height,
        )
        assertEquals("1080p", source(1920, 1080).qualityLabel())
        assertEquals("720p", source(1280, 720).qualityLabel())
        assertEquals("4K", source(3840, 2160).qualityLabel())
        assertEquals(null, source(0, 0).qualityLabel())
    }

    @Test
    fun adsRankBelowTheRealVideo() {
        val real = WebMediaSource(
            url = "https://cdn.example/master.m3u8",
            pageUrl = "https://example.com/watch",
            title = "Movie",
            durationMs = 7_200_000L,
            width = 1920,
            height = 1080,
            isHls = true,
            isMaster = true,
            confidence = 90,
        )
        val ad = WebMediaSource(
            url = "https://ads.example/preroll.mp4",
            pageUrl = "https://example.com/watch",
            title = "Ad",
            durationMs = 15_000L,
            width = 640,
            height = 360,
            likelyAd = true,
            confidence = 10,
        )
        assertTrue(webMediaScore(real) > webMediaScore(ad))
    }
}
