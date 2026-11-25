package com.nuvio.app.features.plugins.external

import android.util.Log
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginSubtitleResult
import com.nuvio.app.features.tmdb.TmdbMetadataService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

private const val TAG = "CloudStreamRunner"
private const val EXECUTION_TIMEOUT_MS = 120_000L
private const val LOAD_LINKS_TIMEOUT_MS = 60_000L
private const val HOME_TIMEOUT_MS = 90_000L
private const val MIN_TITLE_SIMILARITY = 0.5

/**
 * Some extensions resolve extractor links in detached coroutines and call the callback after
 * loadLinks() has returned (Goojara does this). Keep the collector open until the link list has
 * been quiet for a moment, otherwise those providers look like they returned zero links.
 */
private const val LATE_LINK_GRACE_MS = 3_500L
private const val LATE_LINK_QUIET_AFTER_RESULT_MS = 600L
private const val LATE_LINK_WAIT_CAP_MS = 12_000L

/**
 * Some extensions leave their "latest" tab empty (FStream's Tendances is one). When the
 * default feed has no items, probe the remaining tabs for a few seconds each and use the
 * first one that returns something. The budget stays small so Lucid TV's pool wait and the
 * browse screen's first paint are not held up indefinitely.
 */
private const val TAB_FALLBACK_WINDOW_MS = 25_000L
private const val TAB_PROBE_TIMEOUT_MS = 12_000L
private const val MAX_TAB_FALLBACK_PROBES = 16

private fun Throwable.isFatalExtensionError(): Boolean =
    this is kotlinx.coroutines.CancellationException ||
        this is VirtualMachineError ||
        this is ThreadDeath

/**
 * Bridges the app's TMDB-id model to CloudStream's text/URL provider API.
 *
 * Flow: TMDB id -> localized title/year -> search() -> best match -> load() ->
 * loadLinks() -> [PluginRuntimeResult]. `TmdbProvider` packages get the JSON
 * payload they expect first, with a TMDB URL as fallback.
 */
/**
 * Categories a provider actually offers. Some extensions declare tabs without setting
 * `hasMainPage`, so honour real tabs instead of treating the provider as search-only.
 */
internal fun cloudStreamBrowseTabs(api: MainAPI): List<MainPageData> {
    val tabs = try {
        api.mainPage.orEmpty()
    } catch (error: Throwable) {
        if (error.isFatalExtensionError()) throw error
        emptyList()
    }
    val named = tabs.filter { it.name.isNotBlank() || it.data.isNotBlank() }
    if (named.isNotEmpty()) return named
    return if (runCatching { api.hasMainPage }.getOrDefault(false)) tabs else emptyList()
}

private data class HomePage(
    val apiIndex: Int,
    val api: MainAPI,
    val tab: MainPageData,
    val items: List<PluginBrowseItem>,
    val hasNext: Boolean,
)

private data class FallbackHome(
    val category: PluginBrowseCategory,
    val tab: MainPageData,
    val items: List<PluginBrowseItem>,
    val hasNext: Boolean,
)

private fun browseCategory(
    apiIndex: Int,
    api: MainAPI,
    tab: MainPageData,
    providerCount: Int,
): PluginBrowseCategory {
    val label = tab.name.ifBlank { tab.data }
    return PluginBrowseCategory(
        apiIndex = apiIndex,
        apiName = api.name,
        tabName = label,
        label = if (providerCount > 1) "${api.name} · $label" else label,
    )
}

private fun tabKey(tab: MainPageData): String = tab.name.ifBlank { tab.data }

private fun buildBrowseCategories(
    apis: List<MainAPI>,
    tabsByApi: List<List<MainPageData>>,
): List<PluginBrowseCategory> = buildList {
    apis.forEachIndexed { index, api ->
        tabsByApi.getOrNull(index).orEmpty().forEach { tab ->
            if (tabKey(tab).isBlank()) return@forEach
            add(browseCategory(index, api, tab, apis.size))
        }
    }
}.distinctBy { it.key }

internal class CloudStreamExtensionRunner(
    private val loader: CloudStreamExtensionLoader,
) {
    /**
     * The tab each provider used on its last successful page. Without this, page 2 of an
     * extension whose "latest" tab is empty would silently switch back to that empty tab.
     */
    private val selectedTabs = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun tabCacheKey(profileId: Int, scraperId: String, apiIndex: Int): String =
        "$profileId:$scraperId:$apiIndex"
    suspend fun execute(
        scraperId: String,
        profileId: Int,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        contentTitle: String? = null,
        useFirstResult: Boolean = false,
    ): List<PluginRuntimeResult> {
        val apis = loader.loadExtension(scraperId, profileId)
        if (apis.isEmpty()) return emptyList()

        val results = coroutineScope {
            val semaphore = Semaphore(3)
            apis.map { api ->
                async {
                    semaphore.withPermit {
                        try {
                            withTimeoutOrNull(EXECUTION_TIMEOUT_MS) {
                                executeApi(api, tmdbId, mediaType, season, episode, contentTitle, useFirstResult)
                            } ?: emptyList()
                        } catch (e: Throwable) {
                            if (e.isFatalExtensionError()) throw e
                            Log.w(TAG, "Provider ${api.name} failed: ${e.javaClass.simpleName}: ${e.message}")
                            emptyList()
                        }
                    }
                }
            }.awaitAll()
        }
        return results.flatten().distinctBy { it.url }
    }

    private suspend fun executeApi(
        api: MainAPI,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        contentTitle: String?,
        useFirstResult: Boolean,
    ): List<PluginRuntimeResult> {
        if (api is TmdbProvider && tmdbId.toIntOrNull() != null) {
            return executeTmdbProvider(api, tmdbId, mediaType, season, episode)
        }
        return executeSearchBased(api, tmdbId, mediaType, season, episode, contentTitle, useFirstResult)
    }

    /** Browse-mode search: returns the provider's own results, with no TMDB matching. */
    suspend fun search(
        scraperId: String,
        profileId: Int,
        query: String,
    ): List<PluginBrowseItem> {
        val apis = loader.loadExtension(scraperId, profileId)
        if (apis.isEmpty()) return emptyList()
        return coroutineScope {
            apis.mapIndexed { index, api ->
                async {
                    try {
                        api.search(query, 1)?.items.orEmpty()
                            .map { result -> result.toBrowseItem(index, api.name) }
                    } catch (e: Throwable) {
                        if (e.isFatalExtensionError()) throw e
                        Log.d(TAG, "${api.name}: browse search failed: ${e.javaClass.simpleName}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten().distinctBy { it.apiIndex to it.url }
        }
    }

    /**
     * Catalogs/tabs exposed by the extension. Loading the package is enough; no page is
     * fetched, so the Lucid TV source picker can show a catalog list immediately.
     */
    suspend fun categories(
        scraperId: String,
        profileId: Int,
    ): List<PluginBrowseCategory> {
        val apis = loader.loadExtension(scraperId, profileId)
        if (apis.isEmpty()) return emptyList()
        return buildBrowseCategories(apis, apis.map { cloudStreamBrowseTabs(it) })
    }

    /**
     * Provider home/latest feed. Uses the first (or explicitly "latest") tab
     * exposed by the extension; returns null when the provider has no feed.
     *
     * When the default tab is empty, the loader probes the provider's other tabs so
     * extensions that leave "latest" blank still populate Lucid TV and browse rows.
     */
    suspend fun latest(
        scraperId: String,
        profileId: Int,
        page: Int,
        category: PluginBrowseCategory? = null,
    ): PluginLatestPage? {
        val apis = loader.loadExtension(scraperId, profileId)
        if (apis.isEmpty()) return null

        val tabsByApi = apis.map { cloudStreamBrowseTabs(it) }
        val categories = buildBrowseCategories(apis, tabsByApi)

        val targets = if (category != null) {
            val api = apis.getOrNull(category.apiIndex) ?: return null
            val tab = tabsByApi.getOrNull(category.apiIndex).orEmpty()
                .firstOrNull { it.name == category.tabName || (it.name.isBlank() && it.data == category.tabName) }
                ?: return null
            listOf(Triple(category.apiIndex, api, tab))
        } else {
            apis.mapIndexedNotNull { index, api ->
                val tabs = tabsByApi[index]
                val remembered = selectedTabs[tabCacheKey(profileId, scraperId, index)]
                val tab = tabs.firstOrNull { tabKey(it) == remembered }
                    ?: tabs.firstOrNull {
                        it.name.contains("latest", ignoreCase = true) ||
                            it.data.contains("latest", ignoreCase = true)
                    }
                    ?: tabs.firstOrNull()
                    ?: return@mapIndexedNotNull null
                Triple(index, api, tab)
            }
        }
        if (categories.isNotEmpty() || targets.isNotEmpty()) {
            Log.d(
                TAG,
                "browse tabs: " + apis.mapIndexed { index, api ->
                    "${api.name}(has=${runCatching { api.hasMainPage }.getOrDefault(false)}, tabs=${tabsByApi[index].size})"
                }.joinToString(),
            )
        }

        var homes = coroutineScope {
            targets.map { (index, api, tab) ->
                async { fetchHomePage(index, api, tab, page, HOME_TIMEOUT_MS) }
            }.awaitAll().filterNotNull()
        }
        var activeCategory = if (apis.size == 1) {
            homes
                .filter { it.items.isNotEmpty() }
                .takeIf { it.size == 1 }
                ?.first()
                ?.let { browseCategory(it.apiIndex, it.api, it.tab, apis.size) }
        } else {
            null
        }

        // "Latest" is optional. FStream, for example, returns zero items for Tendances.
        // Probe the remaining tabs instead of leaving Lucid TV with an empty pool.
        if (homes.none { it.items.isNotEmpty() } && category == null && page == 1) {
            findFallbackHome(apis, tabsByApi, targets)?.let { fallback ->
                homes = listOf(
                    HomePage(
                        apiIndex = fallback.category.apiIndex,
                        api = apis[fallback.category.apiIndex],
                        tab = fallback.tab,
                        items = fallback.items,
                        hasNext = fallback.hasNext,
                    ),
                )
                activeCategory = fallback.category
            }
        }

        homes
            .filter { it.items.isNotEmpty() }
            .forEach { home ->
                selectedTabs[tabCacheKey(profileId, scraperId, home.apiIndex)] = tabKey(home.tab)
            }

        return PluginLatestPage(
            items = homes.flatMap { it.items }.distinctBy { it.apiIndex to it.url },
            hasNext = homes.any { it.hasNext },
            categories = categories,
            activeCategory = activeCategory,
        )
    }

    private suspend fun fetchHomePage(
        apiIndex: Int,
        api: MainAPI,
        tab: MainPageData,
        page: Int,
        timeoutMs: Long,
    ): HomePage? {
        return try {
            val request = MainPageRequest(tab.name, tab.data, tab.horizontalImages)
            val response = withTimeoutOrNull(timeoutMs) {
                api.getMainPage(page, request)
            } ?: run {
                Log.d(TAG, "${api.name}/${tab.name}: home page $page timed out")
                return null
            }
            val items = response.items
                .flatMap { list -> list.list.map { it.toBrowseItem(apiIndex, api.name) } }
                .distinctBy { it.apiIndex to it.url }
            Log.d(
                TAG,
                "${api.name}/${tab.name}: home page $page -> ${items.size} items, next=${response.hasNext}",
            )
            HomePage(apiIndex, api, tab, items, response.hasNext)
        } catch (e: Throwable) {
            if (e.isFatalExtensionError()) throw e
            Log.d(TAG, "${api.name}/${tab.name}: home page $page failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun findFallbackHome(
        apis: List<MainAPI>,
        tabsByApi: List<List<MainPageData>>,
        attempted: List<Triple<Int, MainAPI, MainPageData>>,
    ): FallbackHome? {
        val attemptedKeys = attempted.mapTo(HashSet()) { (index, _, tab) -> index to tabKey(tab) }
        val deadline = System.currentTimeMillis() + TAB_FALLBACK_WINDOW_MS
        var probes = 0
        for ((index, api) in apis.withIndex()) {
            for (tab in tabsByApi.getOrNull(index).orEmpty()) {
                val key = tabKey(tab)
                if (key.isBlank() || !attemptedKeys.add(index to key)) continue
                if (probes++ >= MAX_TAB_FALLBACK_PROBES) return null
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) return null
                val home = fetchHomePage(
                    apiIndex = index,
                    api = api,
                    tab = tab,
                    page = 1,
                    timeoutMs = minOf(remaining, TAB_PROBE_TIMEOUT_MS),
                ) ?: continue
                if (home.items.isNotEmpty()) {
                    return FallbackHome(
                        category = browseCategory(index, api, tab, apis.size),
                        tab = tab,
                        items = home.items,
                        hasNext = home.hasNext,
                    )
                }
            }
        }
        return null
    }

    private fun SearchResponse.toBrowseItem(apiIndex: Int, apiName: String): PluginBrowseItem =
        PluginBrowseItem(
            apiIndex = apiIndex,
            apiName = apiName,
            name = name,
            url = url,
            posterUrl = posterUrl,
            type = browseType(),
            year = browseYear(),
        )

    /** Browse-mode metadata: a movie gets a data string; a series gets episodes. */
    suspend fun loadDetail(
        scraperId: String,
        profileId: Int,
        apiIndex: Int,
        url: String,
    ): PluginBrowseDetail? {
        val api = loader.loadExtension(scraperId, profileId).getOrNull(apiIndex) ?: return null
        val response = try {
            api.load(url)
        } catch (e: Throwable) {
            if (e.isFatalExtensionError()) throw e
            Log.d(TAG, "${api.name}: browse load failed: ${e.javaClass.simpleName}")
            null
        } ?: return null

        return when (response) {
            is MovieLoadResponse -> response.toBrowseDetail(
                apiIndex = apiIndex,
                apiName = api.name,
                isSeries = false,
                movieData = response.dataUrl,
                episodes = emptyList(),
            )

            is LiveStreamLoadResponse -> response.toBrowseDetail(
                apiIndex = apiIndex,
                apiName = api.name,
                isSeries = false,
                movieData = response.dataUrl,
                episodes = emptyList(),
            )

            is TvSeriesLoadResponse -> response.toBrowseDetail(
                apiIndex = apiIndex,
                apiName = api.name,
                isSeries = true,
                movieData = null,
                episodes = response.episodes.map { episode ->
                    PluginBrowseEpisode(
                        season = episode.season,
                        episode = episode.episode,
                        name = episode.name.orEmpty(),
                        data = episode.data,
                        posterUrl = episode.posterUrl,
                        description = episode.description,
                        runtimeMinutes = episode.runTime,
                    )
                },
            )

            is AnimeLoadResponse -> response.toBrowseDetail(
                apiIndex = apiIndex,
                apiName = api.name,
                isSeries = true,
                movieData = null,
                episodes = response.episodes.values.flatten().map { episode ->
                    PluginBrowseEpisode(
                        season = episode.season,
                        episode = episode.episode,
                        name = episode.name.orEmpty(),
                        data = episode.data,
                        posterUrl = episode.posterUrl,
                        description = episode.description,
                        runtimeMinutes = episode.runTime,
                    )
                },
            )

            else -> null
        }
    }

    private fun LoadResponse.toBrowseDetail(
        apiIndex: Int,
        apiName: String,
        isSeries: Boolean,
        movieData: String?,
        episodes: List<PluginBrowseEpisode>,
    ): PluginBrowseDetail = PluginBrowseDetail(
        apiIndex = apiIndex,
        name = name,
        posterUrl = posterUrl,
        description = plot,
        isSeries = isSeries,
        movieData = movieData,
        episodes = episodes,
        backgroundUrl = backgroundPosterUrl,
        tags = tags.orEmpty(),
        durationMinutes = duration,
        contentRating = contentRating,
        actors = actors.orEmpty().mapNotNull { actorData ->
            actorData.actor?.let { actor ->
                PluginBrowseActor(
                    name = actor.name,
                    imageUrl = actor.image,
                    role = actorData.roleString,
                )
            }
        },
        trailers = trailers.orEmpty().mapNotNull { trailer ->
            trailer.extractorUrl?.takeIf { it.isNotBlank() }?.let { url ->
                PluginBrowseTrailer(url = url, headers = trailer.headers.orEmpty())
            }
        },
        recommendations = recommendations.orEmpty()
            .filter { it.url != url }
            .distinctBy { it.url }
            .map { it.toBrowseItem(apiIndex, apiName) },
        comingSoon = comingSoon,
        status = (this as? TvSeriesLoadResponse)?.showStatus?.name
            ?: (this as? AnimeLoadResponse)?.showStatus?.name,
    )

    /** Resolve links for a browse movie data string or selected episode. */
    suspend fun loadStreams(
        scraperId: String,
        profileId: Int,
        apiIndex: Int,
        data: String,
        season: Int?,
        episode: Int?,
        timeoutMs: Long? = null,
    ): List<PluginRuntimeResult> {
        val api = loader.loadExtension(scraperId, profileId).getOrNull(apiIndex) ?: return emptyList()
        val links = loadLinks(api, data, timeoutMs ?: LOAD_LINKS_TIMEOUT_MS)
        if (links.isNotEmpty()) return links
        // Live providers often return the playable HLS/DASH URL as the load() data and rely
        // on an extractor to echo it back. If that extractor is missing or fails, keep the
        // direct URL instead of reporting a dead channel.
        return listOfNotNull(directMediaResult(api.name, data))
    }

    private fun directMediaResult(provider: String, data: String): PluginRuntimeResult? {
        val url = data.trim()
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val type = when {
            path.endsWith(".m3u8") -> "hls"
            path.endsWith(".mpd") -> "dash"
            listOf(".mp4", ".mkv", ".ts", ".flv", ".webm", ".mp3").any { path.endsWith(it) } -> null
            else -> return null
        }
        return PluginRuntimeResult(
            title = "Direct",
            name = "Direct",
            url = url,
            type = type,
            provider = provider,
        )
    }

    private fun SearchResponse.browseType(): String = when (type?.name) {
        "TvSeries", "Anime", "OVA", "Cartoon", "AsianDrama" -> "tv"
        else -> "movie"
    }

    private fun SearchResponse.browseYear(): Int? = when (this) {
        is MovieSearchResponse -> year
        is TvSeriesSearchResponse -> year
        is AnimeSearchResponse -> year
        else -> null
    }

    private suspend fun executeTmdbProvider(
        api: MainAPI,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): List<PluginRuntimeResult> {
        val numericId = tmdbId.toIntOrNull() ?: return emptyList()
        val type = if (mediaType.equals("movie", ignoreCase = true)) "movie" else "tv"
        val json = """{"id":$numericId,"type":"$type"}"""

        val direct = runCatching { api.load(json) }.getOrNull()
        val directData = direct?.let { extractData(it, season, episode) }
        if (directData != null) {
            return loadLinks(api, directData)
        }

        val tmdbUrl = "https://www.themoviedb.org/$type/$numericId"
        val fallback = runCatching { api.load(tmdbUrl) }.getOrNull()
        val fallbackData = fallback?.let { extractData(it, season, episode) }
        return if (fallbackData != null) loadLinks(api, fallbackData) else emptyList()
    }

    private suspend fun executeSearchBased(
        api: MainAPI,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        contentTitle: String?,
        useFirstResult: Boolean,
    ): List<PluginRuntimeResult> {
        val hint = contentTitle?.trim()?.takeIf { it.isNotEmpty() }
        val enrichment = if (hint == null) {
            tmdbId.takeIf { it.toIntOrNull() != null }?.let {
                runCatching { TmdbMetadataService.enrichmentForExtension(it, mediaType) }.getOrNull()
            }
        } else {
            null
        }
        val title = hint
            ?: enrichment?.localizedTitle?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val year = enrichment?.releaseInfo?.take(4)?.toIntOrNull()
        val candidates = buildList {
            add(title)
            enrichment?.originalTitle
                ?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
                ?.let(::add)
        }

        var searchResults = trySearch(api, title)
        if (searchResults.isNullOrEmpty() && title.contains(Regex("[:\\-–—]"))) {
            val simplified = title.replace(Regex("[:\\-–—]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            searchResults = trySearch(api, simplified)
        }
        if (searchResults.isNullOrEmpty()) {
            for (candidate in candidates.drop(1)) {
                searchResults = trySearch(api, candidate)
                if (!searchResults.isNullOrEmpty()) break
            }
        }
        if (searchResults.isNullOrEmpty()) return emptyList()
        Log.d(TAG, "${api.name}: ${searchResults.size} search results for '$title'")

        val best = if (useFirstResult) {
            searchResults.firstOrNull()
        } else {
            findBestMatch(searchResults, candidates, year, mediaType)
        }
        if (best == null) {
            Log.d(TAG, "${api.name}: no title matched '$title' among ${searchResults.size} results")
            return emptyList()
        }
        Log.d(TAG, "${api.name}: using '${best.name}' for '$title'")
        val loadResponse = runCatching { api.load(best.url) }.getOrNull() ?: return emptyList()
        val data = extractData(loadResponse, season, episode) ?: return emptyList()
        return loadLinks(api, data)
    }

    private suspend fun trySearch(api: MainAPI, query: String): List<SearchResponse>? = try {
        api.search(query, 1)?.items
    } catch (e: Throwable) {
        if (e.isFatalExtensionError()) throw e
        Log.d(TAG, "search(\"$query\") failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun extractData(response: LoadResponse, season: Int?, episode: Int?): String? = when (response) {
        is MovieLoadResponse -> response.dataUrl
        is LiveStreamLoadResponse -> response.dataUrl
        is TvSeriesLoadResponse -> findEpisode(response.episodes, season, episode)?.data
        is AnimeLoadResponse -> {
            val allEpisodes = response.episodes.values.flatten()
            findEpisode(allEpisodes, season, episode)?.data
        }
        else -> null
    }

    private fun findEpisode(episodes: List<Episode>, season: Int?, episode: Int?): Episode? {
        val index = matchExtensionEpisodeIndex(
            entries = episodes.map { it.season to it.episode },
            season = season,
            episode = episode,
        ) ?: return null
        return episodes.getOrNull(index)
    }

    private suspend fun loadLinks(
        api: MainAPI,
        data: String,
        timeoutMs: Long = LOAD_LINKS_TIMEOUT_MS,
    ): List<PluginRuntimeResult> {
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subtitles = Collections.synchronizedList(mutableListOf<SubtitleFile>())

        val completed = withTimeoutOrNull(timeoutMs) {
            var callCompleted = false
            try {
                api.loadLinks(
                    data = data,
                    isCasting = false,
                    subtitleCallback = { subtitles.add(it) },
                    callback = { links.add(it) },
                )
                callCompleted = true
            } catch (e: Throwable) {
                if (e.isFatalExtensionError()) throw e
                Log.w(TAG, "loadLinks failed: ${e.javaClass.simpleName}: ${e.message} (${links.size} links)")
            }

            // Keep collecting detached extractor callbacks for a short window. If the
            // provider already produced links, wait only for a quiet beat; if it produced
            // none, give detached resolvers more time before declaring the item dead.
            val quietMs = if (links.isNotEmpty()) {
                LATE_LINK_QUIET_AFTER_RESULT_MS
            } else {
                LATE_LINK_GRACE_MS
            }
            val capAt = System.currentTimeMillis() + LATE_LINK_WAIT_CAP_MS
            var lastCount = links.size + subtitles.size
            var lastChangeAt = System.currentTimeMillis()
            while (true) {
                delay(100)
                val now = System.currentTimeMillis()
                val count = links.size + subtitles.size
                if (count != lastCount) {
                    lastCount = count
                    lastChangeAt = now
                } else if (now - lastChangeAt >= quietMs) {
                    break
                }
                if (now >= capAt) break
            }
            if (links.isNotEmpty() || !callCompleted) {
                Log.d(
                    TAG,
                    "${api.name}: collected ${links.size} links after loadLinks returned (completed=$callCompleted)",
                )
            }
            callCompleted
        }
        if (completed == null) {
            Log.w(TAG, "loadLinks timed out after ${timeoutMs}ms (${links.size} links collected)")
        }
        Log.d(TAG, "${api.name}: ${links.size} links, ${subtitles.size} subtitles completed=$completed")
        val domainSubtitles = subtitles.mapNotNull { file ->
            val url = file.url.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val language = file.lang.takeIf { it.isNotBlank() } ?: "Unknown"
            PluginSubtitleResult(
                url = url,
                language = language,
                name = null,
                headers = subtitleHeaders(file),
            )
        }

        return links
            .filter { link ->
                val url = link.url
                url.startsWith("http://") || url.startsWith("https://")
            }
            .map { link -> link.toResult(api.name, domainSubtitles) }
    }

    private fun subtitleHeaders(file: SubtitleFile): Map<String, String>? = runCatching {
        val method = file.javaClass.methods.find { it.name == "getHeaders" }
        @Suppress("UNCHECKED_CAST")
        (method?.invoke(file) as? Map<String, String>)?.ifEmpty { null }
    }.getOrNull()

    private fun ExtractorLink.toResult(
        provider: String,
        subtitles: List<PluginSubtitleResult>,
    ): PluginRuntimeResult {
        val streamType = when (type) {
            ExtractorLinkType.M3U8 -> "hls"
            ExtractorLinkType.DASH -> "dash"
            else -> null
        }
        val allHeaders = buildMap {
            putAll(headers)
            if (referer.isNotBlank()) put("Referer", referer)
        }
        return PluginRuntimeResult(
            title = name,
            name = source,
            url = url,
            quality = Qualities.getStringByInt(quality).ifEmpty { null },
            type = streamType,
            headers = allHeaders.ifEmpty { null },
            provider = provider,
            subtitles = subtitles.ifEmpty { null },
        )
    }

    private fun findBestMatch(
        results: List<SearchResponse>,
        candidateTitles: List<String>,
        targetYear: Int?,
        mediaType: String,
    ): SearchResponse? {
        val isMovie = mediaType.equals("movie", ignoreCase = true)
        val movieTypes = setOf("Movie", "AnimeMovie", "Documentary", "Torrent")
        val tvTypes = setOf("TvSeries", "Anime", "OVA", "Cartoon", "AsianDrama")
        val catchAllTvTypes = setOf("Anime", "OVA", "AsianDrama")

        return results.mapNotNull { result ->
            val typeName = result.type?.name
            val resultYear = when (result) {
                is MovieSearchResponse -> result.year
                is TvSeriesSearchResponse -> result.year
                is AnimeSearchResponse -> result.year
                else -> null
            }
            val similarity = candidateTitles.maxOfOrNull { calculateSimilarity(result.name, it) } ?: 0.0
            val exactTitle = similarity >= 0.95

            if (typeName != null && !exactTitle) {
                val typeOk = if (isMovie) typeName in movieTypes else typeName in tvTypes
                if (!typeOk) return@mapNotNull null
            }
            if (!isMovie && typeName in catchAllTvTypes && similarity < 0.9) {
                return@mapNotNull null
            }
            if (targetYear != null && resultYear != null && kotlin.math.abs(targetYear - resultYear) > 1) {
                return@mapNotNull null
            }

            val yearBonus = if (targetYear != null && resultYear == targetYear) 0.15 else 0.0
            val typeBonus = when {
                typeName == null -> 0.0
                isMovie && typeName in movieTypes -> 0.05
                !isMovie && typeName in tvTypes -> 0.05
                else -> 0.0
            }
            result to (similarity + yearBonus + typeBonus)
        }
            .filter { it.second >= MIN_TITLE_SIMILARITY }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun calculateSimilarity(left: String, right: String): Double {
        val a = left.lowercase().trim()
        val b = right.lowercase().trim()
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val normalizedA = normalizeTitle(a)
        val normalizedB = normalizeTitle(b)
        if (normalizedA == normalizedB) return 0.95
        if (normalizedA.isEmpty() || normalizedB.isEmpty()) return 0.0

        if (normalizedA.contains(normalizedB) || normalizedB.contains(normalizedA)) {
            val shortLength = minOf(normalizedA.length, normalizedB.length).toDouble()
            val longLength = maxOf(normalizedA.length, normalizedB.length).toDouble()
            if (shortLength / longLength >= 0.8) return 0.85
        }

        val distance = levenshtein(normalizedA, normalizedB)
        val maxLength = maxOf(normalizedA.length, normalizedB.length)
        return 1.0 - (distance.toDouble() / maxLength)
    }

    private fun normalizeTitle(value: String): String = value
        .replace(Regex("\\(\\d{4}\\)"), " ")
        .replace(Regex("\\b\\d{4}\\b"), " ")
        .replace(Regex("\\b(temporada|season)\\s*\\d+\\b"), " ")
        .replace(Regex("\\b[st]\\d{1,2}\\b"), " ")
        .replace(Regex("\\b(part|parte)\\s*\\d+\\b"), " ")
        .replace(
            Regex("\\b(latino|castellano|subtitulado|sub\\s*espa(ñ|n)ol|espa(ñ|n)ol|dual|vose|vostfr|subbed|dubbed)\\b"),
            " ",
        )
        .replace(Regex("[:\\-–—]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun levenshtein(left: String, right: String): Int {
        val m = left.length
        val n = right.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost,
                )
            }
        }
        return dp[m][n]
    }
}
