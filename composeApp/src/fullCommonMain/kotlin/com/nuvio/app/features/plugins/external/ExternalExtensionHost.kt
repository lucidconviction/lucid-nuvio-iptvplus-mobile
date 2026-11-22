package com.nuvio.app.features.plugins.external

import com.nuvio.app.features.plugins.PluginRuntimeResult

/**
 * Platform bridge for CloudStream `.cs3` extensions.
 *
 * The common plugin state, repository parser and stream pipeline only depend on
 * this interface. Android-full provides a DexClassLoader implementation; iOS-full
 * and the Play Store build provide an unsupported/no-op implementation.
 */
internal interface ExternalExtensionHost {
    /** True only on builds that can load Android DEX code (GitHub-distributed full APK). */
    val isSupported: Boolean

    fun hasExtension(scraperId: String, profileId: Int): Boolean

    /**
     * Downloads and validates a `.cs3` package. Must be atomic: a failed refresh
     * leaves the previously installed package usable.
     */
    suspend fun install(
        scraperId: String,
        profileId: Int,
        url: String,
        fileHash: String?,
        expectedSize: Long? = null,
    ): Boolean

    fun uninstall(scraperId: String, profileId: Int)

    suspend fun execute(
        scraperId: String,
        profileId: Int,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        searchPaused: Boolean,
        contentTitle: String? = null,
        useFirstResult: Boolean = false,
    ): List<PluginRuntimeResult>

    suspend fun hasSettings(scraperId: String, profileId: Int): Boolean

    fun openSettings(scraperId: String, profileId: Int)

    /** Provider search for the browse screen (no TMDB title matching). */
    suspend fun search(
        scraperId: String,
        profileId: Int,
        query: String,
    ): List<PluginBrowseItem>

    /** Provider home/latest feed, one page at a time. */
    suspend fun latest(
        scraperId: String,
        profileId: Int,
        page: Int,
    ): PluginLatestPage?

    /** Load a selected search result's metadata and episode list. */
    suspend fun loadDetail(
        scraperId: String,
        profileId: Int,
        apiIndex: Int,
        url: String,
    ): PluginBrowseDetail?

    /** Resolve streams for a movie data string or a selected episode. */
    suspend fun loadStreams(
        scraperId: String,
        profileId: Int,
        apiIndex: Int,
        data: String,
        season: Int?,
        episode: Int?,
    ): List<PluginRuntimeResult>
}

/** Supplied by a platform source directory (androidFull / iosFull). */
internal val externalExtensionHost: ExternalExtensionHost
    get() = platformExternalExtensionHost
