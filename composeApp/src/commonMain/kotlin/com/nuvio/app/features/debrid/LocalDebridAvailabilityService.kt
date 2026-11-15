package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.epochMs

object LocalDebridAvailabilityService {
    private data class CachedAvailability(
        val status: StreamDebridCacheStatus,
        val atMs: Long,
    )

    /** A short-lived hash cache shared by the stream screen and the player source panel. */
    private val statusCache = HashMap<String, CachedAvailability>()
    private val statusCacheLock = kotlinx.atomicfu.locks.SynchronizedObject()

    fun cacheCheckConfiguration(): String =
        cacheCheckAccount()?.let { account -> "${account.provider.id}:${account.apiKey.hashCode()}" } ?: "none"

    fun hasPendingCacheCheck(
        groups: List<AddonStreamGroup>,
        eligibleGroupIds: Set<String>? = null,
    ): Boolean {
        cacheCheckAccount() ?: return false
        return groups
            .filter { group -> eligibleGroupIds == null || group.addonId in eligibleGroupIds }
            .any { group ->
                group.streams.any { stream ->
                    stream.localAvailabilityHash() != null &&
                        stream.debridCacheStatus?.state !in FINAL_CACHE_STATES
                }
            }
    }

    fun markChecking(
        groups: List<AddonStreamGroup>,
        eligibleGroupIds: Set<String>? = null,
    ): List<AddonStreamGroup> {
        val account = cacheCheckAccount() ?: return groups
        return groups.updateAvailabilityStatus(eligibleGroupIds) { stream ->
            if (stream.localAvailabilityHash() == null || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) {
                stream
            } else {
                stream.copy(
                    debridCacheStatus = StreamDebridCacheStatus(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        state = StreamDebridCacheState.CHECKING,
                    ),
                )
            }
        }
    }

    suspend fun annotateCachedAvailability(
        groups: List<AddonStreamGroup>,
        eligibleGroupIds: Set<String>? = null,
    ): List<AddonStreamGroup> {
        val account = cacheCheckAccount() ?: return groups
        val hashes = groups
            .filter { group -> eligibleGroupIds == null || group.addonId in eligibleGroupIds }
            .flatMap { group ->
                group.streams.mapNotNull { stream ->
                    stream.localAvailabilityHash()
                        ?.takeUnless { stream.debridCacheStatus?.state in FINAL_CACHE_STATES }
                }
            }
            .distinct()
        if (hashes.isEmpty()) return groups

        val known = cachedStatuses(account, hashes)
        val missing = hashes.filterNot(known::containsKey)
        val fetched = if (missing.isEmpty()) {
            emptyMap()
        } else {
            LocalDebridService.checkCached(account = account, hashes = missing)
        }
        if (fetched != null && fetched.isNotEmpty()) {
            val resolved = missing.associateWith { hash ->
                fetched[hash].let { cachedItem ->
                    StreamDebridCacheStatus(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        state = if (cachedItem == null) StreamDebridCacheState.NOT_CACHED else StreamDebridCacheState.CACHED,
                        cachedName = cachedItem?.name,
                        cachedSize = cachedItem?.size,
                    )
                }
            }
            storeStatuses(account, resolved)
        }
        val allStatuses = if (fetched == null) {
            known
        } else {
            known + missing.associateWith { hash ->
                fetched[hash].let { cachedItem ->
                    StreamDebridCacheStatus(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        state = if (cachedItem == null) StreamDebridCacheState.NOT_CACHED else StreamDebridCacheState.CACHED,
                        cachedName = cachedItem?.name,
                        cachedSize = cachedItem?.size,
                    )
                }
            }
        }

        return groups.updateAvailabilityStatus(eligibleGroupIds) { stream ->
            val hash = stream.localAvailabilityHash() ?: return@updateAvailabilityStatus stream
            if (stream.debridCacheStatus?.state in FINAL_CACHE_STATES) return@updateAvailabilityStatus stream
            stream.copy(
                debridCacheStatus = allStatuses[hash] ?: StreamDebridCacheStatus(
                    providerId = account.provider.id,
                    providerName = account.provider.displayName,
                    state = StreamDebridCacheState.UNKNOWN,
                ),
            )
        }
    }

    suspend fun isCached(hash: String): Boolean? {
        val account = cacheCheckAccount() ?: return null
        val normalized = hash.trim().lowercase().takeIf { it.isNotBlank() } ?: return null
        cachedStatuses(account, listOf(normalized))[normalized]?.let { status ->
            return when (status.state) {
                StreamDebridCacheState.CACHED -> true
                StreamDebridCacheState.NOT_CACHED -> false
                else -> null
            }
        }
        val cached = LocalDebridService.isCached(account, normalized) ?: return null
        storeStatuses(
            account,
            mapOf(
                normalized to StreamDebridCacheStatus(
                    providerId = account.provider.id,
                    providerName = account.provider.displayName,
                    state = if (cached) StreamDebridCacheState.CACHED else StreamDebridCacheState.NOT_CACHED,
                ),
            ),
        )
        return cached
    }

    private fun cachedStatuses(
        account: DebridServiceCredential,
        hashes: List<String>,
    ): Map<String, StreamDebridCacheStatus> {
        val now = epochMs()
        val prefix = "${account.provider.id}:${account.apiKey.hashCode()}:"
        return kotlinx.atomicfu.locks.synchronized(statusCacheLock) {
            statusCache.keys.removeAll { key ->
                (statusCache[key]?.atMs ?: 0L) + CACHE_TTL_MS < now
            }
            hashes.mapNotNull { hash ->
                statusCache["$prefix$hash"]?.status?.let { hash to it }
            }.toMap()
        }
    }

    private fun storeStatuses(
        account: DebridServiceCredential,
        statuses: Map<String, StreamDebridCacheStatus>,
    ) {
        val prefix = "${account.provider.id}:${account.apiKey.hashCode()}:"
        val now = epochMs()
        kotlinx.atomicfu.locks.synchronized(statusCacheLock) {
            statuses.forEach { (hash, status) ->
                statusCache["$prefix$hash"] = CachedAvailability(status = status, atMs = now)
            }
            if (statusCache.size > MAX_CACHE_ENTRIES) {
                val oldest = statusCache.entries
                    .sortedBy { it.value.atMs }
                    .take(statusCache.size - MAX_CACHE_ENTRIES)
                oldest.forEach { statusCache.remove(it.key) }
            }
        }
    }

    private fun cacheCheckAccount(): DebridServiceCredential? {
        val settings = DebridSettingsRepository.snapshot()
        if (!settings.canResolvePlayableLinks) return null
        return settings.activeResolverCredential
            ?.takeIf { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
    }

    private const val CACHE_TTL_MS = 5L * 60_000L
    private const val MAX_CACHE_ENTRIES = 4_000
}

private val FINAL_CACHE_STATES = setOf(
    StreamDebridCacheState.CACHED,
    StreamDebridCacheState.NOT_CACHED,
)

internal fun StreamItem.localAvailabilityHash(): String? =
    infoHash
        ?.trim()
        ?.lowercase()
        ?.takeIf { isInstalledAddonStream && needsLocalDebridResolve && it.isNotBlank() }

private fun List<AddonStreamGroup>.updateAvailabilityStatus(
    eligibleGroupIds: Set<String>?,
    transform: (StreamItem) -> StreamItem,
): List<AddonStreamGroup> =
    map { group ->
        if (eligibleGroupIds != null && group.addonId !in eligibleGroupIds) return@map group
        var changed = false
        val updatedStreams = group.streams.map { stream ->
            val updated = transform(stream)
            if (updated != stream) changed = true
            updated
        }
        if (changed) group.copy(streams = updatedStreams) else group
    }
