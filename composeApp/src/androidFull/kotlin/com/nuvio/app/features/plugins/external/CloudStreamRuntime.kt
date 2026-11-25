package com.nuvio.app.features.plugins.external

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.nicehttp.ignoreAllSSLErrors
import com.nuvio.app.core.network.IPv4FirstDns
import java.io.File
import java.lang.ref.WeakReference
import java.security.Security
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.conscrypt.Conscrypt
import java.io.IOException

/**
 * Lazily initializes the CloudStream host runtime. Nothing here runs unless the
 * user installs/opens a CloudStream repository or provider, so the normal app
 * cold start is untouched.
 */
/**
 * CloudStream providers frequently burst several page/embed requests at once, and some hosts
 * answer 429 before settling down. A short bounded retry saves otherwise healthy extensions
 * from being reported as "no playable source".
 */
private class ExtensionRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        var lastResponse: Response? = null
        while (attempt < MAX_ATTEMPTS) {
            val response = try {
                chain.proceed(request)
            } catch (error: IOException) {
                if (request.method != "GET" || attempt >= MAX_ATTEMPTS - 1) throw error
                Thread.sleep(RETRY_DELAYS_MS[attempt])
                attempt++
                continue
            }
            lastResponse = response
            if (request.method != "GET" || (response.code != 429 && response.code != 503)) {
                return response
            }
            if (attempt >= MAX_ATTEMPTS - 1) return response
            val retryAfterMs = response.header("Retry-After")
                ?.toLongOrNull()
                ?.times(1_000L)
                ?.coerceIn(500L, 5_000L)
            response.close()
            Thread.sleep(retryAfterMs ?: RETRY_DELAYS_MS[attempt])
            attempt++
        }
        return lastResponse ?: error("Retry interceptor produced no response")
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val RETRY_DELAYS_MS = longArrayOf(700L, 1_600L)
    }
}

/**
 * Some CloudStream extensions resolve every mirror at once. Hosts such as Goojara answer that
 * burst with 429 even though a sequence of the same requests succeeds. Serialize the common
 * redirect endpoint so the first mirrors can return their Location header.
 */
private class ExtensionRedirectThrottleInterceptor : Interceptor {
    private val lock = Object()
    private val semaphore = java.util.concurrent.Semaphore(1)
    private var lastRequestAtMs = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!needsThrottle(request.url)) return chain.proceed(request)
        semaphore.acquire()
        try {
            synchronized(lock) {
                val waitMs = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAtMs)
                if (waitMs > 0L) Thread.sleep(waitMs)
            }
            val response = chain.proceed(request)
            synchronized(lock) { lastRequestAtMs = System.currentTimeMillis() }
            return response
        } finally {
            semaphore.release()
        }
    }

    private fun needsThrottle(url: HttpUrl): Boolean {
        val host = url.host.lowercase()
        val path = url.encodedPath.lowercase()
        return path.endsWith("/go.php") || host.contains("goojara")
    }

    private companion object {
        const val MIN_INTERVAL_MS = 750L
    }
}

internal object CloudStreamRuntime {
    private const val TAG = "CloudStreamRuntime"

    @Volatile private var initialized = false
    private var application: Application? = null
    private var currentActivity = WeakReference<Activity?>(null)

    private val cookieJar: CookieJar = object : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val hostCookies = synchronized(store) { store[url.host]?.toList() } ?: return emptyList()
            val now = System.currentTimeMillis()
            return hostCookies.filter { it.expiresAt > now }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                cookies.forEach { cookie ->
                    hostCookies.removeAll { it.name == cookie.name }
                    hostCookies.add(cookie)
                }
            }
        }
    }

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val hostApplication = context.applicationContext as? Application ?: return
            application = hostApplication

            runCatching {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }.onFailure { Log.w(TAG, "Conscrypt init failed: ${it.message}") }

            runCatching {
                com.lagradost.cloudstream3.app.baseClient = OkHttpClient.Builder()
                    .dns(IPv4FirstDns())
                    .cookieJar(cookieJar)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .ignoreAllSSLErrors()
                    .addInterceptor(ExtensionRetryInterceptor())
                    .addInterceptor(ExtensionRedirectThrottleInterceptor())
                    .cache(Cache(File(hostApplication.cacheDir, "cs_http_cache"), 50L * 1024L * 1024L))
                    .build()
            }.onFailure { Log.w(TAG, "NiceHttp base client init failed: ${it.message}") }

            AcraApplication.context = hostApplication
            com.nuvio.app.AppActivityTracker.current?.let { activity ->
                currentActivity = WeakReference<Activity?>(activity)
                CommonActivity.activity = activity
                AcraApplication.setActivity(activity)
            }
            hostApplication.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityResumed(activity: Activity) {
                        currentActivity = WeakReference<Activity?>(activity)
                        CommonActivity.activity = activity
                        AcraApplication.setActivity(activity)
                    }

                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) {
                        if (currentActivity.get() === activity) {
                            currentActivity = WeakReference(null)
                            AcraApplication.setActivity(null)
                            if (CommonActivity.activity === activity) {
                                CommonActivity.activity = null
                            }
                        }
                    }
                },
            )

            initialized = true
        }
    }

    fun openSettings(context: Context, scraperId: String, profileId: Int) {
        ensureInitialized(context)
        val activity = currentActivity.get() ?: com.nuvio.app.AppActivityTracker.current
        if (activity == null) {
            Log.w(TAG, "No foreground Activity for extension settings")
            return
        }
        Log.d(TAG, "Starting ExtensionSettingsActivity for $scraperId (profile $profileId)")
        ExtensionSettingsActivity.open(activity, scraperId, profileId)
    }

    fun currentContext(context: Context): Context = application ?: context
}
