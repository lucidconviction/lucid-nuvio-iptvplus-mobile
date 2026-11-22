package com.nuvio.app.features.plugins.browse

import androidx.compose.runtime.Composable
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PluginBrowseRoute

/**
 * Full implementation lives in the full distributions; Play Store and App Store
 * builds provide a no-op because they cannot load CloudStream DEX packages.
 */
@Composable
internal expect fun PluginBrowseDestination(
    route: PluginBrowseRoute,
    navController: NuvioNavigator,
)
