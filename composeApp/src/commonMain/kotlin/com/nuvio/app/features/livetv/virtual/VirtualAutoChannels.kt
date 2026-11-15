package com.nuvio.app.features.livetv.virtual

import com.nuvio.app.features.addons.ManagedAddon

/**
 * Automatic Lucid TV channels, one per installed add-on catalog. This is the mobile port of the
 * TV app's discovery: a channel is a catalog definition with a stable id, so it keeps the same
 * number, schedule and cached pool across launches even though it is never saved as a custom
 * channel.
 */
internal object VirtualAutoChannels {
    const val MAX_CHANNELS = 60

    fun sourceKey(manifestUrl: String, catalogId: String, type: String): String =
        "$manifestUrl|$catalogId|$type"

    fun discover(
        addons: List<ManagedAddon>,
        includeSeries: Boolean,
        excludedSources: Set<String> = emptySet(),
    ): List<VirtualChannelSpec> {
        val wanted = if (includeSeries) setOf("movie", "series") else setOf("movie")
        return addons.asSequence()
            .filter { it.enabled && it.manifest != null && !it.manifest.behaviorHints.adult }
            .flatMap { addon ->
                val manifest = addon.manifest ?: return@flatMap emptySequence()
                manifest.catalogs.asSequence()
                    .filter { catalog ->
                        catalog.type in wanted &&
                            catalog.extra.none { it.isRequired } &&
                            sourceKey(addon.manifestUrl, catalog.id, catalog.type) !in excludedSources
                    }
                    .map { catalog -> addon to catalog }
            }
            .distinctBy { (addon, catalog) ->
                "${addon.manifestUrl}|${catalog.type}|${catalog.id}"
            }
            .take(MAX_CHANNELS)
            .map { (addon, catalog) -> spec(addon, catalog) }
            .toList()
    }

    private fun spec(
        addon: ManagedAddon,
        catalog: com.nuvio.app.features.addons.AddonCatalog,
    ): VirtualChannelSpec {
        val series = catalog.type.equals("series", ignoreCase = true)
        val manifest = addon.manifest
        val id = "vc_" + VirtualSchedule.stableHash(
            "${addon.manifestUrl}|${manifest?.id.orEmpty()}|${catalog.type}|${catalog.id}"
        ).toString(16)
        return VirtualChannelSpec(
            id = id,
            name = if (series) {
                "${addon.displayTitle} · ${catalog.name} · Series"
            } else {
                "${addon.displayTitle} · ${catalog.name}"
            },
            kind = if (series) VirtualChannelKind.ADDON_SERIES else VirtualChannelKind.ADDON_ROTATE,
            source = VirtualSource.AddonCatalog(
                manifestUrl = addon.manifestUrl,
                addonId = manifest?.id.orEmpty(),
                addonName = addon.displayTitle,
                catalogId = catalog.id,
                catalogName = catalog.name,
                contentType = catalog.type,
            ),
            pool = VirtualPoolRules(maxItems = MAX_POOL_ITEMS),
            playout = VirtualPlayout(
                mode = VirtualPlayoutMode.LOOP,
                slotMinutes = if (series) 45 else 90,
                seriesSlotMinutes = 45,
                order = if (series) VirtualOrder.AIR else VirtualOrder.SHUFFLE,
                anchorMs = 0L,
            ),
            join = VirtualJoin(maxStartPercent = -1),
            auto = true,
        )
    }

    private const val MAX_POOL_ITEMS = 120
}
