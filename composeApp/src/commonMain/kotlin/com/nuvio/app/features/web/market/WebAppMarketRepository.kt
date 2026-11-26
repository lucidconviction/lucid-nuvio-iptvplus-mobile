package com.nuvio.app.features.web.market

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

data class WebAppMarketState(
    val catalog: MarketCatalog? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Loads the market catalogue bundled in the APK. The seed is FMHY's SFW
 * streaming sections, captured by Lucid Hikari; no network is needed on first open.
 */
object WebAppMarketRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(WebAppMarketState())
    val state: StateFlow<WebAppMarketState> = _state.asStateFlow()

    private var loadJob: Job? = null

    @OptIn(ExperimentalResourceApi::class)
    fun ensureLoaded() {
        if (loadJob != null || _state.value.catalog != null) return
        loadJob = scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val catalog = runCatching {
                val text = Res.readBytes("files/webapp_market_seed.json").decodeToString()
                json.decodeFromString(MarketCatalog.serializer(), text)
            }.getOrNull()?.let { parsed ->
                parsed.copy(apps = parsed.apps.filterNot { it.adult })
            }
            _state.value = WebAppMarketState(
                catalog = catalog,
                loading = false,
                error = if (catalog == null) "Could not load the market catalogue." else null,
            )
        }
    }

    /** Retries the bundled catalogue after a failed load. */
    fun reload() {
        loadJob = null
        ensureLoaded()
    }
}
