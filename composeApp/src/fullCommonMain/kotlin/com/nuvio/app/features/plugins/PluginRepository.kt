package com.nuvio.app.features.plugins

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.plugins.external.ExternalRepoParser
import com.nuvio.app.features.plugins.external.cloudStreamContentTypes
import com.nuvio.app.features.plugins.external.externalExtensionHost
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.plugins.runtime.PluginRuntime
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.plugins_error_enter_repo_url
import nuvio.composeapp.generated.resources.plugins_error_enter_valid_url
import nuvio.composeapp.generated.resources.plugins_error_provider_not_found
import nuvio.composeapp.generated.resources.plugins_error_short_code_failed
import nuvio.composeapp.generated.resources.plugins_repository_already_installed
import nuvio.composeapp.generated.resources.plugins_repository_install_failed
import nuvio.composeapp.generated.resources.plugins_repository_refresh_failed
import org.jetbrains.compose.resources.getString

@Serializable
private data class PluginRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
private data class PluginPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

private data class PluginPersistenceSnapshot(
    val profileId: Int,
    val generation: Long,
    val revision: Long,
    val state: PluginsUiState,
)

private data class LoadedPluginState(
    val state: PluginsUiState,
    val requiresMigration: Boolean,
)

actual object PluginRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("PluginRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(PluginsUiState())
    actual val uiState: StateFlow<PluginsUiState> = _uiState.asStateFlow()

    private var initialized = false
    private var currentProfileId = 1
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val persistenceGeneration = atomic(0L)
    private val persistenceRevision = atomic(0L)
    private val persistenceLock = SynchronizedObject()
    private val persistedRevisionByProfile = mutableMapOf<Int, Long>()

    actual fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        val shouldRefreshStoredRepos = !initialized || currentProfileId != effectiveProfileId
        ensureStateLoadedForProfile(effectiveProfileId)
        if (!shouldRefreshStoredRepos) return

        val state = _uiState.value
        val nowEpochMs = currentEpochMillis()
        state.repositories.filter { repo ->
            shouldRefreshRepository(repo, state.scrapers, nowEpochMs)
        }.forEach { repo ->
            refreshRepositoryInternal(repo.manifestUrl, pushAfterRefresh = false, ensureInitialized = false)
        }
    }

    actual fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (effectiveProfileId == currentProfileId && initialized) return

        cancelActiveRefreshes()
        currentProfileId = effectiveProfileId
        initialized = false
        _uiState.value = PluginsUiState()
    }

    actual fun clearLocalState() {
        cancelActiveRefreshes()
        persistenceGeneration.incrementAndGet()
        currentProfileId = 1
        initialized = false
        _uiState.value = PluginsUiState()
    }

    actual suspend fun pullFromServer(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        ensureStateLoadedForProfile(effectiveProfileId)
        runCatching {
            val rows = SupabaseProvider.client.postgrest
                .from("plugins")
                .select {
                    filter { eq("profile_id", currentProfileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<PluginRow>()

            val urls = dedupeManifestUrls(rows.map { it.url })
            val existingState = _uiState.value
            val existingReposByUrl = existingState.repositories.associateBy { it.manifestUrl }
            val nowEpochMs = currentEpochMillis()
            val nextRepos = urls.map { url ->
                val existing = existingReposByUrl[url]
                if (existing == null) {
                    PluginRepositoryItem(
                        manifestUrl = url,
                        name = url.substringBefore("?").substringAfterLast('/'),
                        isRefreshing = true,
                    )
                } else {
                    val shouldRefresh = shouldRefreshRepository(
                        repository = existing,
                        scrapers = existingState.scrapers,
                        nowEpochMs = nowEpochMs,
                    )
                    existing.copy(
                        isRefreshing = shouldRefresh,
                        errorMessage = if (shouldRefresh) null else existing.errorMessage,
                    )
                }
            }
            val nextScrapers = existingState.scrapers.filter { scraper ->
                urls.contains(scraper.repositoryUrl)
            }

            _uiState.value = PluginsUiState(
                pluginsEnabled = _uiState.value.pluginsEnabled,
                groupStreamsByRepository = _uiState.value.groupStreamsByRepository,
                repositories = nextRepos,
                scrapers = nextScrapers,
            )
            persist()

            nextRepos.filter(PluginRepositoryItem::isRefreshing).forEach { repository ->
                refreshRepository(repository.manifestUrl, pushAfterRefresh = false)
            }

            initialized = true
        }.onFailure { error ->
            log.e(error) { "pullFromServer failed" }
        }
    }

    actual suspend fun addRepository(rawUrl: String): AddPluginRepositoryResult {
        initialize()
        val inputUrl = try {
            resolveRepositoryInput(rawUrl)
        } catch (error: IllegalArgumentException) {
            return AddPluginRepositoryResult.Error(error.message ?: getString(Res.string.plugins_error_enter_valid_url))
        }

        if (_uiState.value.repositories.any { repository ->
                repository.manifestUrl.equals(inputUrl, ignoreCase = true) ||
                    runCatching { normalizeManifestUrl(inputUrl) }.getOrNull()
                        ?.equals(repository.manifestUrl, ignoreCase = true) == true ||
                    runCatching { normalizeManifestUrl(repository.manifestUrl) }.getOrNull()
                        ?.equals(inputUrl, ignoreCase = true) == true
            }
        ) {
            return AddPluginRepositoryResult.Error(getString(Res.string.plugins_repository_already_installed))
        }

        return try {
            val previousById = _uiState.value.scrapers.associateBy { it.id }
            val (repo, scrapers) = fetchRepositoryData(
                inputUrl = inputUrl,
                previousScrapers = previousById,
            )
            _uiState.update { state ->
                state.copy(
                    repositories = state.repositories + repo,
                    scrapers = state.scrapers.filterNot { it.repositoryUrl == repo.manifestUrl } + scrapers,
                )
            }
            persist()
            pushToServer()
            AddPluginRepositoryResult.Success(repo)
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            AddPluginRepositoryResult.Error(error.message ?: getString(Res.string.plugins_repository_install_failed))
        }
    }

    actual fun removeRepository(manifestUrl: String) {
        initialize()
        val removedScrapers = _uiState.value.scrapers
            .filter { it.repositoryUrl == manifestUrl && it.type == PluginRepositoryType.EXTERNAL_DEX }
        if (removedScrapers.isNotEmpty()) {
            // Unloading DEX packages can touch the disk; keep it off the UI thread.
            scope.launch(Dispatchers.IO) {
                removedScrapers.forEach { scraper ->
                    externalExtensionHost.uninstall(scraper.id, currentProfileId)
                }
            }
        }
        _uiState.update { state ->
            state.copy(
                repositories = state.repositories.filterNot { it.manifestUrl == manifestUrl },
                scrapers = state.scrapers.filterNot { it.repositoryUrl == manifestUrl },
            )
        }
        persist()
        pushToServer()
    }

    actual fun refreshAll() {
        initialize()
        _uiState.value.repositories.forEach { repo ->
            refreshRepositoryInternal(repo.manifestUrl, pushAfterRefresh = false, ensureInitialized = false)
        }
    }

    actual fun refreshRepository(manifestUrl: String, pushAfterRefresh: Boolean) {
        refreshRepositoryInternal(manifestUrl, pushAfterRefresh, ensureInitialized = true)
    }

    private fun refreshRepositoryInternal(
        manifestUrl: String,
        pushAfterRefresh: Boolean,
        ensureInitialized: Boolean,
    ) {
        if (ensureInitialized) {
            initialize()
        }
        val existingJob = activeRefreshJobs[manifestUrl]
        if (existingJob?.isActive == true) return

        markRefreshing(manifestUrl)
        var refreshJob: Job? = null
        refreshJob = scope.launch {
            try {
                val result = try {
                    val previous = _uiState.value.scrapers.associateBy { it.id }
                    Result.success(fetchRepositoryData(manifestUrl, previous))
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    Result.failure(error)
                }

                _uiState.update { state ->
                    if (state.repositories.none { it.manifestUrl == manifestUrl }) return@update state
                    result.fold(
                        onSuccess = { (repo, scrapers) ->
                            val updatedRepos = state.repositories.map { existing ->
                                if (existing.manifestUrl == manifestUrl) repo else existing
                            }
                            state.copy(
                                repositories = updatedRepos,
                                scrapers = state.scrapers.filterNot { it.repositoryUrl == manifestUrl } + scrapers,
                            )
                        },
                        onFailure = { error ->
                            state.copy(
                                repositories = state.repositories.map { existing ->
                                    if (existing.manifestUrl == manifestUrl) {
                                        existing.copy(
                                            isRefreshing = false,
                                            errorMessage = error.message ?: runBlocking { getString(Res.string.plugins_repository_refresh_failed) },
                                        )
                                    } else {
                                        existing
                                    }
                                },
                            )
                        },
                    )
                }
                persist()
                if (pushAfterRefresh) {
                    pushToServer()
                }
            } finally {
                if (activeRefreshJobs[manifestUrl] === refreshJob) {
                    activeRefreshJobs.remove(manifestUrl)
                }
            }
        }
        activeRefreshJobs[manifestUrl] = refreshJob
    }

    actual fun toggleScraper(scraperId: String, enabled: Boolean) {
        initialize()
        _uiState.update { state ->
            state.copy(
                scrapers = state.scrapers.map { scraper ->
                    if (scraper.id == scraperId) {
                        scraper.copy(enabled = if (scraper.manifestEnabled) enabled else false)
                    } else {
                        scraper
                    }
                },
            )
        }
        persist()
    }

    actual fun setPluginsEnabled(enabled: Boolean) {
        initialize()
        _uiState.update { it.copy(pluginsEnabled = enabled) }
        persist()
    }

    actual fun setGroupStreamsByRepository(enabled: Boolean) {
        initialize()
        _uiState.update { it.copy(groupStreamsByRepository = enabled) }
        persist()
    }

    actual fun setLocalPluginSearchPaused(paused: Boolean) {
        PluginRuntime.setSearchPaused(paused)
    }

    actual fun getEnabledScrapersForType(type: String): List<PluginScraper> {
        initialize()
        if (!_uiState.value.pluginsEnabled) return emptyList()
        return _uiState.value.scrapers.filter { scraper ->
            scraper.enabled && scraper.supportsType(type)
        }
    }

    actual suspend fun testScraper(
        scraperId: String,
        contentTitle: String?,
        tmdbId: String?,
        useFirstResult: Boolean,
    ): Result<List<PluginRuntimeResult>> {
        initialize()
        val scraper = _uiState.value.scrapers.find { it.id == scraperId }
            ?: return Result.failure(IllegalArgumentException(getString(Res.string.plugins_error_provider_not_found)))

        val mediaType = if (scraper.supportsType("movie")) "movie" else "tv"
        val season = if (mediaType == "tv") 1 else null
        val episode = if (mediaType == "tv") 1 else null
        val sampleTitle = contentTitle?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (mediaType == "movie") "The Matrix" else "Game of Thrones"
        val testTmdbId = tmdbId?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (mediaType == "movie") "603" else "1396"
        return executeScraperInternal(
            scraper = scraper,
            tmdbId = testTmdbId,
            mediaType = mediaType,
            season = season,
            episode = episode,
            respectSearchPause = false,
            contentTitle = sampleTitle,
            useFirstResult = useFirstResult,
        )
    }

    actual suspend fun hasExtensionSettings(scraperId: String): Boolean {
        initialize()
        val scraper = _uiState.value.scrapers.find { it.id == scraperId } ?: return false
        if (scraper.type != PluginRepositoryType.EXTERNAL_DEX) return scraper.hasSettings

        val hasSettings = withContext(Dispatchers.IO) {
            externalExtensionHost.hasSettings(scraperId, currentProfileId)
        }
        if (hasSettings && !scraper.hasSettings) {
            _uiState.update { state ->
                state.copy(
                    scrapers = state.scrapers.map { item ->
                        if (item.id == scraperId) item.copy(hasSettings = true) else item
                    },
                )
            }
            persist()
        }
        return hasSettings
    }

    actual fun openExtensionSettings(scraperId: String) {
        initialize()
        val scraper = _uiState.value.scrapers.find { it.id == scraperId } ?: return
        if (scraper.type == PluginRepositoryType.EXTERNAL_DEX) {
            externalExtensionHost.openSettings(scraperId, currentProfileId)
        }
    }

    actual suspend fun executeScraper(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        contentTitle: String?,
    ): Result<List<PluginRuntimeResult>> = executeScraperInternal(
        scraper = scraper,
        tmdbId = tmdbId,
        mediaType = mediaType,
        season = season,
        episode = episode,
        respectSearchPause = true,
        contentTitle = contentTitle,
    )

    private suspend fun executeScraperInternal(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        respectSearchPause: Boolean,
        contentTitle: String? = null,
        useFirstResult: Boolean = false,
    ): Result<List<PluginRuntimeResult>> {
        val resolvedTmdbId = resolvePluginTmdbId(
            tmdbId = tmdbId,
            mediaType = mediaType,
        )

        return runCatching {
            when (scraper.type) {
                PluginRepositoryType.NUVIO_JS -> PluginRuntime.executePlugin(
                    code = scraper.code,
                    tmdbId = resolvedTmdbId,
                    mediaType = normalizePluginType(mediaType),
                    season = season,
                    episode = episode,
                    scraperId = scraper.id,
                    respectSearchPause = respectSearchPause,
                )

                PluginRepositoryType.EXTERNAL_DEX -> withContext(Dispatchers.IO) {
                    externalExtensionHost.execute(
                        scraperId = scraper.id,
                        profileId = currentProfileId,
                        tmdbId = resolvedTmdbId,
                        mediaType = normalizePluginType(mediaType),
                        season = season,
                        episode = episode,
                        searchPaused = respectSearchPause,
                        contentTitle = contentTitle,
                        useFirstResult = useFirstResult,
                    )
                }
            }
        }
    }

    private suspend fun resolvePluginTmdbId(
        tmdbId: String,
        mediaType: String,
    ): String {
        val trimmed = tmdbId.trim()
        if (trimmed.isBlank()) return tmdbId

        return TmdbService.ensureTmdbId(
            videoId = trimmed,
            mediaType = mediaType,
        ) ?: trimmed
    }

    private suspend fun fetchRepositoryData(
        inputUrl: String,
        previousScrapers: Map<String, PluginScraper>,
    ): Pair<PluginRepositoryItem, List<PluginScraper>> {
        // Explicit CloudStream URLs are tried first so we don't hammer an
        // invalid `<url>/manifest.json` path.
        if (externalExtensionHost.isSupported && looksLikeExplicitExternalRepo(inputUrl)) {
            fetchExternalRepositoryData(inputUrl, previousScrapers)?.let { return it }
        }

        val manifestUrl = runCatching { normalizeManifestUrl(inputUrl) }.getOrNull()
        if (manifestUrl != null) {
            try {
                return fetchNuvioRepositoryData(manifestUrl, previousScrapers)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Fall through to the CloudStream parser.
            }
        }

        if (externalExtensionHost.isSupported) {
            fetchExternalRepositoryData(inputUrl, previousScrapers)?.let { return it }
        }

        error("Failed to parse repository: unrecognized format")
    }

    private suspend fun fetchNuvioRepositoryData(
        manifestUrl: String,
        previousScrapers: Map<String, PluginScraper>,
    ): Pair<PluginRepositoryItem, List<PluginScraper>> {
        val storageProfileId = currentProfileId
        return withContext(Dispatchers.Default) {
            val payload = httpGetText(manifestUrl)
            val manifest = PluginManifestParser.parse(payload)
            val baseUrl = manifestUrl.substringBefore("?").removeSuffix("/manifest.json")

            val scrapers = manifest.scrapers
                .filter { scraper -> scraper.isSupportedOnCurrentPlatform() }
                .mapNotNull { info ->
                    val codeUrl = if (info.filename.startsWith("http://") || info.filename.startsWith("https://")) {
                        info.filename
                    } else {
                        "$baseUrl/${info.filename.trimStart('/')}"
                    }
                    runCatching {
                        val code = httpGetText(codeUrl)
                        val scraperId = "${manifestUrl.lowercase()}:${info.id}"
                        val cached = PluginStorage.saveScraperCode(
                            profileId = storageProfileId,
                            scraperId = scraperId,
                            code = code,
                            overwrite = true,
                        )
                        if (!cached) {
                            log.w { "Failed to cache plugin scraper $scraperId" }
                        }
                        val previous = previousScrapers[scraperId]
                        val enabled = when {
                            !info.enabled -> false
                            previous != null -> previous.enabled
                            else -> info.enabled
                        }

                        PluginScraper(
                            id = scraperId,
                            repositoryUrl = manifestUrl,
                            name = info.name,
                            description = info.description.orEmpty(),
                            version = info.version,
                            filename = info.filename,
                            supportedTypes = info.supportedTypes,
                            enabled = enabled,
                            manifestEnabled = info.enabled,
                            hasSettings = info.hasSettings,
                            logo = info.logo,
                            contentLanguage = info.contentLanguage ?: emptyList(),
                            formats = info.formats ?: info.supportedFormats,
                            code = code,
                        )
                    }.getOrNull()
                }

            val repo = PluginRepositoryItem(
                manifestUrl = manifestUrl,
                name = manifest.name,
                description = manifest.description,
                version = manifest.version,
                scraperCount = scrapers.size,
                lastUpdated = currentEpochMillis(),
                isRefreshing = false,
                errorMessage = null,
                type = PluginRepositoryType.NUVIO_JS,
            )
            repo to scrapers
        }
    }

    private suspend fun fetchExternalRepositoryData(
        inputUrl: String,
        previousScrapers: Map<String, PluginScraper>,
    ): Pair<PluginRepositoryItem, List<PluginScraper>>? {
        val parsed = ExternalRepoParser.tryParse(inputUrl) ?: return null
        val repoUrl = normalizeRepositoryUrl(inputUrl)
        val storageProfileId = currentProfileId

        val scrapers = coroutineScope {
            val semaphore = Semaphore(4)
            parsed.plugins.map { entry ->
                async {
                    semaphore.withPermit {
                        installExternalEntry(
                            entry = entry,
                            repoUrl = repoUrl,
                            profileId = storageProfileId,
                            previousScrapers = previousScrapers,
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (scrapers.isEmpty()) {
            error("Repository contained no installable extensions")
        }

        return PluginRepositoryItem(
            manifestUrl = repoUrl,
            name = parsed.name,
            description = parsed.description,
            version = null,
            scraperCount = scrapers.size,
            lastUpdated = currentEpochMillis(),
            isRefreshing = false,
            errorMessage = null,
            type = PluginRepositoryType.EXTERNAL_DEX,
        ) to scrapers
    }

    private suspend fun installExternalEntry(
        entry: com.nuvio.app.features.plugins.external.ExternalPluginEntry,
        repoUrl: String,
        profileId: Int,
        previousScrapers: Map<String, PluginScraper>,
    ): PluginScraper? {
        val scraperId = "${repoUrl.lowercase()}:${entry.internalName}"
        val previous = previousScrapers[scraperId]
        val alreadyInstalled = previous != null &&
            previous.extensionHash.equals(entry.fileHash, ignoreCase = true) &&
            previous.version == entry.version.toString() &&
            externalExtensionHost.hasExtension(scraperId, profileId)

        if (!alreadyInstalled) {
            val installed = try {
                externalExtensionHost.install(
                    scraperId = scraperId,
                    profileId = profileId,
                    url = entry.url,
                    fileHash = entry.fileHash,
                    expectedSize = entry.fileSize,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
            if (!installed) {
                log.w { "Failed to install CloudStream extension ${entry.internalName}" }
                return null
            }
        }

        return PluginScraper(
            id = scraperId,
            repositoryUrl = repoUrl,
            name = entry.name,
            description = entry.description.orEmpty(),
            version = entry.version.toString(),
            filename = entry.url,
            supportedTypes = cloudStreamContentTypes(entry.tvTypes),
            enabled = previous?.enabled ?: (entry.status == 1),
            manifestEnabled = entry.status == 1,
            hasSettings = previous?.hasSettings ?: false,
            logo = entry.iconUrl,
            contentLanguage = emptyList(),
            formats = null,
            code = "",
            type = PluginRepositoryType.EXTERNAL_DEX,
            extensionHash = entry.fileHash,
        )
    }

    private fun looksLikeExplicitExternalRepo(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("cloudstreamrepo://") || lower.startsWith("cloudstream://")) return true
        if (lower.contains("cs.repo/?")) return true
        val fileName = lower.substringBefore('?').substringAfterLast('/')
        return fileName.endsWith(".json") && !fileName.endsWith("manifest.json")
    }

    private fun normalizeRepositoryUrl(rawUrl: String): String {
        var value = rawUrl.trim()
        require(value.isNotEmpty()) { runBlocking { getString(Res.string.plugins_error_enter_repo_url) } }

        val prefixes = listOf("cloudstreamrepo://", "cloudstream://", "https://cs.repo/?", "http://cs.repo/?")
        for (prefix in prefixes) {
            if (value.startsWith(prefix, ignoreCase = true)) {
                value = value.substring(prefix.length).trim()
                if (!value.contains("://")) value = "https://$value"
                break
            }
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://$value"
        }
        return value.substringBefore('#').trimEnd('/')
    }

    private suspend fun resolveRepositoryInput(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { getString(Res.string.plugins_error_enter_repo_url) }
        if (isRepositoryShortCode(trimmed)) {
            return resolveShortCode(trimmed)
                ?: throw IllegalArgumentException(getString(Res.string.plugins_error_short_code_failed, trimmed))
        }
        return normalizeRepositoryUrl(trimmed)
    }

    /**
     * Resolve `cutt.ly/<code>` to its target repository URL. Mirrors the
     * short-code flow in ReSeq's PluginManager.
     */
    private suspend fun resolveShortCode(code: String): String? {
        val headers = mapOf("User-Agent" to "NuvioTV/1.0")
        val direct = runCatching {
            httpRequestRaw(
                method = "GET",
                url = "https://cutt.ly/$code",
                headers = headers,
                body = "",
                followRedirects = false,
                maxResponseBodyBytes = 64 * 1024,
            )
        }.getOrNull()
        val location = direct?.headers?.get("location")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (location != null) {
            return normalizeRepositoryUrl(location)
        }

        val followed = runCatching {
            httpRequestRaw(
                method = "GET",
                url = "https://cutt.ly/$code",
                headers = headers,
                body = "",
                followRedirects = true,
                maxResponseBodyBytes = 64 * 1024,
            )
        }.getOrNull() ?: return null
        if (followed.status in 200..299 &&
            !followed.url.equals("https://cutt.ly/$code", ignoreCase = true)
        ) {
            return normalizeRepositoryUrl(followed.url)
        }
        return null
    }

    private fun PluginManifestScraper.isSupportedOnCurrentPlatform(): Boolean {
        val platform = currentPluginPlatform().lowercase()
        val supported = supportedPlatforms?.map { it.lowercase() }?.toSet().orEmpty()
        val disabled = disabledPlatforms?.map { it.lowercase() }?.toSet().orEmpty()
        if (supported.isNotEmpty() && platform !in supported) return false
        if (platform in disabled) return false
        return true
    }

    private fun shouldRefreshRepository(
        repository: PluginRepositoryItem,
        scrapers: List<PluginScraper>,
        nowEpochMs: Long,
    ): Boolean = isPluginRepositoryRefreshDue(repository.lastUpdated, nowEpochMs) ||
        scrapers.count { scraper -> scraper.repositoryUrl == repository.manifestUrl } < repository.scraperCount

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { state ->
            state.copy(
                repositories = state.repositories.map { repo ->
                    if (repo.manifestUrl == manifestUrl) {
                        repo.copy(isRefreshing = true, errorMessage = null)
                    } else {
                        repo
                    }
                },
            )
        }
    }

    private fun pushToServer() {
        scope.launch {
            runCatching {
                val repos = _uiState.value.repositories.mapIndexed { index, repo ->
                    PluginPushItem(
                        url = repo.manifestUrl,
                        name = repo.name,
                        enabled = true,
                        sortOrder = index,
                    )
                }

                val params = buildJsonObject {
                    put("p_profile_id", currentProfileId)
                    put("p_plugins", json.encodeToJsonElement(repos))
                }
                SupabaseProvider.client.postgrest.rpc("sync_push_plugins", params)
            }.onFailure { error ->
                log.e(error) { "pushToServer failed" }
            }
        }
    }

    private fun persist() {
        val snapshot = PluginPersistenceSnapshot(
            profileId = currentProfileId,
            generation = persistenceGeneration.value,
            revision = persistenceRevision.incrementAndGet(),
            state = _uiState.value,
        )
        val requiresCodeWrite = snapshot.state.scrapers.any { scraper ->
            scraper.type == PluginRepositoryType.NUVIO_JS &&
                !PluginStorage.hasScraperCode(snapshot.profileId, scraper.id)
        }
        if (requiresCodeWrite) {
            scope.launch { persist(snapshot) }
        } else {
            persist(snapshot)
        }
    }

    private fun persist(snapshot: PluginPersistenceSnapshot) {
        if (snapshot.generation != persistenceGeneration.value) return
        var cached = true
        snapshot.state.scrapers.forEach { scraper ->
            if (scraper.type != PluginRepositoryType.NUVIO_JS) return@forEach
            val scraperCached = PluginStorage.saveScraperCode(
                profileId = snapshot.profileId,
                scraperId = scraper.id,
                code = scraper.code,
                overwrite = false,
            )
            cached = scraperCached && cached
        }
        if (!cached || snapshot.generation != persistenceGeneration.value) {
            if (!cached) {
                log.w { "Failed to persist plugin scraper cache for profile ${snapshot.profileId}" }
            }
            return
        }

        synchronized(persistenceLock) {
            if (snapshot.generation != persistenceGeneration.value) return@synchronized
            val persistedRevision = persistedRevisionByProfile[snapshot.profileId] ?: Long.MIN_VALUE
            if (snapshot.revision < persistedRevision) return@synchronized
            val payload = snapshot.state.toStoredPluginsState()
            PluginStorage.saveState(snapshot.profileId, json.encodeToString(payload))
            persistedRevisionByProfile[snapshot.profileId] = snapshot.revision
        }
    }

    private fun loadStoredState(profileId: Int): StoredPluginsState? {
        val raw = PluginStorage.loadState(profileId)?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            json.decodeFromString<StoredPluginsState>(raw)
        }.getOrNull()
    }

    private fun cancelActiveRefreshes() {
        activeRefreshJobs.values.forEach(Job::cancel)
        activeRefreshJobs.clear()
    }

    private fun ensureStateLoadedForProfile(profileId: Int) {
        if (initialized && currentProfileId == profileId) return

        if (currentProfileId != profileId) {
            cancelActiveRefreshes()
        }

        currentProfileId = profileId
        val loadedState = loadStateAsUiState(profileId)
        _uiState.value = loadedState.state
        initialized = true
        if (loadedState.requiresMigration) persist()
    }

    private fun loadStateAsUiState(profileId: Int): LoadedPluginState {
        val stored = loadStoredState(profileId)
        var requiresMigration = false
        val scrapers = stored?.scrapers
            ?.mapNotNull { storedScraper ->
                storedScraper.restorePluginScraper { scraperId ->
                    PluginStorage.loadScraperCode(profileId, scraperId)
                }?.also { restored ->
                    requiresMigration = requiresMigration || restored.requiresMigration
                }?.scraper
            }
            ?: emptyList()
        return LoadedPluginState(
            state = PluginsUiState(
                pluginsEnabled = stored?.pluginsEnabled ?: true,
                groupStreamsByRepository = stored?.groupStreamsByRepository ?: false,
                repositories = stored?.repositories
                    ?.map {
                        PluginRepositoryItem(
                            manifestUrl = it.manifestUrl,
                            name = it.name,
                            description = it.description,
                            version = it.version,
                            scraperCount = it.scraperCount,
                            lastUpdated = it.lastUpdated,
                            isRefreshing = false,
                            errorMessage = null,
                            type = it.type,
                        )
                    }
                    ?: emptyList(),
                scrapers = scrapers,
            ),
            requiresMigration = requiresMigration,
        )
    }

    private fun dedupeManifestUrls(urls: List<String>): List<String> =
        urls.map(::ensureManifestSuffix).distinct()

    private fun ensureManifestSuffix(url: String): String {
        val path = url.substringBefore("?").trimEnd('/')
        val query = url.substringAfter("?", "")
        val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
        return if (query.isEmpty()) withSuffix else "$withSuffix?$query"
    }

    private fun normalizeManifestUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        require(trimmed.isNotEmpty()) { runBlocking { getString(Res.string.plugins_error_enter_repo_url) } }

        val normalizedScheme = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }

        val withoutFragment = normalizedScheme.substringBefore("#")
        val query = withoutFragment.substringAfter("?", "")
        val path = withoutFragment.substringBefore("?").trimEnd('/')
        val manifestPath = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
        return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && !active.id.isBlank() && active.usesPrimaryPlugins) 1 else profileId
    }
}

/** A short code is an alphanumeric token such as `cspr` or `0094`, not a URL or domain. */
internal fun isRepositoryShortCode(input: String): Boolean {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.contains("://") || trimmed.contains('/') || trimmed.contains('.')) return false
    return trimmed.length in 2..64 && trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
