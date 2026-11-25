package com.nuvio.app.features.watched

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.simkl.SimklSyncRepository
import com.nuvio.app.features.simkl.toSimklShowIdSiblings
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.tracking.effectiveWatchProgressSource
import com.nuvio.app.features.tracking.providerId
import com.nuvio.app.features.trakt.TraktProgressRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val BADGE_RESOLUTION_CONCURRENCY = 4

/**
 * Only the most recent series are checked. A large library can have hundreds of completed
 * series; resolving all of them means hundreds of metadata downloads on every launch.
 */
private const val BADGE_RESOLUTION_BATCH = 32
private const val AMBIGUOUS_MARKER = "__ambiguous__"

private val log = Logger.withTag("WatchedBadgeBulk")
private val resolutionMutex = Mutex()

/**
 * Fingerprint of the watched/progress data each series' badge depends on. A state publish from
 * [WatchedRepository.updateFullyWatchedSeriesStates] must not start another full scan.
 */
private val resolvedSignatures = mutableMapOf<String, Int>()

suspend fun resolveWatchedBadgesBulk(
    watchedItems: List<WatchedItem>,
    progressEntries: List<WatchProgressEntry>,
    todayIsoDate: String = CurrentDateProvider.todayIsoDate(),
): Boolean = withContext(Dispatchers.Default) {
    // Badge calculation publishes watched state, which re-enters this function. Never run
    // two scans at once and never rescan inputs that were already resolved.
    if (!resolutionMutex.tryLock()) return@withContext false
    try {
        val touchedSeriesIds = buildSet {
            watchedItems.forEach { item ->
                if (item.type.isSeriesLikeWatchedType() && item.season != null && item.episode != null) {
                    add(item.id)
                }
            }
            progressEntries.forEach { entry ->
                if (entry.parentMetaType.isSeriesLikeWatchedType() && entry.isEpisode && entry.isEffectivelyCompleted) {
                    add(entry.parentMetaId)
                }
            }
            WatchedRepository.baseFullyWatchedSeriesKeys().mapNotNullTo(this, ::extractContentIdFromWatchedKey)
        }
        if (touchedSeriesIds.isEmpty()) return@withContext true

        // Use the full watchedKeys from UI state which includes extra keys from
        // provider alternate IDs (e.g. Simkl anime alternate MAL/Kitsu keys).
        val watchedKeys = WatchedRepository.uiState.value.watchedKeys
        val watchedById = watchedItems.groupBy { it.id }
        val progressById = progressEntries.groupBy { it.parentMetaId }

        fun signature(contentId: String): Int {
            var hash = 17
            watchedById[contentId].orEmpty().forEach { item ->
                hash = 31 * hash + (item.season ?: -1)
                hash = 31 * hash + (item.episode ?: -1)
                hash = 31 * hash + item.markedAtEpochMs.hashCode()
            }
            progressById[contentId].orEmpty().forEach { entry ->
                hash = 31 * hash + (entry.seasonNumber ?: -1)
                hash = 31 * hash + (entry.episodeNumber ?: -1)
                hash = 31 * hash + entry.lastUpdatedEpochMs.hashCode()
                hash = 31 * hash + entry.lastPositionMs.hashCode()
            }
            return hash
        }

        val pending = touchedSeriesIds.filter { resolvedSignatures[it] != signature(it) }
        if (pending.isEmpty()) return@withContext true

        val batch = pending
            .sortedByDescending { contentId ->
                progressById[contentId].orEmpty().maxOfOrNull { it.lastUpdatedEpochMs } ?: 0L
            }
            .take(BADGE_RESOLUTION_BATCH)
        log.i { "Bulk badge resolution starting: ${batch.size}/${touchedSeriesIds.size} series candidates" }

        val completedVideoIds = progressEntries
            .asSequence()
            .filter { it.isEffectivelyCompleted }
            .mapTo(HashSet()) { it.videoId }
        val semaphore = Semaphore(BADGE_RESOLUTION_CONCURRENCY)
        val resolvedStates = linkedMapOf<String, Boolean>()

        for (contentId in batch) {
            semaphore.withPermit {
                val meta = try {
                    MetaDetailsRepository.fetch(type = "series", id = contentId, cacheResult = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                if (meta != null) {
                    val isFullyWatched = WatchedRepository.calculateFullyWatchedSeriesState(
                        meta = meta,
                        todayIsoDate = todayIsoDate,
                        isEpisodeWatched = { episode ->
                            val keys = watchedItemKeys(meta.type, meta.id, episode.season, episode.episode)
                            if (keys.any(watchedKeys::contains)) {
                                true
                            } else {
                                val episodeNumber = episode.episode
                                if (episodeNumber != null) {
                                    com.nuvio.app.features.simkl.SimklAnimeWatchedFallback.isWatched(episode.id, episodeNumber)
                                } else {
                                    false
                                }
                            }
                        },
                        isEpisodeCompleted = { episode ->
                            completedVideoIds.contains(meta.episodePlaybackId(episode))
                        },
                    )
                    resolvedStates[watchedItemKey(meta.type, meta.id)] = isFullyWatched
                }
            }
            yield()
        }

        // The update can add new fully-watched series keys. Mark those too, so the publish
        // below cannot enqueue another scan for them.
        resolvedStates.keys.forEach { key ->
            extractContentIdFromWatchedKey(key)?.let { id -> resolvedSignatures[id] = signature(id) }
        }
        WatchedRepository.updateFullyWatchedSeriesStates(resolvedStates)

        // Mark every candidate, including the deferred ones and ones whose metadata failed.
        // Otherwise the update above would immediately trigger another full scan.
        touchedSeriesIds.forEach { resolvedSignatures[it] = signature(it) }
        resolvedSignatures.keys.retainAll(touchedSeriesIds)
        log.i { "Bulk badge resolution complete: ${batch.size}/${touchedSeriesIds.size}" }

        // Sibling expansion
        expandFullyWatchedWithSiblings()
        true
    } finally {
        resolutionMutex.unlock()
    }
}

fun expandFullyWatchedWithSiblings() {
    val siblingMap = getActiveProviderSiblingMap()
    if (siblingMap.isEmpty()) {
        WatchedRepository.setExpandedFullyWatchedSeriesKeys(emptySet())
        return
    }

    // Use base keys (without previous sibling expansion) to avoid feedback loops
    val baseKeys = WatchedRepository.baseFullyWatchedSeriesKeys()
    if (baseKeys.isEmpty()) {
        WatchedRepository.setExpandedFullyWatchedSeriesKeys(emptySet())
        return
    }

    // Compute only the sibling-derived keys (not including base keys themselves)
    val siblingKeys = buildSet {
        for (key in baseKeys) {
            val contentId = extractContentIdFromWatchedKey(key) ?: continue
            val siblings = siblingMap[contentId] ?: continue
            siblings.forEach { siblingId ->
                if (siblingId != contentId && !siblingId.startsWith(AMBIGUOUS_MARKER)) {
                    val siblingKey = rebuildWatchedKeyWithSiblingId(key, siblingId)
                    if (siblingKey != null) add(siblingKey)
                }
            }
        }
    }

    if (siblingKeys != WatchedRepository.currentExpandedSiblingKeys()) {
        log.i { "Sibling expansion: ${baseKeys.size} base keys -> +${siblingKeys.size} sibling keys" }
        WatchedRepository.setExpandedFullyWatchedSeriesKeys(siblingKeys)
    }
}

private fun getActiveProviderSiblingMap(): Map<String, Set<String>> {
    val source = TrackingSettingsRepository.uiState.value.watchProgressSource
    val effectiveSource = effectiveWatchProgressSource(
        requestedSource = source,
        isProviderAuthenticated = { providerId ->
            com.nuvio.app.features.tracking.TrackingProviderRegistry.isAuthenticated(providerId)
        },
    )
    return effectiveSource.providerId?.let {
        com.nuvio.app.features.tracking.TrackingProviderRegistry.progressProvider(it)?.showIdSiblings()
    }.orEmpty()
}

private fun extractContentIdFromWatchedKey(key: String): String? {
    // Format: "type:contentId:season:episode"
    // Split from the end to handle contentIds with colons (like "tmdb:123")
    val parts = key.split(':')
    if (parts.size < 4) return null
    // Last two parts are season and episode (-1:-1)
    // First part is type, everything in between is contentId
    val type = parts.first()
    val season = parts[parts.size - 2]
    val episode = parts.last()
    if (season.toIntOrNull() == null || episode.toIntOrNull() == null) return null
    val contentId = parts.subList(1, parts.size - 2).joinToString(":")
    return contentId.takeIf { it.isNotBlank() }
}

private fun rebuildWatchedKeyWithSiblingId(originalKey: String, siblingId: String): String? {
    val parts = originalKey.split(':')
    if (parts.size < 4) return null
    val type = parts.first()
    return watchedItemKey(type = type, id = siblingId)
}

private fun String.isSeriesLikeWatchedType(): Boolean =
    trim().lowercase() in setOf("series", "show", "tv", "tvshow", "anime")
