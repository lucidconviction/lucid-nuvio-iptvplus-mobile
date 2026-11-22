package com.nuvio.app.features.plugins.external

/**
 * Index of the provider catalog entry that safely matches a requested
 * season/episode, using CloudStream's flat/absolute episode conventions.
 */
internal fun matchExtensionEpisodeIndex(
    entries: List<Pair<Int?, Int?>>,
    season: Int?,
    episode: Int?,
): Int? {
    if (entries.isEmpty() || episode == null) return null
    if (season != null) {
        val exact = entries.indexOfFirst { (s, e) -> s == season && e == episode }
        return exact.takeIf { it >= 0 }
    }
    val absolute = entries.indexOfFirst { (s, e) -> e == episode && s == null }
    if (absolute >= 0) return absolute
    val anyEpisode = entries.indexOfFirst { (_, e) -> e == episode }
    return anyEpisode.takeIf { it >= 0 }
}
