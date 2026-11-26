package com.nuvio.app.features.music

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Music page and its floating player live in Android-only code because playback uses the
 * SmartTube YouTube engine and a foreground media session. Other platforms leave these empty,
 * so the Music tab is not shown.
 */
object MusicHost {
    /** The full Music tab. */
    var screen: (@Composable (modifier: Modifier) -> Unit)? = null

    /** The compact player shown over every other tab while a song is loaded. */
    var miniPlayer: (@Composable (modifier: Modifier, onOpenMusic: () -> Unit) -> Unit)? = null

    /** When true the floating video/mini-player combo also stays over the Music tab itself. */
    val floating = MutableStateFlow(false)

    val available: Boolean get() = screen != null
}
