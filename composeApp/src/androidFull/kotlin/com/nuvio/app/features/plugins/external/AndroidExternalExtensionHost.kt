package com.nuvio.app.features.plugins.external

import android.content.Context
import android.util.Log
import com.nuvio.app.features.plugins.PluginRuntimeResult

/**
 * Android-full implementation of the CloudStream host. Initialized from
 * [com.nuvio.app.features.plugins.PluginStorage.initialize], which is called by
 * the shared MainActivity only in the full distribution.
 */
internal object AndroidExternalExtensionHost : ExternalExtensionHost {
    private const val TAG = "CloudStreamHost"

    @Volatile private var appContext: Context? = null

    private val extractorRegistry by lazy { ExternalExtractorRegistry() }
    private val loaderLock = Any()
    private var loaderRef: CloudStreamExtensionLoader? = null
    private var runnerRef: CloudStreamExtensionRunner? = null

    override val isSupported: Boolean = true

    fun initialize(context: Context) {
        // Store only; Conscrypt/NiceHttp/Activity hooks initialize on first use.
        appContext = context.applicationContext
    }

    private fun loader(): CloudStreamExtensionLoader? {
        val context = appContext ?: return null
        loaderRef?.let { return it }
        return synchronized(loaderLock) {
            loaderRef ?: CloudStreamExtensionLoader(context, extractorRegistry).also { loaderRef = it }
        }
    }

    private fun runner(): CloudStreamExtensionRunner? {
        val loader = loader() ?: return null
        runnerRef?.let { return it }
        return synchronized(loaderLock) {
            runnerRef ?: CloudStreamExtensionRunner(loader).also { runnerRef = it }
        }
    }

    override fun hasExtension(scraperId: String, profileId: Int): Boolean =
        loader()?.hasExtension(scraperId, profileId) == true

    override suspend fun install(
        scraperId: String,
        profileId: Int,
        url: String,
        fileHash: String?,
        expectedSize: Long?,
    ): Boolean {
        val loader = loader() ?: return false
        return loader.downloadExtension(
            scraperId = scraperId,
            profileId = profileId,
            downloadUrl = url,
            fileHash = fileHash,
            expectedSize = expectedSize,
        ) != null
    }

    override fun uninstall(scraperId: String, profileId: Int) {
        loader()?.deleteExtension(scraperId, profileId)
    }

    override suspend fun execute(
        scraperId: String,
        profileId: Int,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        searchPaused: Boolean,
        contentTitle: String?,
        useFirstResult: Boolean,
    ): List<PluginRuntimeResult> {
        // DEX providers are only invoked by an active stream search; the JS
        // runtime's pause is a scheduling hint, not a correctness requirement.
        @Suppress("UNUSED_VARIABLE")
        val paused = searchPaused
        return runner()?.execute(
            scraperId = scraperId,
            profileId = profileId,
            tmdbId = tmdbId,
            mediaType = mediaType,
            season = season,
            episode = episode,
            contentTitle = contentTitle,
            useFirstResult = useFirstResult,
        ).orEmpty()
    }

    override suspend fun hasSettings(scraperId: String, profileId: Int): Boolean =
        settingsCallback(scraperId, profileId) != null

    override fun openSettings(scraperId: String, profileId: Int) {
        val context = appContext ?: run {
            Log.w(TAG, "openSettings before initialize")
            return
        }
        CloudStreamRuntime.openSettings(context, scraperId, profileId)
    }

    /** Called from [ExtensionSettingsActivity] off the main thread. */
    fun settingsCallback(scraperId: String, profileId: Int): ((Context) -> Unit)? =
        loader()?.settingsCallback(scraperId, profileId)
}

internal val platformExternalExtensionHost: ExternalExtensionHost = AndroidExternalExtensionHost
