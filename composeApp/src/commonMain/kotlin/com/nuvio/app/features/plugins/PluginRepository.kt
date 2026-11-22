package com.nuvio.app.features.plugins

import kotlinx.coroutines.flow.StateFlow

expect object PluginRepository {
    val uiState: StateFlow<PluginsUiState>

    fun initialize()

    fun onProfileChanged(profileId: Int)

    fun clearLocalState()

    suspend fun pullFromServer(profileId: Int)

    suspend fun addRepository(rawUrl: String): AddPluginRepositoryResult

    fun removeRepository(manifestUrl: String)

    fun refreshAll()

    fun refreshRepository(manifestUrl: String, pushAfterRefresh: Boolean = false)

    fun toggleScraper(scraperId: String, enabled: Boolean)

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
}