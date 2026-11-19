package com.nuvio.app.features.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Creates the platform browser controller (Android WebView today). */
@Composable
expect fun rememberWebBrowserController(): WebBrowserController

/** Hosts the native browser view. */
@Composable
expect fun WebBrowserHost(
    controller: WebBrowserController,
    modifier: Modifier = Modifier,
)

/**
 * Routes a stream detected in the browser through the platform's loopback
 * proxy when one exists, so the player's HLS child requests keep the page's
 * Referer/Cookie/UA. Returns [url] unchanged when no proxy is available.
 */
expect fun prepareWebStreamForPlayer(
    url: String,
    headers: Map<String, String>,
    pageUrl: String,
): String
