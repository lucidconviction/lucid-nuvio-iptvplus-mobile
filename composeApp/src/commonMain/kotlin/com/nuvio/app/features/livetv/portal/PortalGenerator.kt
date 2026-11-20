package com.nuvio.app.features.livetv.portal

import kotlinx.coroutines.flow.StateFlow

/** One working Xtream portal found by "Generate portals". */
data class GeneratedPortal(
    val baseUrl: String,
    val username: String,
    val password: String,
    val name: String,
    val expiry: String?,
    val maxConnections: String?,
    val m3uUrl: String,
    val source: String,
    val added: Boolean = false,
) {
    val key: String get() = baseUrl.lowercase() + "|" + username.lowercase()
    val host: String get() = baseUrl.substringAfter("://").substringBefore('/')
}

/** Progress shown while a generation run is checking panel after panel. */
data class PortalGeneratorProgress(
    val status: String,
    val tried: Int = 0,
    val found: Int = 0,
    val target: Int = 15,
)

/**
 * "Generate portals" is a network/parser feature ported from Lucid Hikari. It lives on
 * Android because the search sources use Android's crypto and paste-site clients; other
 * platforms simply don't show the action.
 */
expect object PortalGeneratorPlatform {
    val available: Boolean

    /** Portals found by previous runs, kept outside the Live TV source list until Add. */
    val savedPortals: StateFlow<List<GeneratedPortal>>

    suspend fun generate(
        target: Int = 15,
        onProgress: (PortalGeneratorProgress) -> Unit = {},
    ): List<GeneratedPortal>

    /** Adds [found] to the saved search results; returns how many were new. */
    fun save(found: List<GeneratedPortal>): Int

    fun remove(portal: GeneratedPortal)

    /** Installs one saved portal as an ordinary Xtream source. */
    fun addToLiveTv(portal: GeneratedPortal): Boolean
}
