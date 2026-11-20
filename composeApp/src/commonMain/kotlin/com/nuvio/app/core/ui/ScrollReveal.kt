package com.nuvio.app.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A gentle reveal for items that enter composition while scrolling: they fade, rise a little
 * and settle at full scale. Lazy rows only compose the items near the viewport, so this is the
 * moment a card is first seen; the animation is one-shot per item and runs on the layer, not in
 * the layout pass.
 */
@Composable
fun Modifier.scrollReveal(
    key: Any? = Unit,
    distance: Dp = 16.dp,
    durationMillis: Int = NuvioTokens.Motion.sheetEnterMillis,
): Modifier {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = durationMillis,
                easing = NuvioTokens.Motion.standard,
            ),
        )
    }
    val rise = distance
    return this.graphicsLayer {
        val value = progress.value
        alpha = value
        translationY = (1f - value) * rise.toPx()
        val scale = 0.985f + 0.015f * value
        scaleX = scale
        scaleY = scale
    }
}
