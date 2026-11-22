package com.nuvio.app.features.plugins.browse

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.plugins.external.PluginBrowseItem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Everything needed to reopen one extension card outside the browse screen. */
internal data class PluginCatalogRef(
    val scraperId: String,
    val scraperName: String,
    val apiIndex: Int,
    val name: String,
    val url: String,
    val posterUrl: String?,
    val type: String,
    val year: Int?,
)

/**
 * Resolves extension cards shown on Discover/Home back to their provider item.
 * The registry is in-memory, matching the lifetime of those catalog feeds.
 */
internal object PluginCatalogRegistry {
    private val lock = SynchronizedObject()
    private val idsByKey = mutableMapOf<String, String>()
    private val refsById = mutableMapOf<String, PluginCatalogRef>()
    private var nextId = 1L

    fun register(
        scraperId: String,
        scraperName: String,
        item: PluginBrowseItem,
    ): String = synchronized(lock) {
        val key = "$scraperId|${item.apiIndex}|${item.url}"
        idsByKey.getOrPut(key) {
            val id = "plugin-item:${nextId++}"
            refsById[id] = PluginCatalogRef(
                scraperId = scraperId,
                scraperName = scraperName,
                apiIndex = item.apiIndex,
                name = item.name,
                url = item.url,
                posterUrl = item.posterUrl,
                type = item.type,
                year = item.year,
            )
            id
        }
    }

    fun resolve(id: String): PluginCatalogRef? = synchronized(lock) {
        refsById[id]
    }

    fun clear() = synchronized(lock) {
        idsByKey.clear()
        refsById.clear()
    }
}

internal fun PluginBrowseItem.toCatalogMetaPreview(
    scraperId: String,
    scraperName: String,
): MetaPreview {
    val id = PluginCatalogRegistry.register(
        scraperId = scraperId,
        scraperName = scraperName,
        item = this,
    )
    return MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = posterUrl,
        releaseInfo = year?.toString(),
    )
}
