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
}

/** Supplied by a platform source directory (androidFull / iosFull). */
internal val externalExtensionHost: ExternalExtensionHost
    get() = platformExternalExtensionHost
