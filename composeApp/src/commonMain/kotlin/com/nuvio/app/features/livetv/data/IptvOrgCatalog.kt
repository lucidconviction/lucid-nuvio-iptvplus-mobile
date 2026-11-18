package com.nuvio.app.features.livetv.data

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.livetv.model.LiveTvClock
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

enum class IptvOrgListType { COUNTRY, CATEGORY, REGION }

/** One iptv-org playlist the user can add, with the country/category/region title it belongs to. */
data class IptvOrgList(
    val type: IptvOrgListType,
    val code: String,
    val name: String,
    val flag: String? = null,
    val subtitle: String,
    val url: String,
)

data class IptvOrgCatalogData(
    val countries: List<IptvOrgList> = emptyList(),
    val categories: List<IptvOrgList> = emptyList(),
    val regions: List<IptvOrgList> = emptyList(),
    val fromCache: Boolean = false,
)

/**
 * The iptv-org playlist index: the small JSON lists the project publishes at
 * iptv-org.github.io/api, turned into country/category/region M3U URLs.
 *
 * Lists are cached on disk for a day and kept for offline use when a refresh fails. The cached
 * file is plain JSON (not a downloaded playlist), so it stays small.
 */
internal object IptvOrgCatalog {
    private const val BASE = "https://iptv-org.github.io/api"
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    private const val USER_AGENT = "NuvioPlusIPTV"
    private val client by lazy { createApiHttpClient() }
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var memory: IptvOrgCatalogData? = null
    private var memoryAtMs = 0L

    suspend fun load(force: Boolean = false): IptvOrgCatalogData = lock.withLock {
        val now = LiveTvClock.nowMs()
        if (!force && memory != null && now - memoryAtMs < CACHE_TTL_MS) return memory!!
        withContext(Dispatchers.Default) {
            coroutineScope {
                val countries = async { loadList("countries", force, ::parseCountries) }
                val categories = async { loadList("categories", force, ::parseCategories) }
                val regions = async { loadList("regions", force, ::parseRegions) }
                val countryList = countries.await()
                val categoryList = categories.await()
                val regionList = regions.await()
                IptvOrgCatalogData(
                    countries = countryList.first,
                    categories = categoryList.first,
                    regions = regionList.first,
                    fromCache = countryList.second || categoryList.second || regionList.second,
                ).also {
                    memory = it
                    memoryAtMs = now
                }
            }
        }
    }

    private suspend fun loadList(
        name: String,
        force: Boolean,
        parse: (String) -> List<IptvOrgList>,
    ): Pair<List<IptvOrgList>, Boolean> {
        val fileName = "iptv-org-$name.json"
        val now = LiveTvClock.nowMs()
        val cacheFresh = LiveTvFiles.exists(LiveTvDirs.IPTV, fileName) &&
            LiveTvFiles.lastModified(LiveTvDirs.IPTV, fileName) > 0L &&
            now - LiveTvFiles.lastModified(LiveTvDirs.IPTV, fileName) < CACHE_TTL_MS
        if (!force && cacheFresh) {
            LiveTvFiles.readSmall(LiveTvDirs.IPTV, fileName)?.let { cached ->
                runCatching { return parse(cached) to true }
                LiveTvFiles.delete(LiveTvDirs.IPTV, fileName)
            }
        }

        val fetched = runCatching {
            client.get("$BASE/$name.json") {
                header("User-Agent", USER_AGENT)
            }.bodyAsText()
        }.getOrNull()
        if (!fetched.isNullOrBlank()) {
            LiveTvFiles.writeSmall(LiveTvDirs.IPTV, fileName, fetched)
            return parse(fetched) to false
        }

        LiveTvFiles.readSmall(LiveTvDirs.IPTV, fileName)?.let { cached ->
            return parse(cached) to true
        }
        error("Could not load the iptv-org $name list")
    }

    private fun array(text: String): JsonArray = json.parseToJsonElement(text).jsonArray
    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

    internal fun parseCountries(text: String): List<IptvOrgList> = buildList {
        add(
            IptvOrgList(
                type = IptvOrgListType.COUNTRY,
                code = "ALL",
                name = "All channels",
                flag = "🌍",
                subtitle = "iptv-org · all countries · very large list",
                url = "https://iptv-org.github.io/iptv/index.m3u",
            )
        )
        array(text).forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            val code = o.text("code")
            val name = o.text("name")
            if (code.isBlank() || name.isBlank()) return@forEach
            add(
                IptvOrgList(
                    type = IptvOrgListType.COUNTRY,
                    code = code,
                    name = name,
                    flag = o.text("flag").takeIf { it.isNotEmpty() },
                    subtitle = "iptv-org · country playlist · $code",
                    url = "https://iptv-org.github.io/iptv/countries/${code.lowercase()}.m3u",
                )
            )
        }
    }.sortedWith(compareBy({ it.code != "ALL" }, { it.name.lowercase() }))

    internal fun parseCategories(text: String): List<IptvOrgList> = buildList {
        array(text).forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            val id = o.text("id")
            val name = o.text("name")
            if (id.isBlank() || name.isBlank()) return@forEach
            val description = o.text("description")
            add(
                IptvOrgList(
                    type = IptvOrgListType.CATEGORY,
                    code = id,
                    name = name,
                    subtitle = description.ifBlank { "iptv-org · category playlist · $id" },
                    url = "https://iptv-org.github.io/iptv/categories/$id.m3u",
                )
            )
        }
    }.sortedBy { it.name.lowercase() }

    internal fun parseRegions(text: String): List<IptvOrgList> = buildList {
        array(text).forEach { element ->
            val o = element as? JsonObject ?: return@forEach
            val code = o.text("code")
            val name = o.text("name")
            if (code.isBlank() || name.isBlank()) return@forEach
            add(
                IptvOrgList(
                    type = IptvOrgListType.REGION,
                    code = code,
                    name = name,
                    subtitle = "iptv-org · region playlist · $code",
                    url = "https://iptv-org.github.io/iptv/regions/${code.lowercase()}.m3u",
                )
            )
        }
    }.sortedBy { it.name.lowercase() }
}

/** Duplicate matching ignores case and a trailing slash. */
internal fun normalizeIptvOrgUrl(url: String): String = url.trim().trimEnd('/').lowercase()
