package com.nuvio.app.features.plugins.external

import android.util.Log
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
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
 * Bridges the app's TMDB-id model to CloudStream's text/URL provider API.
 *
 * Flow: TMDB id -> localized title/year -> search() -> best match -> load() ->
 * loadLinks() -> [PluginRuntimeResult]. `TmdbProvider` packages get the JSON
 * payload they expect first, with a TMDB URL as fallback.
 */
internal class CloudStreamExtensionRunner(
    private val loader: CloudStreamExtensionLoader,
) {
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
                        } catch (e: Exception) {
                            Log.w(TAG, "Provider ${api.name} failed: ${e.javaClass.simpleName}: ${e.message}")
                            emptyList()
                        } catch (e: LinkageError) {
                            Log.w(TAG, "Provider ${api.name} linkage error: ${e.javaClass.simpleName}: ${e.message}")
                            emptyList()
                        } catch (e: NotImplementedError) {
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
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "${api.name}: browse search failed: ${e.javaClass.simpleName}")
                        emptyList()
                    } catch (e: LinkageError) {
                        Log.d(TAG, "${api.name}: browse search linkage error")
                        emptyList()
                    } catch (e: NotImplementedError) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten().distinctBy { it.apiIndex to it.url }
        }
    }

    /**
     * Provider home/latest feed. Uses the first (or explicitly "latest") tab
     * exposed by the extension; returns null when the provider has no feed.
     */
    suspend fun latest(
        scraperId: String,
        profileId: Int,
        page: Int,
        category: PluginBrowseCategory? = null,
    ): PluginLatestPage? {
        val apis = loader.loadExtension(scraperId, profileId)
        if (apis.isEmpty()) return null

        val categories = buildList {
            apis.forEachIndexed { index, api ->
                if (!api.hasMainPage) return@forEachIndexed
                api.mainPage.orEmpty().forEach { tab ->
                    add(
                        PluginBrowseCategory(
                            apiIndex = index,
                            apiName = api.name,
                            tabName = tab.name,
                            label = if (apis.size > 1) "${api.name} · ${tab.name}" else tab.name,
                        ),
                    )
                }
            }
        }.distinctBy { it.key }

        val targets = if (category != null) {
            val api = apis.getOrNull(category.apiIndex) ?: return null
            val tab = api.mainPage.orEmpty().firstOrNull { it.name == category.tabName } ?: return null
            listOf(Triple(category.apiIndex, api, tab))
        } else {
            apis.mapIndexedNotNull { index, api ->
                if (!api.hasMainPage) return@mapIndexedNotNull null
                val tabs = api.mainPage.orEmpty()
                val tab = tabs.firstOrNull { it.name.contains("latest", ignoreCase = true) }
                    ?: tabs.firstOrNull()
                    ?: return@mapIndexedNotNull null
                Triple(index, api, tab)
            }
        }

        val homes = coroutineScope {
            targets.map { (index, api, tab) ->
                async {
                    try {
                        val request = MainPageRequest(tab.name, tab.data, tab.horizontalImages)
                        val response = withTimeoutOrNull(HOME_TIMEOUT_MS) {
                            api.getMainPage(page, request)
                        } ?: run {
                            Log.d(TAG, "${api.name}/${tab.name}: home page $page timed out")
                            return@async null
                        }
                        val items = response.items
                            .flatMap { list -> list.list.map { it.toBrowseItem(index, api.name) } }
                            .distinctBy { it.apiIndex to it.url }
                        Log.d(
                            TAG,
                            "${api.name}/${tab.name}: home page $page -> ${items.size} items, next=${response.hasNext}",
                        )
                        items to response.hasNext
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d(TAG, "${api.name}/${tab.name}: home page $page failed: ${e.javaClass.simpleName}: ${e.message}")
                        null
                    } catch (e: LinkageError) {
                        Log.d(TAG, "${api.name}/${tab.name}: home page linkage error")
                        null
                    } catch (e: NotImplementedError) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return PluginLatestPage(
            items = homes.flatMap { it.first }.distinctBy { it.apiIndex to it.url },
            hasNext = homes.any { it.second },
            categories = categories,
        )
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "${api.name}: browse load failed: ${e.javaClass.simpleName}")
            null
        } catch (e: LinkageError) {
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
            trailer.extractorUrl?.takeIf { it.isNotBlank() }?.let(::PluginBrowseTrailer)
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
        return loadLinks(api, data, timeoutMs ?: LOAD_LINKS_TIMEOUT_MS)
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
    } catch (e: Exception) {
        Log.d(TAG, "search(\"$query\") failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    } catch (e: LinkageError) {
        null
    } catch (e: NotImplementedError) {
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
            try {
                api.loadLinks(
                    data = data,
                    isCasting = false,
                    subtitleCallback = { subtitles.add(it) },
                    callback = { links.add(it) },
                )
                true
            } catch (e: Exception) {
                Log.w(TAG, "loadLinks failed: ${e.javaClass.simpleName}: ${e.message} (${links.size} links)")
                false
            } catch (e: LinkageError) {
                Log.w(TAG, "loadLinks linkage error: ${e.javaClass.simpleName}: ${e.message} (${links.size} links)")
                false
            }
        }
        if (completed == null) {
            Log.w(TAG, "loadLinks timed out after ${timeoutMs}ms (${links.size} links collected)")
        }
        Log.d(TAG, "${api.name}: ${links.size} links, ${subtitles.size} subtitles")

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
