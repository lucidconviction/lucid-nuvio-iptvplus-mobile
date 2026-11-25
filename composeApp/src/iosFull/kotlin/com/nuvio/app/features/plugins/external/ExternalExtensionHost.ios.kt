package com.nuvio.app.features.plugins.external

import com.nuvio.app.features.plugins.PluginRuntimeResult

/**
 * iOS has no Dalvik/DEX runtime. CloudStream repositories stay visible only as
 * unsupported metadata; the UI hides the extension option when [isSupported] is
 * false, so this path is a safety net.
 */
internal object UnsupportedExternalExtensionHost : ExternalExtensionHost {
    override val isSupported: Boolean = false

    override fun hasExtension(scraperId: String, profileId: Int): Boolean = false

    override suspend fun install(
        scraperId: String,
        profileId: Int,
        url: String,
        fileHash: String?,
        expectedSize: Long?,
    ): Boolean = false

    override fun uninstall(scraperId: String, profileId: Int) = Unit

    override suspend fun execute(
        scraperId: String,
        profileId: Int,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        searchPaused: Boolean,
        contentTitle: String?,
        useFirstResult: Boolean,
    ): List<PluginRuntimeResult> = emptyList()

    override suspend fun hasSettings(scraperId: String, profileId: Int): Boolean = false

    override fun openSettings(scraperId: String, profileId: Int) = Unit

    override suspend fun search(
        scraperId: String,
        profileId: Int,
        query: String,
    ): List<PluginBrowseItem> = emptyList()

    override suspend fun categories(
        scraperId: String,
        profileId: Int,
    ): List<PluginBrowseCategory> = emptyList()

    override suspend fun latest(
        scraperId: String,
        profileId: Int,
        page: Int,
        category: PluginBrowseCategory?,
    ): PluginLatestPage? = null

    override suspend fun loadDetail(
        scraperId: String,
        profileId: Int,
        apiIndex: Int,
        url: String,
    ): PluginBrowseDetail? = null

    override suspend fun loadStreams(
        scraperId: String,
        profileId: Int,
        apiIndex: Int,
        data: String,
        season: Int?,
        episode: Int?,
        timeoutMs: Long?,
    ): List<PluginRuntimeResult> = emptyList()

    override fun isExtensionLoaded(scraperId: String, profileId: Int): Boolean = false

    override fun releaseExtension(scraperId: String, profileId: Int) = Unit
}

internal val platformExternalExtensionHost: ExternalExtensionHost = UnsupportedExternalExtensionHost
