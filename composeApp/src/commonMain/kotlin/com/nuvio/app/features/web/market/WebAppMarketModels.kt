package com.nuvio.app.features.web.market

import kotlinx.serialization.Serializable

/** One site in the Web App Market. Ported from Lucid Hikari's FMHY-backed market. */
@Serializable
data class MarketApp(
    val name: String,
    val url: String,
    val categoryId: String,
    val source: String = "fmhy",
    val rank: Int = 0,
    val note: String = "",
    val adult: Boolean = false,
)

@Serializable
data class MarketCatalog(
    val schema: Int = SCHEMA,
    val fetchedAt: Long = 0L,
    val apps: List<MarketApp> = emptyList(),
) {
    companion object {
        const val SCHEMA = 1
    }
}

data class MarketCategory(
    val id: String,
    val groupId: String,
    val label: String,
)

data class MarketGroup(
    val id: String,
    val label: String,
    val categories: List<MarketCategory>,
)

/**
 * The market's navigation. Only the SFW FMHY sections are included; the adult catalogue is
 * deliberately not shipped with Nuvio.
 */
object MarketCategories {
    private const val MOVIES = "movies"
    private const val ANIME = "anime"
    private const val LIVE = "live"
    private const val SPORTS = "sports"
    private const val ARCHIVES = "archives"

    private fun category(id: String, group: String, label: String) =
        MarketCategory(id = id, groupId = group, label = label)

    val all: List<MarketCategory> = listOf(
        category("stream-aggregators", MOVIES, "Stream Aggregators"),
        category("p-stream-forks", MOVIES, "P-Stream Forks"),
        category("dedicated-server", MOVIES, "Dedicated Servers"),
        category("multi-server", MOVIES, "Multi-Server"),
        category("stream-lounges", MOVIES, "Stream Lounges"),
        category("free-w-ads", MOVIES, "Free with Ads"),
        category("video-streaming", MOVIES, "Video Streaming"),
        category("tv-streaming", MOVIES, "TV Streaming"),
        category("drama-streaming", MOVIES, "Drama Streaming"),
        category("anime-streaming", ANIME, "Anime Streaming"),
        category("cartoon-streaming", ANIME, "Cartoon Streaming"),
        category("live-tv", LIVE, "Live TV"),
        category("live-sports", SPORTS, "Live Sports"),
        category("sports-replays", SPORTS, "Sports Replays"),
        category("classics-public-domain", ARCHIVES, "Classics / Public Domain"),
        category("film-archives", ARCHIVES, "Film Archives"),
    )

    val groups: List<MarketGroup> = listOf(
        MarketGroup(MOVIES, "Movies & TV", all.filter { it.groupId == MOVIES }),
        MarketGroup(ANIME, "Anime & Cartoons", all.filter { it.groupId == ANIME }),
        MarketGroup(LIVE, "Live TV", all.filter { it.groupId == LIVE }),
        MarketGroup(SPORTS, "Sports", all.filter { it.groupId == SPORTS }),
        MarketGroup(ARCHIVES, "Archives", all.filter { it.groupId == ARCHIVES }),
    )

    fun category(id: String): MarketCategory? = all.firstOrNull { it.id == id }

    fun matches(query: String, app: MarketApp): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        val category = category(app.categoryId)?.label.orEmpty()
        return app.name.contains(q, ignoreCase = true) ||
            hostOf(app.url).contains(q, ignoreCase = true) ||
            category.contains(q, ignoreCase = true)
    }

    fun hostOf(url: String): String =
        url.substringAfter("://", url)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .removePrefix("www.")
            .lowercase()
}
