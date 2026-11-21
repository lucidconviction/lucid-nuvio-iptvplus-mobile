package com.nuvio.app.features.livetv.ui.sports

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.livetv.sports.SportsEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One event stream found in an installed add-on. */
internal data class SportsAddonMatch(
    val item: MetaPreview,
    val addonName: String,
    val manifestUrl: String,
)

/**
 * Looks through installed add-ons for a game's event streams. Sports add-ons usually expose a
 * "Live Sports"/"Sports Streams" catalog with a search extra; those are queried by the teams and
 * event title, then the results are matched locally so unrelated catalog items are not shown.
 */
internal object SportsAddonSearch {
    private const val CACHE_MS = 5 * 60_000L
    private const val MAX_CATALOGS = 10
    private const val MAX_RESULTS = 8
    private const val FETCH_TIMEOUT_MS = 8_000L

    private val cache = HashMap<String, Pair<Long, List<SportsAddonMatch>>>()
    private val cacheLock = Mutex()

    suspend fun matches(ev: SportsEvent): List<SportsAddonMatch> {
        val now = com.nuvio.app.features.livetv.model.LiveTvClock.nowMs()
        val cached = cacheLock.withLock {
            val entry = cache[ev.id]
            if (entry != null && now - entry.first < CACHE_MS) {
                entry.second
            } else {
                cache.remove(ev.id)
                null
            }
        }
        if (cached != null) return cached
        runCatching { AddonRepository.initialize() }
        val addons = AddonRepository.uiState.value.addons.filter { it.enabled && it.manifest != null }
        if (addons.isEmpty()) return emptyList()

        val terms = termsFor(ev)
        val jobs = ArrayList<Pair<String, com.nuvio.app.features.addons.AddonCatalog>>()
        for (addon in addons) {
            val manifest = addon.manifest ?: continue
            for (catalog in manifest.catalogs) {
                if (jobs.size >= MAX_CATALOGS) break
                val searchable = catalog.extra.any { it.name.equals("search", ignoreCase = true) }
                val sportsLike = looksSportsLike(catalog.name) || looksSportsLike(catalog.id) ||
                    catalog.type.equals("other", ignoreCase = true) || catalog.type.equals("live", ignoreCase = true)
                if (searchable || sportsLike) jobs += addon.manifestUrl to catalog
            }
        }

        val found = coroutineScope {
            jobs.map { (manifestUrl, catalog) ->
                async {
                    val search = catalog.extra.firstOrNull { it.name.equals("search", ignoreCase = true) }
                    val queries: List<String?> = if (search != null) terms else listOf(null)
                    val addonName = addons.firstOrNull { it.manifestUrl == manifestUrl }?.displayTitle ?: "Add-on"
                    val out = ArrayList<SportsAddonMatch>()
                    for (query in queries) {
                        val page = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                            runCatching {
                                fetchCatalogPage(
                                    manifestUrl = manifestUrl,
                                    type = catalog.type,
                                    catalogId = catalog.id,
                                    search = query,
                                    maxItems = 40,
                                )
                            }.getOrNull()
                        } ?: continue
                        page.items.forEach { item ->
                            if (matchesEvent(ev.title, ev.sides.map { it.team.name } + ev.sides.map { it.team.abbr }, item.name)) {
                                out += SportsAddonMatch(item, addonName, manifestUrl)
                            }
                        }
                    }
                    out
                }
            }.awaitAll().flatten()
        }

        val result = found
            .distinctBy { "${it.manifestUrl}:${it.item.id}" }
            .sortedByDescending { scoreMatch(ev, it.item.name) }
            .take(MAX_RESULTS)
        cacheLock.withLock { cache[ev.id] = now to result }
        return result
    }

    private fun termsFor(ev: SportsEvent): List<String> {
        val teams = ev.sides.map { it.team.name }.filter { it.isNotBlank() }
        val out = LinkedHashSet<String>()
        out += ev.title
        if (teams.size >= 2) out += teams.joinToString(" ")
        out += teams.joinToString(" vs ")
        return out.toList()
    }

    private fun looksSportsLike(text: String): Boolean {
        val t = text.lowercase()
        return listOf("sport", "live", "event", "stream", "fixture", "match", "nba", "nfl", "soccer", "football", "ufc", "boxing")
            .any { it in t }
    }

    private fun scoreMatch(ev: SportsEvent, name: String): Int {
        val n = norm(name)
        if (n.isBlank()) return 0
        val title = norm(ev.title)
        val teams = ev.sides.map { norm(it.team.name) }.filter { it.length >= 3 }
        val abbrs = ev.sides.map { norm(it.team.abbr) }.filter { it.length == 2 || it.length == 3 }
        var score = 0
        if (title.length >= 6 && (n.contains(title) || title.contains(n))) score += 8
        teams.forEach { if (it.length >= 3 && n.contains(it)) score += 5 }
        abbrs.forEach { if (it.length in 2..3 && hasWord(n, it)) score += 2 }
        score += distinctTitleWords(ev.title).count { it.length >= 4 && n.contains(it) }
        return score
    }

    private fun matchesEvent(title: String, names: List<String>, itemName: String): Boolean {
        val n = norm(itemName)
        if (n.isBlank()) return false
        val titleNorm = norm(title)
        if (titleNorm.length >= 6 && (n.contains(titleNorm) || titleNorm.contains(n))) return true
        val teams = names.map { norm(it) }.filter { it.length >= 3 }.distinct()
        val hits = teams.count { n.contains(it) }
        if (teams.size >= 2 && hits >= 2) return true
        if (hits >= 1 && teams.size >= 2) {
            // One full team name plus the other team's short code is enough for event cards.
            val abbrs = names.map { norm(it) }.filter { it.length == 3 }
            if (abbrs.count { it in n } >= 2) return true
        }
        val words = distinctTitleWords(title)
        return words.count { it.length >= 4 && n.contains(it) } >= 2
    }

    private fun distinctTitleWords(title: String): List<String> =
        norm(title).split(' ').filter { it.length >= 4 }.distinct()

    private fun hasWord(text: String, word: String): Boolean =
        text == word || text.startsWith("$word ") || text.endsWith(" $word") || text.contains(" $word ")

    private fun norm(value: String): String = buildString(value.length) {
        var space = true
        value.lowercase().forEach { c ->
            if (c.isLetterOrDigit()) {
                append(c)
                space = false
            } else if (!space) {
                append(' ')
                space = true
            }
        }
    }.trim()
}
