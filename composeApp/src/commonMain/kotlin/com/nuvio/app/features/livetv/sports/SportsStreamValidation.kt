package com.nuvio.app.features.livetv.sports

import com.nuvio.app.features.livetv.ui.quickchannels.QuickStreamChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * On-demand liveness validation for the channels matched to a sports event.
 *
 * The global Stream validation sheet can hide dead channels from Live TV, but the sports page
 * can be opened before that run, or long after it. This keeps a short-lived per-event result:
 * only channels whose URL answered are shown, and the user can re-check from the game page.
 *
 * The probe is the same conservative [QuickStreamChecker] used by Quick Channels. Unknown or
 * unreachable streams are hidden here because the user asked for only working streams; the
 * "Check again" action puts them back into the probe when a provider recovers.
 */
internal data class SportsStreamHealthState(
    val checking: Set<String> = emptySet(),
    val checked: Set<String> = emptySet(),
    /** Event id -> channel keys whose stream answered. */
    val aliveKeys: Map<String, Set<String>> = emptyMap(),
    /** Event id -> last completed probe, in epoch milliseconds. */
    val lastCheckedMs: Map<String, Long> = emptyMap(),
)

internal object SportsStreamValidation {

    private const val TTL_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<String, Job>()
    private val signatures = mutableMapOf<String, String>()

    private val _state = MutableStateFlow(SportsStreamHealthState())
    val state: StateFlow<SportsStreamHealthState> = _state.asStateFlow()

    /** Starts one probe per event. Safe to call from composition; duplicate calls are ignored. */
    fun ensureChecked(eventId: String, matches: List<ChannelMatch>, force: Boolean = false) {
        val channels = matches
            .map { it.channel }
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
        if (channels.isEmpty()) {
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            _state.value = _state.value.copy(
                checking = _state.value.checking - eventId,
                checked = _state.value.checked + eventId,
                aliveKeys = _state.value.aliveKeys + (eventId to emptySet()),
                lastCheckedMs = _state.value.lastCheckedMs + (eventId to now),
            )
            return
        }
        // QuickStreamChecker speaks HTTP(S). Keep RTSP/UDP/RTMP channels visible instead of
        // hiding a working stream that the probe cannot speak to.
        val probeable = channels.filter {
            it.url.startsWith("http://", ignoreCase = true) ||
                it.url.startsWith("https://", ignoreCase = true)
        }
        val probeableUrls = probeable.mapTo(HashSet()) { it.url }
        if (probeable.isEmpty()) {
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            _state.value = _state.value.copy(
                checking = _state.value.checking - eventId,
                checked = _state.value.checked + eventId,
                aliveKeys = _state.value.aliveKeys + (eventId to matches.mapTo(HashSet()) { it.channel.key }),
                lastCheckedMs = _state.value.lastCheckedMs + (eventId to now),
            )
            return
        }
        if (jobs[eventId]?.isActive == true) return
        val signature = probeable.map { it.url }.sorted().joinToString("|")
        val last = _state.value.lastCheckedMs[eventId] ?: 0L
        if (!force && signatures[eventId] == signature && _state.value.checked.contains(eventId) &&
            kotlin.time.Clock.System.now().toEpochMilliseconds() - last < TTL_MS
        ) {
            return
        }
        signatures[eventId] = signature
        _state.value = _state.value.copy(
            checking = _state.value.checking + eventId,
            checked = _state.value.checked + eventId,
            aliveKeys = _state.value.aliveKeys + (eventId to emptySet()),
        )
        jobs[eventId] = scope.launch {
            val alive = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
            val outcomes = runCatching {
                QuickStreamChecker.checkBatch(
                    probeable,
                    forceRefresh = force,
                    assumeAliveOnUnknown = false,
                ) { url, isAlive ->
                    if (isAlive) {
                        val keys = matches.filter { it.channel.url == url }.map { it.channel.key }
                        if (keys.isNotEmpty()) {
                            alive.update { it + keys }
                            _state.value = _state.value.copy(
                                aliveKeys = _state.value.aliveKeys + (eventId to alive.value),
                            )
                        }
                    }
                }
            }.getOrDefault(emptyMap())
            val finalAlive = (alive.value + matches
                .filter { it.channel.url !in probeableUrls || outcomes[it.channel.url] == true }
                .map { it.channel.key }).toSet()
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            _state.value = _state.value.copy(
                checking = _state.value.checking - eventId,
                checked = _state.value.checked + eventId,
                aliveKeys = _state.value.aliveKeys + (eventId to finalAlive),
                lastCheckedMs = _state.value.lastCheckedMs + (eventId to now),
            )
            jobs.remove(eventId)
        }
    }

    fun isAlive(eventId: String, channelKey: String): Boolean =
        _state.value.aliveKeys[eventId]?.contains(channelKey) == true

    fun isChecking(eventId: String): Boolean = _state.value.checking.contains(eventId)

    fun isChecked(eventId: String): Boolean = _state.value.checked.contains(eventId)

    fun visibleMatches(eventId: String, matches: List<ChannelMatch>): List<ChannelMatch> {
        if (!isChecked(eventId)) return emptyList()
        val alive = _state.value.aliveKeys[eventId] ?: return emptyList()
        return matches.filter { it.channel.key in alive }
    }

    /** Forces a new probe for an event, used by the "Check streams" action. */
    fun refresh(eventId: String, matches: List<ChannelMatch>) {
        jobs[eventId]?.cancel()
        jobs.remove(eventId)
        ensureChecked(eventId, matches, force = true)
    }

    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _state.value = SportsStreamHealthState()
    }
}

/** The channels whose URL answered. Kept pure so the selection can be unit-tested. */
internal fun sportsAliveChannelKeys(
    matches: List<ChannelMatch>,
    outcomes: Map<String, Boolean>,
): Set<String> = matches
    .filter { outcomes[it.channel.url] == true }
    .mapTo(HashSet()) { it.channel.key }
