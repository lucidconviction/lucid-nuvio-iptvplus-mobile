package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's liquid-glass icon tile: a translucent pane with a bright top edge, a soft
 * refraction glow and a specular sweep. It is drawn in Compose (no blur shader), so every
 * platform gets the same look and it stays cheap enough for icon-heavy settings and bars.
 */
@Composable
fun LiquidGlassIconFrame(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    selected: Boolean = false,
    accent: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val resolvedAccent = accent ?: MaterialTheme.nuvio.colors.accent
    val top = if (selected) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.20f)
    val middle = Color.White.copy(alpha = if (selected) 0.10f else 0.055f)
    val bottom = resolvedAccent.copy(alpha = if (selected) 0.30f else 0.14f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to top,
                        0.52f to middle,
                        1f to bottom,
                    ),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.58f),
                        Color.White.copy(alpha = 0.10f),
                        resolvedAccent.copy(alpha = 0.24f),
                    ),
                ),
                shape = shape,
            )
            .drawWithContent {
                drawContent()
                val highlightCenter = Offset(size.width * 0.23f, size.height * 0.10f)
                val highlightRadius = size.minDimension * 0.72f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                        center = highlightCenter,
                        radius = highlightRadius,
                    ),
                    radius = highlightRadius,
                    center = highlightCenter,
                )
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A glass tile with one icon in it. */
@Composable
fun LiquidGlassIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    frameSize: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    shape: Shape = RoundedCornerShape(14.dp),
    selected: Boolean = false,
) {
    val resolvedTint = tint ?: MaterialTheme.nuvio.colors.accent
    LiquidGlassIconFrame(
        modifier = modifier.size(frameSize),
        shape = shape,
        selected = selected,
        accent = resolvedTint,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = resolvedTint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** The circular variant used around back and toolbar buttons. */
@Composable
fun LiquidGlassRoundIconFrame(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    selected: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) = LiquidGlassIconFrame(
    modifier = modifier,
    shape = CircleShape,
    selected = selected,
    accent = accent,
    content = content,
)
