package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.tracking.WatchProgressSource

internal fun projectWatchProgressSourceEntries(
    source: WatchProgressSource,
    nuvioEntries: Collection<WatchProgressEntry>,
    providerEntries: Collection<WatchProgressEntry>,
): List<WatchProgressEntry> = if (source.providerId == null) {
    nuvioEntries.toList()
} else {
    // Trakt and Simkl only know the titles they track. A YouTube video played in Nuvio has no
    // provider record, so a provider-only projection made it vanish from Home's Continue
    // Watching row. Keep Nuvio's local YouTube history alongside the provider snapshot; the
    // provider still owns every tracked movie/episode.
    val localYouTubeEntries = nuvioEntries.filter { entry -> entry.isYouTubeProgressEntry() }
    if (localYouTubeEntries.isEmpty()) {
        providerEntries.toList()
    } else {
        val providerKeys = providerEntries.mapTo(HashSet()) { entry -> entry.resolvedProgressKey() }
        providerEntries.toList() + localYouTubeEntries.filterNot { entry ->
            entry.resolvedProgressKey() in providerKeys
        }
    }
}
