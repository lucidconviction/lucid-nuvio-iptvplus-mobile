package com.nuvio.app.features.web

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * The registrable domain (eTLD+1) for the common two-level public suffixes. Sites routinely
 * split their app and API across sibling subdomains (www.site.com + api.site.com); treating
 * those as third-party made ad blocking and redirect protection break the site's own requests.
 */
private val TWO_LEVEL_PUBLIC_SUFFIXES = setOf(
    "co.uk", "org.uk", "ac.uk", "gov.uk", "net.uk",
    "com.au", "net.au", "org.au", "co.nz", "net.nz", "org.nz",
    "co.jp", "co.kr", "co.in", "co.id", "co.za", "co.nz",
    "com.br", "com.mx", "com.ar", "com.co", "com.tr", "com.cn",
    "com.tw", "com.hk", "com.sg", "com.my", "com.ph", "com.pk",
    "com.eg", "com.sa", "com.ua", "com.vn", "com.ru",
)

internal fun registrableDomain(host: String): String {
    val clean = host.trim().trimEnd('.').lowercase()
    // IP literals have no registrable domain; comparing their last labels would treat
    // unrelated addresses as the same site.
    if (clean.contains(':') || clean.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return clean
    val parts = clean.split('.').filter { it.isNotBlank() }
    if (parts.size <= 2) return clean
    val lastTwo = parts.takeLast(2).joinToString(".")
    return if (lastTwo in TWO_LEVEL_PUBLIC_SUFFIXES) {
        parts.takeLast(3).joinToString(".")
    } else {
        lastTwo
    }
}

/**
 * The Android Web-Apps browser. The WebView setup, media probe, HLS polyfill and popup rules are
 * ported from Lucid Hikari's WebViewActivity (GPL-3.0), adapted to Nuvio's controller and player.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
internal class AndroidWebBrowserController(
    private val appContext: Context,
) : WebBrowserController {

    private val _state = MutableStateFlow(WebBrowserUiState())
    override val state: StateFlow<WebBrowserUiState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var popupChild: WebView? = null
    private var popupDialog: Dialog? = null
    private var pendingUrl: String? = null
    private var settings = WebAppsSettings()
    private var scripts: List<WebUserscript> = emptyList()
    private var lastLoadedUrl: String = ""
    private var pendingThumbnailSiteId: String? = null
    private var thumbnailCaptureRunnable: Runnable? = null
    private var thumbnailListener: ((siteId: String, path: String) -> Unit)? = null
    private var mediaScanRunnable: Runnable? = null
    private var autoPlayRunnable: Runnable? = null
    private var rendererRestarts = 0
    private var pendingMainFrameUrl: String? = null
    private var allowNextMainFrame = false
    /** Whether the popup now on screen was opened by an actual tap. */
    @Volatile private var popupFromGesture = false
    /**
     * A popup that exists for the page's sake but is not shown: ad-gated players
     * call window.open and refuse to start until it returns a real window. The
     * window is created, kept off-screen, muted and closed after a few seconds,
     * so the user never sees the ad and the player still proceeds.
     */
    @Volatile private var popupHidden = false
    /** Distinct foreign hosts crossed without a gesture in one automatic chain. */
    @Volatile private var redirectChainHost = ""
    @Volatile private var redirectChainCount = 0
    @Volatile private var redirectChainAt = 0L
    private var lastChoiceAt = 0L
    private var lastChoiceUrl: String? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var customViewContainer: FrameLayout? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserLauncher: ((Intent) -> Unit)? = null
    private var lastBlockedNoteAt = 0L
    private var lastDownloadToastAt = 0L
    private var nextShieldEventId = 0L
    private var lastAdSkipAt = 0L
    private val handedOffUrls = Collections.synchronizedSet(HashSet<String>())
    private val shieldEvents = ArrayDeque<WebShieldEvent>()
    private var probeExecutor = newProbeExecutor()
    @Volatile private var videoPlaying = false
    @Volatile private var lastUserInteractionMs: Long = 0L
    @Volatile private var currentUserAgent: String = ""
    @Volatile private var pageUrl: String = ""
    @Volatile private var pageTitle: String = ""
    private val detected = Collections.synchronizedMap(LinkedHashMap<String, WebMediaSource>())
    private val probing = Collections.synchronizedSet(HashSet<String>())
    /** Last thumbnail attempt per source, so the 2.5 s DOM scan cannot allocate bitmaps forever. */
    private val thumbnailCaptures = Collections.synchronizedMap(HashMap<String, Long>())

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * The device's own WebView user agent, without the WebView-only markers.
     *
     * This used to be a hardcoded "Pixel 8 / Chrome 124" string. Sites feature-
     * detect from the UA, and serving them a year-old Chromium while the WebView
     * is actually current is how menus and players set themselves up for APIs
     * the reported build never had — Dailymotion's drawer stayed in its closed
     * DOM state (height 0) while the page behind it dimmed. Hikari uses the
     * stock UA for the same reason; only an explicit desktop/iPhone/custom
     * choice overrides it.
     */
    private val chromeMobileUa: String by lazy {
        val stock = runCatching {
            android.webkit.WebSettings.getDefaultUserAgent(appContext)
        }.getOrDefault(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        )
        stock
            .replace("; wv", "")
            .replace(" Version/4.0", "")
            .replace("  ", " ")
            .trim()
    }

    private val iphoneUa =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    /** Login/payment/Cloudflare hosts that are always allowed through redirect protection. */
    private val builtInAllowedHosts = setOf(
        "accounts.google.com",
        "myaccount.google.com",
        "paypal.com",
        "stripe.com",
        "challenges.cloudflare.com",
        "cloudflare.com",
    )

    private val hlsLib: String by lazy {
        runCatching {
            appContext.assets.open("web/hls.min.js").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    private val videoScanJs: String by lazy { readWebAsset("web/video_scan.js") }
    private val performanceScanJs: String by lazy { readWebAsset("web/performance_scan.js") }
    private val stuckMonitorJs: String by lazy { readWebAsset("web/stuck_monitor.js") }
    private val playingMonitorJs: String by lazy { readWebAsset("web/playing_monitor.js") }
    private val shieldJs: String by lazy { readWebAsset("web/shield.js") }
    private val snifferJs: String by lazy { readWebAsset("web/sniffer.js") }
    private val adSkipJs: String by lazy { readWebAsset("web/ad_skip.js") }
    private val adCleanJs: String by lazy { readWebAsset("web/ad_clean.js") }
    private val testPageHtml: String by lazy { readWebAsset("web/test_page.html") }

    private fun readWebAsset(path: String): String =
        runCatching {
            appContext.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrDefault("")

    /** evaluateJavascript throws if the view was destroyed between a callback and this call. */
    private fun WebView.evalJs(script: String) {
        runCatching { evaluateJavascript(script, null) }
    }

    private fun newProbeExecutor() = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "nuvio-web-probe").apply { isDaemon = true }
    }

    /**
     * The host disposes its WebView whenever the user leaves the browser, but this controller
     * (and its screen) can stay alive. A shut-down probe pool would throw
     * RejectedExecutionException from shouldInterceptRequest on the next visit and kill the app.
     */
    private fun ensureProbeExecutor() {
        if (probeExecutor.isShutdown) probeExecutor = newProbeExecutor()
    }

    fun attach(view: WebView) {
        if (webView === view) return
        ensureProbeExecutor()
        val previous = webView
        webView = view
        if (previous != null) destroyWebView(previous)
        Thread { WebAdBlocker.load(appContext) }.start()
        configure(view)
        val url = pendingUrl ?: lastLoadedUrl.takeIf { it.isNotBlank() }
        pendingUrl = null
        if (!url.isNullOrBlank()) loadWhenMeasured(view, url)
    }

    /**
     * Loads [url] once the WebView has a real size.
     *
     * Loading during composition happens before the first layout, and a WebView
     * laid out at 0 height resolves every CSS `vh`/`dvh` to **0 for the life of
     * the page**. The page still draws (the layout viewport is measured later),
     * so the symptom is not a blank page: it is site UI that computes its own
     * height from the viewport — Dailymotion's menu drawer opened with
     * `height: 0px`, dimming the page while the menu never appeared. Chrome on
     * the same phone, given a measured viewport, shows the menu normally.
     */
    private fun loadWhenMeasured(view: WebView, url: String) {
        if (view.width > 0 && view.height > 0) {
            view.loadUrl(url)
            return
        }
        pendingUrl = url
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (v.width <= 0 || v.height <= 0) return
                v.removeOnLayoutChangeListener(this)
                if (v !== webView) return
                val queued = pendingUrl
                pendingUrl = null
                if (!queued.isNullOrBlank()) v.loadUrl(queued)
            }
        })
    }

    /** Compose removed this WebView (renderer restart or leaving the screen). */
    fun releaseView(view: WebView) {
        if (webView === view) return
        destroyWebView(view)
    }

    private fun destroyWebView(view: WebView?) {
        if (view == null) return
        // Detach before destroy. Destroying an attached WebView is what produces native
        // "WebView.destroy() called while still attached" failures on some ROMs.
        mainHandler.post {
            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
            runCatching { view.stopLoading() }
            runCatching { view.destroy() }
        }
    }

    /** The device has no usable WebView provider (disabled System WebView, broken ROM). */
    fun reportWebViewUnavailable(error: Throwable) {
        _state.value = _state.value.copy(
            loading = false,
            error = "The browser engine is unavailable on this device. Update Android System WebView, then try again.",
        )
        noteShield("security", "WebView unavailable: ${error.javaClass.simpleName}", "")
    }

    override fun load(url: String) {
        ensureProbeExecutor()
        val normalized = normalizeWebUrl(url) ?: url.trim()
        lastLoadedUrl = normalized
        pendingMainFrameUrl = normalized
        val view = webView
        if (view == null) {
            pendingUrl = normalized
        } else {
            loadWhenMeasured(view, normalized)
        }
    }

    override fun goBack() {
        webView?.let {
            if (it.canGoBack()) {
                allowNextMainFrame = true
                it.goBack()
            } else {
                updateNavigation()
            }
        }
    }

    override fun goForward() {
        webView?.let {
            if (it.canGoForward()) {
                allowNextMainFrame = true
                it.goForward()
            } else {
                updateNavigation()
            }
        }
    }

    override fun reload() {
        val view = webView
        if (view == null) {
            // The renderer was lost (or the host has not attached yet). Let the host create a
            // fresh WebView and load the last page.
            rendererRestarts = 0
            pendingUrl = lastLoadedUrl.takeIf { it.isNotBlank() }
            _state.value = _state.value.copy(
                error = null,
                rendererGeneration = _state.value.rendererGeneration + 1,
            )
            return
        }
        allowNextMainFrame = true
        view.reload()
    }

    override fun stopLoading() {
        webView?.stopLoading()
    }

    override fun pause() {
        webView?.onPause()
        popupChild?.onPause()
        mediaScanRunnable?.let { mainHandler.removeCallbacks(it) }
        mediaScanRunnable = null
        autoPlayRunnable?.let { mainHandler.removeCallbacks(it) }
        autoPlayRunnable = null
        webView?.evalJs(
            "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})}catch(e){}})()",
        )
    }

    override fun resume() {
        webView?.onResume()
        popupChild?.onResume()
        val view = webView ?: return
        if (pageUrl.isNotBlank()) {
            view.evalJs(videoScanJs)
            view.evalJs(performanceScanJs)
        }
    }

    override fun destroy() {
        mediaScanRunnable?.let { mainHandler.removeCallbacks(it) }
        mediaScanRunnable = null
        autoPlayRunnable?.let { mainHandler.removeCallbacks(it) }
        autoPlayRunnable = null
        thumbnailCaptureRunnable?.let { mainHandler.removeCallbacks(it) }
        thumbnailCaptureRunnable = null
        hideFullscreen()
        dismissPopup()
        runCatching { CookieManager.getInstance().flush() }
        val view = webView
        webView = null
        // Let Compose detach the AndroidView before destroying the native WebView.
        destroyWebView(view)
        probeExecutor.shutdownNow()
    }

    override fun updateAdblockLists() {
        Thread { runCatching { WebAdBlocker.update(appContext) } }.start()
    }

    override fun scanForMedia() {
        val view = webView ?: return
        view.evalJs(videoScanJs)
        view.evalJs(performanceScanJs)
    }

    override fun playInBrowser(source: WebMediaSource) {
        val view = webView ?: return
        // Do NOT call onPause() here: a paused WebView can ignore the data-page load and keep
        // showing the previous frame. Just pause the page's media and keep the view running.
        view.onResume()
        view.evalJs(
            "(function(){try{document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})}catch(e){}})()",
        )
        val headers = source.headers.filterKeys { key ->
            key.startsWith("X-", ignoreCase = true) ||
                key.equals("Authorization", ignoreCase = true)
        }
        val headersJson = org.json.JSONObject().apply {
            headers.forEach { (key, value) -> put(key, value) }
        }.toString()
        val urlJs = org.json.JSONObject.quote(source.url)
        val html = if (source.isHls || source.streamType == "hls") {
            hlsPlayerHtml(urlJs, headersJson)
        } else {
            directPlayerHtml(urlJs)
        }
        val base = source.pageUrl.ifBlank { source.url }
        view.stopLoading()
        view.loadDataWithBaseURL(base, html, "text/html", "utf-8", base)
    }

    private fun playerShell(script: String, headScript: String = ""): String {
        fun safe(code: String) = code.replace("</script", "<\\/script")
        val library = headScript.takeIf { it.isNotBlank() }
            ?.let { "<script>${safe(it)}</script>" }
            .orEmpty()
        return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <style>
            html,body{margin:0;height:100%;background:#000;color:#ddd;font:14px system-ui,sans-serif;overflow:hidden}
            video{position:fixed;inset:0;width:100%;height:100%;object-fit:contain;background:#000;z-index:1}
            #back{position:fixed;top:10px;left:10px;z-index:3;background:rgba(0,0,0,.62);
              border:1px solid rgba(255,255,255,.3);color:#fff;border-radius:999px;
              padding:7px 14px;text-decoration:none;font:14px system-ui,sans-serif}
            #err{position:fixed;inset:0;display:none;align-items:center;justify-content:center;
              text-align:center;padding:24px;color:#bbb}
          </style>
          $library
        </head>
        <body>
          <a id="back" href="javascript:history.back()">‹ Back</a>
          <video id="v" controls autoplay playsinline></video>
          <div id="err">This video could not play in the browser. Try “Play in Nuvio”.</div>
          <script>${safe(script)}</script>
        </body>
        </html>
        """.trimIndent()
    }

    private fun directPlayerHtml(urlJs: String): String = playerShell(
        """
        (function(){
          var v=document.getElementById('v');
          var failed=false;
          function fail(){if(failed)return;failed=true;document.getElementById('err').style.display='flex';}
          v.addEventListener('error',fail);
          v.src=$urlJs;
          v.play().catch(function(){});
          setTimeout(function(){if(v.readyState===0)fail();},12000);
        })();
        """.trimIndent()
    )

    private fun hlsPlayerHtml(urlJs: String, headersJson: String): String = playerShell(
        """
        (function(){
          var url=$urlJs;
          var headers=$headersJson;
          var v=document.getElementById('v');
          v.setAttribute('data-nuvio-browser','1');
          var started=false;
          var failed=false;
          var began=Date.now();
          function fail(){if(failed)return;failed=true;document.getElementById('err').style.display='flex';}
          v.addEventListener('error',function(){if(started)fail();});
          function attachHls(){
            if(!(window.Hls && window.Hls.isSupported()))return false;
            var hls=new window.Hls({enableWorker:true,lowLatencyMode:true,
              xhrSetup:function(xhr){
                for(var k in headers){
                  try{xhr.setRequestHeader(k,headers[k]);}catch(e){}
                }
              }});
            hls.loadSource(url);
            hls.attachMedia(v);
            hls.on(window.Hls.Events.MANIFEST_PARSED,function(){v.play().catch(function(){});});
            hls.on(window.Hls.Events.ERROR,function(e,d){if(d&&d.fatal)fail();});
            v.__nuvioBrowserHls=hls;
            v.__nuvioHlsAttached=hls;
            started=true;
            return true;
          }
          function start(){
            if(attachHls())return;
            if(Date.now()-began>10000 && v.canPlayType('application/vnd.apple.mpegurl')){
              v.src=url;
              v.play().catch(function(){});
              started=true;
              return;
            }
            setTimeout(start,250);
          }
          start();
          setTimeout(function(){if(!started)fail();},15000);
        })();
        """.trimIndent(),
        // loadDataWithBaseURL creates a fresh document; the hls.js injected into the page we
        // came from is gone. Put the bundled library in the player document itself.
        headScript = hlsLib,
    )

    override fun beginThumbnailCapture(siteId: String) {
        thumbnailCaptureRunnable?.let { mainHandler.removeCallbacks(it) }
        thumbnailCaptureRunnable = null
        pendingThumbnailSiteId = siteId
    }

    override fun setThumbnailCaptureListener(listener: ((siteId: String, path: String) -> Unit)?) {
        thumbnailListener = listener
    }

    override fun applySettings(settings: WebAppsSettings, scripts: List<WebUserscript>) {
        val previousUa = effectiveUserAgent(this.settings)
        this.settings = settings
        this.scripts = scripts
        val nextUa = effectiveUserAgent(settings)
        webView?.let { view ->
            view.settings.userAgentString = nextUa
            currentUserAgent = nextUa
            if (!previousUa.equals(nextUa, ignoreCase = true) && lastLoadedUrl.isNotBlank()) view.reload()
        }
        injectAdSkipConfig()
    }

    override fun exitFullscreen(): Boolean {
        val hadFullscreen = customView != null
        hideFullscreen()
        return hadFullscreen
    }

    override fun consumeAutoPlayCandidate() {
        autoPlayRunnable?.let { mainHandler.removeCallbacks(it) }
        autoPlayRunnable = null
        if (_state.value.autoPlayCandidate != null || _state.value.autoPlayAlternatives.isNotEmpty()) {
            _state.value = _state.value.copy(
                autoPlayCandidate = null,
                autoPlayAlternatives = emptyList(),
            )
        }
    }

    override fun clearDownloadMessage() {
        if (_state.value.downloadMessage != null) {
            _state.value = _state.value.copy(downloadMessage = null)
        }
    }

    override fun noteShieldEvent(kind: String, message: String, host: String) {
        noteShield(kind, message, host)
    }

    override fun openTestPage() {
        val view = webView ?: return
        lastLoadedUrl = "https://nuvio.test/"
        view.loadDataWithBaseURL(
            "https://nuvio.test/",
            testPageHtml,
            "text/html",
            "utf-8",
            null,
        )
    }

    override fun download(source: WebMediaSource) {
        if (source.drm != null) {
            setDownloadMessage("This source is DRM-protected and cannot be downloaded.")
            return
        }
        if (source.isDash) {
            setDownloadMessage("DASH downloads are not supported yet. Play it in Nuvio instead.")
            return
        }
        if (source.isHls) {
            setDownloadMessage("Preparing HLS download…")
            WebMediaDownloader.downloadHls(
                context = appContext,
                source = source,
                onMessage = { message -> setDownloadMessage(message) },
            )
            return
        }
        downloadDirect(source.url, currentUserAgent, "", null, source.headers)
    }

    private fun effectiveUserAgent(settings: WebAppsSettings): String {
        if (settings.desktopMode && settings.userAgentProfile == WebUserAgentProfile.ChromeMobile) {
            return desktopUa
        }
        return when (settings.userAgentProfile) {
            WebUserAgentProfile.ChromeMobile -> chromeMobileUa
            WebUserAgentProfile.DesktopChrome -> desktopUa
            WebUserAgentProfile.IPhoneSafari -> iphoneUa
            WebUserAgentProfile.Custom -> settings.customUserAgent.ifBlank { chromeMobileUa }
        }
    }

    private fun configure(view: WebView) {
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        // Wide viewport mode gives Chromium a 980-px-wide layout box whose
        // HEIGHT is not the view; on this WebView every CSS vh/dvh unit
        // resolved to 0 while 100vw stayed correct. Sites that size a drawer
        // or player with vh then computed height 0 — Dailymotion's menu opened
        // into an invisible 0px box while its dim backdrop covered the page.
        // Hikari leaves both at the WebView defaults; so do we.
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        // Deliberately NOT offscreenPreRaster: it pre-rasterises a band around
        // the viewport, and on this WebView that band rendered page content but
        // dropped fixed-position layers — the site's own menu drawer and the
        // player's control overlay existed in the DOM (visible, z-index 200)
        // and never appeared on screen. Hikari never enables it.
        settings.offscreenPreRaster = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { settings.safeBrowsingEnabled = true }
        }
        settings.userAgentString = effectiveUserAgent(this.settings)
        currentUserAgent = settings.userAgentString ?: effectiveUserAgent(this.settings)
        view.setBackgroundColor(0xFF070909.toInt())
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastUserInteractionMs = SystemClock.uptimeMillis()
            }
            false
        }
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.isBlank()) return@setDownloadListener
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                setDownloadMessage("This download is generated inside the page and cannot be saved directly.")
                return@setDownloadListener
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@setDownloadListener
            downloadDirect(url, userAgent, contentDisposition, mimeType, null)
        }
        if (appContext.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        ) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.addJavascriptInterface(Bridge(), "NuvioWebBridge")
        installDocumentStartScripts(view)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (handleUrl(view, url)) return true
                if (request?.isForMainFrame == true && shouldBlockMainFrame(url, request.hasGesture())) {
                    // A blocked navigation can still carry the page the click was
                    // actually for (an ad click-tracker's `fb=` / `url=` parameter).
                    // Follow that, instead of leaving an empty document behind.
                    val dest = adGateDestination(url)
                    if (dest != null && view != null) {
                        noteShield("redirect", "Followed the link behind ${hostOf(url)}.", hostOf(url).orEmpty())
                        view.loadUrl(dest)
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val newUrl = url.orEmpty()
                pendingMainFrameUrl?.let { pending ->
                    if (sameUrl(pending, newUrl)) pendingMainFrameUrl = null
                }
                videoPlaying = false
                mediaScanRunnable?.let { mainHandler.removeCallbacks(it) }
                mediaScanRunnable = null
                autoPlayRunnable?.let { mainHandler.removeCallbacks(it) }
                autoPlayRunnable = null
                val samePage = newUrl.isNotBlank() &&
                    pageUrl.isNotBlank() &&
                    newUrl.substringBefore('#') == pageUrl.substringBefore('#')
                pageUrl = newUrl
                if (newUrl.isNotBlank()) lastLoadedUrl = newUrl
                if (!samePage) {
                    detected.clear()
                    handedOffUrls.clear()
                    thumbnailCaptures.clear()
                    _state.value = _state.value.copy(
                        detectedMedia = emptyList(),
                        autoPlayCandidate = null,
                        autoPlayAlternatives = emptyList(),
                    )
                }
                _state.value = _state.value.copy(
                    url = pageUrl,
                    loading = true,
                    progress = 0f,
                    detectedMedia = if (samePage) synchronized(detected) { detected.values.toList() } else emptyList(),
                    error = null,
                )
                injectUserscripts(atStart = true)
                injectAdSkipConfig()
                injectSniffer()
                injectShield()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageUrl = url.orEmpty()
                _state.value = _state.value.copy(
                    url = pageUrl,
                    loading = false,
                    progress = 1f,
                    error = null,
                )
                updateNavigation()
                injectUserscripts(atStart = false)
                injectAdClean()
                injectAdSkipConfig()
                injectSniffer()
                injectShield()
                view?.evalJs(videoScanJs)
                view?.evalJs(performanceScanJs)
                view?.evalJs(stuckMonitorJs)
                view?.evalJs(playingMonitorJs)
                // Single-page players create their <video> long after onPageFinished. Keep
                // scanning the DOM and performance entries, like Hikari does.
                mediaScanRunnable?.let { mainHandler.removeCallbacks(it) }
                val scan = object : Runnable {
                    override fun run() {
                        val v = webView ?: return
                        v.evalJs(videoScanJs)
                        v.evalJs(performanceScanJs)
                        v.evalJs(stuckMonitorJs)
                        v.evalJs(playingMonitorJs)
                        mainHandler.postDelayed(this, 2_500L)
                    }
                }
                mediaScanRunnable = scan
                mainHandler.postDelayed(scan, 1_500L)
                pendingThumbnailSiteId?.let { siteId ->
                    // A redirect or challenge often finishes more than once; keep resetting the
                    // timer so the screenshot shows the final landing page, not the interstitial.
                    thumbnailCaptureRunnable?.let { mainHandler.removeCallbacks(it) }
                    val capture = Runnable {
                        thumbnailCaptureRunnable = null
                        pendingThumbnailSiteId = null
                        captureThumbnail(siteId)
                    }
                    thumbnailCaptureRunnable = capture
                    mainHandler.postDelayed(capture, 2_500L)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = error?.description?.toString()?.takeIf { it.isNotBlank() }
                            ?: "This page couldn't load.",
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                // Do NOT cover the page for HTTP errors: Cloudflare and many players return 403
                // on the first request and then render the challenge/player anyway. The page must
                // stay interactive.
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?,
            ): Boolean {
                // Heavy player pages can exhaust the WebView renderer. Return true so Android
                // does not kill the app, and let WebBrowserHost build a fresh WebView.
                mediaScanRunnable?.let { mainHandler.removeCallbacks(it) }
                mediaScanRunnable = null
                webView = null
                rendererRestarts++
                _state.value = _state.value.copy(
                    loading = false,
                    error = if (rendererRestarts <= MAX_RENDER_RESTARTS) {
                        "The page stopped responding. Reloading it."
                    } else {
                        "This page keeps stopping. Try another site or open it in an external browser."
                    },
                    rendererGeneration = if (rendererRestarts <= MAX_RENDER_RESTARTS) {
                        _state.value.rendererGeneration + 1
                    } else {
                        _state.value.rendererGeneration
                    },
                )
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null) return null
                val url = request.url.toString()

                // Cloudflare's challenge bootstrap is never ad traffic.
                if (WebAdBlocker.isCloudflareInfra(url)) return null

                if (request.isForMainFrame) {
                    if (pendingMainFrameUrl?.let { sameUrl(it, url) } == true) {
                        pendingMainFrameUrl = null
                        return null
                    }
                    if (shouldBlockMainFrame(url, request.hasGesture())) {
                        // A main frame is never answered with an empty body: that is
                        // the white page users read as "the app broke". A gate with a
                        // destination is followed; anything else gets a way back.
                        val dest = adGateDestination(url)
                        if (dest != null) {
                            noteShield("redirect", "Followed the link behind ${hostOf(url)}.", hostOf(url).orEmpty())
                        }
                        return adGatePage(dest)
                    }
                    return null
                }

                if (isMediaRequest(request)) {
                    if (VIDEO_URL_RE.containsMatchIn(url)) {
                        runCatching {
                            maybeAddMedia(url, request.requestHeaders, detectedBy = "request")
                        }
                    }
                    return null
                }

                val host = hostOf(url).orEmpty()
                val refererHost = request.requestHeaders?.entries
                    ?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
                    ?.value
                    ?.let(::hostOf)
                val pageHost = refererHost ?: currentPageHost()
                val sameSite = host.isNotBlank() && pageHost != null && isSameSite(host, pageHost)
                // A host list names streaming sites for their ad SUBRESOURCES, not
                // because the site itself is an advert. Blocking the page's own
                // host, or a player/CDN domain, is what turned Dailymotion into a
                // half-loaded page and its video into a permanent spinner.
                val firstParty = sameSite || WebAdBlocker.isAlwaysAllowedHost(host)
                val type = WebAdBlocker.requestType(request)
                val isSubframeDocument = type == "other" &&
                    request.requestHeaders?.entries?.any { header ->
                        header.key.equals("Accept", ignoreCase = true) &&
                            header.value.contains("text/html", ignoreCase = true)
                    } == true

                if (this@AndroidWebBrowserController.settings.adBlockEnabled &&
                    !isAdblockWhitelisted(host)
                ) {
                    // Never strip the page's own layout CSS/fonts. Ads in CSS are rare; a
                    // missing stylesheet makes the whole site look broken.
                    if (type == "stylesheet" || type == "font" || type == "media") return null

                    // Downloaded hosts lists occasionally contain CDN hosts. Built-in rules
                    // still apply to same-site requests; the big external lists do not. Also
                    // keep scripts and XHR on the curated built-ins: a broad EasyList rule that
                    // happens to match the site's own app bundle makes a page look half-loaded.
                    val allowBroadRules = type != "script" && type != "xmlhttprequest"
                    // Downloaded EasyList/AdGuard lists are only checked for third-party
                    // iframe documents. Running up to 15k regexes for every third-party image
                    // made image-heavy sites load very slowly; the curated built-in rules and
                    // cosmetic hiding already cover banners and popups.
                    val includeExtra = isSubframeDocument && !sameSite
                    // uBlock/EasyList rules: full strength on third-party images, frames and
                    // other resources. Same-site requests, player iframes, scripts and XHR only
                    // get the curated built-ins, so a broad filter-list rule cannot strip the
                    // site's own code or a player frame. Any filter-engine error must fail open:
                    // an exception here runs on Chromium's worker thread and would kill the app.
                    val adBlocked = runCatching {
                        if (!allowBroadRules) {
                            // Player bundles and API calls must survive: blocking an ad SDK
                            // script often stops the site's own player from initialising.
                            // Popups and ad iframes are handled by the other rules.
                            false
                        } else {
                            // Hosts: never the site's own host and never a
                            // player/CDN. URL patterns still run, because an ad
                            // served from the site's own `/ads/…` path is real.
                            (!firstParty && WebAdBlocker.isBlocked(url, includeExtra = includeExtra)) ||
                                WebAdBlocker.matchesPattern(
                                    request,
                                    pageHost,
                                    includeFilterRules = includeExtra,
                                )
                        }
                    }.getOrDefault(false)
                    if (adBlocked) {
                        noteAdBlocked(host, type)
                        return blockedResponse()
                    }
                }
                // Streams are routinely served from a CDN that sends no CORS
                // headers, while the page's player fetches them with XHR (HLS
                // via MSE does). Blink then refuses the response and the player
                // sits on "0 seconds of 0 seconds" — the reported ppv.st player
                // failure. Re-fetching cross-origin XHR/media here and returning
                // it with CORS headers is the only place an embedded WebView can
                // bridge that gap.
                return corsProxyResponse(request)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                _state.value = _state.value.copy(
                    progress = newProgress / 100f,
                    loading = newProgress < 100,
                )
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                pageTitle = title.orEmpty()
                _state.value = _state.value.copy(title = pageTitle)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                // Some sites open the player from an async click handler, so the WebView does
                // not always credit a gesture. A tap in the last 3 s still counts as the user
                // asking for the window; popunders that fire seconds later do not.
                val tapped = isUserGesture || recentGesture(3_000L)
                if (this@AndroidWebBrowserController.settings.popupProtection && !tapped) {
                    noteShield("popup", "Blocked a popup that was not opened by a tap.", currentPageHost().orEmpty())
                    showBlockedToast("Blocked an automatic popup")
                    return false
                }
                // One popup at a time. Ad scripts answer a single tap with a storm of
                // windows; replacing the open one let the storm keep the screen busy.
                if (popupDialog != null) {
                    noteShield("popup", "Blocked an extra popup while one is open.", currentPageHost().orEmpty())
                    return false
                }
                if (popupChild != null) {
                    if (popupHidden) {
                        // Ad-gated players open a CHAIN of windows (the first is
                        // the ad slot, the next is the tracker it returned). Both
                        // stay invisible, but the chain must be allowed to move:
                        // each new hidden window replaces the previous one.
                        dismissPopup()
                    } else {
                        noteShield("popup", "Blocked an extra popup while one is open.", currentPageHost().orEmpty())
                        return false
                    }
                }
                if (videoPlaying && !isUserGesture && !recentGesture(1_500L)) {
                    noteShield("popup", "Blocked an ad popup during playback.", currentPageHost().orEmpty())
                    showBlockedToast("Blocked an ad popup")
                    return false
                }
                popupFromGesture = tapped
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val activity = appContext.findActivity()
                if (activity == null) {
                    noteShield("popup", "Popup could not be shown: no activity.", currentPageHost().orEmpty())
                    return false
                }
                if (popupDialog != null) dismissPopup()
                val child = runCatching { createPopupWebView(activity) }.getOrNull() ?: run {
                    noteShield("popup", "Popup could not be created.", currentPageHost().orEmpty())
                    return false
                }
                popupChild = child
                transport.webView = child
                if (this@AndroidWebBrowserController.settings.popupProtection) {
                    // Hand the page a REAL window (some players check it) but keep
                    // it off-screen, muted, and short-lived. Showing it lets the ad
                    // through; blocking it leaves the player on its idle screen.
                    popupHidden = true
                    android.util.Log.d("NuvioShield", "hidden popup created for " + currentPageHost())
                    resultMsg.sendToTarget()
                    mainHandler.postDelayed({
                        if (popupChild === child && popupHidden) {
                            android.util.Log.d("NuvioShield", "hidden popup closed")
                            dismissPopup()
                        }
                    }, 20_000L)
                    mainHandler.postDelayed({
                        if (popupChild === child && popupHidden) {
                            runCatching {
                                child.evaluateJavascript(
                                    "document.querySelectorAll('video,audio').forEach(function(m){try{m.muted=true;m.pause()}catch(e){}})",
                                    null,
                                )
                            }
                        }
                    }, 1_200L)
                    return true
                }
                popupHidden = false
                runCatching { showPopupWindow(child) }
                if (popupDialog == null || popupChild !== child) {
                    // showPopupWindow already cleaned up; do not hand a destroyed view back to
                    // Chromium.
                    if (popupChild === child) popupChild = null
                    destroyPopupView(child)
                    noteShield("popup", "Popup could not be shown.", currentPageHost().orEmpty())
                    return false
                }
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                if (window === popupChild) dismissPopup()
            }

            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?,
            ) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                val activity = appContext.findActivity()
                if (activity == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                val decor = activity.window.decorView as? ViewGroup
                if (decor == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                val container = FrameLayout(activity).apply {
                    setBackgroundColor(0xFF000000.toInt())
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
                // Some WebView versions hand back a custom view that still has a parent.
                // Re-parenting it avoids "child already has a parent" force-closes.
                val attached = runCatching {
                    (view.parent as? ViewGroup)?.removeView(view)
                    container.addView(
                        view,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    decor.addView(container)
                    true
                }.getOrDefault(false)
                if (!attached) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                customViewContainer = container
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                runCatching {
                    WindowCompat.getInsetsController(activity.window, decor).apply {
                        systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
                _state.value = _state.value.copy(fullscreen = true)
            }

            override fun onHideCustomView() {
                hideFullscreen(notifyWebView = false)
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                val text = message.orEmpty()
                if (this@AndroidWebBrowserController.settings.blockPromoDialogs && (!recentGesture(2_500L) || videoPlaying)) {
                    noteShield("js", "Suppressed page alert: ${text.take(140)}", hostOf(url).orEmpty())
                    result?.confirm()
                    return true
                }
                showJsAlert(text, result)
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                val text = message.orEmpty()
                if (this@AndroidWebBrowserController.settings.blockPromoDialogs && (!recentGesture(2_500L) || videoPlaying)) {
                    noteShield("js", "Suppressed page confirm: ${text.take(140)}", hostOf(url).orEmpty())
                    result?.cancel()
                    return true
                }
                val activity = appContext.findActivity()
                if (activity == null) {
                    result?.cancel()
                    return true
                }
                runCatching {
                    AlertDialog.Builder(activity)
                        .setTitle(hostOf(url).orEmpty().ifBlank { "This page says" })
                        .setMessage(text)
                        .setPositiveButton("OK") { _, _ -> result?.confirm() }
                        .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                }.onFailure { result?.cancel() }
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                if (this@AndroidWebBrowserController.settings.blockPromoDialogs && (!recentGesture(2_500L) || videoPlaying)) {
                    noteShield("js", "Suppressed page prompt: ${message.orEmpty().take(140)}", hostOf(url).orEmpty())
                    result?.cancel()
                    return true
                }
                val activity = appContext.findActivity()
                if (activity == null) {
                    result?.cancel()
                    return true
                }
                val input = EditText(activity).apply {
                    setText(defaultValue.orEmpty())
                    setSingleLine()
                }
                runCatching {
                    AlertDialog.Builder(activity)
                        .setTitle(hostOf(url).orEmpty().ifBlank { "This page says" })
                        .setMessage(message.orEmpty())
                        .setView(input)
                        .setPositiveButton("OK") { _, _ -> result?.confirm(input.text?.toString().orEmpty()) }
                        .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                }.onFailure { result?.cancel() }
                return true
            }

            override fun onJsBeforeUnload(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                if (this@AndroidWebBrowserController.settings.blockBeforeUnload && videoPlaying) {
                    noteShield("js", "Suppressed \"leave site?\" while a video is playing.", hostOf(url).orEmpty())
                    result?.cancel()
                } else {
                    result?.confirm()
                }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val launcher = fileChooserLauncher
                val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()
                if (launcher == null || intent == null) {
                    fileChooserCallback = null
                    return false
                }
                return runCatching {
                    launcher(intent)
                    true
                }.getOrElse {
                    fileChooserCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                request?.deny()
            }
        }
    }

    fun setFileChooserLauncher(launcher: ((Intent) -> Unit)?) {
        fileChooserLauncher = launcher
    }

    fun onFileChooserResult(resultCode: Int, data: Intent?) {
        val callback = fileChooserCallback ?: return
        fileChooserCallback = null
        if (resultCode != Activity.RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return
        }
        val uris = buildList {
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index)?.uri?.let(::add)
            }
            data.data?.let(::add)
        }.distinct().toTypedArray()
        callback.onReceiveValue(uris)
    }

    private fun recentGesture(windowMs: Long = 2_500L): Boolean =
        SystemClock.uptimeMillis() - lastUserInteractionMs <= windowMs

    private fun noteShield(kind: String, message: String, host: String = "") {
        val now = SystemClock.uptimeMillis()
        if (kind == "ad" && now - lastBlockedNoteAt < 2_000L) return
        if (kind == "ad") lastBlockedNoteAt = now
        val event = WebShieldEvent(
            id = ++nextShieldEventId,
            kind = kind,
            message = message.take(220),
            host = host.take(120),
            atMs = System.currentTimeMillis(),
        )
        synchronized(shieldEvents) {
            shieldEvents.addFirst(event)
            while (shieldEvents.size > 60) shieldEvents.removeLast()
        }
        val snapshot = synchronized(shieldEvents) { shieldEvents.toList() }
        mainHandler.post {
            _state.value = _state.value.copy(shieldEvents = snapshot)
        }
    }

    private fun noteAdBlocked(host: String, type: String) {
        noteShield("ad", "Blocked $type from ${host.ifBlank { "an ad host" }}.", host)
    }

    private fun showBlockedToast(message: String) {
        mainHandler.post {
            runCatching { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun setDownloadMessage(message: String) {
        _state.value = _state.value.copy(downloadMessage = message)
        val now = SystemClock.uptimeMillis()
        val important = message.startsWith("Saved") ||
            message.contains("failed", ignoreCase = true) ||
            message.contains("cannot", ignoreCase = true) ||
            message.contains("Could not", ignoreCase = true) ||
            message.startsWith("Preparing") ||
            message.startsWith("Recording")
        if (important || now - lastDownloadToastAt >= 3_000L) {
            lastDownloadToastAt = now
            mainHandler.post {
                runCatching { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun downloadDirect(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        extraHeaders: Map<String, String>?,
    ) {
        if (isPrivateWebHost(hostOf(url))) {
            setDownloadMessage("Local network downloads are blocked.")
            return
        }
        runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Downloaded from Web-Apps")
                mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
                addRequestHeader("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: currentUserAgent)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Cookie", it)
                }
                pageUrl.takeIf { it.isNotBlank() }?.let { addRequestHeader("Referer", it) }
                extraHeaders?.forEach { (key, value) ->
                    if (key.isNotBlank() && value.isNotBlank()) addRequestHeader(key, value)
                }
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            noteShield("download", "Download started: $fileName", hostOf(url).orEmpty())
            setDownloadMessage("Download started: $fileName")
        }.onFailure {
            setDownloadMessage("Could not start the download.")
        }
    }

    /**
     * Re-fetches a cross-origin XHR/media request and returns it with CORS
     * headers, or null to let Blink handle it normally.
     *
     * A WebView has no switch that relaxes CORS, and a page cannot read a
     * stream host that does not send `Access-Control-Allow-Origin` — HLS over
     * MSE is always read, so the player fails even though the URL is public.
     * Only requests that carry an `Origin` and are cross-site are touched;
     * same-site traffic, documents, styles, fonts and scripts are untouched.
     * The body is streamed, so a multi-megabyte segment is not buffered in
     * memory. A failed proxy fetch returns null: the original request then
     * behaves exactly as before rather than becoming a new failure.
     */
    private fun corsProxyResponse(request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) return null
        // Chromium omits Origin on same-process fetches often enough that the
        // main page's own API call (ppv.st -> mumbai.television.st/api/event)
        // arrived without it. A missing Origin is treated as an anonymous
        // request: `*` is exactly the grant such a fetch needs.
        val origin = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
            ?.takeIf { it.isNotBlank() }
        val host = request.url.host ?: return null
        if (origin != null) {
            val originHost = runCatching { java.net.URI(origin).host }.getOrNull() ?: return null
            if (isSameSite(host, originHost)) return null
        }
        // A navigation is not a CORS fetch. Proxying an iframe's HTML document
        // strips the response context the ad wrapper/player expects (its
        // scripts then build nothing). Sec-Fetch-* is the reliable signal;
        // Accept is the fallback for requests from older engines.
        val headers = request.requestHeaders
        val dest = headers?.entries?.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
        val mode = headers?.entries?.firstOrNull { it.key.equals("Sec-Fetch-Mode", true) }?.value
        val accept = headers?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty()
        if (dest == "document" || dest == "iframe" || mode == "navigate" ||
            accept.contains("text/html", ignoreCase = true)
        ) {
            return null
        }
        val type = WebAdBlocker.requestType(request)
        if (type != "xmlhttprequest" && type != "media" && type != "other") return null
        val method = request.method.uppercase()
        // A request body is not available here, so POST/PUT cannot be replayed
        // faithfully — and replaying one without its body would be worse than
        // the CORS refusal.
        if (method != "GET" && method != "HEAD" && method != "OPTIONS") return null

        val allowedOrigin = origin ?: "*"
        val allowHeaders = mapOf(
            "Access-Control-Allow-Origin" to allowedOrigin,
            "Access-Control-Allow-Credentials" to if (origin == null) "false" else "true",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers" to (
                request.requestHeaders?.keys?.joinToString(", ").orEmpty().ifBlank { "*" }
                ),
            "Access-Control-Expose-Headers" to "*",
            "Access-Control-Max-Age" to "600",
            "Vary" to "Origin",
        )
        if (method == "OPTIONS") {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                204,
                "No Content",
                allowHeaders,
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        val url = request.url.toString()
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                // The WebView would hand back gzip; keep the bytes untouched so
                // Content-Length/Range stay truthful.
                setRequestProperty("Accept-Encoding", "identity")
                request.requestHeaders?.forEach { (key, value) ->
                    if (key.equals("Origin", true) || key.equals("Referer", true) ||
                        key.equals("User-Agent", true) || key.startsWith("Accept", true) ||
                        key.equals("Range", true) || key.equals("If-Range", true)
                    ) {
                        setRequestProperty(key, value)
                    }
                }
                // The embed player's XHR often omits Referer; some stream CDNs
                // reject a request without it (403 from nginx) even when the
                // token is valid. The page the user is on is the honest one.
                if (getRequestProperty("Referer") == null && pageUrl.isNotBlank()) {
                    setRequestProperty("Referer", pageUrl)
                }
                CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            }
            val code = connection.responseCode
            android.util.Log.d(
                "NuvioWebProxy",
                "proxy " + code + " " + host + " " + mimeShort(url) + " (origin=" + allowedOrigin + ")",
            )
            if (code !in 200..299) return@runCatching null
            val body = connection.inputStream
            val mime = connection.contentType?.substringBefore(';')?.trim()
                ?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val headers = LinkedHashMap(allowHeaders)
            if (connection.contentLengthLong >= 0) {
                headers["Content-Length"] = connection.contentLengthLong.toString()
            }
            connection.getHeaderField("Content-Range")?.let { headers["Content-Range"] = it }
            connection.getHeaderField("Accept-Ranges")?.let { headers["Accept-Ranges"] = it }
            connection.getHeaderField("Content-Encoding")?.let { headers["Content-Encoding"] = it }
            WebResourceResponse(
                mime,
                "utf-8",
                code,
                connection.responseMessage ?: "OK",
                headers,
                body,
            )
        }.getOrNull()
    }

    private fun mimeShort(url: String): String =
        url.substringAfterLast('/').substringBefore('?').take(60)

    private fun createPopupWebView(activity: Activity): WebView =
        WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = currentUserAgent
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setBackgroundColor(0xFF070909.toInt())
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(Bridge(), "NuvioWebBridge")
            installDocumentStartScripts(this)
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    downloadDirect(url, userAgent, contentDisposition, mimeType, null)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (handleUrl(v, url)) return true
                    return false
                }

                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(v, url, favicon)
                    val target = url.orEmpty()
                    // The blank window an ad script opens before writing into it is not
                    // itself an ad. Judging it by its first real navigation is what keeps
                    // real popup players working.
                    if (target == "about:blank" || target.startsWith("javascript:")) return
                    if (!target.startsWith("http://") && !target.startsWith("https://")) {
                        // A popup to an app scheme is a hand-off the user did not ask for.
                        if (popupFromGesture) return
                        dismissPopup()
                        return
                    }
                    val host = hostOf(target).orEmpty()
                    val opener = currentPageHost()
                    val sameSite = opener != null && host.isNotBlank() && isSameSite(host, opener)
                    val adTarget = !sameSite && runCatching {
                        WebAdBlocker.isBlocked(target, includeExtra = true) ||
                            WebAdBlocker.matchesPatternUrl(
                                target,
                                opener,
                                type = "document",
                                includeFilterRules = true,
                            )
                    }.getOrDefault(false)
                    // A player's play button is the most common popup trigger on
                    // streaming sites, and the window it opens is almost never
                    // same-site. With protection on, only the page's own site (or
                    // a host the user allowed) may own a popup; a cross-site one is
                    // closed even when the tap that opened it was real.
                    val crossSite = !sameSite && !isAllowedHost(host)
                    if (this@AndroidWebBrowserController.settings.popupProtection && popupHidden) {
                        if (crossSite || adTarget || isBlockedPopupHost(host)) {
                            // Keep the window alive but invisible: closing it
                            // immediately breaks players that wait for a popup to
                            // finish navigating. It is muted and auto-closed.
                            noteShield("popup", "Kept an ad popup hidden ($host).", host)
                        } else if (v != null) {
                            // Same-site and the user did tap: the site's own
                            // window, safe to show.
                            popupHidden = false
                            runCatching { showPopupWindow(v) }
                        }
                        return
                    }
                    if (isBlockedPopupHost(host) || adTarget || (this@AndroidWebBrowserController.settings.popupProtection && crossSite)) {
                        noteShield("popup", "Blocked popup to $host.", host)
                        dismissPopup()
                    }
                }

                override fun onPageFinished(v: WebView?, url: String?) {
                    super.onPageFinished(v, url)
                    if (v == null) return
                    v.evalJs(videoScanJs)
                    v.evalJs(performanceScanJs)
                    v.evalJs(stuckMonitorJs)
                    v.evalJs(playingMonitorJs)
                    if (this@AndroidWebBrowserController.settings.adBlockEnabled &&
                        this@AndroidWebBrowserController.settings.cosmeticFiltering
                    ) {
                        v.evalJs(adCleanJs)
                    }
                    if (this@AndroidWebBrowserController.settings.mediaSnifferEnabled) {
                        v.evalJs(snifferJs)
                    }
                    // Popup players often create their <video> a moment after the page loads.
                    mainHandler.postDelayed({
                        if (popupChild === v) {
                            v.evalJs(videoScanJs)
                            v.evalJs(performanceScanJs)
                        }
                    }, 2_500L)
                }

                override fun onRenderProcessGone(
                    v: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?,
                ): Boolean {
                    if (v === popupChild) dismissPopup()
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(v: WebView?, title: String?) {
                    title?.takeIf { it.isNotBlank() }?.let { updatePopupTitle(it) }
                }

                override fun onCloseWindow(window: WebView?) {
                    if (window === popupChild) dismissPopup()
                }

                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    request?.deny()
                }
            }
        }

    private fun showPopupWindow(child: WebView) {
        val activity = appContext.findActivity() ?: return
        val titleView = TextView(activity).apply {
            text = "Popup"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            maxLines = 1
            setPadding(28, 0, 16, 0)
        }
        val closeButton = Button(activity).apply {
            text = "Close"
            setOnClickListener { dismissPopup() }
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF111515.toInt())
            addView(
                titleView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            addView(
                closeButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF070909.toInt())
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (48 * activity.resources.displayMetrics.density).toInt(),
                ),
            )
            addView(
                child,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
            setContentView(root)
            setCancelable(true)
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissPopup()
                    true
                } else {
                    false
                }
            }
            setOnDismissListener {
                if (popupDialog === this) {
                    popupDialog = null
                    val current = popupChild
                    popupChild = null
                    destroyPopupView(current)
                }
            }
        }
        popupDialog = dialog
        runCatching { dialog.show() }.onFailure {
            noteShield("popup", "Popup could not be shown.", currentPageHost().orEmpty())
            dismissPopup()
        }
    }

    private fun updatePopupTitle(title: String) {
        runCatching {
            val root = popupDialog?.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
            val bar = root.getChildAt(0) as? ViewGroup ?: return
            (bar.getChildAt(0) as? TextView)?.text = title
        }
    }

    private fun dismissPopup() {
        popupHidden = false
        val dialog = popupDialog
        popupDialog = null
        runCatching { dialog?.dismiss() }
        val child = popupChild
        popupChild = null
        destroyPopupView(child)
    }

    private fun destroyPopupView(child: WebView?) {
        // Never destroy a WebView from inside its own WebViewClient callback: destroyWebView
        // posts the cleanup so the Chromium callback has returned first.
        destroyWebView(child)
    }

    private fun hideFullscreen(notifyWebView: Boolean = true) {
        val container = customViewContainer
        val callback = customViewCallback
        customView = null
        customViewContainer = null
        customViewCallback = null
        container?.let { view ->
            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        }
        val activity = appContext.findActivity()
        if (activity != null) {
            runCatching { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            runCatching {
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
        if (notifyWebView) runCatching { callback?.onCustomViewHidden() }
        if (_state.value.fullscreen) _state.value = _state.value.copy(fullscreen = false)
    }

    private fun showJsAlert(message: String, result: JsResult?) {
        val activity = appContext.findActivity()
        if (activity == null) {
            result?.confirm()
            return
        }
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("This page says")
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result?.confirm() }
                .setOnCancelListener { result?.confirm() }
                .show()
        }.onFailure { result?.confirm() }
    }

    private fun adSkipConfigJson(): JSONObject = JSONObject().apply {
        put("mode", settings.adSkipMode.name)
        put("speed", settings.adFastForwardSpeed.toDouble().coerceIn(2.0, 16.0))
        put("mute", settings.muteDuringAds)
        put("skipIntros", settings.skipIntros)
        put("skipRecaps", settings.skipRecaps)
        put("autoNext", settings.autoNextEpisode)
        put("aggressive", settings.adSkipMode == WebAdSkipMode.Aggressive)
    }

    private fun injectAdSkipConfig() {
        val view = webView ?: return
        val script = "window.__NUVIO_AD_CONFIG=${adSkipConfigJson()};" + adSkipJs
        view.evalJs(script)
    }

    private fun installDocumentStartScripts(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                "window.__NUVIO_BLOCK_NOTIFICATIONS=${settings.blockNotifications};" + shieldJs,
                setOf("*"),
            )
            if (settings.adSkipMode != WebAdSkipMode.Off) {
                WebViewCompat.addDocumentStartJavaScript(
                    view,
                    "window.__NUVIO_AD_CONFIG=${adSkipConfigJson()};" + adSkipJs,
                    setOf("*"),
                )
            }
            if (settings.mediaSnifferEnabled) {
                WebViewCompat.addDocumentStartJavaScript(view, snifferJs, setOf("*"))
            }
            // Cosmetic filtering is injected after the page has a DOM. Hiding elements at
            // document-start could remove a player wrapper before the player was created.
        }
    }

    private fun injectSniffer() {
        if (!settings.mediaSnifferEnabled) return
        val shield = JSONObject().apply {
            put("blockNotifications", settings.blockNotifications)
        }
        val view = webView ?: return
        view.evalJs("window.__NUVIO_SHIELD=$shield;")
        view.evalJs(snifferJs)
        view.evalJs(performanceScanJs)
    }

    private fun injectShield() {
        val view = webView ?: return
        val block = settings.blockNotifications
        view.evalJs("window.__NUVIO_BLOCK_NOTIFICATIONS=$block;")
        view.evalJs(shieldJs)
    }

    private fun scheduleAutoPlay() {
        if (settings.autoPlayMode == WebAutoPlayMode.Off) return
        autoPlayRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            autoPlayRunnable = null
            evaluateAutoPlay()
        }
        autoPlayRunnable = runnable
        mainHandler.postDelayed(runnable, 1_800L)
    }

    private fun evaluateAutoPlay() {
        if (settings.autoPlayMode == WebAutoPlayMode.Off) return
        if (!videoPlaying && !recentGesture(15_000L)) return
        val candidates = synchronized(detected) { detected.values.toList() }
            .filter { source ->
                !source.likelyAd &&
                    source.drm == null &&
                    !source.isAudioOnly &&
                    source.confidence >= 45 &&
                    !isSegmentUrl(source.url) &&
                    source.url !in handedOffUrls
            }
            .sortedByDescending { webMediaScore(it) }
            .take(3)
        if (candidates.isEmpty()) return
        val best = candidates.first()
        handedOffUrls += best.url
        _state.value = _state.value.copy(
            autoPlayCandidate = best,
            autoPlayAlternatives = candidates.drop(1),
        )
    }

    private fun isSegmentUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".aac") ||
            path.contains("/seg-") || path.contains("/segment")
    }

    private fun isMediaUrl(url: String): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        if (isSegmentUrl(url)) return false
        if (!VIDEO_URL_RE.containsMatchIn(url)) return false
        return !url.lowercase().contains(".vtt") && !url.lowercase().contains(".srt")
    }

    private fun captureVideoThumbnail(url: String, rect: android.graphics.RectF?) {
        val view = webView ?: return
        if (rect == null || rect.width() <= 0f || rect.height() <= 0f) return
        // Only grab a frame from a playing video. The DOM scanner repeats every 2.5 s; without
        // this guard every scan allocated a full-screen bitmap, which could exhaust memory and
        // force-close the app while watching a stream.
        if (!videoPlaying) return
        if (detected[url]?.thumbnailPath != null) return
        val now = SystemClock.uptimeMillis()
        val last = thumbnailCaptures[url] ?: 0L
        if (now - last < 20_000L) return
        thumbnailCaptures[url] = now
        mainHandler.post {
            val width = view.width
            val height = view.height
            if (width <= 0 || height <= 0) return@post
            val left = (rect.left * width).toInt().coerceIn(0, width - 1)
            val top = (rect.top * height).toInt().coerceIn(0, height - 1)
            val right = (rect.right * width).toInt().coerceIn(left + 1, width)
            val bottom = (rect.bottom * height).toInt().coerceIn(top + 1, height)
            val bitmap = runCatching {
                val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(full)
                view.draw(canvas)
                val crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
                full.recycle()
                val target = Bitmap.createScaledBitmap(crop, 320, 180, true)
                crop.recycle()
                target
            }.getOrNull() ?: return@post
            Thread {
                runCatching {
                    val dir = File(appContext.filesDir, "web_media_thumbs").apply { mkdirs() }
                    val file = File(dir, "${url.hashCode().toUInt().toString(16)}.jpg")
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    }
                    bitmap.recycle()
                    val current = detected[url]
                    if (current != null) {
                        detected[url] = current.copy(
                            thumbnailPath = file.absolutePath,
                            thumbnailUrl = "file://${file.absolutePath}",
                        )
                        mainHandler.post {
                            _state.value = _state.value.copy(detectedMedia = synchronized(detected) { detected.values.toList() })
                        }
                    }
                }.onFailure { bitmap.recycle() }
            }.start()
        }
    }

    private fun Context.findActivity(): Activity? {
        var context: Context? = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return context as? Activity
    }

    private fun handleUrl(view: WebView?, url: String): Boolean {
        return when {
            url.startsWith("http://") || url.startsWith("https://") ||
                url.startsWith("data:") || url.startsWith("blob:") ||
                url.startsWith("javascript:") || url == "about:blank" -> false
            url.startsWith("file://") || url.startsWith("content://") -> {
                noteShield("security", "Blocked a local-file link.", currentPageHost().orEmpty())
                true
            }
            url.startsWith("intent:") -> {
                if (!recentGesture(5_000L)) {
                    noteShield("security", "Blocked an automatic app link.", currentPageHost().orEmpty())
                    return true
                }
                runCatching {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                true
            }
            else -> {
                if (!recentGesture(5_000L)) {
                    noteShield("security", "Blocked an automatic external link.", currentPageHost().orEmpty())
                    return true
                }
                runCatching {
                    appContext.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                true
            }
        }
    }

    private fun hostOf(url: String?): String? = runCatching {
        if (url.isNullOrBlank()) null else URL(url).host
    }.getOrNull()?.trim()?.trimEnd('.')?.lowercase()?.takeIf { it.isNotBlank() }

    /**
     * Must stay WebView-free: shouldInterceptRequest runs on a Chromium worker thread, and
     * WebView.getUrl() throws WebViewMethodCalledOnWrongThread there. pageUrl is updated on main.
     */
    private fun currentPageHost(): String? = hostOf(pageUrl)

    private fun sameUrl(a: String, b: String): Boolean =
        a.trimEnd('/') == b.trimEnd('/')

    private fun isSameSite(host: String, current: String): Boolean {
        val a = host.lowercase().trimEnd('.')
        val b = current.lowercase().trimEnd('.')
        if (a == b || a.endsWith(".$b") || b.endsWith(".$a")) return true
        return registrableDomain(a) == registrableDomain(b)
    }

    private fun isBlockedHost(host: String, hosts: Set<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        return hosts.any { listed ->
            val entry = listed.lowercase().trimEnd('.')
            h == entry || h.endsWith(".$entry")
        }
    }

    private fun isAllowedHost(host: String): Boolean =
        isBlockedHost(host, builtInAllowedHosts) || isBlockedHost(host, settings.allowedHosts)

    private fun isBlockedRedirectHost(host: String): Boolean =
        isBlockedHost(host, settings.blockedRedirectHosts)

    private fun isBlockedPopupHost(host: String): Boolean =
        isBlockedHost(host, settings.blockedPopupHosts)

    private fun isAdblockWhitelisted(host: String): Boolean =
        host.isNotBlank() && isBlockedHost(host, settings.adBlockWhitelist)

    private fun normalizeHost(raw: String): String? {
        val value = raw.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trimEnd('.')
            .lowercase()
        return value.takeIf {
            it.isNotBlank() && it.length <= 253 && !it.contains(' ') &&
                (it == "localhost" || it.contains('.'))
        }
    }

    private fun saveSettings(newSettings: WebAppsSettings) {
        settings = newSettings
        WebAppsRepository.updateSettings(newSettings)
    }

    private fun allowHost(host: String) {
        val h = normalizeHost(host) ?: return
        saveSettings(
            settings.copy(
                allowedHosts = settings.allowedHosts + h,
                blockedRedirectHosts = settings.blockedRedirectHosts - h,
                blockedPopupHosts = settings.blockedPopupHosts - h,
            )
        )
    }

    private fun blockPopupHost(host: String) {
        val h = normalizeHost(host) ?: return
        saveSettings(
            settings.copy(
                blockedPopupHosts = settings.blockedPopupHosts + h,
                allowedHosts = settings.allowedHosts - h,
            )
        )
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /**
     * The real destination carried by an ad gate.
     *
     * Streaming sites and Rumble wrap a click in a click-tracker on an ad host
     * (`d4.ads.rmbl.ws/c?…&fb=<base64 of the video page>`): the main frame is
     * sent to the tracker, which then 302s to the page. Blocking the tracker
     * outright left the WebView on an empty document — the "white page after
     * the first click" report. The destination is in the query, so take it and
     * load the page the click was for; only a gate with nothing usable behind
     * it is cancelled.
     */
    private fun adGateDestination(raw: String): String? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        for (key in listOf(
            "fb", "url", "u", "dest", "destination", "redirect", "redirect_url", "to", "target",
        )) {
            val v = uri.getQueryParameter(key) ?: continue
            for (candidate in listOf(v, decodeBase64(v))) {
                val c = candidate?.trim().orEmpty()
                if (c.startsWith("http://") || c.startsWith("https://")) return c
            }
        }
        return null
    }

    private fun decodeBase64(raw: String): String? = runCatching {
        val t = Uri.decode(raw).trim()
        val bytes = runCatching {
            Base64.decode(t, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.recoverCatching {
            Base64.decode(t, Base64.DEFAULT)
        }.getOrThrow()
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    /**
     * What a main-frame navigation to a blocked host renders instead of a blank
     * document. With a destination it forwards there immediately; with none it
     * says what happened and offers the way back, because a white page reads as
     * "the app is broken".
     */
    private fun adGatePage(destination: String?): WebResourceResponse {
        val style = "html,body{background:#000;color:#fff;font:15px sans-serif;margin:0}" +
            ".w{display:flex;height:100vh;flex-direction:column;gap:14px;align-items:center;" +
            "justify-content:center;padding:24px;text-align:center}a{color:#7DD3FC}"
        val html = if (destination != null) {
            val url = destination.replace("\"", "%22")
            "<!doctype html><meta charset=\"utf-8\">" +
                "<meta http-equiv=\"refresh\" content=\"0;url=" + url + "\">" +
                "<style>" + style + "</style><div class=\"w\">Opening…</div>" +
                "<script>location.replace(" + JSONObject.quote(destination) + ")</script>"
        } else {
            "<!doctype html><meta charset=\"utf-8\"><style>" + style + "</style>" +
                "<div class=\"w\"><div>An ad link was blocked.</div>" +
                "<a href=\"javascript:history.back()\">Go back</a></div>"
        }
        return WebResourceResponse(
            "text/html", "utf-8",
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun choiceDue(url: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (url == lastChoiceUrl && now - lastChoiceAt < 5_000L) return false
        lastChoiceUrl = url
        lastChoiceAt = now
        return true
    }

    @Volatile
    private var choiceShowing = false

    private fun showChoice(
        title: String,
        message: String,
        options: List<Pair<String, () -> Unit>>,
        onCancel: () -> Unit = {},
    ): Boolean {
        if (choiceShowing) {
            onCancel()
            return false
        }
        val activity = appContext.findActivity()
        if (activity == null) {
            onCancel()
            return false
        }
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) {
                onCancel()
                return@post
            }
            choiceShowing = true
            runCatching {
                val density = activity.resources.displayMetrics.density
                val pad = (16 * density).toInt()
                var dialog: AlertDialog? = null
                val box = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(pad, pad / 4, pad, pad / 2)
                }
                for ((label, action) in options) {
                    box.addView(
                        android.widget.Button(activity).apply {
                            text = label
                            setOnClickListener {
                                dialog?.dismiss()
                                action()
                            }
                        },
                        android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
                dialog = AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setView(box)
                    .setNegativeButton("Cancel") { _, _ -> onCancel() }
                    .setOnCancelListener { onCancel() }
                    .setOnDismissListener { choiceShowing = false }
                    .create()
                dialog?.show()
            }.onFailure {
                choiceShowing = false
                onCancel()
            }
        }
        return true
    }

    private fun askPopupPermission(url: String, host: String, onAllow: (Boolean) -> Unit): Boolean {
        if (!choiceDue(url)) {
            onAllow(false)
            return false
        }
        return showChoice(
            title = "Open this popup?",
            message = "This page tried to open a popup from $host. Popups from other sites are blocked by default.",
            options = listOf(
                "Allow once" to { onAllow(true) },
                "Always allow $host" to {
                    allowHost(host)
                    onAllow(true)
                },
                "Block always $host" to {
                    blockPopupHost(host)
                    onAllow(false)
                    noteShield("popup", "Blocked popups from $host.", host)
                },
            ),
            onCancel = { onAllow(false) },
        )
    }

    private fun shouldBlockMainFrame(url: String, hasGesture: Boolean): Boolean {
        if (allowNextMainFrame) {
            allowNextMainFrame = false
            return false
        }
        val pending = pendingMainFrameUrl
        if (pending != null && sameUrl(pending, url)) {
            pendingMainFrameUrl = null
            return false
        }
        if (url == "about:blank") return false
        if (!settings.redirectProtection) return false
        if (WebAdBlocker.isCloudflareInfra(url)) return false
        if (VIDEO_URL_RE.containsMatchIn(url)) return false
        val host = hostOf(url) ?: return false
        if (isAllowedHost(host)) return false
        val current = currentPageHost() ?: return false
        if (isSameSite(host, current)) return false
        if (isBlockedRedirectHost(host)) {
            noteShield("redirect", "Blocked redirect to $host.", host)
            return true
        }
        // A real tap is the user choosing this navigation, and it resets the chain.
        val gesture = hasGesture || recentGesture(3_000L)
        if (gesture) {
            redirectChainCount = 0
            redirectChainHost = ""
        }
        // A foreign redirect while a video is already playing is an ad hijack.
        if (videoPlaying && !gesture) {
            noteShield("redirect", "Blocked an ad redirect to $host during playback.", host)
            return true
        }
        if (!gesture) {
            // An automatic navigation to a host the ad lists know is a redirect
            // hijack, not a page the user asked for. A click-tracker's destination
            // is followed by the caller when it carries one.
            val adNavigation = runCatching {
                WebAdBlocker.isBlocked(url, includeExtra = true) ||
                    WebAdBlocker.matchesPatternUrl(
                        url,
                        current,
                        type = "document",
                        includeFilterRules = true,
                    )
            }.getOrDefault(false)
            if (adNavigation) {
                noteShield("redirect", "Blocked an ad redirect to $host.", host)
                return true
            }
            // A short chain of ordinary redirects (http -> https, a login hop, a
            // mirror) is normal. Three distinct foreign hosts inside eight
            // seconds is a hijack: real redirects do not tour ad domains.
            val now = SystemClock.uptimeMillis()
            if (now - redirectChainAt > 8_000L || host == redirectChainHost) {
                redirectChainHost = host
                redirectChainCount = 1
            } else {
                redirectChainHost = host
                redirectChainCount += 1
            }
            redirectChainAt = now
            if (redirectChainCount >= 3) {
                noteShield("redirect", "Blocked a redirect chain through $host.", host)
                return true
            }
        }
        // Everything else fails open: a page that cannot redirect at all looks
        // broken, and the gates above already cover the hijacks that matter.
        return false
    }

    private fun updateNavigation() {
        val view = webView ?: return
        _state.value = _state.value.copy(
            canGoBack = view.canGoBack(),
            canGoForward = view.canGoForward(),
            url = view.url ?: _state.value.url,
        )
    }

    private fun injectUserscripts(atStart: Boolean) {
        runCatching {
            val view = webView ?: return
            val url = pageUrl.ifBlank { view.url.orEmpty() }
            if (url.isBlank()) return
            WebUserscriptManager.scriptsFor(url, scripts, atStart).forEach { code ->
                view.evalJs(code)
            }
        }
    }

    private fun injectAdClean() {
        if (!settings.adBlockEnabled || !settings.cosmeticFiltering) return
        webView?.evalJs(adCleanJs)
    }

    private fun isMediaRequest(request: WebResourceRequest): Boolean {
        val headers = request.requestHeaders
        if (headers?.keys?.any { it.equals("Range", ignoreCase = true) } == true) return true
        val accept = headers?.entries?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
            ?.lowercase().orEmpty()
        if (accept.contains("video/") || accept.contains("application/octet-stream") ||
            accept.contains("mpegurl")
        ) {
            return true
        }
        val u = request.url.toString().lowercase()
        return u.contains(".mp4") || u.contains(".m3u8") || u.contains(".mpd") ||
            u.contains(".webm") || u.contains(".mkv") || u.contains(".mov") || u.contains(".m4v") ||
            u.contains(".ts?") || u.contains("videoplayback") || u.contains("/videos/") ||
            u.contains("/media/") || u.contains("/master") || u.contains("/manifest") ||
            u.contains("/playlist")
    }

    private data class MediaProbe(
        val streamType: String?,
        val durationMs: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        val bandwidth: Long? = null,
        val isMaster: Boolean = false,
        val variants: List<WebMediaVariant> = emptyList(),
        val isLive: Boolean = false,
        val isAudioOnly: Boolean = false,
        val drm: String? = null,
    )

    private fun maybeAddMedia(
        rawUrl: String,
        requestHeaders: Map<String, String>? = null,
        infoWidth: Int = 0,
        infoHeight: Int = 0,
        infoDurationMs: Long = 0L,
        posterUrl: String? = null,
        infoTitle: String? = null,
        detectedBy: String? = null,
        videoRect: android.graphics.RectF? = null,
        audioOnly: Boolean = false,
    ) {
        val url = rawUrl.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
        if (isPrivateWebHost(hostOf(url))) return
        if (isSegmentUrl(url)) return
        // A variant URL belongs to its master entry; do not show it as another video.
        if (synchronized(detected) { detected.values.any { master -> master.isMaster && master.variants.any { sameUrl(it.url, url) } } }) {
            return
        }

        // A DOM video often arrives after the request interceptor added the bare URL. Merge its
        // real duration/resolution so the picker can rank the actual video above 10-second ads.
        val existing = detected[url]
        if (existing != null) {
            // The DOM scanner reports the same video every 2.5 s. Only update state when a value
            // actually changed; otherwise the browser recomposed forever while a stream played.
            val newDetectedBy = detectedBy?.takeIf { it.isNotBlank() && it !in existing.detectedBy }
            val nextWidth = infoWidth.takeIf { it > 0 } ?: existing.width
            val nextHeight = infoHeight.takeIf { it > 0 } ?: existing.height
            val nextDuration = infoDurationMs.takeIf { it > 0 } ?: existing.durationMs
            val changed = (infoWidth > 0 && infoWidth != existing.width) ||
                (infoHeight > 0 && infoHeight != existing.height) ||
                (infoDurationMs > 0 &&
                    kotlin.math.abs(infoDurationMs - (existing.durationMs ?: 0L)) >= 1_000L) ||
                (!posterUrl.isNullOrBlank() && posterUrl != existing.posterUrl) ||
                (!infoTitle.isNullOrBlank() && infoTitle != existing.title) ||
                newDetectedBy != null ||
                (audioOnly && !existing.isAudioOnly)
            if (changed) {
                val updated = existing.copy(
                    width = nextWidth,
                    height = nextHeight,
                    durationMs = nextDuration,
                    posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: existing.posterUrl,
                    thumbnailUrl = existing.thumbnailUrl ?: posterUrl?.takeIf { it.isNotBlank() },
                    title = infoTitle?.takeIf { it.isNotBlank() } ?: existing.title,
                    detectedBy = if (newDetectedBy == null) existing.detectedBy else existing.detectedBy + newDetectedBy,
                    isAudioOnly = existing.isAudioOnly || audioOnly,
                ).let {
                    it.copy(
                        likelyAd = looksLikeAd(url, it.durationMs, it.width, it.height),
                        confidence = mediaConfidence(it),
                    )
                }
                detected[url] = updated
                mainHandler.post {
                    _state.value = _state.value.copy(detectedMedia = synchronized(detected) { detected.values.toList() })
                }
            }
            captureVideoThumbnail(url, videoRect)
            scheduleAutoPlay()
            return
        }
        if (!probing.add(url)) return
        val executor = probeExecutor
        if (executor.isShutdown) {
            probing.remove(url)
            return
        }
        val requestPageUrl = pageUrl
        val submitted = runCatching {
            executor.execute {
                val headers = mediaHeaders(url, requestHeaders)
                val probe = runCatching { probeMedia(url, headers) }.getOrNull()
                    ?: if (looksLikeDirectMedia(url)) MediaProbe(streamType = streamTypeForUrl(url)) else null
                probing.remove(url)
                if (probe == null) return@execute
                // The user navigated while the probe was running; do not leak the old page's
                // media into the new page's picker.
                if (pageUrl != requestPageUrl) return@execute
                val width = infoWidth.takeIf { it > 0 } ?: probe.width
                val height = infoHeight.takeIf { it > 0 } ?: probe.height
                val duration = infoDurationMs.takeIf { it > 0 } ?: probe.durationMs
                val source = WebMediaSource(
                    url = url,
                    pageUrl = pageUrl,
                    title = infoTitle?.takeIf { it.isNotBlank() }
                        ?: pageTitle.ifBlank { webHost(url) },
                    headers = headers,
                    streamType = probe.streamType,
                    isHls = probe.streamType == "hls",
                    isDash = probe.streamType == "dash",
                    durationMs = duration,
                    width = width,
                    height = height,
                    bandwidth = probe.bandwidth,
                    isMaster = probe.isMaster,
                    variantCount = probe.variants.size.coerceAtLeast(1),
                    likelyAd = looksLikeAd(url, duration, width, height),
                    posterUrl = posterUrl?.takeIf { it.isNotBlank() },
                    thumbnailUrl = posterUrl?.takeIf { it.isNotBlank() },
                    isLive = probe.isLive,
                    isAudioOnly = audioOnly || probe.isAudioOnly,
                    drm = probe.drm,
                    variants = probe.variants,
                    detectedBy = setOfNotNull(detectedBy),
                ).let {
                    it.copy(confidence = mediaConfidence(it))
                }
                detected[url] = source
                trimDetected(keep = url)
                mainHandler.post {
                    _state.value = _state.value.copy(detectedMedia = synchronized(detected) { detected.values.toList() })
                }
                captureVideoThumbnail(url, videoRect)
                scheduleAutoPlay()
            }
        }.isSuccess
        if (!submitted) probing.remove(url)
    }

    /** Heavy catalog pages can expose hundreds of clips; keep memory bounded. */
    private fun trimDetected(keep: String) {
        synchronized(detected) {
            while (detected.size > 80) {
                val victim = detected.keys.firstOrNull { it != keep && it !in handedOffUrls }
                    ?: break
                detected.remove(victim)
            }
        }
    }

    private fun mediaConfidence(source: WebMediaSource): Int {
        var score = 35
        if (source.isMaster) score += 30
        if ((source.durationMs ?: 0L) >= 120_000L) score += 20
        if ((source.width ?: 0) >= 1280 || (source.height ?: 0) >= 720) score += 12
        if ("dom" in source.detectedBy || "video" in source.detectedBy) score += 12
        if ("sniff" in source.detectedBy) score += 6
        if (source.isHls) score += 5
        if (source.drm != null) score -= 20
        if (source.isAudioOnly) score -= 30
        if (source.likelyAd) score -= 60
        return score.coerceIn(0, 100)
    }

    private fun looksLikeAd(url: String, durationMs: Long?, width: Int?, height: Int?): Boolean {
        val lower = url.lowercase()
        val adPath = listOf(
            "/ad/", "/ads/", "/advert", "preroll", "midroll", "pre-roll",
            "popunder", "banner", "vast", "vmap", "adserver", "doubleclick",
        ).any { lower.contains(it) }
        val adHost = hostOf(url)?.let { host ->
            listOf("doubleclick.net", "googlesyndication.com", "adnxs.com", "popads.net")
                .any { host == it || host.endsWith(".$it") }
        } == true
        val shortClip = durationMs != null && durationMs in 1..45_000L
        val smallVideo = (width ?: 0) in 1..640 || (height ?: 0) in 1..360
        return adPath || adHost || (shortClip && smallVideo)
    }

    private fun looksLikeDirectMedia(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv") ||
            path.endsWith(".mov") || path.endsWith(".m4v") || path.endsWith(".m3u8") ||
            path.endsWith(".mpd") || path.endsWith(".m4s")
    }

    private fun streamTypeForUrl(url: String): String? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "hls"
            path.endsWith(".mpd") -> "dash"
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv") ||
                path.endsWith(".mov") || path.endsWith(".m4v") -> "video"
            else -> null
        }
    }

    private fun captureThumbnail(siteId: String) {
        val view = webView ?: return
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return

        // Draw the top of the visible page into a wide 16:10 preview. That is the part of a home
        // page that identifies the site (header, hero, first row of content) and it matches the
        // card shape, so nothing important is cropped away.
        val targetWidth = 800
        val targetHeight = 500
        val aspect = targetWidth.toFloat() / targetHeight.toFloat()
        val sourceWidth = width.toFloat()
        val sourceHeight = (width / aspect).coerceAtMost(height.toFloat())
        val bitmap = runCatching {
            val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val scale = targetWidth.toFloat() / sourceWidth
            canvas.scale(scale, scale)
            canvas.clipRect(0f, 0f, sourceWidth, sourceHeight)
            view.draw(canvas)
            bmp
        }.getOrNull() ?: return

        Thread {
            val dir = File(appContext.filesDir, "web_thumbs").apply { mkdirs() }
            val file = File(dir, "$siteId-${System.currentTimeMillis()}.jpg")
            val saved = runCatching {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }.getOrDefault(false)
            bitmap.recycle()
            if (saved) {
                // The path changes on every capture so Coil cannot serve a stale cached image.
                dir.listFiles()
                    ?.filter {
                        (it.name.startsWith("$siteId-") || it.name == "$siteId.jpg") && it != file
                    }
                    ?.forEach { it.delete() }
                mainHandler.post { thumbnailListener?.invoke(siteId, file.absolutePath) }
            }
        }.start()
    }

    private fun mediaHeaders(
        url: String,
        requestHeaders: Map<String, String>? = null,
    ): Map<String, String> {
        fun requestHeader(name: String): String? =
            requestHeaders?.entries
                ?.firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.takeIf { it.isNotBlank() }

        val referer = requestHeader("Referer")
            ?: pageUrl.takeIf { it.isNotBlank() }
        val origin = requestHeader("Origin")
            ?: referer?.let(::originOf)
        return buildMap {
            if (!referer.isNullOrBlank()) put("Referer", referer)
            if (!origin.isNullOrBlank()) put("Origin", origin)
            put("User-Agent", requestHeader("User-Agent") ?: currentUserAgent)
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: requestHeader("Cookie")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
            // Some players sign each request with a custom header (session id, token). Carry
            // those through the probe and the handoff so the app player can fetch the same URL.
            requestHeaders?.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank() &&
                    (key.startsWith("X-", ignoreCase = true) ||
                        key.equals("Authorization", ignoreCase = true))
                ) {
                    put(key, value)
                }
            }
        }
    }

    private fun originOf(url: String): String? {
        val scheme = url.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = url.substringAfter("://").substringBefore('/')
        return if (host.isNotBlank()) "$scheme://$host" else null
    }

    private fun probeMedia(url: String, headers: Map<String, String>): MediaProbe? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            // No Range header here: manifests can be gzip-compressed, and a truncated gzip
            // stream fails to decompress. We only read the first 64 KB and close.
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return null
            val contentType = connection.contentType?.lowercase().orEmpty()
            val bytes = connection.inputStream.use { input ->
                val stream = if (connection.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                    GZIPInputStream(input)
                } else {
                    input
                }
                stream.use { readUpTo(it, 64 * 1024) }
            }
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
            val text = String(bytes, Charsets.UTF_8)
            val baseUrl = connection.url?.toString() ?: url
            when {
                head.startsWith("#EXTM3U") || contentType.contains("mpegurl") ->
                    parseHlsManifest(text, baseUrl)
                contentType.contains("application/dash+xml") || head.contains("<mpd") ->
                    parseDashManifest(text)
                contentType.contains("video/") -> MediaProbe(streamTypeForUrl(url) ?: "video")
                contentType.contains("audio/") -> MediaProbe(streamTypeForUrl(url), isAudioOnly = true)
                contentType.contains("application/octet-stream") && looksLikeMp4(bytes) ->
                    MediaProbe(streamTypeForUrl(url))
                else -> null
            }
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readUpTo(stream: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = stream.read(buffer)
            if (read <= 0) break
            out.write(buffer, 0, minOf(read, limit - out.size()))
        }
        return out.toByteArray()
    }

    private fun parseHlsManifest(text: String, baseUrl: String): MediaProbe {
        var durationMs = 0L
        var master = false
        var hasEndList = false
        var bestWidth = 0
        var bestHeight = 0
        var bestBandwidth = 0L
        var drm: String? = null
        var hasVideoVariant = false
        val variants = mutableListOf<WebMediaVariant>()
        var pendingVariant: WebMediaVariant? = null
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                master = true
                hasVideoVariant = true
                val attrs = trimmed.substringAfter(':')
                val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""").find(attrs)
                val width = resolution?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val height = resolution?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(attrs)
                    ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val codecs = Regex("""CODECS="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                val frameRate = Regex("""FRAME-RATE=([\d.]+)""").find(attrs)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
                pendingVariant = WebMediaVariant(
                    url = "",
                    width = width.takeIf { it > 0 },
                    height = height.takeIf { it > 0 },
                    bandwidth = bandwidth.takeIf { it > 0L },
                    codecs = codecs,
                    frameRate = frameRate,
                )
                if (width.toLong() * height > bestWidth.toLong() * bestHeight) {
                    bestWidth = width
                    bestHeight = height
                }
                bestBandwidth = maxOf(bestBandwidth, bandwidth)
            } else if (trimmed.isNotBlank() && !trimmed.startsWith("#") && pendingVariant != null) {
                val variant = pendingVariant
                pendingVariant = null
                if (variant != null) {
                    variants += variant.copy(url = resolveUrl(baseUrl, trimmed))
                }
            } else if (trimmed.startsWith("#EXTINF:")) {
                val seconds = trimmed.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                durationMs += (seconds * 1000).toLong()
            } else if (trimmed.startsWith("#EXT-X-ENDLIST")) {
                hasEndList = true
            } else if (trimmed.startsWith("#EXT-X-KEY:")) {
                val method = Regex("""METHOD=([^,]+)""").find(trimmed)
                    ?.groupValues?.get(1)?.uppercase().orEmpty()
                if (method.contains("SAMPLE-AES")) drm = "sample-aes"
                else if (method.contains("AES-128") && drm == null) drm = "aes-128"
            } else if (trimmed.startsWith("#EXT-X-MEDIA:")) {
                val type = Regex("""TYPE=([^,]+)""").find(trimmed)?.groupValues?.get(1)
                if (type != null && !type.equals("VIDEO", ignoreCase = true)) {
                    // Audio/subtitle groups are not a video source.
                }
            }
        }
        return MediaProbe(
            streamType = "hls",
            durationMs = durationMs.takeIf { it > 0L },
            width = bestWidth.takeIf { it > 0 },
            height = bestHeight.takeIf { it > 0 },
            bandwidth = bestBandwidth.takeIf { it > 0L },
            isMaster = master,
            variants = variants,
            isLive = !master && !hasEndList,
            isAudioOnly = false,
            drm = drm,
        )
    }

    private fun parseDashManifest(text: String): MediaProbe {
        var bestWidth = 0
        var bestHeight = 0
        var bestBandwidth = 0L
        var hasVideo = false
        val lower = text.lowercase()
        val drm = when {
            lower.contains("urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed") -> "widevine"
            lower.contains("urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95") -> "playready"
            lower.contains("<contentprotection") -> "dash-drm"
            else -> null
        }
        Regex("""<Representation\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            val tag = match.value
            val w = Regex("""width="(\d+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = Regex("""height="(\d+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val bw = Regex("""bandwidth="(\d+)"""").find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val mime = Regex("""mimeType="([^"]+)"""").find(tag)?.groupValues?.get(1).orEmpty()
            if (w > 0 || h > 0 || mime.contains("video", ignoreCase = true)) hasVideo = true
            if (w.toLong() * h > bestWidth.toLong() * bestHeight) {
                bestWidth = w
                bestHeight = h
            }
            bestBandwidth = maxOf(bestBandwidth, bw)
        }
        return MediaProbe(
            streamType = "dash",
            width = bestWidth.takeIf { it > 0 },
            height = bestHeight.takeIf { it > 0 },
            bandwidth = bestBandwidth.takeIf { it > 0L },
            isLive = lower.contains("type=\"dynamic\""),
            isAudioOnly = !hasVideo,
            drm = drm,
        )
    }

    private fun resolveUrl(baseUrl: String, reference: String): String =
        runCatching { java.net.URI(baseUrl).resolve(reference).toString() }.getOrDefault(reference)

    private fun looksLikeMp4(body: ByteArray): Boolean {
        if (body.size < 12) return false
        val tag = String(body, 4, 4, Charsets.ISO_8859_1)
        return tag == "ftyp" || tag == "moov" || tag == "mdat" || tag == "styp"
    }

    private inner class Bridge {
        @JavascriptInterface
        fun media(url: String) {
            runCatching { maybeAddMedia(url, detectedBy = "js") }
        }

        @JavascriptInterface
        fun mediaInfo(url: String, width: Int, height: Int, durationMs: Double) {
            runCatching {
                maybeAddMedia(
                    rawUrl = url,
                    infoWidth = width,
                    infoHeight = height,
                    infoDurationMs = durationMs.toLong(),
                    detectedBy = "dom",
                )
            }
        }

        @JavascriptInterface
        fun mediaMeta(json: String) {
            runCatching {
                val body = JSONObject(json)
                val url = body.optString("url")
                if (url.isBlank()) return@runCatching
                val rectArray = body.optJSONArray("rect")
                val rect = rectArray?.takeIf { it.length() >= 4 }?.let {
                    android.graphics.RectF(
                        it.optDouble(0).toFloat(),
                        it.optDouble(1).toFloat(),
                        it.optDouble(2).toFloat(),
                        it.optDouble(3).toFloat(),
                    )
                }
                maybeAddMedia(
                    rawUrl = url,
                    infoWidth = body.optInt("width", 0),
                    infoHeight = body.optInt("height", 0),
                    infoDurationMs = (body.optDouble("duration", 0.0) * 1000).toLong(),
                    posterUrl = body.optString("poster").takeIf { it.isNotBlank() },
                    infoTitle = body.optString("title").takeIf { it.isNotBlank() },
                    detectedBy = "dom",
                    videoRect = rect,
                    audioOnly = body.optBoolean("audioOnly", false),
                )
            }
        }

        @JavascriptInterface
        fun sniff(json: String) {
            runCatching {
                val body = JSONObject(json)
                val type = body.optString("kind", "media")
                if (type == "page") {
                    val urls = body.optJSONArray("urls") ?: return@runCatching
                    for (index in 0 until urls.length()) {
                        val url = urls.optString(index)
                        if (isMediaUrl(url)) maybeAddMedia(url, detectedBy = "page")
                    }
                    return@runCatching
                }
                val url = body.optString("url")
                if (!isMediaUrl(url)) return@runCatching
                val headers = body.optJSONObject("headers")?.let { objectMap ->
                    buildMap {
                        objectMap.keys().forEach { key ->
                            objectMap.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
                        }
                    }
                }
                maybeAddMedia(
                    rawUrl = url,
                    requestHeaders = headers,
                    posterUrl = body.optString("poster").takeIf { it.isNotBlank() },
                    infoTitle = body.optString("title").takeIf { it.isNotBlank() },
                    detectedBy = if (type == "mse" || type == "fetch" || type == "xhr") type else "sniff",
                )
            }
        }

        @JavascriptInterface
        fun adSkip(json: String) {
            runCatching {
                val body = JSONObject(json)
                val now = SystemClock.uptimeMillis()
                if (now - lastAdSkipAt < 900L) return@runCatching
                lastAdSkipAt = now
                val skipped = body.optInt("skipped", 0)
                val fastForwarded = body.optInt("fastForwarded", 0)
                val savedSeconds = body.optLong("savedSeconds", 0L)
                val current = _state.value
                _state.value = current.copy(
                    adsSkipped = current.adsSkipped + skipped,
                    adsFastForwarded = current.adsFastForwarded + fastForwarded,
                    adSecondsSaved = current.adSecondsSaved + savedSeconds,
                )
                if (skipped > 0) {
                    noteShield("adskip", "Skipped an ad.", pageUrl)
                } else if (fastForwarded > 0) {
                    noteShield("adskip", "Fast-forwarded an ad.", pageUrl)
                }
            }
        }

        @JavascriptInterface
        fun stuck(url: String) {
            runCatching { maybeAddMedia(url, detectedBy = "stuck") }
        }

        @JavascriptInterface
        fun playing(value: Boolean) {
            videoPlaying = value
            if (value) scheduleAutoPlay()
        }

        @JavascriptInterface
        fun userscriptGet(scriptId: String, key: String): String? =
            runCatching { WebAppsRepository.gmGet(scriptId, key) }.getOrNull()

        @JavascriptInterface
        fun userscriptSet(scriptId: String, key: String, valueJson: String) {
            runCatching { WebAppsRepository.gmSet(scriptId, key, valueJson) }
        }

        @JavascriptInterface
        fun userscriptDelete(scriptId: String, key: String) {
            runCatching { WebAppsRepository.gmDelete(scriptId, key) }
        }

        @JavascriptInterface
        fun userscriptList(scriptId: String): String =
            runCatching { WebAppsRepository.gmList(scriptId) }.getOrDefault("")
    }

    private companion object {
        const val MAX_RENDER_RESTARTS = 2

        val VIDEO_URL_RE = Regex(
            """(\.mp4|\.webm|\.mkv|\.mov|\.m4v|\.m3u8|\.mpd|videoplayback|/master|/manifest|/playlist)""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** Built-in hosts list plus downloaded hosts/uBlock filter lists, ported from Lucid Hikari (GPL-3.0). */
internal object WebAdBlocker {
    private val builtIn = setOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com", "googletagmanager.com",
        "googletagservices.com", "google-analytics.com", "adservice.google.com", "2mdn.net", "adf.ly",
        "taboola.com", "outbrain.com", "adnxs.com", "criteo.com", "criteo.net", "rubiconproject.com",
        "moatads.com", "quantserve.com", "scorecardresearch.com", "amazon-adsystem.com", "adform.net",
        "smartadserver.com", "openx.net", "pubmatic.com", "sovrn.com", "casalemedia.com", "lijit.com",
        "adsafeprotected.com", "popads.net", "popunder.ru", "adsterra.com", "propellerads.com",
        "adroll.com", "media.net", "yieldmo.com", "adcolony.com", "inmobi.com", "admarvel.com",
        "flurry.com", "startappservice.com", "smartyads.com", "admob.com", "applovin.com",
        "vungle.com", "chartboost.com", "mopub.com", "supersonicads.com", "revcontent.com",
        "adpushup.com", "snigelweb.com", "adthrive.com", "ezoic.net", "mediavine.com",
        "trafficjunky.net", "exoclick.com", "juicyads.com",
    )

    private val hostsListUrls = listOf(
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "https://adaway.org/hosts.txt",
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
    )

    private val filterListUrls = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
        "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
        // AdGuard-compatible lists. They use the same network-rule subset as uBO.
        "https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_2_Base/filter.txt",
        "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
    )

    /** Keep filter-list parsing and per-request checks bounded on a phone. */
    private const val MAX_FILTER_RULES = 15_000

    @Volatile
    private var extraHosts: Set<String> = emptySet()

    @Volatile
    private var filterRules: List<PatternRule> = emptyList()

    @Volatile
    private var combinedRules: List<PatternRule> = emptyList()

    @Volatile
    private var filtersLoaded = false

    private val builtInRules: List<PatternRule> by lazy {
        BUILTIN_PATTERNS.mapNotNull(::parsePattern)
    }

    private fun compiledPatterns(): List<PatternRule> =
        combinedRules.ifEmpty { builtInRules }

    private fun hostsFile(context: android.content.Context) =
        java.io.File(context.filesDir, "web_adblock_hosts.txt")

    private fun filterDir(context: android.content.Context) =
        java.io.File(context.filesDir, "web_adblock_filters").apply { mkdirs() }

    private fun filterCacheFile(context: android.content.Context, url: String) =
        java.io.File(filterDir(context), url.hashCode().toLong().toString(16) + ".txt")

    fun load(context: android.content.Context) {
        val file = hostsFile(context)
        if (file.exists()) {
            extraHosts = runCatching { file.readLines().filter { it.isNotBlank() }.toSet() }
                .getOrDefault(emptySet())
        }
        if (filtersLoaded) return
        filtersLoaded = true
        Thread {
            val rules = ArrayList<PatternRule>()
            filterDir(context).listFiles()?.forEach { cached ->
                if (!cached.name.endsWith(".txt")) return@forEach
                runCatching {
                    parseFilterList(cached.readText(), MAX_FILTER_RULES - rules.size)
                        .forEach { rules += it }
                }
            }
            filterRules = rules
            combinedRules = builtInRules + rules
        }.start()
    }

    fun update(context: android.content.Context) {
        val hosts = HashSet<String>()
        for (url in hostsListUrls) {
            runCatching {
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "Nuvio")
                }
                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val clean = line.substringBefore('#').trim()
                        if (clean.isBlank()) return@forEach
                        val parts = clean.split(Regex("\\s+"))
                        val host = when {
                            parts.firstOrNull()?.let(::isIp) == true -> parts.getOrNull(1)
                            else -> parts.firstOrNull()
                        }?.trim()?.trimEnd('.')?.lowercase()
                        if (host != null && host.contains('.') &&
                            !host.startsWith("localhost") && !host.endsWith(".local")
                        ) {
                            hosts += host
                        }
                    }
                }
                connection.disconnect()
            }
        }
        if (hosts.isNotEmpty()) {
            extraHosts = hosts
            runCatching { hostsFile(context).writeText(hosts.joinToString("\n")) }
        }

        // EasyList/uBlock network rules: same uBO-style engine, cached for offline use.
        for (url in filterListUrls) {
            runCatching {
                val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 40_000
                    setRequestProperty("User-Agent", "Nuvio")
                }
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                if (text.isNotBlank()) {
                    filterCacheFile(context, url).writeText(text)
                }
            }
        }

        val rules = ArrayList<PatternRule>()
        filterDir(context).listFiles()?.forEach { cached ->
            if (!cached.name.endsWith(".txt")) return@forEach
            runCatching {
                parseFilterList(cached.readText(), MAX_FILTER_RULES - rules.size)
                    .forEach { rules += it }
            }
        }
        filterRules = rules
        combinedRules = builtInRules + rules
    }

    private fun isIp(value: String): Boolean =
        value == "0.0.0.0" || value == "127.0.0.1" || value == "::1" || value.startsWith("::ffff:")

    /** Frameworks/CDNs that must never be blocked, even if a downloaded list names them. */
    private val essentialHosts = setOf(
        "cdn.jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com",
        "ajax.googleapis.com", "fonts.googleapis.com", "fonts.gstatic.com",
        "challenges.cloudflare.com", "cloudflare.com",
    )

    private fun isEssentialHost(host: String): Boolean =
        isBlockedHost(host, essentialHosts)

    /**
     * Player and CDN domains a host list must never take down.
     *
     * These are the domains that serve the VIDEO, not the ad wrapper around it.
     * Lists routinely name them (tube-site CDNs, Dailymotion's `dmcdn.net`,
     * Vimeo's and JW Player's delivery hosts), and a blocked CDN is
     * indistinguishable from a broken player: the page loads and the stream
     * buffers forever. Path rules still run, so an actual ad path on the same
     * host can still be blocked.
     */
    private val alwaysAllowedHosts = setOf(
        "dmcdn.net",
        "googlevideo.com", "ytimg.com", "ggpht.com",
        "fbcdn.net", "twimg.com", "cdninstagram.com",
        "akamaihd.net", "akamaized.net", "edgesuite.net", "llnwd.net",
        "cloudfront.net", "fastly.net", "azureedge.net", "b-cdn.net",
        "jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com",
        "mux.com", "jwplayer.com", "jwplatform.com", "vimeocdn.com",
        "cloudflarestream.com", "vidyoda.com",
    )

    fun isAlwaysAllowedHost(host: String): Boolean =
        host.isNotBlank() && isBlockedHost(host, alwaysAllowedHosts)

    fun isBlocked(url: String, includeExtra: Boolean = true): Boolean {
        val host = runCatching { java.net.URL(url).host?.lowercase() }.getOrNull() ?: return false
        if (isEssentialHost(host) || isAlwaysAllowedHost(host)) return false
        return isBlockedHost(host, builtIn) ||
            (includeExtra && isBlockedHost(host, extraHosts))
    }

    private fun isBlockedHost(host: String, list: Set<String>): Boolean {
        if (list.isEmpty()) return false
        var current = host
        while (true) {
            if (current in list) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
    }

    // ------------------------------------------------ uBO-style network rules
    //
    // The hosts files only match whole domains. These rules catch the ads served
    // from the site's own domain (`/ads/banner`, `/pop.js`), rotating popunder
    // hosts and player-ad scripts. Syntax is the practical EasyList/uBO subset:
    //   ||domain^path*   domain-anchored (any subdomain, any scheme)
    //   |http…|          start/end anchored
    //   plain substring  (with * wildcards)
    //   ^               separator (end of host, /, ?, &, =)
    //   @@…             exception — never block, wins over everything
    //   $third-party, $~third-party, $domain=a|~b, $script, $image, $media, …
    //   $match-case

    data class PatternRule(
        val raw: String,
        val isException: Boolean,
        val regex: Regex,
        val thirdParty: Boolean?,
        val domains: List<DomainOpt>?,
        val types: Set<String>?,
        val keyword: String?,
        val fromFilterList: Boolean = false,
    )

    data class DomainOpt(val domain: String, val negated: Boolean)

    /** Curated uBO-style rules for the ad networks video/streaming sites actually use. */
    private val BUILTIN_PATTERNS = listOf(
        "||popads.net^", "||popcash.net^", "||popunder.net^",
        "||adsterra.com^", "||propellerads.com^", "||propeller-tracking.com^",
        "||adcash.com^", "||exoclick.com^", "||trafficjunky.net^",
        "||juicyads.com^", "||adxpansion.com^", "||hilltopads.net^",
        "||clickadu.com^", "||mgid.com^", "||revcontent.com^",
        "||outbrain.com^", "||taboola.com^", "||criteo.com^",
        "||adnxs.com^", "||rubiconproject.com^", "||pubmatic.com^",
        "||doubleclick.net^", "||googlesyndication.com^",
        "||googleadservices.com^", "||amazon-adsystem.com^",
        "||imasdk.googleapis.com^",
        "/popunder", "/pop-under", "/popup-ad", "/popad.", "/pop.js",
        "/popunder.js", "/popup.js", "/popcash.js", "/pst.js",
        "/ads/banner", "/ads/pop", "/advertising/", "/adserver/",
        "/promo-redirect", "/redirect-ads", "/out.php?",
        "||vast.com^", "/vast.xml", "/vmap", "imasdk",
        "/jwplayer-ads", "/video-ads/", "/preroll",
        "||moatads.com^", "||scorecardresearch.com^", "||quantserve.com^",
        "@@||accounts.google.com^", "@@||paypal.com^", "@@||stripe.com^",
        "@@||cloudflare.com^", "@@||challenges.cloudflare.com^",
    )

    private val optionsRe = Regex("^[a-z~\\-]+(=[^,]*)?(,[a-z~\\-]+(=[^,]*)?)*$")

    fun parsePattern(raw: String): PatternRule? {
        var s = raw.trim()
        if (s.isEmpty() || s.startsWith("!") || s.startsWith("[")) return null
        if (s.contains("##") || s.contains("#@#") || s.contains("#?#") || s.contains("#$#")) return null
        if (s.startsWith("/") && s.endsWith("/") && s.length > 2) return null // regex rule
        val isException = s.startsWith("@@")
        if (isException) s = s.substringAfter("@@")
        var thirdParty: Boolean? = null
        var domains: List<DomainOpt>? = null
        var types: Set<String>? = null
        val optIdx = s.lastIndexOf('$')
        if (optIdx > 0) {
            val opts = s.substring(optIdx + 1)
            if (optionsRe.matches(opts)) {
                s = s.substring(0, optIdx)
                for (o in opts.split(',')) {
                    val name = o.substringBefore('=')
                    val value = o.substringAfter('=', "")
                    when (name) {
                        "third-party" -> thirdParty = true
                        "~third-party" -> thirdParty = false
                        "domain" -> domains = value.split('|')
                            .filter { it.isNotBlank() }
                            .map { DomainOpt(it.trimStart('~').lowercase(), it.startsWith("~")) }
                            .takeIf { it.isNotEmpty() }
                        "script", "stylesheet", "image", "font", "media",
                        "xmlhttprequest", "subdocument", "other", "object",
                        "ping", "websocket" -> types = (types ?: emptySet()) + name
                        "~script", "~stylesheet", "~image", "~font", "~media",
                        "~xmlhttprequest", "~subdocument", "~other" -> Unit
                        "match-case", "document", "elemhide", "generichide", "important",
                        "redirect", "csp", "removeparam", "cookie" -> Unit
                        else -> return null
                    }
                }
            }
        }
        if (s.isEmpty()) return null
        val startAnchor = s.startsWith("|") && !s.startsWith("||")
        val domainAnchor = s.startsWith("||")
        var endAnchor = false
        var core = when {
            domainAnchor -> s.substring(2)
            startAnchor -> s.substring(1)
            else -> s
        }
        if (core.endsWith("|") && !core.endsWith("\\|")) {
            endAnchor = true
            core = core.dropLast(1)
        }
        val keyword = Regex("[a-zA-Z0-9][a-zA-Z0-9._/-]{3,}")
            .find(core)
            ?.value
            ?.lowercase()
            ?.takeIf { it.length >= 4 }
        val body = buildString {
            for (c in core) {
                when (c) {
                    '*' -> append(".*")
                    '^' -> append("(?:[^a-zA-Z0-9_.%-]|$)")
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
        val pattern = when {
            domainAnchor -> "^[a-zA-Z][a-zA-Z0-9+.-]*://([^/]*\\.)?" + body
            startAnchor -> "^" + body
            else -> body
        } + if (endAnchor) "$" else ""
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        return PatternRule(raw, isException, regex, thirdParty, domains, types, keyword)
    }

    private fun parseFilterList(text: String, limit: Int): List<PatternRule> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<PatternRule>(minOf(limit, 2_000))
        for (line in text.lineSequence()) {
            if (out.size >= limit) break
            val raw = line.trim()
            if (raw.isEmpty() || raw.startsWith("!") || raw.startsWith("[")) continue
            if (raw.contains("##") || raw.contains("#@#") || raw.contains("#?#") || raw.contains("#$#")) continue
            parsePattern(raw)?.copy(fromFilterList = true)?.let { out += it }
        }
        return out
    }

    fun requestType(request: android.webkit.WebResourceRequest): String {
        val u = request.url.toString().lowercase()
        val path = u.substringBefore('?')
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> "script"
            path.endsWith(".css") -> "stylesheet"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") ||
                path.endsWith(".ico") || path.endsWith(".avif") || path.endsWith(".bmp") -> "image"
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") ||
                path.endsWith(".otf") || path.endsWith(".eot") -> "font"
            path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".mpd") ||
                path.endsWith(".ts") || path.endsWith(".webm") || path.endsWith(".m4s") -> "media"
            else -> {
                val accept = request.requestHeaders?.entries
                    ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                    ?.value
                    ?.lowercase()
                    .orEmpty()
                when {
                    accept.contains("javascript") || accept.contains("ecmascript") -> "script"
                    accept.contains("css") -> "stylesheet"
                    accept.startsWith("image/") -> "image"
                    accept.contains("font") -> "font"
                    accept.contains("video/") || accept.contains("audio/") ||
                        accept.contains("mpegurl") || accept.contains("mpd") -> "media"
                    request.method.equals("POST", ignoreCase = true) -> "xmlhttprequest"
                    else -> "other"
                }
            }
        }
    }

    private fun sameParty(reqHost: String, pageHost: String): Boolean {
        val r = reqHost.lowercase()
        val p = pageHost.trim().trimEnd('.').lowercase()
        if (r == p || r.endsWith(".$p") || p.endsWith(".$r")) return true
        return registrableDomain(r) == registrableDomain(p)
    }

    private fun ruleApplies(
        rule: PatternRule,
        url: String,
        reqHost: String,
        pageHost: String?,
        type: String,
    ): Boolean {
        if (rule.keyword != null && !url.contains(rule.keyword, ignoreCase = true)) return false
        if (!rule.regex.containsMatchIn(url)) return false
        if (rule.thirdParty != null && pageHost != null) {
            val third = !sameParty(reqHost, pageHost)
            if (rule.thirdParty != third) return false
        }
        rule.domains?.let { opts ->
            val ph = (pageHost ?: "").lowercase()
            var included = opts.none { !it.negated }
            for (o in opts) {
                if (ph == o.domain || ph.endsWith("." + o.domain)) {
                    if (o.negated) return false
                    included = true
                }
            }
            if (!included) return false
        }
        rule.types?.let { if (type !in it) return false }
        return true
    }

    fun matchesPatternUrl(
        url: String,
        pageHost: String?,
        type: String = "other",
        includeFilterRules: Boolean = true,
    ): Boolean {
        val reqHost = runCatching { java.net.URI(url).host }
            .getOrNull()?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        if (reqHost.isEmpty() || isEssentialHost(reqHost)) return false
        val patterns = compiledPatterns()
        if (patterns.isEmpty()) return false
        var blocked = false
        for (rule in patterns) {
            if (!includeFilterRules && rule.fromFilterList) continue
            if (!ruleApplies(rule, url, reqHost, pageHost, type)) continue
            if (rule.isException) return false
            blocked = true
        }
        return blocked
    }

    fun matchesPattern(
        request: android.webkit.WebResourceRequest,
        pageHost: String?,
        includeFilterRules: Boolean = true,
    ): Boolean = matchesPatternUrl(
        request.url.toString(),
        pageHost,
        requestType(request),
        includeFilterRules,
    )

    /** Cloudflare's challenge bootstrap must never be treated as ad traffic. */
    fun isCloudflareInfra(url: String?): Boolean {
        if (url == null) return false
        if (url.contains("/cdn-cgi/")) return true
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "challenges.cloudflare.com" || host.endsWith(".challenges.cloudflare.com") ||
            host == "cloudflare.com" || host.endsWith(".cloudflare.com") ||
            host == "cloudflareinsights.com" || host.endsWith(".cloudflareinsights.com")
    }
}

@Composable
actual fun rememberWebBrowserController(): WebBrowserController {
    // Use the Activity context so popup WebViews created from onCreateWindow can load pages
    // and share the main WebView's session. The controller is disposed with this screen.
    val context = LocalContext.current
    return remember { AndroidWebBrowserController(context) }
}

@Composable
actual fun WebBrowserHost(
    controller: WebBrowserController,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val android = controller as? AndroidWebBrowserController
    val state by controller.state.collectAsState()
    val fileChooser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        android?.onFileChooserResult(result.resultCode, result.data)
    }
    DisposableEffect(android, fileChooser) {
        android?.setFileChooserLauncher { intent ->
            runCatching { fileChooser.launch(intent) }
        }
        onDispose { android?.setFileChooserLauncher(null) }
    }
    // A renderer crash leaves the old WebView unusable. The controller bumps this when that
    // happens; key() gives AndroidView a fresh factory and the page loads again.
    key(state.rendererGeneration) {
        AndroidView(
            factory = { viewContext ->
                runCatching {
                    WebView(viewContext).apply {
                        // Compose creates the native view inside its layout pass; an
                        // implicit WRAP_CONTENT would let the WebView measure at 0
                        // height first, and Chromium keeps that 0 as the viewport
                        // size for CSS vh/dvh (100vh === 0, (min-height: 500px)
                        // never matches). An explicit MATCH_PARENT gives it the real
                        // size before the page is loaded.
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        android?.attach(this)
                    }
                }.getOrElse { error ->
                    android?.reportWebViewUnavailable(error)
                    FrameLayout(viewContext)
                }
            },
            modifier = modifier,
            update = { view -> (view as? WebView)?.let { android?.attach(it) } },
            onRelease = { view -> (view as? WebView)?.let { android?.releaseView(it) } },
        )
    }
    DisposableEffect(controller) {
        controller.resume()
        onDispose {
            controller.pause()
            controller.destroy()
        }
    }
}

/** Android routes browser hand-offs through [StreamProxy]; see its header. */
actual fun prepareWebStreamForPlayer(
    url: String,
    headers: Map<String, String>,
    pageUrl: String,
): String = runCatching {
    val enriched = LinkedHashMap(headers)
    val keys = enriched.keys.map { it.lowercase() }.toSet()
    fun putIfAbsent(key: String, value: String?) {
        if (value.isNullOrBlank()) return
        if (key.lowercase() !in keys) enriched[key] = value
    }
    // The player must look like the page to the CDN. The sniffed request often
    // carries none of these (HLS XHRs are sent without Referer by the embed),
    // and a missing Referer is a 403 on this class of host.
    putIfAbsent("Referer", enriched.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: pageUrl)
    val origin = runCatching {
        val u = java.net.URI(pageUrl)
        u.scheme + "://" + u.host
    }.getOrNull()
    putIfAbsent("Origin", origin)
    putIfAbsent("User-Agent", System.getProperty("http.agent"))
    android.webkit.CookieManager.getInstance().getCookie(url)?.let { putIfAbsent("Cookie", it) }
    android.util.Log.d(
        "NuvioStreamProxy",
        "register host=" + runCatching { java.net.URI(url).host }.getOrNull() +
            " ref=" + enriched.entries.firstOrNull { it.key.equals("Referer", true) }?.value +
            " keys=" + enriched.keys.joinToString(","),
    )
    StreamProxy.register(url, enriched)
}.getOrDefault(url)
