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
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt

/**
 * Lazily initializes the CloudStream host runtime. Nothing here runs unless the
 * user installs/opens a CloudStream repository or provider, so the normal app
 * cold start is untouched.
 */
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
                    .cache(Cache(File(hostApplication.cacheDir, "cs_http_cache"), 50L * 1024L * 1024L))
                    .build()
            }.onFailure { Log.w(TAG, "NiceHttp base client init failed: ${it.message}") }

            AcraApplication.context = hostApplication
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
        val activity = currentActivity.get()
        if (activity == null) {
            Log.w(TAG, "No foreground Activity for extension settings")
            return
        }
        Log.d(TAG, "Starting ExtensionSettingsActivity for $scraperId (profile $profileId)")
        ExtensionSettingsActivity.open(activity, scraperId, profileId)
    }

    fun currentContext(context: Context): Context = application ?: context
}
