package com.nuvio.app.features.livetv.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.livetv.data.IptvOrgCatalog
import com.nuvio.app.features.livetv.data.IptvOrgCatalogData
import com.nuvio.app.features.livetv.data.IptvOrgList
import com.nuvio.app.features.livetv.data.IptvOrgListType
import com.nuvio.app.features.livetv.data.normalizeIptvOrgUrl
import kotlinx.coroutines.launch

/**
 * Browse the iptv-org country, category and region playlists and add one like any other M3U
 * source. The index is cached for a day; a failed refresh keeps the last copy.
 */
@Composable
internal fun IptvOrgBrowserDialog(
    existingUrls: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (IptvOrgList) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(IptvOrgListType.COUNTRY) }
    var query by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<IptvOrgList?>(null) }
    var data by remember { mutableStateOf<IptvOrgCatalogData?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(force: Boolean = false) {
        if (loading) return
        scope.launch {
            loading = true
            error = null
            runCatching { IptvOrgCatalog.load(force) }
                .onSuccess { data = it }
                .onFailure { error = it.message ?: "Could not load the iptv-org lists" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = "Browse M3U playlists",
        message = "Country, category and region channel lists from the iptv-org project.",
    ) {
        val chosen = pending
        if (chosen != null) {
            Text(
                text = "Add ${chosen.name}?",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = chosen.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "The playlist downloads in the background; its channels then appear in Live TV.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            DialogButtons {
                DialogButton(
                    text = "Add playlist",
                    style = DialogButtonStyle.Primary,
                    onClick = {
                        onAdd(chosen)
                        onDismiss()
                    },
                )
                DialogButton(text = "Cancel", onClick = { pending = null })
            }
            return@DialogSurface
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            Chip("Countries", tab == IptvOrgListType.COUNTRY, onClick = { tab = IptvOrgListType.COUNTRY })
            Chip("Categories", tab == IptvOrgListType.CATEGORY, onClick = { tab = IptvOrgListType.CATEGORY })
            Chip("Regions", tab == IptvOrgListType.REGION, onClick = { tab = IptvOrgListType.REGION })
        }

        NuvioInputField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search by name or code",
        )

        when {
            loading && data == null -> {
                NuvioLoadingIndicator()
            }

            error != null && data == null -> {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
                DialogButtons {
                    DialogButton(
                        text = "Retry",
                        style = DialogButtonStyle.Primary,
                        onClick = { load(force = true) },
                    )
                    DialogButton(text = "Close", onClick = onDismiss)
                }
            }

            data != null -> {
                val current = data!!
                val lists = when (tab) {
                    IptvOrgListType.COUNTRY -> current.countries
                    IptvOrgListType.CATEGORY -> current.categories
                    IptvOrgListType.REGION -> current.regions
                }
                val filtered = remember(lists, query) {
                    if (query.isBlank()) lists
                    else lists.filter {
                        it.name.contains(query, ignoreCase = true) ||
                            it.code.contains(query, ignoreCase = true)
                    }
                }
                if (current.fromCache) {
                    Text(
                        text = "Showing cached lists (offline).",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                    )
                }
                if (filtered.isEmpty()) {
                    Text(
                        text = "No playlists match this search.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                    ) {
                        items(filtered, key = { "${it.type}:${it.code}" }) { item ->
                            val alreadyAdded = normalizeIptvOrgUrl(item.url) in existingUrls
                            DialogOption(
                                text = listOfNotNull(item.flag, item.name).joinToString(" "),
                                description = if (alreadyAdded) {
                                    "Already added · ${item.subtitle}"
                                } else {
                                    item.subtitle
                                },
                                selected = alreadyAdded,
                                enabled = !alreadyAdded,
                                onClick = { pending = item },
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "Playlists are maintained by the iptv-org project. Channels may be geo-blocked or offline.",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
        )
    }
}
