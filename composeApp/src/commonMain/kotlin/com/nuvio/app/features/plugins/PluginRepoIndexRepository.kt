package com.nuvio.app.features.plugins

import com.nuvio.app.features.addons.httpGetText
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class PluginRepoIndexEntry(
    val name: String,
    val url: String,
    val verified: Boolean,
)

/**
 * The CloudStream "MegaRepo" (short code 3737) adds every entry from this
 * public repository database when its helper extension runs. The mobile app
 * exposes the same list natively so users can add repositories individually.
 */
internal object PluginRepoIndexRepository {
    internal const val URL =
        "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun fetch(): List<PluginRepoIndexEntry> =
        parsePluginRepoIndex(httpGetText(URL))

    internal fun parse(body: String): List<PluginRepoIndexEntry> =
        parsePluginRepoIndex(body)

    private fun parsePluginRepoIndex(body: String): List<PluginRepoIndexEntry> {
        val array = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> {
                    val url = element.contentOrNull?.trim().orEmpty()
                    if (url.isBlank()) null else entryFor(url, verified = false)
                }

                is JsonObject -> {
                    val url = element["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (url.isBlank()) {
                        null
                    } else {
                        val verified = element["verified"]?.jsonPrimitive?.booleanOrNull == true
                        entryFor(url, verified)
                    }
                }

                else -> null
            }
        }.distinctBy { it.url }
    }

    private fun entryFor(url: String, verified: Boolean): PluginRepoIndexEntry =
        PluginRepoIndexEntry(
            name = displayName(url),
            url = url,
            verified = verified,
        )

    private fun displayName(url: String): String {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return url
        val segments = parsed.pathSegments.filter { it.isNotBlank() }
        return when {
            parsed.host.equals("raw.githubusercontent.com", ignoreCase = true) &&
                segments.size >= 2 -> "${segments[0]}/${segments[1]}"

            segments.isNotEmpty() -> "${parsed.host}/${segments.first()}"
            else -> parsed.host
        }
    }
}
