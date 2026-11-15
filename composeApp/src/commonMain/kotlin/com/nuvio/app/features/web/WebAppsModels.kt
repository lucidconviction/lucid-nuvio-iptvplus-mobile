package com.nuvio.app.features.web

import kotlinx.serialization.Serializable

/** A saved website or web app shown on the Web-Apps home. */
@Serializable
data class SavedWebApp(
    val id: String,
    val name: String,
    val url: String,
    /** Optional custom icon; otherwise the site's favicon is used. */
    val iconUrl: String? = null,
    /** Local screenshot of the site's home page, captured on first open. */
    val thumbnailPath: String? = null,
    /** Changes whenever [thumbnailPath] is recaptured, so image caches reload it. */
    val thumbnailUpdatedAtMs: Long = 0L,
    val createdAtMs: Long = 0L,
)

/** A Tampermonkey-style script that runs inside the WebView only. */
@Serializable
data class WebUserscript(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val code: String,
)

@Serializable
enum class WebAutoPlayMode {
    Off,
    Ask,
    Best,
}

@Serializable
enum class WebAdSkipMode {
    Off,
    ClickOnly,
    ClickAndFastForward,
    Aggressive,
}

@Serializable
enum class WebUserAgentProfile {
    ChromeMobile,
    DesktopChrome,
    IPhoneSafari,
    Custom,
}

@Serializable
data class WebAppsSettings(
    val popupProtection: Boolean = true,
    /** Block main-frame navigations away from the current site (ad hijacks). */
    val redirectProtection: Boolean = true,
    val adBlockEnabled: Boolean = true,
    /**
     * Request desktop pages. Kept for backwards compatibility with 0.9.x settings.
     * New settings should use [userAgentProfile].
     */
    val desktopMode: Boolean = false,
    val homeUrl: String = "https://www.google.com",
    /** Hosts the user allows for cross-site redirects and popups (subdomains included). */
    val allowedHosts: Set<String> = emptySet(),
    /** Hosts the user chose "Block always" for redirects/popups. */
    val blockedRedirectHosts: Set<String> = emptySet(),
    val blockedPopupHosts: Set<String> = emptySet(),
    /** Sites where ad blocking is disabled because it broke the page. */
    val adBlockWhitelist: Set<String> = emptySet(),
    /** Off, ask first, or open the best detected source automatically. */
    val autoPlayMode: WebAutoPlayMode = WebAutoPlayMode.Off,
    /** Off, click only, click + fast-forward, or aggressive (intros/recaps too). */
    val adSkipMode: WebAdSkipMode = WebAdSkipMode.ClickAndFastForward,
    val adFastForwardSpeed: Float = 8f,
    val muteDuringAds: Boolean = true,
    val skipIntros: Boolean = true,
    val skipRecaps: Boolean = true,
    val autoNextEpisode: Boolean = false,
    /** Block JS alerts/confirm/prompt from page-load scripts and during playback. */
    val blockPromoDialogs: Boolean = true,
    /** Reject notification permission prompts that were not user-initiated. */
    val blockNotifications: Boolean = true,
    /** Suppress "leave site?" dialogs while a video is playing. */
    val blockBeforeUnload: Boolean = true,
    val userAgentProfile: WebUserAgentProfile = WebUserAgentProfile.ChromeMobile,
    val customUserAgent: String = "",
    /** Install the fetch/XHR/MSE sniffer on every page. */
    val mediaSnifferEnabled: Boolean = true,
    /** Cosmetic element hiding. Never hides elements that contain a player or menu. */
    val cosmeticFiltering: Boolean = true,
    /** Internal: open the local diagnostic page. */
    val showTestPageAction: Boolean = false,
)

/** One quality variant parsed from an HLS master playlist. */
data class WebMediaVariant(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val bandwidth: Long? = null,
    val codecs: String? = null,
    val frameRate: Double? = null,
)

/** One detected, playable web source. */
data class WebMediaSource(
    val url: String,
    val pageUrl: String,
    val title: String,
    val headers: Map<String, String> = emptyMap(),
    /** "hls", "dash" or null. */
    val streamType: String? = null,
    val isHls: Boolean = false,
    val isDash: Boolean = false,
    /** Duration when the page or manifest exposed it; used to rank ads below the real video. */
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bandwidth: Long? = null,
    /** True when this is an HLS master playlist. */
    val isMaster: Boolean = false,
    /** How many HLS variants were collapsed into this entry by the picker. */
    val variantCount: Int = 1,
    /** A short/small source; shown with an "Ad?" badge and sorted last, never hidden. */
    val likelyAd: Boolean = false,
    /** Poster image exposed by the page, if any. */
    val posterUrl: String? = null,
    /** Remote or local thumbnail chosen for the picker. */
    val thumbnailUrl: String? = null,
    /** Local screenshot crop, when one could be captured. */
    val thumbnailPath: String? = null,
    val isLive: Boolean = false,
    val isAudioOnly: Boolean = false,
    /** "widevine", "playready", "aes-128" or another DRM marker. */
    val drm: String? = null,
    val variants: List<WebMediaVariant> = emptyList(),
    /** 0..100 confidence that this is the real video, not an ad or a segment. */
    val confidence: Int = 0,
    /** request, dom, performance, fetch, xhr, mse, page. */
    val detectedBy: Set<String> = emptySet(),
)

/** A visible shield event, shown in the browser's shield log. */
data class WebShieldEvent(
    val id: Long,
    val kind: String,
    val message: String,
    val host: String = "",
    val atMs: Long = 0L,
)

internal fun webMediaScore(source: WebMediaSource): Long {
    val durationSeconds = (source.durationMs ?: 0L) / 1_000L
    val pixels = (source.width ?: 0).toLong() * (source.height ?: 0).toLong()
    var score = durationSeconds + pixels / 1_000L + (source.bandwidth ?: 0L) / 100_000L
    if (source.isMaster) score += 5_000L
    if (source.isHls) score += 100L
    if (source.isDash) score += 90L
    if (source.isLive) score += 40L
    if (source.isAudioOnly) score -= 300L
    if (source.drm != null) score -= 500L
    score += source.confidence.toLong() * 2L
    if (source.likelyAd) score -= 1_000_000L
    return score
}

internal fun webMediaDurationLabel(durationMs: Long?): String? {
    val totalSeconds = (durationMs ?: 0L) / 1_000L
    if (totalSeconds <= 0L) return null
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

internal fun WebMediaSource.qualityLabel(): String? {
    val w = width ?: 0
    val h = height ?: 0
    if (w <= 0 || h <= 0) return null
    return if (h >= 2160) "4K" else if (h >= 1440) "1440p" else if (h >= 1080) "1080p"
    else if (h >= 720) "720p" else if (h >= 480) "480p" else "${h}p"
}

data class WebBrowserUiState(
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Float = 0f,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val detectedMedia: List<WebMediaSource> = emptyList(),
    val error: String? = null,
    /** Bumped when Android kills the WebView renderer, so the host creates a fresh WebView. */
    val rendererGeneration: Int = 0,
    /** A source the controller wants Nuvio to open because auto-play is enabled. */
    val autoPlayCandidate: WebMediaSource? = null,
    val autoPlayAlternatives: List<WebMediaSource> = emptyList(),
    /** Recent popup/redirect/ad/filter events, newest first. */
    val shieldEvents: List<WebShieldEvent> = emptyList(),
    val adsSkipped: Int = 0,
    val adsFastForwarded: Int = 0,
    val adSecondsSaved: Long = 0L,
    val fullscreen: Boolean = false,
    /** User-facing result of a download request. */
    val downloadMessage: String? = null,
)

/** What the common UI needs from the Android WebView. */
interface WebBrowserController {
    val state: kotlinx.coroutines.flow.StateFlow<WebBrowserUiState>
    fun load(url: String)
    fun goBack()
    fun goForward()
    fun reload()
    fun stopLoading()
    fun destroy()
    fun pause() {}
    fun resume() {}
    fun applySettings(settings: WebAppsSettings, scripts: List<WebUserscript>)
    /** Downloads the optional hosts lists in the background. */
    fun updateAdblockLists() {}
    /** Re-scans the current page for playable media (useful for blob/MSE players). */
    fun scanForMedia() {}
    /** Plays a detected source inside the WebView instead of handing it to Nuvio's player. */
    fun playInBrowser(source: WebMediaSource) {}
    /** Captures the next finished page as this saved site's screenshot. */
    fun beginThumbnailCapture(siteId: String) {}
    /** Receives the saved file path of a captured site screenshot. */
    fun setThumbnailCaptureListener(listener: ((siteId: String, path: String) -> Unit)?) {}
    /** Leaves HTML5 fullscreen. Returns true when a fullscreen view was closed. */
    fun exitFullscreen(): Boolean = false
    /** The UI consumed (or dismissed) the auto-play suggestion. */
    fun consumeAutoPlayCandidate() {}
    /** Clears the last download message after it is shown. */
    fun clearDownloadMessage() {}
    /** Opens the local diagnostic page (debug and power-user builds). */
    fun openTestPage() {}
    /** Adds a visible shield event from the UI (for example a user block action). */
    fun noteShieldEvent(kind: String, message: String, host: String) {}
    /** Requests a download of a detected source. */
    fun download(source: WebMediaSource) {}
}

internal expect val webAppsPlatformAvailable: Boolean

internal expect object WebAppsStorage {
    fun loadSites(): String?
    fun saveSites(json: String)
    fun loadUserscripts(): String?
    fun saveUserscripts(json: String)
    fun loadSettings(): String?
    fun saveSettings(json: String)
    fun getUserscriptValue(scriptId: String, key: String): String?
    fun setUserscriptValue(scriptId: String, key: String, value: String)
    fun deleteUserscriptValue(scriptId: String, key: String)
    fun listUserscriptValues(scriptId: String): String
}

internal fun normalizeWebUrl(raw: String): String? {
    var value = raw.trim()
    if (value.isEmpty()) return null
    if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
        value = "https://$value"
    }
    val host = value
        .substringAfter("://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfter('@')
        .substringBefore(':')
        .lowercase()
    if (host.isBlank()) return null
    val validHost = host == "localhost" ||
        host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) ||
        (host.contains('.') && !host.startsWith('.') && !host.endsWith('.'))
    return if (validHost) value else null
}

internal fun webHost(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@').substringBefore(':')

/** True for loopback, private, link-local and local-network hosts; never fetch these from a page. */
internal fun isPrivateWebHost(host: String?): Boolean {
    val h = host?.trim()?.trimEnd('.')?.lowercase().orEmpty()
    if (h.isBlank()) return true
    if (h == "localhost" || h.endsWith(".local") || h == "::1") return true
    if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") ||
        h.startsWith("169.254.") || h.startsWith("0.")
    ) {
        return true
    }
    if (h.startsWith("172.")) {
        val second = h.removePrefix("172.").substringBefore('.').toIntOrNull() ?: return false
        if (second in 16..31) return true
    }
    return false
}

internal fun normalizeWebHost(raw: String): String? {
    val value = raw.trim()
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .trim()
        .trimEnd('.')
        .lowercase()
        .replace(Regex(""":\d+$"""), "")
    return value.takeIf {
        it.isNotBlank() && it.length <= 253 && !it.contains(' ') &&
            (it == "localhost" || it.contains('.'))
    }
}

internal fun webAppFallbackName(url: String): String =
    webHost(url).removePrefix("www.").ifBlank { url.take(40) }

internal fun webFaviconUrl(url: String): String? {
    val host = webHost(url).takeIf { it.isNotBlank() } ?: return null
    return "https://www.google.com/s2/favicons?domain=$host&sz=128"
}

/**
 * Remote preview used until the site has been opened once. The first open replaces it with a
 * locally captured screenshot of the site's actual home page.
 */
internal fun webAppPreviewUrl(url: String): String =
    "https://image.thum.io/get/width/800/crop/500/$url"
