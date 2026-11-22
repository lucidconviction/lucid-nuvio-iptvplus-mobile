package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.plugins.PluginsSettingsPageContent

internal actual fun LazyListScope.pluginsSettingsContent(
    onBrowseProvider: ((PluginScraper) -> Unit)?,
) {
    item {
        PluginsSettingsPageContent(
            modifier = Modifier.fillMaxWidth(),
            onBrowseProvider = onBrowseProvider,
        )
    }
}
