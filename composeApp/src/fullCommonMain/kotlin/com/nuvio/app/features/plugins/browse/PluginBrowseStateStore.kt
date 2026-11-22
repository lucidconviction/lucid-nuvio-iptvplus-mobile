package com.nuvio.app.features.plugins.browse

import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.external.PluginBrowseDetail
import com.nuvio.app.features.plugins.external.PluginBrowseEpisode
import com.nuvio.app.features.plugins.external.PluginBrowseItem

/**
 * Keeps browse state alive while the player is on top of the back stack.
 * Compose disposes the browse screen when the player opens; without this,
 * going back from playback would return to an empty search screen.
 */
internal object PluginBrowseStateStore {
    data class State(
        val query: String = "",
        val results: List<PluginBrowseItem> = emptyList(),
        val detailItem: PluginBrowseItem? = null,
        val detail: PluginBrowseDetail? = null,
        val selectedEpisode: PluginBrowseEpisode? = null,
        val streams: List<PluginRuntimeResult> = emptyList(),
    )

    private val states = mutableMapOf<String, State>()

    fun get(key: String): State = states[key] ?: State()

    fun put(key: String, state: State) {
        states[key] = state
    }

    fun clear(key: String) {
        states.remove(key)
    }
}
