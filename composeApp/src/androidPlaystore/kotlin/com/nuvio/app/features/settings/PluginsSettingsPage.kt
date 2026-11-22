package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.nuvio.app.features.plugins.PluginScraper

internal actual fun LazyListScope.pluginsSettingsContent(
    onBrowseProvider: ((PluginScraper) -> Unit)?,
) = Unit
