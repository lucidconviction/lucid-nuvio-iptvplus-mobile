package com.nuvio.app.features.livetv.portal

import android.content.Context
import com.nuvio.app.features.livetv.data.LiveTvRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Android's "Generate portals": Cloud Vault + Reddit, verified, then shown until Add. */
actual object PortalGeneratorPlatform {
    actual val available: Boolean = true

    private const val PREFS = "portal_search"
    private const val KEY = "portals"

    private val _savedPortals = MutableStateFlow<List<GeneratedPortal>>(emptyList())
    actual val savedPortals: StateFlow<List<GeneratedPortal>> = _savedPortals.asStateFlow()

    private var appContext: Context? = null

    /** Called by [LiveTvPreferencesAndroid] once the app context is available. */
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _savedPortals.value = readSaved(context.applicationContext)
    }

    actual suspend fun generate(
        target: Int,
        onProgress: (PortalGeneratorProgress) -> Unit,
    ): List<GeneratedPortal> {
        val found = PortalGenerator.generate(target = target) { progress ->
            onProgress(
                PortalGeneratorProgress(
                    status = progress.status,
                    tried = progress.tried,
                    found = progress.found,
                    target = progress.target,
                ),
            )
        }
        val already = _savedPortals.value.associateBy { it.key }
        return found.map { p ->
            val key = p.baseUrl.lowercase() + "|" + p.username.lowercase()
            GeneratedPortal(
                baseUrl = p.baseUrl,
                username = p.username,
                password = p.password,
                name = p.name,
                expiry = p.expiry,
                maxConnections = p.maxConnections,
                m3uUrl = p.m3uUrl,
                source = p.source,
                added = already[key]?.added == true,
            )
        }
    }

    actual fun save(found: List<GeneratedPortal>): Int {
        if (found.isEmpty()) return 0
        val current = _savedPortals.value.toMutableList()
        val known = current.mapTo(HashSet()) { it.key }
        var added = 0
        found.forEach { portal ->
            if (known.add(portal.key)) {
                current += portal
                added++
            }
        }
        if (added > 0) {
            _savedPortals.value = current
            persist(current)
        }
        return added
    }

    actual fun remove(portal: GeneratedPortal) {
        val next = _savedPortals.value.filterNot { it.key == portal.key }
        if (next.size == _savedPortals.value.size) return
        _savedPortals.value = next
        persist(next)
    }

    actual fun addToLiveTv(portal: GeneratedPortal): Boolean {
        if (portal.added) return true
        return runCatching {
            LiveTvRepository.addXtream(
                name = portal.name,
                server = portal.baseUrl,
                username = portal.username,
                password = portal.password,
                importVod = false,
                importLive = true,
            )
            val next = _savedPortals.value.map {
                if (it.key == portal.key) it.copy(added = true) else it
            }
            _savedPortals.value = next
            persist(next)
            true
        }.getOrDefault(false)
    }

    private fun persist(list: List<GeneratedPortal>) {
        val context = appContext ?: return
        val array = JSONArray()
        list.forEach { p ->
            array.put(
                JSONObject()
                    .put("baseUrl", p.baseUrl)
                    .put("username", p.username)
                    .put("password", p.password)
                    .put("name", p.name)
                    .put("expiry", p.expiry.orEmpty())
                    .put("maxConnections", p.maxConnections.orEmpty())
                    .put("m3uUrl", p.m3uUrl)
                    .put("source", p.source)
                    .put("added", p.added),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    private fun readSaved(context: Context): List<GeneratedPortal> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<GeneratedPortal>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val base = o.optString("baseUrl")
            val user = o.optString("username")
            val pass = o.optString("password")
            if (base.isBlank() || user.isBlank() || pass.isBlank()) continue
            out += GeneratedPortal(
                baseUrl = base,
                username = user,
                password = pass,
                name = o.optString("name").ifBlank { "Xtream portal" },
                expiry = o.optString("expiry").takeIf { it.isNotBlank() },
                maxConnections = o.optString("maxConnections").takeIf { it.isNotBlank() },
                m3uUrl = o.optString("m3uUrl"),
                source = o.optString("source").ifBlank { "Portal search" },
                added = o.optBoolean("added", false),
            )
        }
        return out
    }
}
