package com.nuvio.app.features.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioInfoBadge
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.features.plugins.runtime.PluginRuntime
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.plugins_badge_cloudstream
import nuvio.composeapp.generated.resources.plugins_badge_disabled
import nuvio.composeapp.generated.resources.plugins_badge_enabled
import nuvio.composeapp.generated.resources.plugins_badge_enabled_providers
import nuvio.composeapp.generated.resources.plugins_badge_javascript
import nuvio.composeapp.generated.resources.plugins_badge_providers
import nuvio.composeapp.generated.resources.plugins_badge_refreshing
import nuvio.composeapp.generated.resources.plugins_badge_repos
import nuvio.composeapp.generated.resources.plugins_browse_action
import nuvio.composeapp.generated.resources.plugins_button_install_repo
import nuvio.composeapp.generated.resources.plugins_button_installing
import nuvio.composeapp.generated.resources.plugins_button_test_provider
import nuvio.composeapp.generated.resources.plugins_button_testing
import nuvio.composeapp.generated.resources.plugins_cloudstream_consent_title
import nuvio.composeapp.generated.resources.plugins_cloudstream_consent_message
import nuvio.composeapp.generated.resources.plugins_cloudstream_consent_accept
import nuvio.composeapp.generated.resources.plugins_cloudstream_consent_cancel
import nuvio.composeapp.generated.resources.plugins_cd_delete_repo
import nuvio.composeapp.generated.resources.plugins_cd_provider_settings
import nuvio.composeapp.generated.resources.plugins_cd_refresh_repo
import nuvio.composeapp.generated.resources.plugins_add_repo_hint
import nuvio.composeapp.generated.resources.plugins_add_repo_hint_js_only
import nuvio.composeapp.generated.resources.plugins_empty_providers
import nuvio.composeapp.generated.resources.plugins_extension_no_settings
import nuvio.composeapp.generated.resources.plugins_empty_repos_subtitle
import nuvio.composeapp.generated.resources.plugins_empty_repos_title
import nuvio.composeapp.generated.resources.plugins_enable_globally_desc
import nuvio.composeapp.generated.resources.plugins_enable_globally_title
import nuvio.composeapp.generated.resources.plugins_error_enter_repo_url
import nuvio.composeapp.generated.resources.plugins_group_by_repo_desc
import nuvio.composeapp.generated.resources.plugins_group_by_repo_title
import nuvio.composeapp.generated.resources.plugins_input_manifest_placeholder
import nuvio.composeapp.generated.resources.plugins_message_installed
import nuvio.composeapp.generated.resources.plugins_message_cloudstream_installed
import nuvio.composeapp.generated.resources.plugins_provider_disabled_by_repo
import nuvio.composeapp.generated.resources.plugins_provider_no_description
import nuvio.composeapp.generated.resources.plugins_provider_version
import nuvio.composeapp.generated.resources.plugins_manage
import nuvio.composeapp.generated.resources.plugins_manage_extensions
import nuvio.composeapp.generated.resources.plugins_repo_counts
import nuvio.composeapp.generated.resources.plugins_repo_fallback_label
import nuvio.composeapp.generated.resources.plugins_repo_no_enabled
import nuvio.composeapp.generated.resources.plugins_repo_no_extensions
import nuvio.composeapp.generated.resources.plugins_repo_version
import nuvio.composeapp.generated.resources.plugins_section_add_repo
import nuvio.composeapp.generated.resources.plugins_section_extensions
import nuvio.composeapp.generated.resources.plugins_section_installed_repos
import nuvio.composeapp.generated.resources.plugins_section_overview
import nuvio.composeapp.generated.resources.plugins_section_providers
import nuvio.composeapp.generated.resources.plugins_test_error_title
import nuvio.composeapp.generated.resources.plugins_test_failed
import nuvio.composeapp.generated.resources.plugins_test_no_results
import nuvio.composeapp.generated.resources.plugins_test_results_count
import nuvio.composeapp.generated.resources.plugins_test_dialog_title
import nuvio.composeapp.generated.resources.plugins_test_dialog_hint
import nuvio.composeapp.generated.resources.plugins_test_title_label
import nuvio.composeapp.generated.resources.plugins_test_tmdb_label
import nuvio.composeapp.generated.resources.plugins_test_use_first_result
import nuvio.composeapp.generated.resources.plugins_test_use_first_result_desc
import nuvio.composeapp.generated.resources.plugins_test_run
import nuvio.composeapp.generated.resources.plugins_test_cancel
import org.jetbrains.compose.resources.stringResource

@Composable
fun PluginsSettingsPageContent(
    modifier: Modifier = Modifier,
    onBrowseProvider: ((PluginScraper) -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        PluginRepository.initialize()
    }

    val uiState by PluginRepository.uiState.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var repositoryUrl by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var consentRepoUrl by remember { mutableStateOf<String?>(null) }

    var testingScraperId by remember { mutableStateOf<String?>(null) }
    val testResults = remember { mutableStateMapOf<String, List<PluginRuntimeResult>>() }

    var testDialogScraper by remember { mutableStateOf<PluginScraper?>(null) }
    var testQuery by rememberSaveable { mutableStateOf("") }
    var testTmdbId by rememberSaveable { mutableStateOf("") }
    var testUseFirstResult by rememberSaveable { mutableStateOf(true) }

    var configuringScraper by remember { mutableStateOf<PluginScraper?>(null) }
    var configuringLayout by remember { mutableStateOf<String?>(null) }

    val sortedRepos = remember(uiState.repositories) {
        uiState.repositories.sortedBy { it.name.lowercase() }
    }
    val repositoryNameByUrl = remember(sortedRepos) {
        sortedRepos.associate { it.manifestUrl to it.name }
    }
    val sortedScrapers = remember(uiState.scrapers, repositoryNameByUrl) {
        uiState.scrapers.sortedWith(
            compareBy<PluginScraper>(
                { repositoryNameByUrl[it.repositoryUrl]?.lowercase() ?: it.repositoryUrl.lowercase() },
                { it.name.lowercase() },
            ),
        )
    }

    val repoFallbackLabel = stringResource(Res.string.plugins_repo_fallback_label)
    val testFailedDefault = stringResource(Res.string.plugins_test_failed)
    val testErrorTitle = stringResource(Res.string.plugins_test_error_title)
    val installedTemplate = stringResource(Res.string.plugins_message_installed)
    val cloudStreamInstalledTemplate = stringResource(Res.string.plugins_message_cloudstream_installed)
    val enterRepoUrlError = stringResource(Res.string.plugins_error_enter_repo_url)
    val noExtensionSettingsMessage = stringResource(Res.string.plugins_extension_no_settings)
    val addRepoHint = stringResource(
        if (com.nuvio.app.features.plugins.external.externalExtensionHost.isSupported) {
            Res.string.plugins_add_repo_hint
        } else {
            Res.string.plugins_add_repo_hint_js_only
        },
    )

    fun installRepository(requested: String) {
        isAdding = true
        message = null
        coroutineScope.launch {
            when (val result = PluginRepository.addRepository(requested)) {
                is AddPluginRepositoryResult.Success -> {
                    repositoryUrl = ""
                    message = if (result.repository.type == PluginRepositoryType.EXTERNAL_DEX) {
                        cloudStreamInstalledTemplate.replace("%1\$s", result.repository.name)
                    } else {
                        installedTemplate.replace("%1\$s", result.repository.name)
                    }
                    if (result.repository.type == PluginRepositoryType.EXTERNAL_DEX) {
                        coroutineScope.launch {
                            PluginRepository.detectCloudStreamSettings(result.repository.manifestUrl)
                        }
                    }
                }
                is AddPluginRepositoryResult.Error -> {
                    message = result.message
                }
            }
            isAdding = false
        }
    }

    fun runProviderTest(scraper: PluginScraper, title: String?, tmdbId: String?, useFirstResult: Boolean = false) {
        testingScraperId = scraper.id
        coroutineScope.launch {
            PluginRepository.testScraper(
                scraperId = scraper.id,
                contentTitle = title,
                tmdbId = tmdbId,
                useFirstResult = useFirstResult,
            ).onSuccess { results ->
                testResults[scraper.id] = results
            }.onFailure { error ->
                testResults[scraper.id] = listOf(
                    PluginRuntimeResult(
                        title = testErrorTitle,
                        name = error.message ?: testFailedDefault,
                        url = "about:error",
                    ),
                )
            }
            testingScraperId = null
        }
    }

    var repositoryPageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val repositoryPage = repositoryPageUrl?.let { url ->
        sortedRepos.firstOrNull { it.manifestUrl == url }
    }
    LaunchedEffect(repositoryPageUrl, sortedRepos) {
        if (repositoryPageUrl != null && repositoryPage == null) {
            repositoryPageUrl = null
        }
    }
    PlatformBackHandler(enabled = repositoryPageUrl != null) {
        repositoryPageUrl = null
    }

    fun openProviderSettings(scraper: PluginScraper) {
        if (scraper.type == PluginRepositoryType.EXTERNAL_DEX) {
            coroutineScope.launch {
                if (PluginRepository.hasExtensionSettings(scraper.id)) {
                    PluginRepository.openExtensionSettings(scraper.id)
                } else {
                    message = noExtensionSettingsMessage
                }
            }
        } else {
            coroutineScope.launch {
                val layout = PluginRuntime.getPluginSettingsLayout(scraper.code, scraper.id)
                if (layout != null) {
                    configuringScraper = scraper
                    configuringLayout = layout
                }
            }
        }
    }

    fun openProviderTest(scraper: PluginScraper) {
        if (scraper.type == PluginRepositoryType.EXTERNAL_DEX) {
            testDialogScraper = scraper
            testQuery = if (scraper.supportsType("movie")) "The Matrix" else "Game of Thrones"
            testTmdbId = if (scraper.supportsType("movie")) "603" else "1396"
        } else {
            runProviderTest(scraper, null, null)
        }
    }

    val renderProviderCard: @Composable (PluginScraper) -> Unit = { scraper ->
        val repositoryName = repositoryNameByUrl[scraper.repositoryUrl]
            ?: scraper.repositoryUrl.fallbackRepositoryLabel(repoFallbackLabel)
        PluginProviderCard(
            scraper = scraper,
            repositoryName = repositoryName,
            results = testResults[scraper.id],
            isTesting = testingScraperId == scraper.id,
            onBrowse = onBrowseProvider?.let { browse -> { browse(scraper) } },
            onSettings = { openProviderSettings(scraper) },
            onTest = { openProviderTest(scraper) },
            onToggle = { enabled -> PluginRepository.toggleScraper(scraper.id, enabled) },
        )
    }

    val renderProviderRow: @Composable (PluginScraper) -> Unit = { scraper ->
        PluginProviderRow(
            scraper = scraper,
            results = testResults[scraper.id],
            isTesting = testingScraperId == scraper.id,
            onBrowse = onBrowseProvider?.let { browse -> { browse(scraper) } },
            onSettings = { openProviderSettings(scraper) },
            onTest = { openProviderTest(scraper) },
            onToggle = { enabled -> PluginRepository.toggleScraper(scraper.id, enabled) },
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val page = repositoryPage
        if (page != null) {
            LaunchedEffect(page.manifestUrl) {
                PluginRepository.detectCloudStreamSettings(page.manifestUrl)
            }
            NuvioScreenHeader(
                title = page.name,
                includeStatusBarPadding = false,
                onBack = { repositoryPageUrl = null },
            )
            RepositoryExtensionsSummary(
                repository = page,
                enabledCount = sortedScrapers.count { it.repositoryUrl == page.manifestUrl && it.enabled },
                onRefresh = { PluginRepository.refreshRepository(page.manifestUrl, pushAfterRefresh = true) },
                onDelete = {
                    PluginRepository.removeRepository(page.manifestUrl)
                    repositoryPageUrl = null
                },
            )
            NuvioSectionLabel(stringResource(Res.string.plugins_section_extensions))
            val repositoryScrapers = sortedScrapers.filter { it.repositoryUrl == page.manifestUrl }
            if (repositoryScrapers.isEmpty()) {
                Text(
                    text = stringResource(Res.string.plugins_repo_no_extensions),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                repositoryScrapers.forEach { scraper -> renderProviderCard(scraper) }
            }
        } else {
        NuvioSectionLabel(stringResource(Res.string.plugins_section_overview))
        NuvioSurfaceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NuvioInfoBadge(text = stringResource(Res.string.plugins_badge_repos, sortedRepos.size))
                NuvioInfoBadge(text = stringResource(Res.string.plugins_badge_providers, sortedScrapers.size))
                NuvioInfoBadge(
                    text = if (uiState.pluginsEnabled) {
                        stringResource(Res.string.plugins_badge_enabled)
                    } else {
                        stringResource(Res.string.plugins_badge_disabled)
                    },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.plugins_enable_globally_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(Res.string.plugins_enable_globally_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = uiState.pluginsEnabled,
                    onCheckedChange = { PluginRepository.setPluginsEnabled(it) },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.plugins_group_by_repo_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(Res.string.plugins_group_by_repo_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = uiState.groupStreamsByRepository,
                    onCheckedChange = { PluginRepository.setGroupStreamsByRepository(it) },
                )
            }
        }

        NuvioSectionLabel(stringResource(Res.string.plugins_section_add_repo))
        NuvioSurfaceCard {
            NuvioInputField(
                value = repositoryUrl,
                onValueChange = {
                    repositoryUrl = it
                    message = null
                },
                placeholder = stringResource(Res.string.plugins_input_manifest_placeholder),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = addRepoHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            NuvioPrimaryButton(
                text = if (isAdding) {
                    stringResource(Res.string.plugins_button_installing)
                } else {
                    stringResource(Res.string.plugins_button_install_repo)
                },
                enabled = repositoryUrl.isNotBlank() && !isAdding,
                onClick = {
                    val requested = repositoryUrl.trim()
                    if (requested.isBlank()) {
                        message = enterRepoUrlError
                        return@NuvioPrimaryButton
                    }
                    if (CloudStreamConsent.isRequired(requested) && !CloudStreamConsent.isAccepted()) {
                        consentRepoUrl = requested
                    } else {
                        installRepository(requested)
                    }
                },
            )
            message?.let { text ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        NuvioSectionLabel(stringResource(Res.string.plugins_section_installed_repos))
        if (sortedRepos.isEmpty()) {
            NuvioSurfaceCard {
                Text(
                    text = stringResource(Res.string.plugins_empty_repos_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.plugins_empty_repos_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            sortedRepos.forEach { repo ->
                val enabledScrapers = sortedScrapers.filter { scraper ->
                    scraper.repositoryUrl == repo.manifestUrl && scraper.enabled
                }
                NuvioSurfaceCard {
                    RepositoryGroupHeader(
                        repository = repo,
                        enabledCount = enabledScrapers.size,
                        onManage = { repositoryPageUrl = repo.manifestUrl },
                    )
                    repo.errorMessage?.let { errorMessage ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (enabledScrapers.isEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(Res.string.plugins_repo_no_enabled),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                        enabledScrapers.forEach { scraper -> renderProviderRow(scraper) }
                    }
                }
            }
        }

        val orphanScrapers = sortedScrapers.filter { scraper ->
            sortedRepos.none { it.manifestUrl == scraper.repositoryUrl }
        }
        if (orphanScrapers.isNotEmpty()) {
            NuvioSectionLabel(stringResource(Res.string.plugins_section_providers))
            orphanScrapers.forEach { scraper -> renderProviderRow(scraper) }
        }
        }
    }

    if (configuringScraper != null && configuringLayout != null) {
        PluginSettingsDialog(
            scraperId = configuringScraper!!.id,
            scraperName = configuringScraper!!.name,
            layoutJson = configuringLayout!!,
            onDismiss = {
                configuringScraper = null
                configuringLayout = null
            }
        )
    }

    testDialogScraper?.let { scraper ->
        AlertDialog(
            onDismissRequest = { testDialogScraper = null },
            title = { Text(stringResource(Res.string.plugins_test_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(Res.string.plugins_test_dialog_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    NuvioInputField(
                        value = testQuery,
                        onValueChange = { testQuery = it },
                        placeholder = stringResource(Res.string.plugins_test_title_label),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    NuvioInputField(
                        value = testTmdbId,
                        onValueChange = { testTmdbId = it },
                        placeholder = stringResource(Res.string.plugins_test_tmdb_label),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(Res.string.plugins_test_use_first_result),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(Res.string.plugins_test_use_first_result_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = testUseFirstResult,
                            onCheckedChange = { testUseFirstResult = it },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selected = scraper
                        testDialogScraper = null
                        runProviderTest(selected, testQuery, testTmdbId, testUseFirstResult)
                    },
                ) {
                    Text(stringResource(Res.string.plugins_test_run))
                }
            },
            dismissButton = {
                TextButton(onClick = { testDialogScraper = null }) {
                    Text(stringResource(Res.string.plugins_test_cancel))
                }
            },
        )
    }

    consentRepoUrl?.let { requested ->
        AlertDialog(
            onDismissRequest = { consentRepoUrl = null },
            title = { Text(stringResource(Res.string.plugins_cloudstream_consent_title)) },
            text = {
                Text(
                    text = stringResource(Res.string.plugins_cloudstream_consent_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        CloudStreamConsent.accept()
                        consentRepoUrl = null
                        installRepository(requested)
                    },
                ) {
                    Text(stringResource(Res.string.plugins_cloudstream_consent_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { consentRepoUrl = null }) {
                    Text(stringResource(Res.string.plugins_cloudstream_consent_cancel))
                }
            },
        )
    }
}

private fun String.fallbackRepositoryLabel(fallback: String): String {
    val withoutQuery = substringBefore("?")
    val withoutManifest = withoutQuery.removeSuffix("/manifest.json")
    val host = withoutManifest.substringAfter("://", withoutManifest).substringBefore('/')
    return host.ifBlank {
        withoutManifest.substringAfterLast('/').ifBlank { fallback }
    }
}

@Composable
private fun RepositoryExtensionsSummary(
    repository: PluginRepositoryItem,
    enabledCount: Int,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = repository.name,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                repository.version?.let { version ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(Res.string.plugins_repo_version, version),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                repository.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row {
                NuvioIconActionButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = stringResource(Res.string.plugins_cd_refresh_repo),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onRefresh,
                )
                NuvioIconActionButton(
                    icon = Icons.Rounded.Delete,
                    contentDescription = stringResource(Res.string.plugins_cd_delete_repo),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NuvioInfoBadge(text = stringResource(Res.string.plugins_badge_providers, repository.scraperCount))
            NuvioInfoBadge(
                text = if (repository.type == PluginRepositoryType.EXTERNAL_DEX) {
                    stringResource(Res.string.plugins_badge_cloudstream)
                } else {
                    stringResource(Res.string.plugins_badge_javascript)
                },
            )
            NuvioInfoBadge(text = stringResource(Res.string.plugins_badge_enabled_providers, enabledCount))
        }
        repository.errorMessage?.let { errorMessage ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PluginProviderCard(
    scraper: PluginScraper,
    repositoryName: String,
    results: List<PluginRuntimeResult>?,
    isTesting: Boolean,
    onBrowse: (() -> Unit)?,
    onSettings: () -> Unit,
    onTest: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val isCloudStream = scraper.type == PluginRepositoryType.EXTERNAL_DEX
    val showSettings = if (isCloudStream) {
        scraper.settingsChecked && scraper.hasSettings
    } else {
        scraper.hasSettings
    }

    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Extension,
                    contentDescription = null,
                    tint = if (scraper.enabled) Color(0xFF68B76A) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = repositoryName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = scraper.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = scraper.description.ifBlank {
                            stringResource(Res.string.plugins_provider_no_description)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showSettings) {
                    IconButton(onClick = onSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(Res.string.plugins_cd_provider_settings),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Switch(
                    checked = scraper.enabled,
                    onCheckedChange = onToggle,
                    enabled = scraper.manifestEnabled,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NuvioInfoBadge(text = scraper.supportedTypes.joinToString(" | "))
            NuvioInfoBadge(
                text = if (isCloudStream) {
                    stringResource(Res.string.plugins_badge_cloudstream)
                } else {
                    stringResource(Res.string.plugins_badge_javascript)
                },
            )
            NuvioInfoBadge(text = stringResource(Res.string.plugins_provider_version, scraper.version))
            if (!scraper.manifestEnabled) {
                NuvioInfoBadge(text = stringResource(Res.string.plugins_provider_disabled_by_repo))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isCloudStream && onBrowse != null) {
                NuvioPrimaryButton(
                    text = stringResource(Res.string.plugins_browse_action),
                    modifier = Modifier.weight(1f),
                    onClick = onBrowse,
                )
            }
            NuvioPrimaryButton(
                text = if (isTesting) {
                    stringResource(Res.string.plugins_button_testing)
                } else {
                    stringResource(Res.string.plugins_button_test_provider)
                },
                modifier = Modifier.weight(1f),
                enabled = !isTesting,
                onClick = onTest,
            )
        }

        if (!results.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.plugins_test_results_count, results.size),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            results.take(8).forEach { result ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = result.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = result.url,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        } else if (results != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.plugins_test_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RepositoryGroupHeader(
    repository: PluginRepositoryItem,
    enabledCount: Int,
    onManage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onManage),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = repository.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(
                    Res.string.plugins_repo_counts,
                    repository.scraperCount,
                    enabledCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (repository.isRefreshing) {
            Text(
                text = stringResource(Res.string.plugins_badge_refreshing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(
            text = stringResource(Res.string.plugins_manage),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PluginProviderRow(
    scraper: PluginScraper,
    results: List<PluginRuntimeResult>?,
    isTesting: Boolean,
    onBrowse: (() -> Unit)?,
    onSettings: () -> Unit,
    onTest: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val isCloudStream = scraper.type == PluginRepositoryType.EXTERNAL_DEX
    val showSettings = if (isCloudStream) {
        scraper.settingsChecked && scraper.hasSettings
    } else {
        scraper.hasSettings
    }
    val subtitle = buildList {
        add(scraper.supportedTypes.joinToString(" | "))
        add(stringResource(Res.string.plugins_provider_version, scraper.version))
        if (!scraper.manifestEnabled) {
            add(stringResource(Res.string.plugins_provider_disabled_by_repo))
        }
    }.joinToString(" · ")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scraper.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCloudStream && onBrowse != null) {
                    IconButton(onClick = onBrowse) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(Res.string.plugins_browse_action),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onTest, enabled = !isTesting) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = stringResource(Res.string.plugins_button_test_provider),
                        tint = if (isTesting) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                if (showSettings) {
                    IconButton(onClick = onSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(Res.string.plugins_cd_provider_settings),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Switch(
                    checked = scraper.enabled,
                    onCheckedChange = onToggle,
                    enabled = scraper.manifestEnabled,
                )
            }
        }
        if (!results.isNullOrEmpty()) {
            Text(
                text = stringResource(Res.string.plugins_test_results_count, results.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (results != null) {
            Text(
                text = stringResource(Res.string.plugins_test_no_results),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
