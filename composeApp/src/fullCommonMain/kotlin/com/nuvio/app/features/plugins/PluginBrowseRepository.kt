package com.nuvio.app.features.plugins

import com.nuvio.app.features.plugins.external.PluginBrowseDetail
import com.nuvio.app.features.plugins.external.PluginBrowseItem
import com.nuvio.app.features.plugins.external.externalExtensionHost
import com.nuvio.app.features.profiles.ProfileRepository

/**
 * Browse/search facade for CloudStream providers. Kept separate from the
 * normal title-driven PluginRepository execution path.
 */
internal object PluginBrowseRepository {
    fun currentProfileId(): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && !active.id.isBlank() && active.usesPrimaryPlugins) {
            1
        } else {
            ProfileRepository.activeProfileId
        }
    }

    suspend fun search(scraperId: String, query: String): Result<List<PluginBrowseItem>> = runCatching {
        externalExtensionHost.search(
            scraperId = scraperId,
            profileId = currentProfileId(),
            query = query,
        )
    }

    suspend fun loadDetail(
        scraperId: String,
        item: PluginBrowseItem,
    ): Result<PluginBrowseDetail?> = runCatching {
        externalExtensionHost.loadDetail(
            scraperId = scraperId,
            profileId = currentProfileId(),
            apiIndex = item.apiIndex,
            url = item.url,
        )
    }

    suspend fun loadStreams(
        scraperId: String,
        detail: PluginBrowseDetail,
        season: Int?,
        episode: Int?,
        data: String,
    ): Result<List<com.nuvio.app.features.plugins.PluginRuntimeResult>> = runCatching {
        externalExtensionHost.loadStreams(
            scraperId = scraperId,
            profileId = currentProfileId(),
            apiIndex = detail.apiIndex,
            data = data,
            season = season,
            episode = episode,
        )
    }
}
