package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

/**
 * Puts the host activity in landscape while the player is on screen when
 * [enabled], and restores whatever orientation it had on leave. No-op on
 * platforms without an activity orientation (iOS).
 */
@Composable
expect fun ApplyPlayerLandscape(enabled: Boolean)
