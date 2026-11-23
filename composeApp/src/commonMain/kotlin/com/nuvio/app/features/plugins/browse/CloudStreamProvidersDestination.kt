package com.nuvio.app.features.plugins.browse

import androidx.compose.runtime.Composable
import com.nuvio.app.navigation.CloudStreamProvidersRoute
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PluginBrowseRoute
import com.nuvio.app.rememberGuardedPopBackStack

@Composable
internal fun CloudStreamProvidersDestination(
    route: CloudStreamProvidersRoute,
    navController: NuvioNavigator,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    CloudStreamProvidersScreen(
        onBack = onBack,
        onProviderClick = { scraper ->
            navController.navigate(
                PluginBrowseRoute(
                    scraperId = scraper.id,
                    scraperName = scraper.name,
                ),
            )
        },
    )
}
