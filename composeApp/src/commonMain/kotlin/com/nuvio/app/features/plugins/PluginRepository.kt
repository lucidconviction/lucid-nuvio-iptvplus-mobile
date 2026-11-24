package com.nuvio.app.features.plugins

import com.nuvio.app.features.plugins.external.PluginBrowseEpisode
import com.nuvio.app.features.plugins.external.PluginBrowseItem
import com.nuvio.app.features.plugins.external.PluginBrowseCategory
import com.nuvio.app.features.plugins.external.PluginLatestPage
import kotlinx.coroutines.flow.StateFlow

expect object PluginRepository {
    val uiState: StateFlow<PluginsUiState>

    fun initialize(refreshDue: Boolean = false)

    fun onProfileChanged(profileId: Int)

    fun clearLocalState()

    suspend fun pullFromServer(profileId: Int)

    suspend fun addRepository(rawUrl: String): AddPluginRepositoryResult

    fun removeRepository(manifestUrl: String)

    fun refreshAll()

    fun refreshRepository(manifestUrl: String, pushAfterRefresh: Boolean = false)

    fun toggleScraper(scraperId: String, enabled: Boolean)

    fun setScraperShowOnHome(scraperId: String, showOnHome: Boolean)

    fun setScraperShowInLiveTv(scraperId: String, showInLiveTv: Boolean)

    fun setScraperShowInSports(scraperId: String, showInSports: Boolean)

    fun setScraperShowInLucidTv(scraperId: String, showInLucidTv: Boolean)

    fun setPluginsEnabled(enabled: Boolean)

    fun setGroupStreamsByRepository(enabled: Boolean)

    fun setLocalPluginSearchPaused(paused: Boolean)

    fun getEnabledScrapersForType(type: String): List<PluginScraper>

    suspend fun testScraper(
        scraperId: String,
        contentTitle: String? = null,
        tmdbId: String? = null,
        useFirstResult: Boolean = false,
    ): Result<List<PluginRuntimeResult>>

    /** Loads the package if necessary and reports whether it exposes a settings UI. */
    suspend fun hasExtensionSettings(scraperId: String): Boolean

    /** Opens a CloudStream extension's own settings Activity. No-op for JS plugins. */
    fun openExtensionSettings(scraperId: String)

    suspend fun executeScraper(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        contentTitle: String? = null,
    ): Result<List<PluginRuntimeResult>>

    /** Provider search used by Discover and the plugin catalog pages. */
    internal suspend fun browseSearch(
        scraperId: String,
        query: String,
    ): List<PluginBrowseItem>

    /** Provider home/latest feed used by Discover and the plugin catalog pages. */
    internal suspend fun browseLatest(
        scraperId: String,
        page: Int,
        category: PluginBrowseCategory? = null,
    ): PluginLatestPage?

    /** Resolve a CloudStream browse item into streams for Lucid TV playback. */
    internal suspend fun browseStreams(
        scraperId: String,
        apiIndex: Int,
        url: String,
        season: Int?,
        episode: Int?,
        timeoutMs: Long? = null,
    ): List<PluginRuntimeResult>

    /** Load a series' episode list for Lucid TV marathons. */
    internal suspend fun browseEpisodes(
        scraperId: String,
        apiIndex: Int,
        url: String,
    ): List<PluginBrowseEpisode>

    /** Loads unchecked CloudStream extensions once to discover whether they expose settings. */
    suspend fun detectCloudStreamSettings(repositoryUrl: String? = null)
}