package com.nuvio.app.features.livetv.player

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.hasPendingEnabledManifests
import com.nuvio.app.features.addons.isWaitingForFirstEnabledManifest
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import com.nuvio.app.features.livetv.virtual.VirtualChannelRepository
import com.nuvio.app.features.livetv.virtual.VirtualPlaybackSettings
import com.nuvio.app.features.livetv.virtual.VirtualTune
import com.nuvio.app.features.livetv.virtual.VirtualChannels
import com.nuvio.app.features.livetv.virtual.VirtualSourceOrder
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.player.sanitizePlaybackResponseHeaders
import com.nuvio.app.features.servers.ServerPlayback
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamProxyHeaders
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.YouTubeStreamResolver
import com.nuvio.app.features.streams.epochMs
import com.nuvio.app.features.streams.supportsStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A resolved virtual channel tune: the item on now and a player-ready source. */
internal data class VirtualResolvedPlayback(
    val tune: VirtualTune,
    val stream: StreamItem,
    val launch: PlayerLaunch,
    val fromCache: Boolean,
)

/** The tune result plus a user-readable reason when it couldn't play. */
internal data class VirtualPlaybackOutcome(
    val resolved: VirtualResolvedPlayback? = null,
    val message: String? = null,
)

/**
 * Turns a Lucid TV item into something the player can open, using the same source pipeline as
 * the normal player: direct HTTP first, then server-hosted sources, then debrid, then P2P.
 * Results are cached in memory and in the persistent stream-link cache, and upcoming slots are
 * resolved ahead of time so a channel change is usually instant.
 */
internal object VirtualTuneResolver {
    private const val SUCCESS_TTL_MS = 10L * 60_000L
    private const val LINK_TTL_MS = 20L * 60_000L
    private const val ERROR_TTL_MS = 30_000L
    private const val ADDON_WAIT_MS = 4_000L
    private const val TUNE_BUDGET_MS = 20_000L
    private const val PREFETCH_BUDGET_MS = 45_000L

    private data class CachedStream(
        val stream: StreamItem?,
        val message: String?,
        val atMs: Long,
        val fromCache: Boolean = false,
    ) {
        fun valid(now: Long = epochMs()): Boolean =
            now - atMs < if (stream != null) SUCCESS_TTL_MS else ERROR_TTL_MS
    }

    private data class StreamResult(
        val stream: StreamItem?,
        val message: String? = null,
        val fromCache: Boolean = false,
        /** The item can be skipped because sources existed but none could be played. */
        val skipItem: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val cacheLock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val cache = HashMap<String, CachedStream>()
    private val blocked = HashMap<String, Long>()
    private val inFlight = HashMap<String, Deferred<StreamResult>>()

    suspend fun resolve(
        channel: LiveChannel,
        profileId: Int,
        nowMs: Long = LiveTvClock.nowMs(),
        budgetMs: Long = TUNE_BUDGET_MS,
    ): VirtualPlaybackOutcome {
        if (!VirtualChannels.isVirtualKey(channel.key)) {
            return VirtualPlaybackOutcome(message = "That isn't a Lucid TV channel.")
        }
        var tune = VirtualChannelRepository.tune(channel.key, nowMs)
            ?: return VirtualPlaybackOutcome(message = "This channel has nothing to play right now.")
        if (tune.relay) {
            val stream = relayStream(tune)
                ?: return VirtualPlaybackOutcome(message = "The relay channel has no stream.")
            val launch = buildLaunch(channel, profileId, tune, stream)
                ?: return VirtualPlaybackOutcome(message = OFFLINE)
            VirtualChannelRepository.clearError(channel.key)
            return VirtualPlaybackOutcome(VirtualResolvedPlayback(tune, stream, launch, fromCache = true))
        }
        val settings = VirtualChannelRepository.settings.value
        var skipped = 0
        while (true) {
            // A catalog can surface a series as a single item. Without an episode no stream
            // add-on can answer, so skip it instead of asking with a half id.
            if (tune.item.type == "series" && (tune.season == null || tune.episode == null)) {
                if (skipped < MAX_ITEM_SKIPS &&
                    VirtualChannelRepository.markUnplayable(channel.key, tune.item.id)
                ) {
                    skipped++
                    tune = VirtualChannelRepository.tune(channel.key, LiveTvClock.nowMs())
                        ?: return VirtualPlaybackOutcome(message = OFFLINE)
                    continue
                }
                val message = "No playable episode was found for ${tune.item.title}."
                VirtualChannelRepository.reportError(channel.key, message)
                return VirtualPlaybackOutcome(message = message)
            }

            var result = withTimeoutOrNull(budgetMs) {
                streamFor(tune, settings, budgetMs)
            } ?: StreamResult(
                null,
                "This channel took too long to tune. Check your add-ons and try again.",
            )
            var stream = result.stream
            if (stream == null) {
                if (result.skipItem && skipped < MAX_ITEM_SKIPS &&
                    VirtualChannelRepository.markUnplayable(channel.key, tune.item.id)
                ) {
                    skipped++
                    tune = VirtualChannelRepository.tune(channel.key, LiveTvClock.nowMs())
                        ?: return VirtualPlaybackOutcome(message = OFFLINE)
                    continue
                }
                val message = result.message ?: OFFLINE
                VirtualChannelRepository.reportError(channel.key, message)
                return VirtualPlaybackOutcome(message = message)
            }

            // Resolution can take seconds. Re-align the join offset with the guide afterwards, and if
            // the slot rolled over while we were looking up a source, use the new slot instead.
            val fresh = VirtualChannelRepository.tune(channel.key, LiveTvClock.nowMs())
            if (fresh != null) {
                if (sameItem(fresh, tune)) {
                    tune = fresh
                } else {
                    val next = withTimeoutOrNull(budgetMs.coerceAtMost(8_000L)) {
                        streamFor(fresh, settings, 8_000L)
                    }
                    if (next?.stream != null) {
                        tune = fresh
                        result = next
                        stream = next.stream
                    }
                }
            }

            val launch = buildLaunch(channel, profileId, tune, stream)
                ?: return VirtualPlaybackOutcome(message = OFFLINE)
            VirtualChannelRepository.clearError(channel.key)
            return VirtualPlaybackOutcome(
                VirtualResolvedPlayback(tune, stream, launch, fromCache = result.fromCache),
            )
        }
    }

    private fun sameItem(a: VirtualTune, b: VirtualTune): Boolean =
        a.item.id == b.item.id && a.season == b.season && a.episode == b.episode

    /** Resolves the item on now and the next [VirtualPlaybackSettings.prefetchNext] items. */
    suspend fun prefetch(channelKey: String, nowMs: Long = LiveTvClock.nowMs()) {
        val settings = VirtualChannelRepository.settings.value
        val count = settings.prefetchNext.coerceIn(
            VirtualPlaybackSettings.MIN_PREFETCH,
            VirtualPlaybackSettings.MAX_PREFETCH,
        )
        if (count <= 0) return
        val tunes = VirtualChannelRepository.upcomingTunes(channelKey, nowMs, count)
        tunes.forEach { tune ->
            streamFor(tune, settings, PREFETCH_BUDGET_MS)
        }
    }

    /** Prefetches the current item of a channel (used when Live TV opens). */
    suspend fun prefetchCurrent(channelKey: String, nowMs: Long = LiveTvClock.nowMs()) {
        val tune = VirtualChannelRepository.tune(channelKey, nowMs) ?: return
        streamFor(tune, VirtualChannelRepository.settings.value, PREFETCH_BUDGET_MS)
    }

    /** Forgets cached winners for one channel and, when given, blocks the failed source for a while. */
    suspend fun invalidate(
        channelKey: String,
        failedStream: StreamItem? = null,
        nowMs: Long = LiveTvClock.nowMs(),
    ) {
        val specId = VirtualChannels.specIdOf(channelKey) ?: return
        kotlinx.atomicfu.locks.synchronized(cacheLock) {
            cache.keys.filter { it.startsWith("$specId|") }.forEach(cache::remove)
            if (failedStream != null) {
                blocked[failedStream.preparationIdentity()] = epochMs()
            }
        }
        val tune = VirtualChannelRepository.tune(channelKey, nowMs) ?: return
        StreamLinkCacheRepository.remove(linkKey(tune))
    }

    private suspend fun streamFor(
        tune: VirtualTune,
        settings: VirtualPlaybackSettings,
        budgetMs: Long,
    ): StreamResult {
        val key = cacheKey(tune, settings)
        kotlinx.atomicfu.locks.synchronized(cacheLock) { cache[key] }?.let { cached ->
            if (cached.valid()) {
                return StreamResult(cached.stream, cached.message, fromCache = true)
            }
            kotlinx.atomicfu.locks.synchronized(cacheLock) { cache.remove(key) }
        }

        val (deferred, owner) = mutex.withLock {
            val existing = inFlight[key]
            if (existing != null) {
                existing to false
            } else {
                val job = scope.async(start = CoroutineStart.LAZY) {
                    runCatching { resolveFresh(tune, settings, budgetMs, key) }
                        .getOrElse { error ->
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            StreamResult(null, "Couldn't resolve this channel right now.")
                        }
                }
                inFlight[key] = job
                job to true
            }
        }
        if (owner) deferred.start()
        return try {
            deferred.await()
        } finally {
            if (owner) {
                mutex.withLock {
                    if (inFlight[key] === deferred) inFlight.remove(key)
                }
            }
        }
    }

    private suspend fun resolveFresh(
        tune: VirtualTune,
        settings: VirtualPlaybackSettings,
        budgetMs: Long,
        key: String,
    ): StreamResult {
        persistentLink(tune)?.let { cached ->
            remember(key, StreamResult(cached, fromCache = true))
            return StreamResult(cached, fromCache = true)
        }

        awaitAddons()
        val started = epochMs()
        var candidates = withTimeoutOrNull(budgetMs.coerceAtMost(10_000L)) {
            StreamsRepository.resolveStreamCandidates(
                type = tune.item.type,
                videoId = tune.item.streamId?.takeIf { it.isNotBlank() } ?: tune.item.id,
                parentMetaId = tune.item.parentId ?: tune.item.streamId?.takeIf { it.isNotBlank() } ?: tune.item.id,
                season = tune.season,
                episode = tune.episode,
                timeoutMs = budgetMs.coerceAtMost(15_000L),
            )
        }.orEmpty()

        // A stale/empty source state (for example add-ons were still loading) gets one retry.
        if (candidates.isEmpty() && epochMs() - started < budgetMs / 2) {
            candidates = withTimeoutOrNull((budgetMs - (epochMs() - started)).coerceAtLeast(2_000L)) {
                StreamsRepository.resolveStreamCandidates(
                    type = tune.item.type,
                    videoId = tune.item.streamId?.takeIf { it.isNotBlank() } ?: tune.item.id,
                    parentMetaId = tune.item.parentId ?: tune.item.streamId?.takeIf { it.isNotBlank() } ?: tune.item.id,
                    season = tune.season,
                    episode = tune.episode,
                    timeoutMs = budgetMs.coerceAtMost(15_000L),
                    forceRefresh = true,
                )
            }.orEmpty()
        }

        val result = resolveCandidates(
            tune = tune,
            settings = settings,
            candidates = candidates.filterNot(::isBlocked),
            remainingBudgetMs = budgetMs - (epochMs() - started),
        )
        remember(key, result)
        if (result.stream != null) cacheLink(tune, result.stream)
        return result
    }

    /**
     * Resolves a small set of sources in parallel and returns the first playable one. Lucid TV's
     * default order is manual/debrid cache → torrent P2P → HTTP; each stage only starts when the
     * previous one has nothing playable.
     */
    private suspend fun resolveCandidates(
        tune: VirtualTune,
        settings: VirtualPlaybackSettings,
        candidates: List<StreamItem>,
        remainingBudgetMs: Long,
    ): StreamResult {
        if (candidates.isEmpty()) return failureForEmptyCandidates(tune, settings)
        val deadline = epochMs() + remainingBudgetMs.coerceAtLeast(2_000L)

        DebridSettingsRepository.ensureLoaded()
        P2pSettingsRepository.ensureLoaded()

        val directDebridLinks = candidates.filter { it.isDirectDebridStream && it.playableDirectUrl != null }
        val debridCandidates = if (settings.allowDebrid) {
            candidates
                .filter { DirectDebridPlaybackResolver.shouldResolveToPlayableStream(it) }
                .distinctBy { it.preparationIdentity() }
        } else {
            emptyList()
        }
        val directCandidates = candidates
            .filter { it.playableDirectUrl != null }
            .distinctBy { it.preparationIdentity() }
        val serverCandidates = candidates
            .filter { it.playableDirectUrl == null && (it.serverTarget != null || it.needsServerPreparation) }
            .distinctBy { it.preparationIdentity() }
        val youTubeCandidates = candidates
            .filter { it.playableDirectUrl == null && it.youTubeIdToResolve != null }
            .distinctBy { it.preparationIdentity() }

        if (settings.sourceOrder == VirtualSourceOrder.HTTP_FIRST) {
            val http = resolveOneOf(
                directCandidates + serverCandidates + youTubeCandidates,
                tune,
                deadline,
            )
            if (http != null) return StreamResult(http)
            val debrid = resolveOneOf(debridCandidates, tune, deadline)
            if (debrid != null) return StreamResult(debrid)
            p2pCandidate(candidates) { P2pSettingsRepository.uiState.value.p2pEnabled }
                ?.let { return StreamResult(it) }
            return failureForCandidates(tune, settings, candidates)
        }

        // 1. A cached debrid link that is already an HTTP URL.
        directDebridLinks.firstOrNull()?.let { return StreamResult(it) }

        // 2. Cached debrid streams. Their cache check / link lookup should be quick, so don't
        //    hold the channel while an uncached or slow provider responds.
        val cachedDebrid = debridCandidates.filter { stream ->
            stream.isDirectDebridStream || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED
        }
        if (settings.allowDebrid && cachedDebrid.isNotEmpty()) {
            val cacheDeadline = minOf(deadline, epochMs() + DEBRID_CACHE_GRACE_MS)
            resolveOneOf(cachedDebrid, tune, cacheDeadline)?.let { return StreamResult(it) }
        }

        // 3. Torrent P2P, but only when the viewer has P2P turned on. If it is off, a direct
        //    HTTP source is better than surprising them with the consent dialog on every zap.
        p2pCandidate(candidates) { P2pSettingsRepository.uiState.value.p2pEnabled }
            ?.let { return StreamResult(it) }

        // 4. Direct HTTP, server-hosted and YouTube-only sources.
        resolveOneOf(
            directCandidates + serverCandidates + youTubeCandidates,
            tune,
            deadline,
        )?.let { return StreamResult(it) }

        // 5. Debrid streams whose cache status was unknown, as a last resort before failing.
        if (settings.allowDebrid) {
            val uncached = debridCandidates.filterNot { stream ->
                stream.isDirectDebridStream || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED
            }
            resolveOneOf(uncached, tune, deadline)?.let { return StreamResult(it) }
        }

        p2pCandidate(candidates) { AppFeaturePolicy.p2pEnabled }
            ?.let { return StreamResult(it) }
        return failureForCandidates(tune, settings, candidates)
    }

    /** The first torrent that has no direct URL and is not a debrid resolve target. */
    private fun p2pCandidate(
        candidates: List<StreamItem>,
        enabled: () -> Boolean,
    ): StreamItem? {
        if (!AppFeaturePolicy.p2pEnabled || !enabled()) return null
        return candidates.firstOrNull {
            it.p2pInfoHash != null && it.playableDirectUrl == null && !it.isDirectDebridStream
        }
    }

    /** Resolves a set of candidates concurrently and returns the first playable result. */
    private suspend fun resolveOneOf(
        streams: List<StreamItem>,
        tune: VirtualTune,
        deadlineMs: Long,
    ): StreamItem? {
        val unique = streams.distinctBy { it.preparationIdentity() }.take(MAX_PARALLEL_SOURCES)
        if (unique.isEmpty()) return null
        val results = kotlinx.coroutines.channels.Channel<StreamItem>(
            kotlinx.coroutines.channels.Channel.UNLIMITED,
        )
        val jobs = unique.map { stream ->
            scope.async {
                val resolved = resolveCandidate(
                    stream = stream,
                    tune = tune,
                    remainingMs = (deadlineMs - epochMs()).coerceAtLeast(500L),
                )
                if (resolved != null) results.send(resolved)
            }
        }
        val result = withTimeoutOrNull((deadlineMs - epochMs()).coerceAtLeast(500L)) {
            results.receive()
        }
        jobs.forEach { it.cancel() }
        results.close()
        return result
    }

    private suspend fun resolveCandidate(
        stream: StreamItem,
        tune: VirtualTune,
        remainingMs: Long,
    ): StreamItem? {
        stream.playableDirectUrl?.let { return stream }

        if (stream.serverTarget != null || stream.needsServerPreparation) {
            val prepared = withTimeoutOrNull(remainingMs.coerceAtMost(8_000L)) {
                runCatching { ServerPlayback.prepare(stream) }.getOrNull()
            }
            if (prepared?.playableDirectUrl != null) return prepared
        }

        if (stream.youTubeIdToResolve != null) {
            val resolved = withTimeoutOrNull(remainingMs.coerceAtMost(8_000L)) {
                runCatching { YouTubeStreamResolver.Default.resolve(stream) }.getOrNull()
            }
            if (resolved?.playableDirectUrl != null) return resolved
        }

        if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
            DirectDebridPlaybackResolver.cachedPlayableStream(stream, tune.season, tune.episode)?.let { cached ->
                if (cached.playableDirectUrl != null) return cached
            }
            val resolved = withTimeoutOrNull(remainingMs.coerceAtMost(MAX_DEBRID_RESOLVE_MS)) {
                runCatching {
                    DirectDebridPlaybackResolver.resolveToPlayableStream(stream, tune.season, tune.episode)
                }.getOrNull()
            }
            val playable = (resolved as? DirectDebridPlayableResult.Success)?.stream
            if (playable?.playableDirectUrl != null) return playable
        }
        return null
    }

    private fun failureForCandidates(
        tune: VirtualTune,
        settings: VirtualPlaybackSettings,
        candidates: List<StreamItem>,
    ): StreamResult {
        val directOnly = !settings.allowDebrid && candidates.any {
            DebridSettingsRepository.snapshot().canResolvePlayableLinks &&
                DirectDebridPlaybackResolver.shouldResolveToPlayableStream(it)
        }
        if (directOnly) {
            return StreamResult(
                null,
                "Only torrent/debrid sources were found. Turn on debrid for Lucid TV in Playback settings.",
                skipItem = true,
            )
        }
        if (candidates.any { it.shouldOpenExternally }) {
            return StreamResult(null, "This item only opens in an external app.", skipItem = true)
        }
        val torrentOnly = candidates.any {
            it.p2pInfoHash != null && it.playableDirectUrl == null
        }
        if (torrentOnly && AppFeaturePolicy.p2pEnabled && !P2pSettingsRepository.uiState.value.p2pEnabled) {
            return StreamResult(
                null,
                "This item is torrent-only. Turn on P2P in Playback settings, or add a debrid account.",
                skipItem = true,
            )
        }
        return StreamResult(null, "No playable stream was found for this item.", skipItem = true)
    }

    private fun failureForEmptyCandidates(
        tune: VirtualTune,
        settings: VirtualPlaybackSettings,
    ): StreamResult {
        val addons = AddonRepository.uiState.value.addons
        if (addons.isWaitingForFirstEnabledManifest()) {
            return StreamResult(null, "Add-ons are still loading. Try again in a moment.")
        }
        val compatible = addons.any { addon ->
            addon.enabled && addon.manifest?.supportsStream(tune.item.type, tune.item.id) == true
        }
        return if (!compatible) {
            StreamResult(
                null,
                "No installed add-on can stream this catalog's IDs. Add a stream add-on or use a different catalog.",
            )
        } else if (!settings.allowDebrid) {
            StreamResult(null, "No direct stream was found. Debrid is turned off for Lucid TV.")
        } else {
            StreamResult(null, "No streams were found for this item.")
        }
    }

    private suspend fun awaitAddons() {
        runCatching { AddonRepository.initialize() }
        withTimeoutOrNull(ADDON_WAIT_MS) {
            AddonRepository.uiState.first { state ->
                !state.addons.isWaitingForFirstEnabledManifest() && !state.addons.hasPendingEnabledManifests()
            }
        }
    }

    private fun remember(key: String, result: StreamResult) {
        kotlinx.atomicfu.locks.synchronized(cacheLock) {
            cache[key] = CachedStream(
                stream = result.stream,
                message = result.message,
                atMs = epochMs(),
                fromCache = result.fromCache,
            )
            if (cache.size > 80) {
                val oldest = cache.entries.sortedBy { it.value.atMs }.take(cache.size - 80)
                oldest.forEach { cache.remove(it.key) }
            }
        }
    }

    private fun cacheKey(tune: VirtualTune, settings: VirtualPlaybackSettings): String = listOf(
        tune.specId,
        tune.item.type,
        tune.item.streamId?.takeIf { it.isNotBlank() } ?: tune.item.id,
        tune.item.parentId.orEmpty(),
        tune.season ?: 0,
        tune.episode ?: 0,
        settings.allowDebrid,
    ).joinToString("|")

    private fun linkKey(tune: VirtualTune): String = StreamLinkCacheRepository.contentKey(
        type = tune.item.type,
        videoId = tune.item.streamId?.takeIf { it.isNotBlank() } ?: tune.item.id,
        parentMetaId = tune.item.parentId ?: tune.item.streamId?.takeIf { it.isNotBlank() } ?: tune.item.id,
        season = tune.season,
        episode = tune.episode,
    )

    private fun persistentLink(tune: VirtualTune): StreamItem? {
        val entry = StreamLinkCacheRepository.getValid(linkKey(tune), LINK_TTL_MS) ?: return null
        if (entry.url.isBlank() && entry.infoHash.isNullOrBlank()) return null
        return StreamItem(
            name = entry.streamName,
            url = entry.url.takeIf { it.isNotBlank() },
            infoHash = entry.infoHash,
            fileIdx = entry.fileIdx,
            sources = entry.sources,
            addonName = entry.addonName,
            addonId = entry.addonId,
            streamType = entry.streamType,
            behaviorHints = StreamBehaviorHints(
                filename = entry.filename,
                videoSize = entry.videoSize,
                bingeGroup = entry.bingeGroup,
                proxyHeaders = StreamProxyHeaders(
                    request = entry.requestHeaders.takeIf { it.isNotEmpty() },
                    response = entry.responseHeaders.takeIf { it.isNotEmpty() },
                ),
            ),
        )
    }

    private fun cacheLink(tune: VirtualTune, stream: StreamItem) {
        val url = stream.playableDirectUrl.orEmpty()
        val hash = stream.p2pInfoHash
        if (url.isBlank() && hash.isNullOrBlank()) return
        runCatching {
            StreamLinkCacheRepository.save(
                contentKey = linkKey(tune),
                url = url,
                streamName = stream.streamLabel,
                addonName = stream.addonName,
                addonId = stream.addonId,
                requestHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
                responseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
                filename = stream.behaviorHints.filename,
                videoSize = stream.behaviorHints.videoSize,
                infoHash = hash,
                fileIdx = stream.p2pFileIdx,
                sources = stream.sources,
                bingeGroup = stream.behaviorHints.bingeGroup,
                streamType = stream.streamType,
            )
        }
    }

    private fun relayStream(tune: VirtualTune): StreamItem? {
        val url = tune.item.streamUrl ?: return null
        return StreamItem(
            name = tune.item.title,
            title = tune.item.title,
            url = url,
            addonName = VirtualChannels.SOURCE_NAME,
            addonId = VirtualChannels.SOURCE_ID,
            streamType = when {
                url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) -> "hls"
                url.substringBefore('?').endsWith(".mpd", ignoreCase = true) -> "dash"
                else -> null
            },
            behaviorHints = StreamBehaviorHints(
                proxyHeaders = StreamProxyHeaders(request = tune.item.headers),
            ),
            sourceName = VirtualChannels.SOURCE_NAME,
        )
    }

    private fun buildLaunch(
        channel: LiveChannel,
        profileId: Int,
        tune: VirtualTune,
        stream: StreamItem,
    ): PlayerLaunch? {
        val p2pHash = stream.p2pInfoHash
            ?.takeIf { stream.playableDirectUrl == null && AppFeaturePolicy.p2pEnabled }
        val sourceUrl = stream.playableDirectUrl
            ?: p2pHash?.let { hash -> "torrent://$hash" + (stream.p2pFileIdx?.let { "?index=$it" } ?: "") }
            ?: return null
        return PlayerLaunch(
            profileId = profileId,
            title = channel.name,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizePlaybackResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            externalSubtitles = stream.externalSubtitles,
            streamType = if (p2pHash != null) null else stream.streamType,
            logo = channel.logo,
            poster = tune.item.poster ?: channel.logo,
            background = tune.item.backdrop,
            streamTitle = channel.name,
            streamSubtitle = tune.title,
            bingeGroup = stream.behaviorHints.bingeGroup,
            pauseDescription = tune.item.description,
            providerName = channel.sourceName,
            providerAddonId = LiveTvPlayback.PROVIDER_ID,
            contentType = LiveTvPlayback.CONTENT_TYPE,
            videoId = channel.key,
            parentMetaId = channel.key,
            parentMetaType = LiveTvPlayback.CONTENT_TYPE,
            torrentInfoHash = p2pHash,
            torrentFileIdx = if (p2pHash != null) stream.p2pFileIdx else null,
            torrentFilename = if (p2pHash != null) stream.behaviorHints.filename else null,
            torrentTrackers = if (p2pHash != null) stream.p2pTrackers else emptyList(),
            initialPositionMs = tune.startOffsetMs,
            sourceFilename = stream.behaviorHints.filename,
            stream = stream,
        )
    }

    private fun StreamItem.preparationIdentity(): String = listOf(
        addonId,
        url.orEmpty(),
        infoHash.orEmpty(),
        name.orEmpty(),
        behaviorHints.filename.orEmpty(),
    ).joinToString("|")

    private fun isBlocked(stream: StreamItem): Boolean {
        val identity = stream.preparationIdentity()
        return kotlinx.atomicfu.locks.synchronized(cacheLock) {
            val at = blocked[identity]
            if (at == null) return@synchronized false
            if (epochMs() - at > BLOCKED_TTL_MS) {
                blocked.remove(identity)
                false
            } else {
                true
            }
        }
    }

    private val BLOCKED_TTL_MS = 5L * 60_000L
    private const val MAX_PARALLEL_SOURCES = 12
    private const val MAX_DEBRID_RESOLVE_MS = 20_000L
    private const val DEBRID_CACHE_GRACE_MS = 4_000L
    private const val MAX_ITEM_SKIPS = 2

    private const val OFFLINE = "This Lucid TV channel is offline right now"
}
