package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.nuvio.app.features.plugins.PluginScraper

internal expect fun LazyListScope.pluginsSettingsContent(
    onBrowseProvider: ((PluginScraper) -> Unit)? = null,
)
