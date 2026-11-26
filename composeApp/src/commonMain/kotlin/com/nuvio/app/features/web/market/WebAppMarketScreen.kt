package com.nuvio.app.features.web.market

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.web.webFaviconUrl

/**
 * A curated shelf of sites from the FMHY catalogue, grouped and searchable.
 * Tapping a card opens it in the existing Web-Apps browser; the bookmark
 * writes the same saved site the Web-Apps home already uses.
 */
@Composable
internal fun WebAppMarketScreen(
    savedUrls: Set<String>,
    onBack: () -> Unit,
    onOpen: (MarketApp) -> Unit,
    onToggleSaved: (MarketApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val state by WebAppMarketRepository.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var groupId by rememberSaveable { mutableStateOf(MarketCategories.groups.first().id) }
    var categoryId by rememberSaveable {
        mutableStateOf(MarketCategories.groups.first().categories.first().id)
    }

    LaunchedEffect(Unit) { WebAppMarketRepository.ensureLoaded() }
    PlatformBackHandler(enabled = true, onBack = onBack)

    val group = MarketCategories.groups.firstOrNull { it.id == groupId }
        ?: MarketCategories.groups.first()
    val category = group.categories.firstOrNull { it.id == categoryId }
        ?: group.categories.first()
    val apps = state.catalog?.apps.orEmpty()
    val visible = if (query.isBlank()) {
        apps.filter { it.categoryId == category.id }
    } else {
        apps.filter { MarketCategories.matches(query, it) }
    }

    Column(modifier = modifier.fillMaxSize().background(tokens.colors.background)) {
        NuvioScreenHeader(
            title = "Web App Market",
            onBack = onBack,
            actions = {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).padding(end = 4.dp),
                        strokeWidth = 2.dp,
                        color = tokens.colors.accent,
                    )
                } else {
                    IconButton(onClick = { WebAppMarketRepository.reload() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Reload market",
                            tint = tokens.colors.textPrimary,
                        )
                    }
                }
            },
        )
        NuvioInputField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search every category",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s4),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            items(MarketCategories.groups, key = { it.id }) { item ->
                Chip(
                    label = item.label,
                    selected = item.id == group.id,
                    onClick = {
                        groupId = item.id
                        categoryId = item.categories.first().id
                        query = ""
                    },
                )
            }
        }
        LazyRow(
            modifier = Modifier.padding(top = NuvioTokens.Space.s6),
            contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            items(group.categories, key = { it.id }) { item ->
                val count = apps.count { it.categoryId == item.id }
                Chip(
                    label = item.label + if (count > 0) " · $count" else "",
                    selected = item.id == category.id,
                    onClick = {
                        categoryId = item.id
                        query = ""
                    },
                )
            }
        }
        when {
            state.loading && state.catalog == null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = tokens.colors.accent)
            }

            visible.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(NuvioTokens.Space.s24),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: if (query.isBlank()) {
                        "No sites in this category yet."
                    } else {
                        "No sites match “$query”."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NuvioTokens.Space.s16,
                    end = NuvioTokens.Space.s16,
                    top = NuvioTokens.Space.s10,
                    bottom = NuvioTokens.Space.s24,
                ),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
            ) {
                items(visible, key = { "${it.categoryId}|${it.url}" }) { app ->
                    MarketAppCard(
                        app = app,
                        saved = app.url in savedUrls,
                        onOpen = { onOpen(app) },
                        onToggleSaved = { onToggleSaved(app) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketAppCard(
    app: MarketApp,
    saved: Boolean,
    onOpen: () -> Unit,
    onToggleSaved: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val host = MarketCategories.hostOf(app.url)
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().height(156.dp),
        shape = tokens.shapes.card,
        color = tokens.colors.surfaceElevated,
    ) {
        Box(Modifier.fillMaxSize()) {
            // The title is drawn as the full-bleed backdrop first. A real favicon covers it
            // when it loads; if the site has none, the title itself becomes the artwork.
            MarketTitleBackdrop(app.name)
            webFaviconUrl(app.url)?.let { iconUrl ->
                AsyncImage(
                    model = iconUrl,
                    contentDescription = app.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.26f),
                            0.34f to Color.Transparent,
                            0.58f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.92f),
                        ),
                    ),
            )
            if (app.rank > 0) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(NuvioTokens.Space.s6),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                ) {
                    Text(
                        text = "#${app.rank}",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            IconButton(
                onClick = onToggleSaved,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
            ) {
                Icon(
                    imageVector = if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (saved) "Remove from Web Apps" else "Save to Web Apps",
                    tint = if (saved) tokens.colors.accent else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s8),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = app.name,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = host,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Full-bleed fallback artwork: the app's own title, set large behind the scrim. */
@Composable
private fun MarketTitleBackdrop(name: String) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        tokens.colors.accent.copy(alpha = 0.72f),
                        tokens.colors.accentStrong.copy(alpha = 0.52f),
                        tokens.colors.surfaceElevated,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            color = Color.White.copy(alpha = 0.94f),
            fontSize = 24.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
