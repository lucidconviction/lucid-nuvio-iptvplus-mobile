package com.nuvio.app.features.web

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The Web-Apps tab: saved sites on the home screen and an embedded WebView browser that can
 * hand detected HTTP/HLS/DASH media to Nuvio's player. The WebView engine is ported from Lucid
 * Hikari (GPL-3.0).
 */
@Composable
fun WebAppsScreen(
    onPlayMedia: (WebMediaSource) -> Unit,
    modifier: Modifier = Modifier,
    onBrowserActiveChange: (Boolean) -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    WebAppsRepository.ensureLoaded()
    val sites by WebAppsRepository.sites.collectAsStateWithLifecycle()
    val scripts by WebAppsRepository.userscripts.collectAsStateWithLifecycle()
    val settings by WebAppsRepository.settings.collectAsStateWithLifecycle()
    val controller = rememberWebBrowserController()
    val browserState by controller.state.collectAsStateWithLifecycle()
    var activeUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var address by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showAddSite by remember { mutableStateOf(false) }
    var editingSite by remember { mutableStateOf<SavedWebApp?>(null) }
    var deletingSite by remember { mutableStateOf<SavedWebApp?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showScripts by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }
    var showBrowserMenu by remember { mutableStateOf(false) }
    var showShieldLog by remember { mutableStateOf(false) }
    var autoPlayDismissed by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(browserState.downloadMessage) {
        val message = browserState.downloadMessage ?: return@LaunchedEffect
        if (message.startsWith("Saved") ||
            message.contains("failed", ignoreCase = true) ||
            message.contains("cannot", ignoreCase = true) ||
            message.contains("Could not", ignoreCase = true) ||
            message.startsWith("Preparing") ||
            message.startsWith("Recording")
        ) {
            NuvioToastController.show(message, 3_500L)
        }
        controller.clearDownloadMessage()
    }
    LaunchedEffect(browserState.autoPlayCandidate, settings.autoPlayMode) {
        val candidate = browserState.autoPlayCandidate ?: return@LaunchedEffect
        if (settings.autoPlayMode == WebAutoPlayMode.Best) {
            controller.consumeAutoPlayCandidate()
            controller.pause()
            onPlayMedia(candidate)
        }
    }

    LaunchedEffect(settings, scripts) {
        controller.applySettings(settings, scripts)
    }
    val browserActive = activeUrl != null
    LaunchedEffect(browserActive) {
        onBrowserActiveChange(browserActive)
    }
    DisposableEffect(Unit) {
        onDispose { onBrowserActiveChange(false) }
    }
    DisposableEffect(controller) {
        controller.setThumbnailCaptureListener { siteId, path ->
            WebAppsRepository.updateSiteThumbnail(siteId, path)
        }
        onDispose { controller.setThumbnailCaptureListener(null) }
    }
    LaunchedEffect(activeUrl) {
        val url = activeUrl
        if (url != null) {
            controller.load(url)
            address = url
        } else {
            controller.consumeAutoPlayCandidate()
        }
    }
    LaunchedEffect(browserState.url) {
        if (browserState.url.isNotBlank()) address = browserState.url
    }

    fun openUrl(raw: String) {
        val normalized = normalizeWebUrl(raw) ?: return
        activeUrl = normalized
        address = normalized
    }

    fun openSavedSite(site: SavedWebApp) {
        controller.beginThumbnailCapture(site.id)
        openUrl(site.url)
    }

    fun openSearch(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val asUrl = normalizeWebUrl(text)
        if (asUrl != null && (text.contains('.') || text.startsWith("http", true))) {
            openUrl(asUrl)
        } else {
            openUrl("https://www.google.com/search?q=${text.replace(" ", "+")}")
        }
    }

    if (activeUrl == null) {
        Column(
            modifier = modifier.fillMaxSize().background(tokens.colors.background).statusBarsPadding(),
        ) {
            NuvioScreenHeader(
                title = "Web-Apps",
                includeStatusBarPadding = false,
                modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8),
                actions = {
                    IconButton(onClick = { showAddSite = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add web app", tint = tokens.colors.textPrimary)
                    }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = tokens.colors.textPrimary)
                    }
                    Menu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuItem(text = "Open browser", onClick = {
                            menu = false
                            openUrl(settings.homeUrl)
                        })
                        MenuItem(text = "Userscripts", onClick = {
                            menu = false
                            showScripts = true
                        })
                        MenuItem(text = "Browser settings", onClick = {
                            menu = false
                            showSettings = true
                        })
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s8),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioInputField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = "Search or enter a website",
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { openSearch(address) }),
                )
                IconButton(onClick = { openSearch(address) }) {
                    Icon(Icons.Filled.Search, contentDescription = "Open", tint = tokens.colors.accent)
                }
            }
            if (sites.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(NuvioTokens.Space.s24),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    Text(
                        text = "Save your streaming sites here for one-tap access. The browser blocks popups, runs userscripts and hands detected videos to Nuvio's player.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                    DialogButton(
                        text = "Add a web app",
                        style = DialogButtonStyle.Primary,
                        onClick = { showAddSite = true },
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(180.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = NuvioTokens.Space.s16,
                        end = NuvioTokens.Space.s16,
                        top = NuvioTokens.Space.s8,
                        bottom = nuvioSafeBottomPadding(NuvioTokens.Space.s24),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                ) {
                    items(sites, key = { it.id }) { site ->
                        WebAppCard(
                            site = site,
                            onClick = { openSavedSite(site) },
                            onLongClick = { editingSite = site },
                        )
                    }
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(tokens.colors.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = NuvioTokens.Space.s4, vertical = NuvioTokens.Space.s4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
            ) {
                IconButton(onClick = {
                    if (controller.state.value.canGoBack) controller.goBack() else activeUrl = null
                }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = tokens.colors.textPrimary)
                }
                IconButton(onClick = { controller.goForward() }, enabled = browserState.canGoForward) {
                    Icon(
                        Icons.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (browserState.canGoForward) tokens.colors.textPrimary else tokens.colors.textDisabled,
                    )
                }
                IconButton(onClick = { controller.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = tokens.colors.textPrimary)
                }
                NuvioInputField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = "Address",
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { openSearch(address) }),
                    trailingContent = {
                        IconButton(onClick = { openSearch(address) }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = "Go",
                                tint = tokens.colors.accent,
                            )
                        }
                    },
                )
                if (browserState.detectedMedia.isNotEmpty()) {
                    // Keep this button in the toolbar, not floating over the WebView: an
                    // AndroidView consumes touches before Compose overlays on top of it.
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .combinedClickable(onClick = { showMedia = true }),
                        color = tokens.colors.accent,
                        contentColor = tokens.colors.onAccent,
                        shape = RoundedCornerShape(50),
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = NuvioTokens.Space.s8,
                                vertical = NuvioTokens.Space.s6,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                browserState.detectedMedia.size.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                IconButton(onClick = { showBrowserMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = tokens.colors.textPrimary)
                }
                Menu(expanded = showBrowserMenu, onDismissRequest = { showBrowserMenu = false }) {
                    MenuItem(text = "Add this page to Web-Apps", onClick = {
                        showBrowserMenu = false
                        WebAppsRepository.addSite(
                            browserState.title.ifBlank { browserPageTitle(address) },
                            browserState.url.ifBlank { address },
                        )
                    })
                    MenuItem(text = "Scan for videos", onClick = {
                        showBrowserMenu = false
                        controller.scanForMedia()
                    })
                    MenuItem(
                        text = "Shield log · ${browserState.shieldEvents.size} events · ${browserState.adsSkipped} ads skipped",
                        onClick = {
                            showBrowserMenu = false
                            showShieldLog = true
                        },
                    )
                    MenuItem(
                        text = "Ad skip: " + when (settings.adSkipMode) {
                            WebAdSkipMode.Off -> "Off"
                            WebAdSkipMode.ClickOnly -> "Skip button"
                            WebAdSkipMode.ClickAndFastForward -> "Skip + fast-forward"
                            WebAdSkipMode.Aggressive -> "Aggressive"
                        },
                        onClick = {
                            showBrowserMenu = false
                            val next = when (settings.adSkipMode) {
                                WebAdSkipMode.Off -> WebAdSkipMode.ClickOnly
                                WebAdSkipMode.ClickOnly -> WebAdSkipMode.ClickAndFastForward
                                WebAdSkipMode.ClickAndFastForward -> WebAdSkipMode.Aggressive
                                WebAdSkipMode.Aggressive -> WebAdSkipMode.Off
                            }
                            val updated = settings.copy(adSkipMode = next)
                            WebAppsRepository.updateSettings(updated)
                            controller.applySettings(updated, scripts)
                        },
                    )
                    MenuItem(text = "Open browser test page", onClick = {
                        showBrowserMenu = false
                        controller.openTestPage()
                    })
                    MenuItem(text = "Play detected video", onClick = {
                        showBrowserMenu = false
                        showMedia = true
                    })
                    MenuItem(text = "Userscripts", onClick = {
                        showBrowserMenu = false
                        showScripts = true
                    })
                    MenuItem(text = "Browser settings", onClick = {
                        showBrowserMenu = false
                        showSettings = true
                    })
                    val currentHost = normalizeWebHost(browserState.url.ifBlank { address }).orEmpty()
                    if (currentHost.isNotBlank()) {
                        val adblockDisabled = settings.adBlockWhitelist.any {
                            currentHost == it || currentHost.endsWith(".$it")
                        }
                        val siteAllowed = settings.allowedHosts.any {
                            currentHost == it || currentHost.endsWith(".$it")
                        }
                        MenuItem(
                            text = if (adblockDisabled) {
                                "Enable ad blocking on this site"
                            } else {
                                "Disable ad blocking on this site"
                            },
                            onClick = {
                                showBrowserMenu = false
                                val whitelist = if (adblockDisabled) {
                                    settings.adBlockWhitelist - currentHost
                                } else {
                                    settings.adBlockWhitelist + currentHost
                                }
                                val updated = settings.copy(adBlockWhitelist = whitelist)
                                WebAppsRepository.updateSettings(updated)
                                controller.applySettings(updated, scripts)
                                controller.reload()
                            },
                        )
                        MenuItem(
                            text = if (siteAllowed) {
                                "Remove site from allow list"
                            } else {
                                "Allow links & popups from this site"
                            },
                            onClick = {
                                showBrowserMenu = false
                                val updated = if (siteAllowed) {
                                    settings.copy(allowedHosts = settings.allowedHosts - currentHost)
                                } else {
                                    settings.copy(allowedHosts = settings.allowedHosts + currentHost)
                                }
                                WebAppsRepository.updateSettings(updated)
                                controller.applySettings(updated, scripts)
                            },
                        )
                    }
                    MenuItem(text = "Open in external browser", onClick = {
                        showBrowserMenu = false
                        runCatching {
                            uriHandler.openUri(browserState.url.ifBlank { address })
                        }
                    })
                    MenuItem(text = "Back to Web-Apps", onClick = {
                        showBrowserMenu = false
                        activeUrl = null
                    })
                }
            }
            if (browserState.loading) {
                LinearProgressIndicator(
                    progress = { browserState.progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = tokens.colors.accent,
                )
            }
            // Saved Web-Apps stay one thumb-tap away while the browser is open. The strip sits at
            // the bottom so it never covers the site's own header or menu.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                WebBrowserHost(controller = controller, modifier = Modifier.fillMaxSize())
                browserState.error?.let { error ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(NuvioTokens.Space.s8),
                        color = tokens.colors.surfaceDialog,
                        shape = tokens.shapes.compactCard,
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = NuvioTokens.Space.s12,
                                vertical = NuvioTokens.Space.s8,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Page error",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = tokens.colors.textPrimary,
                                )
                                Text(
                                    error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.colors.textMuted,
                                )
                            }
                            IconButton(onClick = { controller.reload() }) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "Try again",
                                    tint = tokens.colors.accent,
                                )
                            }
                        }
                    }
                }
            }
            WebAppBottomTabs(
                sites = sites,
                currentUrl = browserState.url.ifBlank { activeUrl.orEmpty() },
                onOpen = { site -> openSavedSite(site) },
                onAdd = { showAddSite = true },
            )
        }
        PlatformBackHandler(enabled = true) {
            if (controller.exitFullscreen()) return@PlatformBackHandler
            if (controller.state.value.canGoBack) controller.goBack() else activeUrl = null
        }
    }

    if (showAddSite || editingSite != null) {
        WebAppEditorDialog(
            site = editingSite,
            onDismiss = { showAddSite = false; editingSite = null },
            onSave = { name, url ->
                if (editingSite == null) WebAppsRepository.addSite(name, url)
                else WebAppsRepository.updateSite(editingSite!!.copy(name = name, url = url))
                showAddSite = false
                editingSite = null
            },
            onDelete = editingSite?.let { site ->
                {
                    WebAppsRepository.removeSite(site.id)
                    editingSite = null
                }
            },
        )
    }

    if (showSettings) {
        WebAppsSettingsDialog(
            settings = settings,
            onUpdate = WebAppsRepository::updateSettings,
            onUpdateAdblock = { controller.updateAdblockLists() },
            onDismiss = { showSettings = false },
        )
    }

    if (showScripts) {
        UserscriptsDialog(
            scripts = scripts,
            onAdd = { name, code -> WebAppsRepository.addUserscript(name, code) },
            onUpdate = WebAppsRepository::updateUserscript,
            onDelete = WebAppsRepository::removeUserscript,
            onDismiss = { showScripts = false },
        )
    }

    if (showMedia) {
        MediaPickerDialog(
            sources = browserState.detectedMedia,
            onPlay = { source ->
                showMedia = false
                controller.consumeAutoPlayCandidate()
                controller.pause()
                onPlayMedia(source)
            },
            onPlayInBrowser = { source ->
                showMedia = false
                controller.playInBrowser(source)
            },
            onDownload = { source ->
                showMedia = false
                controller.download(source)
            },
            onDismiss = { showMedia = false },
        )
    }

    browserState.autoPlayCandidate?.let { candidate ->
        if (settings.autoPlayMode == WebAutoPlayMode.Ask && autoPlayDismissed != candidate.url) {
            AutoPlayDialog(
                candidate = candidate,
                alternatives = browserState.autoPlayAlternatives,
                onPlay = { source ->
                    autoPlayDismissed = candidate.url
                    controller.consumeAutoPlayCandidate()
                    controller.pause()
                    onPlayMedia(source)
                },
                onStay = {
                    autoPlayDismissed = candidate.url
                    controller.consumeAutoPlayCandidate()
                },
            )
        }
    }

    if (showShieldLog) {
        ShieldLogDialog(
            events = browserState.shieldEvents,
            adsSkipped = browserState.adsSkipped,
            adsFastForwarded = browserState.adsFastForwarded,
            adSecondsSaved = browserState.adSecondsSaved,
            onDismiss = { showShieldLog = false },
        )
    }

    deletingSite?.let { site ->
        DialogSurface(
            onDismissRequest = { deletingSite = null },
            title = "Remove web app?",
            message = site.name,
        ) {
            DialogButtons {
                DialogButton(text = "Cancel", onClick = { deletingSite = null })
                DialogButton(
                    text = "Remove",
                    style = DialogButtonStyle.Destructive,
                    onClick = {
                        WebAppsRepository.removeSite(site.id)
                        deletingSite = null
                    },
                )
            }
        }
    }
}

@Composable
private fun WebAppBottomTabs(
    sites: List<SavedWebApp>,
    currentUrl: String,
    onOpen: (SavedWebApp) -> Unit,
    onAdd: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val currentHost = normalizeWebHost(currentUrl)
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.colors.surface)
            .padding(vertical = NuvioTokens.Space.s4),
        contentPadding = PaddingValues(horizontal = NuvioTokens.Space.s10),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        items(sites, key = { it.id }) { site ->
            val host = normalizeWebHost(site.url)
            val active = currentHost != null && host == currentHost
            val icon = site.iconUrl ?: webFaviconUrl(site.url)
            Surface(
                modifier = Modifier
                    .height(40.dp)
                    .widthIn(min = 92.dp, max = 176.dp)
                    .clip(tokens.shapes.compactCard)
                    .clickable { onOpen(site) },
                color = if (active) {
                    tokens.colors.accent.copy(alpha = 0.18f)
                } else {
                    tokens.colors.surfaceElevated
                },
                shape = tokens.shapes.compactCard,
                border = if (active) BorderStroke(1.dp, tokens.colors.accent) else null,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                ) {
                    if (icon != null) {
                        AsyncImage(
                            model = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Public,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (active) tokens.colors.accent else tokens.colors.textMuted,
                        )
                    }
                    Text(
                        text = site.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) tokens.colors.accent else tokens.colors.textPrimary,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item(key = "add-web-app") {
            Surface(
                modifier = Modifier
                    .height(40.dp)
                    .width(44.dp)
                    .clip(tokens.shapes.compactCard)
                    .clickable(onClick = onAdd),
                color = tokens.colors.surfaceElevated,
                shape = tokens.shapes.compactCard,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add web app",
                        tint = tokens.colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WebAppCard(site: SavedWebApp, onClick: () -> Unit, onLongClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val icon = site.iconUrl ?: webFaviconUrl(site.url)
    val preview = site.thumbnailPath?.let { "file://$it#v=${site.thumbnailUpdatedAtMs}" }
        ?: webAppPreviewUrl(site.url)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = tokens.colors.surfaceElevated,
        shape = tokens.shapes.card,
    ) {
        Box(Modifier.fillMaxSize()) {
            // Fallback shown while the remote preview loads (or if it cannot be fetched).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(tokens.colors.surfaceElevated, tokens.colors.surface),
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    AsyncImage(
                        model = icon,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        Icons.Filled.Public,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = tokens.colors.accent,
                    )
                }
            }
            AsyncImage(
                model = preview,
                contentDescription = site.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent),
                        )
                    )
                    .padding(NuvioTokens.Space.s10),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                            .background(tokens.colors.surface.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (icon != null) {
                            AsyncImage(
                                model = icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Public,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = tokens.colors.accent,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            site.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            webHost(site.url).removePrefix("www."),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebAppEditorDialog(
    site: SavedWebApp?,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember(site) { mutableStateOf(site?.name.orEmpty()) }
    var url by remember(site) { mutableStateOf(site?.url.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    DialogSurface(
        onDismissRequest = onDismiss,
        title = if (site == null) "Add web app" else "Edit web app",
    ) {
        NuvioInputField(value = name, onValueChange = { name = it }, placeholder = "Name")
        NuvioInputField(value = url, onValueChange = { url = it; error = null }, placeholder = "https://example.com")
        error?.let { Text(it, color = MaterialTheme.nuvio.colors.danger, style = MaterialTheme.typography.bodySmall) }
        DialogButtons {
            if (onDelete != null) {
                DialogButton(text = "Delete", style = DialogButtonStyle.Destructive, onClick = onDelete)
            }
            DialogButton(text = "Cancel", onClick = onDismiss)
            DialogButton(
                text = "Save",
                style = DialogButtonStyle.Primary,
                onClick = {
                    if (normalizeWebUrl(url) == null) error = "Enter a valid website address."
                    else onSave(name, url)
                },
            )
        }
    }
}

@Composable
private fun WebAppsSettingsDialog(
    settings: WebAppsSettings,
    onUpdate: (WebAppsSettings) -> Unit,
    onUpdateAdblock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var showAllowedSites by remember { mutableStateOf(false) }
    var customUa by remember(settings.customUserAgent) { mutableStateOf(settings.customUserAgent) }
    DialogSurface(onDismissRequest = onDismiss, title = "Browser settings") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            SettingSwitch("Block ad redirects during playback", settings.redirectProtection) {
                onUpdate(settings.copy(redirectProtection = it))
            }
            SettingSwitch("Block popups from other sites", settings.popupProtection) {
                onUpdate(settings.copy(popupProtection = it))
            }
            SettingSwitch("uBlock / AdGuard-style ad blocking", settings.adBlockEnabled) {
                onUpdate(settings.copy(adBlockEnabled = it))
            }
            SettingSwitch("Hide ad placeholders without touching players", settings.cosmeticFiltering) {
                onUpdate(settings.copy(cosmeticFiltering = it))
            }
            ChoiceSetting(
                label = "Auto-play detected videos in Nuvio",
                values = listOf("Off", "Ask", "Best match"),
                selected = settings.autoPlayMode.ordinal,
                onSelect = { onUpdate(settings.copy(autoPlayMode = WebAutoPlayMode.entries[it])) },
            )
            ChoiceSetting(
                label = "Ad skip / fast-forward",
                values = listOf("Off", "Skip button", "Skip + fast-forward", "Aggressive"),
                selected = settings.adSkipMode.ordinal,
                onSelect = { onUpdate(settings.copy(adSkipMode = WebAdSkipMode.entries[it])) },
            )
            ChoiceSetting(
                label = "Fast-forward speed for unskippable ads",
                values = listOf("4×", "8×", "16×"),
                selected = when (settings.adFastForwardSpeed.toInt()) {
                    4 -> 0
                    16 -> 2
                    else -> 1
                },
                onSelect = {
                    onUpdate(settings.copy(adFastForwardSpeed = when (it) { 0 -> 4f; 2 -> 16f; else -> 8f }))
                },
            )
            SettingSwitch("Mute ads while fast-forwarding", settings.muteDuringAds) {
                onUpdate(settings.copy(muteDuringAds = it))
            }
            SettingSwitch("Skip intros and openings", settings.skipIntros) {
                onUpdate(settings.copy(skipIntros = it))
            }
            SettingSwitch("Skip recaps", settings.skipRecaps) {
                onUpdate(settings.copy(skipRecaps = it))
            }
            SettingSwitch("Auto-advance to next episode", settings.autoNextEpisode) {
                onUpdate(settings.copy(autoNextEpisode = it))
            }
            SettingSwitch("Block promotional alerts and confirm dialogs", settings.blockPromoDialogs) {
                onUpdate(settings.copy(blockPromoDialogs = it))
            }
            SettingSwitch("Block notification permission prompts", settings.blockNotifications) {
                onUpdate(settings.copy(blockNotifications = it))
            }
            SettingSwitch("Suppress “leave site?” while a video plays", settings.blockBeforeUnload) {
                onUpdate(settings.copy(blockBeforeUnload = it))
            }
            SettingSwitch("Media sniffer (fetch / XHR / MSE)", settings.mediaSnifferEnabled) {
                onUpdate(settings.copy(mediaSnifferEnabled = it))
            }
            ChoiceSetting(
                label = "Page user agent",
                values = listOf("Chrome Mobile", "Desktop Chrome", "iPhone Safari", "Custom"),
                selected = settings.userAgentProfile.ordinal,
                onSelect = {
                    onUpdate(
                        settings.copy(
                            userAgentProfile = WebUserAgentProfile.entries[it],
                            desktopMode = WebUserAgentProfile.entries[it] == WebUserAgentProfile.DesktopChrome,
                        )
                    )
                },
            )
            if (settings.userAgentProfile == WebUserAgentProfile.Custom) {
                NuvioInputField(
                    value = customUa,
                    onValueChange = { customUa = it },
                    placeholder = "Custom user agent",
                )
                DialogButton(
                    text = "Apply custom user agent",
                    onClick = { onUpdate(settings.copy(customUserAgent = customUa)) },
                )
            }
            Text(
                "Normal links and redirects load normally. Redirects are blocked while a video " +
                    "plays and for hosts you block. Ad blocking uses hosts lists, " +
                    "AdGuard/EasyList/uBlock network rules and safe cosmetic filters. Ad skip is " +
                    "a player-level fallback for ads that cannot be blocked.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            DialogButton(
                text = "Allowed sites for links & popups (${settings.allowedHosts.size})",
                onClick = { showAllowedSites = true },
            )
            DialogButton(
                text = "Update adblock & uBlock filters",
                onClick = onUpdateAdblock,
            )
        }
        DialogButtons {
            DialogButton(text = "Done", style = DialogButtonStyle.Primary, onClick = onDismiss)
        }
    }
    if (showAllowedSites) {
        AllowedSitesDialog(
            settings = settings,
            onUpdate = onUpdate,
            onDismiss = { showAllowedSites = false },
        )
    }
}

@Composable
private fun ChoiceSetting(
    label: String,
    values: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
        ) {
            values.forEachIndexed { index, value ->
                Chip(
                    label = value,
                    selected = index == selected,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun AllowedSitesDialog(
    settings: WebAppsSettings,
    onUpdate: (WebAppsSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val blockedCount = settings.blockedRedirectHosts.size + settings.blockedPopupHosts.size

    fun addHost() {
        val host = normalizeWebHost(input)
        if (host == null) {
            error = "Enter a host like example.com"
            return
        }
        onUpdate(
            settings.copy(
                allowedHosts = settings.allowedHosts + host,
                blockedRedirectHosts = settings.blockedRedirectHosts - host,
                blockedPopupHosts = settings.blockedPopupHosts - host,
            )
        )
        input = ""
        error = null
    }

    DialogSurface(onDismissRequest = onDismiss, title = "Allowed sites") {
        Text(
            "Cross-site redirects and popups are blocked by default. Hosts listed here (and " +
                "their subdomains) can open links and popups.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        if (settings.allowedHosts.isEmpty()) {
            Text("No allowed sites yet.", color = tokens.colors.textMuted, style = MaterialTheme.typography.bodySmall)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
            ) {
                items(settings.allowedHosts.sorted(), key = { it }) { host ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(host, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = {
                            onUpdate(settings.copy(allowedHosts = settings.allowedHosts - host))
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove $host", tint = tokens.colors.danger)
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            NuvioInputField(
                value = input,
                onValueChange = { input = it; error = null },
                placeholder = "example.com",
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addHost() }),
            )
            IconButton(onClick = { addHost() }) {
                Icon(Icons.Filled.Add, contentDescription = "Add allowed site", tint = tokens.colors.accent)
            }
        }
        error?.let { Text(it, color = tokens.colors.danger, style = MaterialTheme.typography.bodySmall) }
        if (blockedCount > 0) {
            DialogButton(
                text = "Clear blocked sites ($blockedCount)",
                onClick = {
                    onUpdate(settings.copy(blockedRedirectHosts = emptySet(), blockedPopupHosts = emptySet()))
                },
            )
        }
        DialogButtons {
            DialogButton(text = "Done", style = DialogButtonStyle.Primary, onClick = onDismiss)
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MediaPickerDialog(
    sources: List<WebMediaSource>,
    onPlay: (WebMediaSource) -> Unit,
    onPlayInBrowser: (WebMediaSource) -> Unit,
    onDownload: (WebMediaSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val displaySources = remember(sources) {
        sources
            .groupBy { it.url }
            .map { (_, group) ->
                val best = group.maxByOrNull { webMediaScore(it) } ?: group.first()
                best.copy(variantCount = maxOf(best.variantCount, group.size))
            }
            .sortedByDescending { webMediaScore(it) }
    }
    DialogSurface(onDismissRequest = onDismiss, title = "Detected videos") {
        Text(
            "Tap a source to play it in Nuvio. Sources are ranked by duration, resolution and " +
                "confidence; ads are pushed to the bottom. Browser plays it inside Web-Apps.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        if (sources.isEmpty()) {
            Text("No videos detected yet. Start the page's video, then try again.", color = tokens.colors.textMuted)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(430.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                itemsIndexed(displaySources, key = { _, source -> source.url }) { index, source ->
                    val isBest = index == 0 && displaySources.size > 1
                    val meta = buildList {
                        add(if (source.isHls) "HLS" else if (source.isDash) "DASH" else "Video")
                        source.qualityLabel()?.let(::add)
                        webMediaDurationLabel(source.durationMs)?.let(::add)
                        if (source.isLive) add("LIVE")
                        if (source.variantCount > 1) add("${source.variantCount} variants")
                        add(webHost(source.url).removePrefix("www."))
                    }.joinToString(" · ")
                    Surface(
                        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onPlay(source) }),
                        color = tokens.colors.surface,
                        shape = tokens.shapes.compactCard,
                    ) {
                        Column(
                            modifier = Modifier.padding(NuvioTokens.Space.s8),
                            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                            ) {
                                MediaThumbnail(source = source)
                                Column(Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                                    ) {
                                        Text(
                                            source.title.ifBlank { webHost(source.url) },
                                            modifier = Modifier.weight(1f, fill = false),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = tokens.colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (isBest) MediaChip("BEST", tokens.colors.accent)
                                        if (source.likelyAd) MediaChip("Ad?", tokens.colors.danger)
                                        if (source.drm != null) MediaChip("DRM", tokens.colors.danger)
                                    }
                                    Text(
                                        meta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tokens.colors.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { onDownload(source) }, enabled = source.drm == null) {
                                    Text("Download", style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(onClick = { onPlayInBrowser(source) }) {
                                    Text("Browser", style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(onClick = { onPlay(source) }) {
                                    Text("Play", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        DialogButtons {
            DialogButton(text = "Close", onClick = onDismiss)
        }
    }
}

@Composable
private fun MediaThumbnail(source: WebMediaSource) {
    val tokens = MaterialTheme.nuvio
    val model = source.thumbnailPath?.let { "file://$it" }
        ?: source.thumbnailUrl
        ?: source.posterUrl
        ?: source.pageUrl.takeIf { it.isNotBlank() }?.let(::webAppPreviewUrl)
    Box(
        modifier = Modifier
            .width(104.dp)
            .height(58.dp)
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = tokens.colors.textMuted,
            modifier = Modifier.size(22.dp),
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun MediaChip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.18f),
        contentColor = color,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s6, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AutoPlayDialog(
    candidate: WebMediaSource,
    alternatives: List<WebMediaSource>,
    onPlay: (WebMediaSource) -> Unit,
    onStay: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(onDismissRequest = onStay, title = "Play this video in Nuvio?") {
        Text(
            "Nuvio found a confident match. Play it in the app player, or stay in the browser.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        Surface(color = tokens.colors.surface, shape = tokens.shapes.compactCard) {
            Row(
                modifier = Modifier.padding(NuvioTokens.Space.s8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                MediaThumbnail(candidate)
                Column(Modifier.weight(1f)) {
                    Text(
                        candidate.title.ifBlank { webHost(candidate.url) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            candidate.qualityLabel(),
                            webMediaDurationLabel(candidate.durationMs),
                            if (candidate.isLive) "LIVE" else null,
                            webHost(candidate.url).removePrefix("www."),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (alternatives.isNotEmpty()) {
            Text("Other sources", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
            alternatives.forEach { source ->
                TextButton(onClick = { onPlay(source) }) {
                    Text(
                        source.title.ifBlank { webHost(source.url) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DialogButtons {
            DialogButton(text = "Stay here", onClick = onStay)
            DialogButton(text = "Play best", style = DialogButtonStyle.Primary, onClick = { onPlay(candidate) })
        }
    }
}

@Composable
private fun ShieldLogDialog(
    events: List<WebShieldEvent>,
    adsSkipped: Int,
    adsFastForwarded: Int,
    adSecondsSaved: Long,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    DialogSurface(onDismissRequest = onDismiss, title = "Shield log") {
        Text(
            "$adsSkipped ads skipped · $adsFastForwarded fast-forwarded · ${adSecondsSaved}s saved",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
        if (events.isEmpty()) {
            Text("No blocked popups, redirects or ads yet.", color = tokens.colors.textMuted)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
            ) {
                items(events, key = { it.id }) { event ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                    ) {
                        MediaChip(event.kind.uppercase(), tokens.colors.accent)
                        Column(Modifier.weight(1f)) {
                            Text(
                                event.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (event.host.isNotBlank()) {
                                Text(
                                    event.host,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        DialogButtons {
            DialogButton(text = "Close", onClick = onDismiss)
        }
    }
}

@Composable
private fun UserscriptsDialog(
    scripts: List<WebUserscript>,
    onAdd: (String, String) -> Unit,
    onUpdate: (WebUserscript) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var editing by remember { mutableStateOf<WebUserscript?>(null) }
    var adding by remember { mutableStateOf(false) }
    DialogSurface(onDismissRequest = onDismiss, title = "Userscripts") {
        if (editing == null && !adding) {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp), verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                items(scripts, key = { it.id }) { script ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = NuvioTokens.Space.s4),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                    ) {
                        Switch(
                            checked = script.enabled,
                            onCheckedChange = { onUpdate(script.copy(enabled = it)) },
                        )
                        Text(
                            script.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { editing = script }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = tokens.colors.textMuted)
                        }
                        IconButton(onClick = { onDelete(script.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = tokens.colors.danger)
                        }
                    }
                }
            }
            DialogButtons {
                DialogButton(text = "Close", onClick = onDismiss)
                DialogButton(
                    text = "Add script",
                    style = DialogButtonStyle.Primary,
                    onClick = { adding = true },
                )
            }
        } else {
            UserscriptEditor(
                script = editing,
                onCancel = { editing = null; adding = false },
                onSave = { name, code ->
                    val current = editing
                    if (current == null) onAdd(name, code) else onUpdate(current.copy(name = name, code = code))
                    editing = null
                    adding = false
                },
            )
        }
    }
}

@Composable
private fun UserscriptEditor(
    script: WebUserscript?,
    onCancel: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(script) { mutableStateOf(script?.name.orEmpty()) }
    var code by remember(script) { mutableStateOf(script?.code.orEmpty()) }
    var importUrl by remember(script) { mutableStateOf("") }
    var importing by remember(script) { mutableStateOf(false) }
    var importError by remember(script) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    NuvioInputField(value = name, onValueChange = { name = it }, placeholder = "Script name")
    // Paste is not the only way a userscript arrives: Greasy Fork and every
    // GitHub gist hand out a URL. Fetch it into the editor (never straight to
    // the list) so the source can still be read before it is saved.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
    ) {
        NuvioInputField(
            value = importUrl,
            onValueChange = { importUrl = it },
            placeholder = "Import from URL (.user.js or Greasy Fork)",
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                if (importUrl.isNotBlank() && !importing) {
                    importing = true
                    importError = null
                    scope.launch {
                        runCatching { WebUserscriptImport.fetch(importUrl) }
                            .onSuccess { fetched ->
                                code = fetched
                                if (name.isBlank()) name = WebUserscriptManager.parse(fetched).name
                            }
                            .onFailure { importError = it.message ?: "Could not download the script" }
                        importing = false
                    }
                }
            }),
        )
        DialogButton(
            text = if (importing) "Fetching…" else "Import",
            style = DialogButtonStyle.Secondary,
            enabled = importUrl.isNotBlank() && !importing,
            onClick = {
                importing = true
                importError = null
                scope.launch {
                    runCatching { WebUserscriptImport.fetch(importUrl) }
                        .onSuccess { fetched ->
                            code = fetched
                            if (name.isBlank()) name = WebUserscriptManager.parse(fetched).name
                        }
                        .onFailure { importError = it.message ?: "Could not download the script" }
                    importing = false
                }
            },
        )
    }
    importError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        modifier = Modifier.fillMaxWidth().height(240.dp),
        placeholder = { Text("// ==UserScript==\n// @match https://example.com/*\n// ==/UserScript==") },
        textStyle = MaterialTheme.typography.bodySmall,
    )
    DialogButtons {
        DialogButton(text = "Cancel", onClick = onCancel)
        DialogButton(
            text = "Save",
            style = DialogButtonStyle.Primary,
            enabled = code.isNotBlank(),
            onClick = { onSave(name, code) },
        )
    }
}

private fun browserPageTitle(url: String): String = webAppFallbackName(url)
