package com.nuvio.app.features.livetv.virtual

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Automatic Lucid channels follow the TV app: one per installed catalog, with a stable id. */
class VirtualAutoChannelsTest {

    private fun addon(
        id: String,
        name: String,
        catalogs: List<AddonCatalog>,
        enabled: Boolean = true,
        adult: Boolean = false,
    ) = ManagedAddon(
        manifestUrl = "https://$id.example/manifest.json",
        manifest = AddonManifest(
            id = id,
            name = name,
            description = "",
            version = "1.0.0",
            resources = listOf(AddonResource(name = "catalog", types = listOf("movie", "series"))),
            types = listOf("movie", "series"),
            catalogs = catalogs,
            behaviorHints = com.nuvio.app.features.addons.AddonBehaviorHints(adult = adult),
            transportUrl = "https://$id.example/",
        ),
        enabled = enabled,
    )

    @Test
    fun everyMovieCatalogBecomesAChannel() {
        val addon = addon(
            id = "cinemeta",
            name = "Cinemeta",
            catalogs = listOf(
                AddonCatalog(type = "movie", id = "top", name = "Top movies"),
                AddonCatalog(type = "movie", id = "year", name = "New movies"),
            ),
        )
        val specs = VirtualAutoChannels.discover(listOf(addon), includeSeries = false)
        assertEquals(2, specs.size)
        assertTrue(specs.all { it.auto })
        assertTrue(specs.all { it.kind == VirtualChannelKind.ADDON_ROTATE })
        assertEquals(
            listOf("Cinemeta · Top movies", "Cinemeta · New movies"),
            specs.map { it.name },
        )
        assertTrue(specs.all { (it.source as VirtualSource.AddonCatalog).catalogId.isNotBlank() })
    }

    @Test
    fun seriesCatalogsAreOnlyUsedWhenEnabled() {
        val addon = addon(
            id = "cinemeta",
            name = "Cinemeta",
            catalogs = listOf(
                AddonCatalog(type = "movie", id = "top", name = "Top movies"),
                AddonCatalog(type = "series", id = "top", name = "Popular"),
            ),
        )
        val moviesOnly = VirtualAutoChannels.discover(listOf(addon), includeSeries = false)
        assertEquals(1, moviesOnly.size)
        val withSeries = VirtualAutoChannels.discover(listOf(addon), includeSeries = true)
        assertEquals(2, withSeries.size)
        val series = withSeries.first { it.kind == VirtualChannelKind.ADDON_SERIES }
        assertEquals("Cinemeta · Popular · Series", series.name)
        assertEquals(VirtualOrder.AIR, series.playout.order)
    }

    @Test
    fun disabledAdultAndRequiredExtraCatalogsAreSkipped() {
        val disabled = addon(
            id = "off",
            name = "Off",
            catalogs = listOf(AddonCatalog(type = "movie", id = "top", name = "Top")),
            enabled = false,
        )
        val adult = addon(
            id = "adult",
            name = "Adult",
            catalogs = listOf(AddonCatalog(type = "movie", id = "top", name = "Top")),
            adult = true,
        )
        val required = addon(
            id = "required",
            name = "Required",
            catalogs = listOf(
                AddonCatalog(
                    type = "movie",
                    id = "genre",
                    name = "By genre",
                    extra = listOf(AddonExtraProperty(name = "genre", isRequired = true)),
                ),
            ),
        )
        val specs = VirtualAutoChannels.discover(listOf(disabled, adult, required), includeSeries = true)
        assertTrue(specs.isEmpty())
    }

    @Test
    fun aCustomCatalogSourceIsNotAlsoMadeAutomatic() {
        val addon = addon(
            id = "cinemeta",
            name = "Cinemeta",
            catalogs = listOf(
                AddonCatalog(type = "movie", id = "top", name = "Top movies"),
                AddonCatalog(type = "movie", id = "year", name = "New movies"),
            ),
        )
        val specs = VirtualAutoChannels.discover(
            addons = listOf(addon),
            includeSeries = false,
            excludedSources = setOf(
                VirtualAutoChannels.sourceKey(addon.manifestUrl, "top", "movie"),
            ),
        )
        assertEquals(listOf("Cinemeta · New movies"), specs.map { it.name })
    }

    @Test
    fun idsAreStableAcrossCallsAndInputOrder() {
        val first = addon(
            id = "a",
            name = "Addon A",
            catalogs = listOf(AddonCatalog(type = "movie", id = "top", name = "Top")),
        )
        val second = addon(
            id = "b",
            name = "Addon B",
            catalogs = listOf(AddonCatalog(type = "movie", id = "top", name = "Top")),
        )
        val forward = VirtualAutoChannels.discover(listOf(first, second), includeSeries = false)
        val reverse = VirtualAutoChannels.discover(listOf(second, first), includeSeries = false)
        val again = VirtualAutoChannels.discover(listOf(first, second), includeSeries = false)
        assertEquals(forward.map { it.id }.toSet(), reverse.map { it.id }.toSet())
        assertEquals(forward.map { it.id }, again.map { it.id })
    }
}
