package com.nuvio.app.features.livetv.virtual

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.livetv.data.LiveTvPreferences
import com.nuvio.app.features.livetv.data.LiveTvRepository
import com.nuvio.app.features.livetv.model.EpgProgram
import com.nuvio.app.features.livetv.model.LiveChannel
import com.nuvio.app.features.livetv.model.LiveTvClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lucid TV's channels and pools. Virtual channels are a merge layer: they are never written into
 * `LiveTvRepository._channels` (which playlists rebuild), so playlist refreshes cannot remove them.
 * The guide is generated from the same pure schedule as playback.
 *
 * Channels are never generated automatically. The viewer adds only the catalogs they want in the
 * Channel Studio, so the guide contains exactly the channels they created. Every VOD channel walks
 * a real-runtime queue, so the guide's now/next and the stream use one clock; a failed item is
 * skipped and the queue re-anchors to the next one.
 */
internal object VirtualChannelRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = LiveTvPreferences
    private val codec = VirtualChannelCodec
    private val poolMutex = Mutex()
    private val poolPermits = kotlinx.coroutines.sync.Semaphore(POOL_CONCURRENCY)
    private val runtimeLock = kotlinx.atomicfu.locks.SynchronizedObject()

    /** Channels the viewer made in the studio. These are the only channels in the guide. */
    private val _specs = MutableStateFlow<List<VirtualChannelSpec>>(emptyList())
    val specs: StateFlow<List<VirtualChannelSpec>> = _specs.asStateFlow()

    /** The enabled channels that are currently in the guide. */
    private val _allSpecs = MutableStateFlow<List<VirtualChannelSpec>>(emptyList())

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    private val _pools = MutableStateFlow<Map<String, VirtualPoolSnapshot>>(emptyMap())
    val pools: StateFlow<Map<String, VirtualPoolSnapshot>> = _pools.asStateFlow()

    private val _programsWindow = MutableStateFlow<Map<String, List<EpgProgram>>>(emptyMap())
    val programsWindow: StateFlow<Map<String, List<EpgProgram>>> = _programsWindow.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val _settings = MutableStateFlow(VirtualPlaybackSettings())
    val settings: StateFlow<VirtualPlaybackSettings> = _settings.asStateFlow()

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

    private val lastAttemptMs = HashMap<String, Long>()
    /** item id -> epoch ms until which it is skipped after "no playable stream". */
    private val unavailable = HashMap<String, Long>()
    /** Where the current queue starts after skipping a broken item. */
    private val queueStart = HashMap<String, String>()
    /** A skipped item re-anchors the queue so the next programme starts at the skip time. */
    private val scheduleAnchor = HashMap<String, Long>()
    private var initialized = false
    private var tickerStarted = false
    private var playlistObserverStarted = false
    private var specsObserverStarted = false
    private var settingsObserverStarted = false
    private var addonObserverStarted = false

    // ---------------------------------------------------------------- lifecycle

    fun ensureLoaded() {
        if (!initialized) {
            initialized = true
            val stored = codec.decodeSpecs(prefs.currentVirtualChannelsJson())
            // Older builds generated a channel for every installed catalog. Those auto channels
            // are gone now: the viewer decides what to add, so drop any auto entries saved by a
            // backup, sync or test build instead of putting them back in the guide.
            val normalized = normalizeSpecs(stored.filterNot { it.auto })
            _specs.value = normalized
            if (normalized != stored) persistSpecs()
            val decodedPools = codec.decodePools(prefs.currentVirtualPoolsJson())
            val repairedPools = repairPools(decodedPools, normalized)
            // Pools only exist for saved channels. Remove snapshots left behind by the old
            // automatic channels and by deleted test channels.
            val liveIds = normalized.mapTo(HashSet()) { it.id }
            val prunedPools = repairedPools.filterKeys { it in liveIds }
            _pools.value = prunedPools
            if (prunedPools != decodedPools) persistPools()
            _settings.value = codec.decodePlaybackSettings(prefs.currentVirtualSettingsJson())
            rebuildSpecs()
            rebuildChannels()
            rebuildWindow()
            refreshStale(forceEmpty = true)
        }
        if (!tickerStarted) {
            tickerStarted = true
            scope.launch {
                while (true) {
                    delay(60_000L)
                    if (_allSpecs.value.isEmpty()) continue
                    rebuildWindow()
                    refreshStale(forceEmpty = false)
                }
            }
        }
        if (!playlistObserverStarted) {
            playlistObserverStarted = true
            // Relay pools are built from Live TV's channels; rebuild them when playlists arrive or change.
            scope.launch {
                LiveTvRepository.channels.collect { channels ->
                    if (channels.isEmpty()) return@collect
                    _allSpecs.value
                        .filter { it.enabled && it.source is VirtualSource.PlaylistGroup }
                        .forEach { spec -> refreshPool(spec.id, force = true) }
                }
            }
        }
        if (!specsObserverStarted) {
            specsObserverStarted = true            // A backup restore or Drive sync can replace the whole channel list; pick it up.
            scope.launch {
                prefs.virtualChannelsJson.collect { raw ->
                    val stored = codec.decodeSpecs(raw)
                    val decoded = normalizeSpecs(stored.filterNot { it.auto })
                    if (decoded == _specs.value) return@collect
                    _specs.value = decoded
                    if (decoded != stored) persistSpecs()
                    val liveIds = decoded.mapTo(HashSet()) { it.id }
                    val prunedPools = _pools.value.filterKeys { it in liveIds }
                    if (prunedPools != _pools.value) {
                        _pools.value = prunedPools
                        persistPools()
                    }
                    rebuildSpecs()
                    rebuildChannels()
                    rebuildWindow()
                    refreshStale(forceEmpty = true)
                }
            }
        }
        if (!settingsObserverStarted) {
            settingsObserverStarted = true
            scope.launch {
                prefs.virtualSettingsJson.collect { raw ->
                    _settings.value = codec.decodePlaybackSettings(raw)
                }
            }
            scope.launch {
                prefs.settings.collect {
                    rebuildSpecs()
                    rebuildChannels()
                    rebuildWindow()
                    refreshStale(forceEmpty = false)
                }
            }
        }
        if (!addonObserverStarted) {
            addonObserverStarted = true
            // Channel definitions do not depend on add-ons, but a rules channel built before the
            // manifests arrived still needs its pool. Rebroadcast when manifests change so Lucid
            // TV can fill that pool; no channels are created here.
            scope.launch {
                AddonRepository.uiState.collect { state ->
                    rebuildSpecs()
                    rebuildChannels()
                    rebuildWindow()
                    // A rules channel built before the manifests arrived has no pool yet. Try it
                    // again as soon as at least one add-on is usable.
                    if (state.addons.any { it.enabled && it.manifest != null }) {
                        _allSpecs.value
                            .filter { it.enabled && it.source is VirtualSource.Rules && _pools.value[it.id]?.items.isNullOrEmpty() }
                            .forEach { refreshPool(it.id, force = true) }
                    }
                    refreshStale(forceEmpty = false)
                }
            }
        }
    }
    fun hasChannels(): Boolean = _allSpecs.value.isNotEmpty()

    /** Saves the HTTP/debrid and prefetch choices. */
    fun updateSettings(settings: VirtualPlaybackSettings) {
        val clean = settings.copy(
            prefetchNext = settings.prefetchNext.coerceIn(
                VirtualPlaybackSettings.MIN_PREFETCH,
                VirtualPlaybackSettings.MAX_PREFETCH,
            ),
        )
        _settings.value = clean
        prefs.setVirtualSettingsJson(codec.encodePlaybackSettings(clean))
    }

    /** Regenerates the near-term guide window (called when Live TV refreshes). */
    fun refreshWindow() {
        if (_allSpecs.value.isEmpty()) return
        rebuildWindow()
    }

    // ---------------------------------------------------------------- edits

    fun create(spec: VirtualChannelSpec): VirtualChannelSpec {
        ensureLoaded()
        val now = LiveTvClock.nowMs()
        val taken = _specs.value.mapTo(HashSet()) { it.id }
        val id = spec.id.takeIf { it.isNotBlank() && it !in taken } ?: VirtualChannelCodec.newId()
        val created = spec.copy(
            id = id,
            name = spec.name.trim().ifBlank { "Lucid TV channel" },
            group = VirtualChannels.DEFAULT_GROUP,
            createdAtMs = spec.createdAtMs.takeIf { it > 0 } ?: now,
            updatedAtMs = now,
            schemaVersion = VirtualChannels.SCHEMA_VERSION,
            auto = false,
        )
        _specs.value = _specs.value + created
        persistSpecs()
        rebuildSpecs()
        rebuildChannels()
        rebuildWindow()
        refreshPool(created.id, force = true)
        return created
    }

    fun update(spec: VirtualChannelSpec) {
        ensureLoaded()
        val now = LiveTvClock.nowMs()
        val before = _specs.value.firstOrNull { it.id == spec.id } ?: return
        val updated = spec.copy(
            name = spec.name.trim().ifBlank { before.name },
            group = VirtualChannels.DEFAULT_GROUP,
            createdAtMs = before.createdAtMs.takeIf { it > 0L } ?: now,
            updatedAtMs = now,
            auto = false,
        )
        _specs.value = if (_specs.value.any { it.id == updated.id }) {
            _specs.value.map { if (it.id == updated.id) updated else it }
        } else {
            _specs.value + updated
        }
        persistSpecs()
        rebuildSpecs()
        rebuildChannels()
        rebuildWindow()
        refreshPool(updated.id, force = true)
    }

    fun delete(id: String) {
        ensureLoaded()
        if (_specs.value.none { it.id == id }) return
        _specs.value = _specs.value.filterNot { it.id == id }
        _pools.value = _pools.value - id
        _errors.value = _errors.value - id
        kotlinx.atomicfu.locks.synchronized(runtimeLock) {
            lastAttemptMs.remove(id)
            queueStart.remove(id)
            scheduleAnchor.remove(id)
            unavailable.keys.removeAll { it.startsWith("$id|") }
        }
        persistSpecs()
        persistPools()
        rebuildSpecs()
        rebuildChannels()
        rebuildWindow()
    }

    /** Removes every Lucid TV channel and its cached guide data. Add-ons and playlists stay. */
    fun deleteAll() {
        ensureLoaded()
        if (_specs.value.isEmpty()) return
        _specs.value = emptyList()
        _pools.value = emptyMap()
        _errors.value = emptyMap()
        kotlinx.atomicfu.locks.synchronized(runtimeLock) {
            lastAttemptMs.clear()
            queueStart.clear()
            scheduleAnchor.clear()
            unavailable.clear()
        }
        persistSpecs()
        persistPools()
        rebuildSpecs()
        rebuildChannels()
        rebuildWindow()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        ensureLoaded()
        val spec = _specs.value.firstOrNull { it.id == id } ?: return
        update(spec.copy(enabled = enabled))
    }

    fun duplicate(id: String): VirtualChannelSpec? {
        ensureLoaded()
        val spec = _specs.value.firstOrNull { it.id == id } ?: return null
        return create(
            spec.copy(
                id = "",
                name = "${spec.name} copy",
                number = null,
                createdAtMs = 0L,
                updatedAtMs = 0L,
                auto = false,
            )
        )
    }

    fun spec(id: String): VirtualChannelSpec? = _specs.value.firstOrNull { it.id == id }

    fun pool(id: String): VirtualPoolSnapshot? = _pools.value[id]

    // ---------------------------------------------------------------- pools

    fun refreshAll() {
        ensureLoaded()
        _allSpecs.value.filter { it.enabled }.forEach { refreshPool(it.id, force = true) }
    }

    fun refreshPool(id: String, force: Boolean = false) {
        ensureLoaded()
        val spec = _allSpecs.value.firstOrNull { it.id == id } ?: return
        if (spec.enabled.not() && force.not()) return
        if (id in _refreshing.value) return
        if (!force) {
            val snapshot = _pools.value[id]
            val staleAfter = spec.pool.refreshHours.coerceAtLeast(1) * 3_600_000L
            val fresh = snapshot != null && snapshot.items.isNotEmpty() && snapshot.fetchedAtMs > 0L &&
                LiveTvClock.nowMs() - snapshot.fetchedAtMs < staleAfter
            if (fresh) return
            val last = lastAttemptMs[id] ?: 0L
            if (LiveTvClock.nowMs() - last < RETRY_AFTER_MS) return
        }
        lastAttemptMs[id] = LiveTvClock.nowMs()
        _refreshing.value = _refreshing.value + id
        scope.launch {
            try {
                val result = runCatching {
                    poolPermits.withPermit { buildPool(spec) }
                }
                result.onSuccess { items ->
                    poolMutex.withLock {
                        if (_specs.value.any { it.id == id }) {
                            _pools.value = _pools.value + (id to VirtualPoolSnapshot(items = items, fetchedAtMs = LiveTvClock.nowMs()))
                            _errors.value = _errors.value - id
                        }
                    }
                    persistPools()
                    rebuildChannels()
                    rebuildWindow()
                }.onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    val message = error.message?.takeIf { it.isNotBlank() } ?: "Could not load this channel's catalog"
                    poolMutex.withLock {
                        if (_specs.value.any { it.id == id }) {
                            _pools.value = _pools.value + (id to (_pools.value[id] ?: VirtualPoolSnapshot()).copy(error = message))
                            _errors.value = _errors.value + (id to message)
                        }
                    }
                    rebuildWindow()
                }
            } finally {
                _refreshing.value = _refreshing.value - id
            }
        }
    }

    private fun refreshStale(forceEmpty: Boolean) {
        val now = LiveTvClock.nowMs()
        _allSpecs.value.filter { it.enabled }.forEach { spec ->
            val snapshot = _pools.value[spec.id]
            val staleAfter = spec.pool.refreshHours.coerceAtLeast(1) * 3_600_000L
            val empty = snapshot == null || snapshot.items.isEmpty()
            val fresh = snapshot != null && snapshot.items.isNotEmpty() && snapshot.fetchedAtMs > 0L && now - snapshot.fetchedAtMs < staleAfter
            if (fresh) return@forEach
            if (!forceEmpty && empty && (lastAttemptMs[spec.id] ?: 0L) > 0L && now - (lastAttemptMs[spec.id] ?: 0L) < RETRY_AFTER_MS) return@forEach
            refreshPool(spec.id, force = forceEmpty && snapshot == null)
        }
    }

    private suspend fun buildPool(spec: VirtualChannelSpec): List<VirtualItem> = withContext(Dispatchers.Default) {
        val items = when (val source = spec.source) {
            is VirtualSource.AddonCatalog -> {
                fetchCatalogPool(source, spec)
            }
            is VirtualSource.AddonItem -> {
                when (spec.kind) {
                    VirtualChannelKind.SERIES_MARATHON -> {
                        val details = MetaDetailsRepository.fetch(source.itemType, source.itemId) ?: return@withContext emptyList()
                        episodesOf(details)
                    }
                    VirtualChannelKind.COLLECTION_LOOP -> {
                        val details = MetaDetailsRepository.fetch(source.itemType, source.itemId)
                        when {
                            details == null -> listOf(
                                VirtualItem(
                                    id = source.itemId,
                                    streamId = source.itemId,
                                    type = source.itemType,
                                    title = source.itemId,
                                )
                            )
                            source.itemType == "series" -> episodesOf(details)
                            else -> listOf(itemOf(details, source.itemType))
                        }
                    }
                    VirtualChannelKind.ADDON_ROTATE -> {
                        val details = MetaDetailsRepository.fetch(source.itemType, source.itemId)
                        if (details != null && source.itemType == "series") {
                            listOf(seriesItemOf(details))
                        } else if (details != null) {
                            listOf(itemOf(details, source.itemType))
                        } else {
                            listOf(
                                VirtualItem(
                                    id = source.itemId,
                                    streamId = source.itemId,
                                    type = source.itemType,
                                    title = source.itemId,
                                )
                            )
                        }
                    }
                    else -> {
                        val details = MetaDetailsRepository.fetch(source.itemType, source.itemId)
                        if (details != null) listOf(seriesItemOf(details)) else emptyList()
                    }
                }
            }
            is VirtualSource.ManualItems -> source.items
            is VirtualSource.PlaylistGroup -> {
                val all = LiveTvRepository.channels.value
                all.asSequence()
                    .filter { it.sourceId == source.sourceId }
                    .filter { source.groupId == null || it.groupId == source.groupId }
                    .map { channel ->
                        VirtualItem(
                            id = "live:" + channel.key,
                            type = "live",
                            title = channel.name,
                            poster = channel.thumbnail ?: channel.logo,
                            description = channel.group,
                            streamUrl = channel.url,
                            headers = channel.headers,
                        )
                    }
                    .toList()
            }
            is VirtualSource.Rules -> fetchRulesPool(source, spec)
        }
        val filtered = filterItems(items, spec)
        orderItems(filtered.distinctBy { it.id }, spec).take(spec.pool.maxItems.coerceIn(1, 10_000))
    }

    private suspend fun fetchCatalogPool(source: VirtualSource.AddonCatalog, spec: VirtualChannelSpec): List<VirtualItem> {
        val items = ArrayList<MetaPreview>()
        var skip: Int? = 0
        var pages = 0
        val cap = spec.pool.maxItems.coerceIn(1, 10_000)
        // A series catalog is expanded into episodes. Fetching hundreds of shows would be
        // pointless: only the first few fill the pool with episodes.
        val previewCap = if (source.contentType.equals("series", true)) {
            minOf(cap, MAX_SERIES_TO_EXPAND)
        } else {
            cap
        }
        while (skip != null && items.size < previewCap && pages < MAX_CATALOG_PAGES) {
            val page = runCatching {
                fetchCatalogPage(
                    manifestUrl = source.manifestUrl,
                    type = source.contentType,
                    catalogId = source.catalogId,
                    genre = source.genre,
                    search = source.search,
                    skip = skip,
                    maxItems = (previewCap - items.size).coerceAtMost(100),
                )
            }.getOrElse { error ->
                if (items.isEmpty()) throw error
                break
            }
            if (page.items.isEmpty()) break
            items += page.items
            val next = page.nextSkip
            if (next == null || next <= (skip ?: 0)) break
            skip = next
            pages++
        }
        val previews = items
            .filter { source.contentType.isBlank() || it.type.isBlank() || it.type.equals(source.contentType, ignoreCase = true) }
            .filterNot { item -> item.genres.any { genre -> genre.equals("Adult", ignoreCase = true) } }
            .map { item -> itemOf(item, source.contentType) }
        val hasSeries = previews.any { it.type.equals("series", true) }
        val expanded = if (hasSeries) expandSeriesItems(previews) else previews
        return enrichMissingRuntimes(expanded).distinctBy { it.id }
    }

    /**
     * A series is not playable without an episode. Expand series catalog entries into their
     * episodes here, so both the guide and the player use the episode id, title and runtime.
     */
    private suspend fun expandSeriesItems(previews: List<VirtualItem>): List<VirtualItem> {
        val series = previews.filter { it.type.equals("series", true) }.take(MAX_SERIES_TO_EXPAND)
        if (series.isEmpty()) return previews
        val details = coroutineScope {
            series.map { item ->
                async {
                    item to runCatching { MetaDetailsRepository.fetch("series", item.id) }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                        .getOrNull()
                }
            }.awaitAll()
        }
        val byId = details.toMap()
        val out = ArrayList<VirtualItem>(previews.size)
        val expanded = ArrayList<List<VirtualItem>>()
        previews.forEach { preview ->
            if (!preview.type.equals("series", true)) {
                out += preview
                return@forEach
            }
            val meta = byId[preview]
            val episodes = meta?.let(::episodesOf)
                ?.take(MAX_EPISODES_PER_SERIES)
                .orEmpty()
            if (episodes.isEmpty()) out += preview else expanded += episodes
        }
        // One episode from each series per round, so the pool is a sampler of the catalog's
        // shows instead of the first show's whole season.
        val rounds = expanded.maxOfOrNull { it.size } ?: 0
        for (round in 0 until rounds) {
            expanded.forEach { episodes -> episodes.getOrNull(round)?.let(out::add) }
        }
        return out
    }

    /**
     * Catalog lists often carry a runtime, but not always. Fill the missing ones so the guide
     * does not fall back to a guessed slot for films whose length is knowable. Lookups are
     * bounded: a broken or slow add-on must not hold the whole channel for minutes.
     */
    private suspend fun enrichMissingRuntimes(items: List<VirtualItem>): List<VirtualItem> {
        if (items.none { it.runtimeMs == null && it.type != "live" && it.type != "series" }) return items
        val budget = kotlinx.atomicfu.atomic(MAX_RUNTIME_LOOKUPS)
        val permits = kotlinx.coroutines.sync.Semaphore(META_CONCURRENCY)
        return coroutineScope {
            items.map { item ->
                async {
                    if (item.runtimeMs != null || item.type == "live" || item.type == "series" ||
                        budget.getAndDecrement() <= 0
                    ) {
                        return@async item
                    }
                    val details = permits.withPermit {
                        withTimeoutOrNull(12_000L) {
                            runCatching { MetaDetailsRepository.fetch(item.type, item.id) }
                                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                                .getOrNull()
                        }
                    }
                    val runtime = details?.let { runtimeMsOf(it.runtime) }
                    if (runtime != null) item.copy(runtimeMs = runtime) else item
                }
            }.awaitAll()
        }
    }

    private suspend fun fetchRulesPool(source: VirtualSource.Rules, spec: VirtualChannelSpec): List<VirtualItem> {
        val addons = AddonRepository.uiState.value.addons
            .filter { it.enabled && it.manifest != null }
            .filter { addon ->
                (source.manifestUrls.isEmpty() || addon.manifestUrl in source.manifestUrls) &&
                    (source.addonIds.isEmpty() || addon.manifest?.id in source.addonIds)
            }
        val out = ArrayList<VirtualItem>()
        for (addon in addons) {
            val manifest = addon.manifest ?: continue
            val catalogs = manifest.catalogs
                .filter { source.contentType.isBlank() || it.type.equals(source.contentType, ignoreCase = true) }
                .take(MAX_RULE_CATALOGS)
            for (catalog in catalogs) {
                if (out.size >= spec.pool.maxItems) break
                val items = runCatching {
                    fetchCatalogPage(
                        manifestUrl = addon.manifestUrl,
                        type = catalog.type,
                        catalogId = catalog.id,
                        genre = source.genres.firstOrNull(),
                        skip = 0,
                        maxItems = (spec.pool.maxItems - out.size).coerceAtMost(150),
                    ).items
                }.getOrDefault(emptyList())
                out += items.map { item -> itemOf(item, source.contentType) }
            }
            if (out.size >= spec.pool.maxItems) break
        }
        return out
    }

    private fun itemOf(item: MetaPreview, defaultType: String = ""): VirtualItem = VirtualItem(
        id = item.id,
        streamId = item.id,
        type = item.type.ifBlank { defaultType },
        title = item.name,
        poster = item.poster,
        backdrop = item.banner ?: item.landscapePoster,
        description = item.description,
        year = yearOf(item.rawReleaseDate ?: item.releaseInfo),
        rating = item.imdbRating?.toFloatOrNull(),
        runtimeMs = runtimeMsOf(item.runtime),
        genres = item.genres,
    )

    private fun seriesItemOf(details: MetaDetails): VirtualItem = VirtualItem(
        id = details.id,
        streamId = details.id,
        type = "series",
        title = details.name,
        poster = details.poster,
        backdrop = details.background,
        description = details.description,
        year = yearOf(details.releaseInfo),
        rating = details.imdbRating?.toFloatOrNull(),
        runtimeMs = runtimeMsOf(details.runtime),
        genres = details.genres,
        parentId = details.id,
    )

    private fun itemOf(details: MetaDetails, type: String): VirtualItem = VirtualItem(
        id = details.id,
        streamId = details.id,
        type = type,
        title = details.name,
        poster = details.poster,
        backdrop = details.background,
        description = details.description,
        year = yearOf(details.releaseInfo),
        rating = details.imdbRating?.toFloatOrNull(),
        runtimeMs = runtimeMsOf(details.runtime),
        genres = details.genres,
        parentId = details.id,
    )

    private fun episodesOf(details: MetaDetails): List<VirtualItem> =
        details.videos.asSequence()
            .filter { it.available }
            .filter { (it.season ?: 0) > 0 && it.episode != null }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            .map { video -> episodeOf(details, video) }
            .toList()

    private fun episodeOf(details: MetaDetails, video: MetaVideo): VirtualItem {
        val season = video.season
        val episode = video.episode
        val id = video.id.takeIf { it.isNotBlank() }
            ?: "${details.id}:$season:$episode"
        return VirtualItem(
            id = id,
            // Stream add-ons build /stream/series/<videoId>.json. For episodes that id must be
            // the episode id ("tt123:1:1"); the parent series id only belongs in parentId.
            streamId = id,
            type = "series",
            title = details.name,
            poster = video.thumbnail ?: details.poster,
            backdrop = details.background,
            description = video.overview ?: details.description,
            year = yearOf(video.released ?: details.releaseInfo),
            rating = video.rating?.toFloat(),
            runtimeMs = video.runtime?.takeIf { it > 0 }?.times(60_000L)
                ?: runtimeMsOf(details.runtime),
            genres = details.genres,
            season = season,
            episode = episode,
            episodeTitle = video.title,
            parentId = details.id,
        )
    }

    private fun filterItems(items: List<VirtualItem>, spec: VirtualChannelSpec): List<VirtualItem> {
        val rules = spec.pool
        val source = spec.source
        val include = when (source) {
            is VirtualSource.Rules -> rules.includeGenres + source.genres
            else -> rules.includeGenres
        }
        val minYear = rules.minYear ?: (source as? VirtualSource.Rules)?.minYear
        val maxYear = rules.maxYear ?: (source as? VirtualSource.Rules)?.maxYear
        val minRating = rules.minRating ?: (source as? VirtualSource.Rules)?.minRating
        val keyword = rules.keyword ?: (source as? VirtualSource.Rules)?.keyword
        val keywordLower = keyword?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return items.filter { item ->
            val yearOk = (minYear == null || (item.year ?: 0) >= minYear) &&
                (maxYear == null || (item.year ?: Int.MAX_VALUE) <= maxYear)
            val ratingOk = minRating == null || (item.rating ?: 0f) >= minRating
            val keywordOk = keywordLower == null ||
                item.title.lowercase().contains(keywordLower) ||
                item.description.orEmpty().lowercase().contains(keywordLower)
            val genres = itemGenres(item)
            val includeOk = include.isEmpty() || include.any { wanted ->
                genres.any { it.equals(wanted, ignoreCase = true) }
            }
            val excludeOk = rules.excludeGenres.none { unwanted ->
                genres.any { it.equals(unwanted, ignoreCase = true) }
            }
            yearOk && ratingOk && keywordOk && includeOk && excludeOk
        }
    }

    private fun itemGenres(item: VirtualItem): List<String> = item.genres

    private fun orderItems(items: List<VirtualItem>, spec: VirtualChannelSpec): List<VirtualItem> {
        val order = when (spec.playout.order) {
            VirtualOrder.MANUAL -> spec.pool.order
            else -> spec.playout.order
        }
        return when (order) {
            VirtualOrder.SHUFFLE -> items.sortedBy { VirtualSchedule.stableHash("${spec.id}|${it.id}") }
            VirtualOrder.SEQUENTIAL, VirtualOrder.MANUAL -> items
            VirtualOrder.RELEASE -> items.sortedWith(
                compareByDescending<VirtualItem> { it.year ?: 0 }.thenBy { it.title.lowercase() }
            )
            VirtualOrder.AIR -> items.sortedWith(
                compareBy<VirtualItem> { it.season ?: 0 }
                    .thenBy { it.episode ?: 0 }
                    .thenBy { it.title.lowercase() }
            )
        }
    }

    // ---------------------------------------------------------------- channels and EPG

    /**
     * Lucid TV only shows channels the viewer added in the Channel Studio. Catalogs are never
     * turned into channels automatically, so the guide stays exactly what the viewer chose.
     */
    private fun rebuildSpecs() {
        val liveSettings = prefs.settings.value
        val custom = _specs.value
        if (!liveSettings.lucidTvEnabled) {
            _allSpecs.value = emptyList()
            return
        }
        val hadChannels = _allSpecs.value.isNotEmpty()
        val all = custom.filter { it.enabled }
        _allSpecs.value = all
        // When Lucid TV is the only Live TV source (no playlist), the guide must still start
        // Live TV's repository so Home rows and the guide have their channel list.
        if (!hadChannels && all.isNotEmpty()) {
            runCatching { LiveTvRepository.ensureLoaded() }
        }
    }

    private fun rebuildChannels() {
        val enabled = _allSpecs.value.filter { it.enabled }
        _channels.value = enabled.mapIndexed { index, spec ->
            VirtualChannels.channelFor(spec, index, _pools.value[spec.id]?.items?.firstOrNull()?.poster)
        }
    }

    private fun rebuildWindow() {
        val now = LiveTvClock.nowMs()
        val map = HashMap<String, List<EpgProgram>>()
        _allSpecs.value.filter { it.enabled }.forEach { spec ->
            val items = availablePool(spec, _pools.value[spec.id]?.items.orEmpty())
            if (items.isEmpty()) return@forEach
            val programs = programsFor(spec, items, now - WINDOW_PAST_MS, now + WINDOW_FUTURE_MS)
            if (programs.isNotEmpty()) map[spec.channelKey] = programs
        }
        _programsWindow.value = map
    }

    private fun programsFor(
        spec: VirtualChannelSpec,
        items: List<VirtualItem>,
        fromMs: Long,
        toMs: Long,
    ): List<EpgProgram> {
        if (items.isEmpty() || toMs <= fromMs) return emptyList()
        val ordered = orderedPool(spec, availablePool(spec, items))
        if (ordered.isEmpty()) return emptyList()
        val segments = VirtualSchedule.playoutSegments(
            anchorMs = queueAnchor(spec),
            items = ordered,
            fallbackDurationMs = fallbackDurationMs(spec),
            fromMs = fromMs,
            toMs = toMs,
        )
        return segments.map(VirtualSchedule::toProgram)
    }

    fun programsIn(keys: Collection<String>, fromMs: Long, toMs: Long): Map<String, List<EpgProgram>> {
        if (keys.isEmpty()) return emptyMap()
        val virtualKeys = keys.filter { VirtualChannels.isVirtualKey(it) }.toSet()
        if (virtualKeys.isEmpty()) return emptyMap()
        val out = HashMap<String, List<EpgProgram>>()
        _allSpecs.value.filter { it.enabled && it.channelKey in virtualKeys }.forEach { spec ->
            val items = _pools.value[spec.id]?.items.orEmpty()
            if (items.isEmpty()) return@forEach
            val programs = programsFor(spec, items, fromMs, toMs)
            if (programs.isNotEmpty()) out[spec.channelKey] = programs
        }
        return out
    }

    fun programDetails(channelKey: String, startMs: Long): EpgProgram? {
        val id = VirtualChannels.specIdOf(channelKey) ?: return null
        val spec = _allSpecs.value.firstOrNull { it.id == id && it.enabled } ?: return null
        val items = _pools.value[spec.id]?.items.orEmpty()
        if (items.isEmpty()) return null
        return programsFor(spec, items, startMs, startMs + 1L)
            .firstOrNull { startMs >= it.startMs && startMs < it.stopMs }
            ?: programsFor(spec, items, startMs - 1L, startMs + 1L).firstOrNull()
    }

    // ---------------------------------------------------------------- tuning

    /**
     * Every VOD channel walks a queue of real media runtimes from a stable anchor. What the
     * guide says is on now is therefore exactly the item (and join offset) that tuning starts,
     * even when a film is two hours long and the fallback slot says ninety minutes.
     */
    suspend fun tune(channelKey: String, nowMs: Long = LiveTvClock.nowMs()): VirtualTune? {
        val id = VirtualChannels.specIdOf(channelKey) ?: return null
        val spec = _allSpecs.value.firstOrNull { it.id == id && it.enabled } ?: return null
        val items = _pools.value[spec.id]?.items.orEmpty()
        if (items.isEmpty()) return null
        val ordered = orderedPool(spec, availablePool(spec, items))
        if (ordered.isEmpty()) return null
        val segment = VirtualSchedule.playoutAt(
            nowMs = nowMs,
            anchorMs = queueAnchor(spec),
            items = ordered,
            fallbackDurationMs = fallbackDurationMs(spec),
        ) ?: return null
        val item = segment.item
        val relay = item.streamUrl != null
        val duration = (segment.endMs - segment.startMs).coerceAtLeast(1L)
        return VirtualTune(
            channelKey = channelKey,
            specId = spec.id,
            item = item,
            season = item.season,
            episode = item.episode,
            episodeTitle = item.episodeTitle,
            title = tuneTitle(item),
            startOffsetMs = if (relay) 0L else VirtualSchedule.joinOffsetMs(
                nowMs = nowMs,
                itemStartMs = segment.startMs,
                itemDurationMs = duration,
                join = spec.join,
                channelId = channelKey,
            ),
            slotStartMs = segment.startMs,
            slotEndMs = segment.endMs,
            relay = relay,
        )
    }

    private fun tuneTitle(item: VirtualItem): String {
        val label = VirtualSchedule.episodeLabel(item.season, item.episode)
        return when {
            item.episodeTitle.isNullOrBlank() && label.isBlank() -> item.title
            item.episodeTitle.isNullOrBlank() -> "${item.title} · $label"
            label.isBlank() -> "${item.title} · ${item.episodeTitle}"
            else -> "${item.title} · $label · ${item.episodeTitle}"
        }
    }

    /**
     * The next [count] items after [fromMs], for prefetching. Mirrors [tune] but does not resolve
     * streams; the playback cache does that in the background.
     */
    suspend fun upcomingTunes(
        channelKey: String,
        fromMs: Long = LiveTvClock.nowMs(),
        count: Int = VirtualPlaybackSettings().prefetchNext,
    ): List<VirtualTune> {
        val id = VirtualChannels.specIdOf(channelKey) ?: return emptyList()
        val spec = _allSpecs.value.firstOrNull { it.id == id && it.enabled } ?: return emptyList()
        val items = _pools.value[spec.id]?.items.orEmpty()
        if (items.isEmpty() || count <= 0) return emptyList()
        val ordered = orderedPool(spec, availablePool(spec, items))
        if (ordered.isEmpty()) return emptyList()
        val anchor = queueAnchor(spec)
        val durations = durations(spec, ordered)
        val cycle = durations.sum().coerceAtLeast(1L)
        val current = VirtualSchedule.playoutAt(fromMs, anchor, ordered, fallbackDurationMs(spec))
            ?: return emptyList()
        var offset = VirtualSchedule.floorMod(current.startMs - anchor, cycle)
        var index = 0
        var accumulated = 0L
        while (index < ordered.lastIndex && offset >= accumulated + durations[index]) {
            accumulated += durations[index]
            index++
        }
        val out = ArrayList<VirtualTune>(count)
        var cursor = current.endMs
        repeat(count) {
            index = (index + 1) % ordered.size
            val item = ordered[index]
            val duration = durations[index]
            out += tuneOf(
                spec = spec,
                channelKey = channelKey,
                item = item,
                startMs = cursor,
                endMs = cursor + duration,
                startOffsetMs = 0L,
            )
            cursor += duration
        }
        return out
    }

    private fun tuneOf(
        spec: VirtualChannelSpec,
        channelKey: String,
        item: VirtualItem,
        startMs: Long,
        endMs: Long,
        startOffsetMs: Long,
    ): VirtualTune {
        val relay = item.streamUrl != null
        return VirtualTune(
            channelKey = channelKey,
            specId = spec.id,
            item = item,
            season = item.season,
            episode = item.episode,
            episodeTitle = item.episodeTitle,
            title = tuneTitle(item),
            startOffsetMs = if (relay) 0L else startOffsetMs,
            slotStartMs = startMs,
            slotEndMs = endMs,
            relay = relay,
        )
    }

    // ---------------------------------------------------------------- queue control

    /** The chosen order, then rotated so a failed item's successor starts the queue after a skip. */
    private fun orderedPool(spec: VirtualChannelSpec, pool: List<VirtualItem>): List<VirtualItem> {
        val ordered = orderItems(pool, spec)
        val startId = kotlinx.atomicfu.locks.synchronized(runtimeLock) { queueStart[spec.id] }
            ?: return ordered
        val index = ordered.indexOfFirst { it.id == startId }
        return if (index <= 0) ordered else ordered.drop(index) + ordered.take(index)
    }

    private fun durations(spec: VirtualChannelSpec, pool: List<VirtualItem>): List<Long> =
        VirtualSchedule.durationsOf(pool, fallbackDurationMs(spec))

    private fun fallbackDurationMs(spec: VirtualChannelSpec): Long =
        spec.rotationMinutes.coerceAtLeast(1) * 60_000L

    private fun queueAnchor(spec: VirtualChannelSpec): Long =
        kotlinx.atomicfu.locks.synchronized(runtimeLock) { scheduleAnchor[spec.id] }
            ?: spec.playout.anchorMs.takeIf { it > 0L }
            ?: spec.createdAtMs.takeIf { it > 0L }
            ?: 0L

    /** Items skipped after "no playable stream", until the retry window expires. */
    private fun availablePool(spec: VirtualChannelSpec, pool: List<VirtualItem>): List<VirtualItem> {
        if (pool.isEmpty()) return pool
        val now = LiveTvClock.nowMs()
        val prefix = "${spec.id}|"
        return kotlinx.atomicfu.locks.synchronized(runtimeLock) {
            unavailable.keys.removeAll { key -> key.startsWith(prefix) && (unavailable[key] ?: 0L) <= now }
            pool.filter { item -> (unavailable["$prefix${item.id}"] ?: Long.MAX_VALUE) > now }
        }
    }

    /**
     * The current item has no playable stream: skip it and start the queue at the next item now.
     * The generated guide is rebuilt from that point, so the guide and the player stay in step.
     *
     * @return true when another item is available and the channel can be tuned again.
     */
    fun markUnplayable(channelKey: String, itemId: String): Boolean {
        val spec = _allSpecs.value.firstOrNull { it.channelKey == channelKey } ?: return false
        val pool = availablePool(spec, _pools.value[spec.id]?.items.orEmpty())
        if (pool.size <= 1) return false
        val ordered = orderedPool(spec, pool)
        val failedIndex = ordered.indexOfFirst { it.id == itemId }
        val next = ordered.drop((failedIndex + 1).coerceAtLeast(0))
            .firstOrNull { it.id != itemId }
            ?: ordered.firstOrNull { it.id != itemId }
            ?: return false
        kotlinx.atomicfu.locks.synchronized(runtimeLock) {
            unavailable["${spec.id}|$itemId"] = LiveTvClock.nowMs() + UNPLAYABLE_TTL_MS
        }
        moveQueueStart(spec, next.id)
        clearError(channelKey)
        return true
    }

    /**
     * A media item ended early (a shorter file than its metadata said, or a dead source that
     * played nothing). Move the queue's clock to the item after [itemId] and start it now.
     */
    fun advanceAfter(channelKey: String, itemId: String): Boolean {
        val spec = _allSpecs.value.firstOrNull { it.channelKey == channelKey } ?: return false
        val pool = availablePool(spec, _pools.value[spec.id]?.items.orEmpty())
        if (pool.size <= 1) return false
        val ordered = orderedPool(spec, pool)
        val index = ordered.indexOfFirst { it.id == itemId }
        if (index < 0) return false
        val next = ordered[(index + 1) % ordered.size]
        if (next.id == itemId) return false
        moveQueueStart(spec, next.id)
        return true
    }

    private fun moveQueueStart(spec: VirtualChannelSpec, itemId: String) {
        kotlinx.atomicfu.locks.synchronized(runtimeLock) {
            queueStart[spec.id] = itemId
            scheduleAnchor[spec.id] = LiveTvClock.nowMs()
        }
        rebuildWindow()
    }

    fun reportError(channelKey: String, message: String) {
        val specId = VirtualChannels.specIdOf(channelKey) ?: return
        _errors.value = _errors.value + (specId to message)
    }

    fun clearError(channelKey: String) {
        val specId = VirtualChannels.specIdOf(channelKey) ?: return
        if (specId in _errors.value) _errors.value = _errors.value - specId
    }

    // ---------------------------------------------------------------- import / export

    fun exportPack(): String = VirtualChannelCodec.encodePack(
        VirtualChannelPack(
            schemaVersion = VirtualChannels.SCHEMA_VERSION,
            exportedAtMs = LiveTvClock.nowMs(),
            channels = _specs.value,
        )
    )

    /** Imports every channel as a new copy; returns how many were added. */
    fun importPack(raw: String): Int {
        ensureLoaded()
        val pack = VirtualChannelCodec.decodePack(raw)
        if (pack.channels.isEmpty()) return 0
        val taken = _specs.value.mapTo(HashSet()) { it.id }
        val now = LiveTvClock.nowMs()
        val additions = pack.channels.map { spec ->
            val id = spec.id.takeIf { it.isNotBlank() && it !in taken } ?: VirtualChannelCodec.newId()
            spec.copy(
                id = id,
                name = spec.name,
                group = VirtualChannels.DEFAULT_GROUP,
                createdAtMs = now,
                updatedAtMs = now,
                schemaVersion = VirtualChannels.SCHEMA_VERSION,
                auto = false,
            )
        }
        _specs.value = _specs.value + additions
        persistSpecs()
        rebuildSpecs()
        rebuildChannels()
        rebuildWindow()
        additions.forEach { refreshPool(it.id, force = true) }
        return additions.size
    }

    // ---------------------------------------------------------------- persistence

    private fun persistSpecs() {
        prefs.setVirtualChannelsJson(
            if (_specs.value.isEmpty()) null else VirtualChannelCodec.encodeSpecs(_specs.value)
        )
    }

    private fun persistPools() {
        prefs.setVirtualPoolsJson(
            if (_pools.value.isEmpty()) null else VirtualChannelCodec.encodePools(_pools.value)
        )
    }

    // ---------------------------------------------------------------- helpers

    /** Every Lucid TV channel lives in one fixed group, and joins at the real EPG position. */
    private fun normalizeSpecs(list: List<VirtualChannelSpec>): List<VirtualChannelSpec> =
        list.map { spec ->
            var normalized = if (spec.group == VirtualChannels.DEFAULT_GROUP) {
                spec
            } else {
                spec.copy(group = VirtualChannels.DEFAULT_GROUP)
            }
            // 20% was the old mobile default; it made playback start minutes away from the guide.
            if (normalized.join.maxStartPercent == 20) {
                normalized = normalized.copy(join = normalized.join.copy(maxStartPercent = -1))
            }
            // Series catalog channels used to run in fixed ROTATE slots, so a 22-minute episode
            // sat frozen until the 45-minute boundary. Run them as a real-runtime queue.
            if (normalized.kind == VirtualChannelKind.ADDON_SERIES &&
                normalized.playout.mode == VirtualPlayoutMode.ROTATE
            ) {
                normalized = normalized.copy(
                    playout = normalized.playout.copy(
                        mode = VirtualPlayoutMode.LOOP,
                        order = VirtualOrder.AIR,
                    ),
                )
            }
            normalized
        }

    /**
     * Pools saved by the first mobile Lucid build used the parent series id as the stream id,
     * and series catalogs were stored as whole shows. Repair the ids in place and force a
     * refresh for the series catalogs so they are expanded into episodes.
     */
    private fun repairPools(
        pools: Map<String, VirtualPoolSnapshot>,
        specs: List<VirtualChannelSpec>,
    ): Map<String, VirtualPoolSnapshot> {
        if (pools.isEmpty()) return pools
        val byId = specs.associateBy { it.id }
        return pools.mapValues { (id, snapshot) ->
            val repairedItems = snapshot.items.map { item ->
                if (item.season != null && item.episode != null && item.streamId != item.id) {
                    item.copy(streamId = item.id)
                } else {
                    item
                }
            }
            val spec = byId[id]
            val source = spec?.source
            val wholeSeries = (spec?.kind == VirtualChannelKind.ADDON_SERIES) ||
                (source is VirtualSource.AddonCatalog && source.contentType.equals("series", true))
            val hasWholeSeriesItems = wholeSeries && repairedItems.any {
                it.type == "series" && it.season == null
            }
            snapshot.copy(
                items = repairedItems,
                fetchedAtMs = if (hasWholeSeriesItems) 0L else snapshot.fetchedAtMs,
            )
        }
    }

    private fun yearOf(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""(19|20)\d{2}""").find(text)?.value?.toIntOrNull()
    }

    private fun runtimeMsOf(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val minutes = Regex("""(\d+)\s*(min|m)\b""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("""^\d+$""").find(text.trim())?.value?.toLongOrNull()
        return minutes?.takeIf { it > 0 }?.times(60_000L)
    }

    private const val WINDOW_PAST_MS = 2L * 60 * 60 * 1000
    private const val WINDOW_FUTURE_MS = 12L * 60 * 60 * 1000
    private const val RETRY_AFTER_MS = 10L * 60 * 1000
    private const val MAX_CATALOG_PAGES = 50
    private const val MAX_RULE_CATALOGS = 6
    private const val MAX_SERIES_TO_EXPAND = 8
    private const val MAX_EPISODES_PER_SERIES = 40
    private const val POOL_CONCURRENCY = 4
    private const val MAX_RUNTIME_LOOKUPS = 24
    private const val META_CONCURRENCY = 3
    private const val UNPLAYABLE_TTL_MS = 60L * 60 * 1000
}
