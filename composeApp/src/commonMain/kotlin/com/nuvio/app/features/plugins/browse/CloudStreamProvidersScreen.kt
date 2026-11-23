package com.nuvio.app.features.plugins.browse

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginRepositoryType
import com.nuvio.app.features.plugins.PluginScraper
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.plugins_badge_cloudstream
import nuvio.composeapp.generated.resources.plugins_browse_provider_hint
import nuvio.composeapp.generated.resources.plugins_provider_version
import nuvio.composeapp.generated.resources.plugins_view_all_empty
import nuvio.composeapp.generated.resources.plugins_view_all_subtitle
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CloudStreamProvidersScreen(
    onBack: () -> Unit,
    onProviderClick: (PluginScraper) -> Unit,
) {
    LaunchedEffect(Unit) {
        PluginRepository.initialize()
    }
    val uiState by PluginRepository.uiState.collectAsStateWithLifecycle()
    val providers = remember(uiState.pluginsEnabled, uiState.scrapers) {
        if (!uiState.pluginsEnabled) {
            emptyList()
        } else {
            uiState.scrapers
                .filter { scraper ->
                    scraper.enabled &&
                        scraper.manifestEnabled &&
                        scraper.type == PluginRepositoryType.EXTERNAL_DEX
                }
                .sortedBy { it.name.lowercase() }
        }
    }
    val tokens = MaterialTheme.nuvio

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background),
    ) {
        val columns = if (maxWidth >= 600.dp) 3 else 2
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(
                key = "cloudstream_header",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                NuvioScreenHeader(
                    title = stringResource(Res.string.plugins_badge_cloudstream),
                    onBack = onBack,
                )
            }
            item(
                key = "cloudstream_count",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Text(
                    text = stringResource(Res.string.plugins_view_all_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (providers.isEmpty()) {
                item(
                    key = "cloudstream_empty",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    NuvioSurfaceCard {
                        Text(
                            text = stringResource(Res.string.plugins_view_all_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(providers, key = { provider -> provider.id }) { provider ->
                    CloudStreamProviderTile(
                        provider = provider,
                        onClick = { onProviderClick(provider) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudStreamProviderTile(
    provider: PluginScraper,
    onClick: () -> Unit,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
        label = "cloudstreamTileScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "cloudstreamTileAlpha",
    )

    NuvioSurfaceCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(156.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!provider.logo.isNullOrBlank()) {
                AsyncImage(
                    model = provider.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = provider.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = provider.description.ifBlank { stringResource(Res.string.plugins_browse_provider_hint) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.weight(1f))
        val versionLabel = stringResource(Res.string.plugins_provider_version, provider.version)
        Text(
            text = "${provider.supportedTypes.joinToString(" | ")} · $versionLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
