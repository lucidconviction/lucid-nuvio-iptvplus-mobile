package com.nuvio.app.features.plugins.external

import com.nuvio.app.features.addons.httpRequestRaw
import io.ktor.http.Url
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Parses CloudStream-compatible repositories.
 *
 * Supported shapes:
 * - `repo.json` with `pluginLists: ["plugins.json", ...]`
 * - a direct `plugins.json` array of [ExternalPluginEntry]
 * - a directory URL that falls back to `<base>/repo.json`
 *
 * URL handling mirrors ReSeq where it matters: GitHub blob/raw/tree links are
 * rewritten to raw.githubusercontent.com, `cloudstreamrepo://` share links are
 * accepted, and relative list/package URLs resolve against the final response
 * URL (after redirects).
 */
internal object ExternalRepoParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    suspend fun tryParse(input: String): ExternalRepoParseResult? {
        val candidate = cloudStreamManifestUrl(input) ?: return null
        val document = fetch(candidate) ?: return null

        parseDocument(document)?.let { return it }

        // Keep extensionless endpoints working, then try the standard layout.
        if (candidate.substringBefore('?').substringAfterLast('/').contains('.')) return null
        val repoJson = joinUrl(document.url, "repo.json") ?: return null
        val fallback = fetch(repoJson) ?: return null
        return parseDocument(fallback)
    }

    private data class Document(val url: String, val body: String)

    private suspend fun fetch(url: String): Document? = try {
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to "NuvioTV/1.0",
            ),
            body = "",
            followRedirects = true,
            maxResponseBodyBytes = 5 * 1024 * 1024,
        )
        if (response.status !in 200..299) null else Document(response.url, response.body)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun parseDocument(document: Document): ExternalRepoParseResult? {
        val trimmed = document.body.trim()
        if (trimmed.isEmpty()) return null

        return try {
            if (trimmed.startsWith("{")) {
                parseRepoManifest(document, trimmed)
            } else if (trimmed.startsWith("[")) {
                val entries = json.decodeFromString<List<ExternalPluginEntry>>(trimmed)
                resultFor(
                    name = document.url
                        .substringBefore('?')
                        .substringAfterLast('/')
                        .removeSuffix(".json")
                        .ifBlank { Url(document.url).host },
                    description = null,
                    entries = entries,
                    documentUrl = document.url,
                )
            } else {
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun parseRepoManifest(document: Document, payload: String): ExternalRepoParseResult? {
        val manifest = json.decodeFromString<ExternalRepoManifest>(payload)
        if (manifest.pluginLists.isEmpty()) return null

        val lists = coroutineScope {
            manifest.pluginLists
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { list ->
                    async {
                        val listUrl = joinUrl(document.url, list) ?: return@async emptyList()
                        val listDocument = fetch(listUrl) ?: return@async emptyList()
                        parsePluginList(listDocument)
                    }
                }
                .awaitAll()
                .flatten()
        }

        if (lists.isEmpty()) return null
        return resultFor(
            name = manifest.name.ifBlank {
                Url(document.url).host
            },
            description = manifest.description,
            entries = lists,
            documentUrl = document.url,
        )
    }

    private fun parsePluginList(document: Document): List<ExternalPluginEntry> = try {
        json.decodeFromString<List<ExternalPluginEntry>>(document.body.trim())
            .mapNotNull { entry -> normalizeEntry(entry, document.url) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private fun resultFor(
        name: String,
        description: String?,
        entries: List<ExternalPluginEntry>,
        documentUrl: String,
    ): ExternalRepoParseResult? {
        val normalized = entries.mapNotNull { entry -> normalizeEntry(entry, documentUrl) }
        if (normalized.isEmpty()) return null
        val byName = linkedMapOf<String, ExternalPluginEntry>()
        for (entry in normalized) {
            val existing = byName[entry.internalName]
            if (existing == null || entry.version > existing.version) {
                byName[entry.internalName] = entry
            }
        }
        return ExternalRepoParseResult(
            name = name,
            description = description,
            plugins = byName.values.toList(),
        )
    }

    private fun normalizeEntry(entry: ExternalPluginEntry, documentUrl: String): ExternalPluginEntry? {
        val id = entry.internalName.trim()
        if (id.isEmpty()) return null
        val packageUrl = joinUrl(documentUrl, entry.url.trim()) ?: return null
        return entry.copy(
            name = entry.name.trim().ifEmpty { id },
            internalName = id,
            url = packageUrl,
            iconUrl = entry.iconUrl?.let { joinUrl(documentUrl, it) },
        )
    }
}

/** Normalizes CloudStream share links and GitHub file links to a fetchable URL. */
internal fun cloudStreamManifestUrl(input: String): String? {
    var value = input.trim()
    if (value.isEmpty()) return null

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

    val parsed = runCatching { Url(value) }.getOrNull() ?: return null
    if (!parsed.host.equals("github.com", ignoreCase = true)) return parsed.toString()

    val segments = parsed.pathSegments.filter { it.isNotBlank() }
    if (segments.size < 2) return parsed.toString()

    val owner = segments[0]
    val repo = segments[1].removeSuffix(".git")
    val raw = StringBuilder("https://raw.githubusercontent.com/$owner/$repo")
    when {
        segments.size == 2 -> raw.append("/HEAD/repo.json")
        segments.size >= 5 && segments[2].lowercase() in setOf("blob", "raw") -> {
            val branch = segments[3]
            raw.append('/').append(branch)
            segments.drop(4).forEach { raw.append('/').append(it) }
        }
        else -> return parsed.toString()
    }
    return raw.toString()
}

/** Resolve a possibly relative URL against a base, including `..` segments. */
internal fun joinUrl(baseUrl: String, relative: String): String? {
    val rel = relative.trim()
    if (rel.isEmpty()) return baseUrl
    if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
    if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return null

    val base = runCatching { Url(baseUrl) }.getOrNull() ?: return null
    if (rel.startsWith("//")) return "${base.protocol.name}:$rel"

    val relPath = rel.substringBefore('?').substringBefore('#')
    val query = if ('?' in rel) rel.substringAfter('?').substringBefore('#') else base.encodedQuery
    val fragment = if ('#' in rel) rel.substringAfter('#') else null

    val path = if (relPath.startsWith("/")) {
        normalizePath(relPath)
    } else {
        val directory = base.encodedPath.substringBeforeLast('/', "")
        normalizePath("$directory/$relPath")
    }

    return buildString {
        append(base.protocol.name).append("://").append(base.host)
        if (base.specifiedPort != 0 && base.specifiedPort != base.protocol.defaultPort) {
            append(':').append(base.specifiedPort)
        }
        append(path)
        if (query.isNotEmpty()) append('?').append(query)
        if (fragment != null) append('#').append(fragment)
    }
}

private fun normalizePath(raw: String): String {
    val segments = raw.split('/')
    val out = ArrayDeque<String>()
    for (segment in segments) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeLast()
            else -> out.addLast(segment)
        }
    }
    val path = "/" + out.joinToString("/")
    return if (raw.endsWith('/') && path != "/") "$path/" else path
}
