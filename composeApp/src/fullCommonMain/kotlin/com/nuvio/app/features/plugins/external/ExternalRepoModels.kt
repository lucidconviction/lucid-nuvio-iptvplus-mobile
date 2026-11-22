package com.nuvio.app.features.plugins.external

import kotlinx.serialization.Serializable

/**
 * Manifest of a CloudStream-compatible repository. `repo.json` points at one or
 * more plugin-list documents; each list is a JSON array of [ExternalPluginEntry].
 */
@Serializable
internal data class ExternalRepoManifest(
    val name: String = "",
    val description: String? = null,
    val manifestVersion: Int = 1,
    val pluginLists: List<String> = emptyList(),
)

/**
 * One extension entry in a CloudStream `plugins.json` document.
 *
 * `internalName` is the provider's stable id; `url` points at a `.cs3` package
 * (or a legacy raw DEX). `version` is numeric in this format, unlike the Nuvio
 * JavaScript manifest which uses a string.
 */
@Serializable
internal data class ExternalPluginEntry(
    val name: String = "",
    val internalName: String,
    val description: String? = null,
    val version: Int = 1,
    val apiVersion: Int = 1,
    val status: Int = 1,
    val authors: List<String>? = null,
    val tvTypes: List<String>? = null,
    val iconUrl: String? = null,
    val url: String,
    val fileSize: Long? = null,
    val repositoryUrl: String? = null,
    val fileHash: String? = null,
)

internal data class ExternalRepoParseResult(
    val name: String,
    val description: String?,
    val plugins: List<ExternalPluginEntry>,
)

/**
 * Maps CloudStream `tvTypes` onto the app's content types. Mirrors ReSeq's
 * `cloudStreamContentTypes`: `Others` can be both a movie and a series; most
 * other TV types are series; movie-like types are movies.
 */
internal fun cloudStreamContentTypes(tvTypes: List<String>?): List<String> {
    val mapped = tvTypes.orEmpty().mapNotNull { raw ->
        when (raw.trim().lowercase()) {
            "others" -> listOf("movie", "tv")
            "movie", "animemovie", "documentary", "torrent" -> listOf("movie")
            "tvseries", "anime", "ova", "cartoon", "asiandrama", "live", "musicvideo" -> listOf("tv")
            else -> null
        }
    }.flatten().distinct()
    return mapped.ifEmpty { listOf("movie", "tv") }
}
