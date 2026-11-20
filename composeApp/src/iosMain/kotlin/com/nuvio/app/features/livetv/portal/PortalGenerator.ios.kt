package com.nuvio.app.features.livetv.portal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Portal generation is Android-only; the Live TV page hides the action elsewhere. */
actual object PortalGeneratorPlatform {
    actual val available: Boolean = false
    private val empty = MutableStateFlow<List<GeneratedPortal>>(emptyList())
    actual val savedPortals: StateFlow<List<GeneratedPortal>> = empty.asStateFlow()

    actual suspend fun generate(
        target: Int,
        onProgress: (PortalGeneratorProgress) -> Unit,
    ): List<GeneratedPortal> = emptyList()

    actual fun save(found: List<GeneratedPortal>): Int = 0
    actual fun remove(portal: GeneratedPortal) = Unit
    actual fun addToLiveTv(portal: GeneratedPortal): Boolean = false
}
