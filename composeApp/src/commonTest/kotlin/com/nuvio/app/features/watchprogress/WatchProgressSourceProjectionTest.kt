package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.tracking.WatchProgressSource
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchProgressSourceProjectionTest {
    @Test
    fun `remote source excludes every Nuvio progress entry`() {
        val nuvioEntries = listOf(
            entry(parentMetaId = "shared", updatedAt = 200L),
            entry(parentMetaId = "nuvio-only", updatedAt = 300L),
        )
        val providerEntries = listOf(
            entry(parentMetaId = "shared", updatedAt = 100L),
        )

        listOf(WatchProgressSource.TRAKT, WatchProgressSource.SIMKL).forEach { source ->
            val projected = projectWatchProgressSourceEntries(
                source = source,
                nuvioEntries = nuvioEntries,
                providerEntries = providerEntries,
            )

            assertEquals(providerEntries, projected)
        }
    }

    @Test
    fun `remote source keeps local YouTube progress alongside provider entries`() {
        val youtube = entry(
            parentMetaId = "youtube:abc123",
            updatedAt = 400L,
            contentType = "youtube",
            videoId = "abc123",
            seasonNumber = null,
            episodeNumber = null,
        )
        val nuvioEntries = listOf(
            entry(parentMetaId = "shared", updatedAt = 200L),
            entry(parentMetaId = "nuvio-only", updatedAt = 300L),
            youtube,
        )
        val providerEntries = listOf(
            entry(parentMetaId = "shared", updatedAt = 100L),
        )

        val projected = projectWatchProgressSourceEntries(
            source = WatchProgressSource.TRAKT,
            nuvioEntries = nuvioEntries,
            providerEntries = providerEntries,
        )

        assertEquals(providerEntries + youtube, projected)
    }

    @Test
    fun `Nuvio source excludes every provider progress entry`() {
        val nuvioEntries = listOf(entry(parentMetaId = "nuvio"))
        val providerEntries = listOf(entry(parentMetaId = "provider"))

        val projected = projectWatchProgressSourceEntries(
            source = WatchProgressSource.NUVIO_SYNC,
            nuvioEntries = nuvioEntries,
            providerEntries = providerEntries,
        )

        assertEquals(nuvioEntries, projected)
    }

    private fun entry(
        parentMetaId: String,
        updatedAt: Long = 1L,
        contentType: String = "series",
        videoId: String = "$parentMetaId:1:1",
        seasonNumber: Int? = 1,
        episodeNumber: Int? = 1,
    ): WatchProgressEntry = WatchProgressEntry(
        contentType = contentType,
        parentMetaId = parentMetaId,
        parentMetaType = contentType,
        videoId = videoId,
        title = parentMetaId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        lastPositionMs = 10L,
        durationMs = 100L,
        lastUpdatedEpochMs = updatedAt,
    )
}
