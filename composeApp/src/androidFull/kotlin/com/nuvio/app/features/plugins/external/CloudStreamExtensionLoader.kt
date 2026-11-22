package com.nuvio.app.features.plugins.external

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis
import com.nuvio.app.core.network.IPv4FirstDns
import dalvik.system.DexClassLoader
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val TAG = "CloudStreamLoader"
private const val MAX_DEX_SIZE = 10L * 1024 * 1024

/** Legacy entry points occasionally expose the registration API without extending BasePlugin. */
private fun looksLikePlugin(instance: Any): Boolean = instance.javaClass.methods.any { method ->
    when (method.name) {
        "getRegisteredMainAPIs" -> method.parameterCount == 0
        "load" -> method.parameterCount == 0 || method.parameterTypes.toList() in listOf(
            listOf(Context::class.java), listOf(Activity::class.java)
        )
        else -> false
    }
}

private class ReflectivePluginWrapper(private val foreignInstance: Any) : Plugin() {
    override fun load(activity: Activity?) {
        load(activity as? Context ?: AcraApplication.context ?: return)
    }

    override fun load(context: Context) {
        val clazz = foreignInstance.javaClass

        try {
            val method = clazz.getMethod("load", Context::class.java)
            method.invoke(foreignInstance, context)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            if (cause is ClassCastException) {
                try {
                    val method = clazz.getMethod("load", Activity::class.java)
                    method.invoke(foreignInstance, null)
                } catch (e2: Exception) {
                    Log.w(TAG, "ReflectivePluginWrapper: load(Activity) failed: ${e2.message}")
                }
            } else {
                Log.w(TAG, "ReflectivePluginWrapper: load(Context) threw: ${cause?.message ?: e.message}")
            }
        } catch (_: NoSuchMethodException) {
            try {
                val method = clazz.getMethod("load", Activity::class.java)
                method.invoke(foreignInstance, null)
            } catch (_: NoSuchMethodException) {
                try {
                    val method = clazz.getMethod("load")
                    method.invoke(foreignInstance)
                } catch (e: Exception) {
                    Log.w(TAG, "ReflectivePluginWrapper: load() failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ReflectivePluginWrapper: load(Activity) failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ReflectivePluginWrapper: load() failed: ${e.message}")
        }

        val apis = clazz.methods.find { it.name == "getRegisteredMainAPIs" && it.parameterCount == 0 }
            ?.invoke(foreignInstance) as? List<*>
        apis.orEmpty().filterIsInstance<MainAPI>().filterNot { it in APIHolder.allProviders }.forEach(::registerMainAPI)
        val extractors = clazz.methods.find { it.name == "getRegisteredExtractorAPIs" && it.parameterCount == 0 }
            ?.invoke(foreignInstance) as? List<*>
        extractors.orEmpty().filterIsInstance<ExtractorApi>().filterNot { it in extractorApis }
            .forEach(::registerExtractorAPI)
    }
}

/**
 * Downloads, validates and loads CloudStream `.cs3` packages.
 *
 * Files are profile-scoped and named by a hash of the scraper id, so a long
 * repository URL cannot exceed filesystem name limits.
 */
class CloudStreamExtensionLoader(
    private val context: Context,
    private val extractorRegistry: ExternalExtractorRegistry,
) {
    private data class LoadedExtension(
        val plugin: BasePlugin?,
        val providers: List<MainAPI>,
        val extractors: List<ExtractorApi>,
    )

    private val httpClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val loaded = mutableMapOf<String, LoadedExtension>()
    private val preloaded = mutableSetOf<String>()

    private fun key(profileId: Int, scraperId: String) = "$profileId:$scraperId"

    private fun extensionsDir(profileId: Int): File {
        val name = if (profileId == 1) "cs_extensions" else "cs_extensions_p$profileId"
        return File(context.filesDir, name).also { it.mkdirs() }
    }

    private val codeCacheDir: File
        get() = File(context.codeCacheDir, "cs_dex_cache").also { it.mkdirs() }

    fun extensionFile(scraperId: String, profileId: Int): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scraperId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(extensionsDir(profileId), "$digest.cs3")
    }

    fun hasExtension(scraperId: String, profileId: Int): Boolean =
        extensionFile(scraperId, profileId).isFile

    suspend fun downloadExtension(
        scraperId: String,
        profileId: Int,
        downloadUrl: String,
        fileHash: String?,
        expectedSize: Long? = null,
    ): File? = withContext(Dispatchers.IO) {
        CloudStreamRuntime.ensureInitialized(context)
        try {
            val targetFile = extensionFile(scraperId, profileId)
            if (expectedSize != null && expectedSize > MAX_DEX_SIZE) {
                Log.w(TAG, "Extension $scraperId rejected: declared size $expectedSize")
                return@withContext null
            }

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "NuvioTV/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download extension $scraperId: HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                val staged = stageExtension(targetFile.parentFile!!, body, fileHash)
                try {
                    synchronized(this@CloudStreamExtensionLoader) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            check(staged.setReadOnly()) { "Could not mark extension read-only" }
                        }
                        android.system.Os.rename(staged.absolutePath, targetFile.absolutePath)
                        evictCache(scraperId, profileId)
                    }
                } finally {
                    staged.delete()
                }
                targetFile
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading extension $scraperId: ${e.message}", e)
            null
        }
    }

    @Synchronized
    fun loadExtension(scraperId: String, profileId: Int): List<MainAPI> {
        val cacheKey = key(profileId, scraperId)
        loaded[cacheKey]?.providers?.let { return it }
        CloudStreamRuntime.ensureInitialized(context)
        val file = extensionFile(scraperId, profileId)
        if (!file.isFile) {
            Log.w(TAG, "Missing extension file for $scraperId (profile $profileId)")
            return emptyList()
        }

        try {
            ensureDexReadOnly(file)
            val loader = DexClassLoader(file.path, codeCacheDir.path, null, context.classLoader)
            AcraApplication.context = context
            extractorRegistry.installGlobal()
            val providersBefore = synchronized(APIHolder.allProviders) { APIHolder.allProviders.toSet() }
            val extractorsBefore = extractorApis.toSet()
            val plugin = findAndLoadPlugin(loader, file)
            if (plugin != null) {
                try {
                    if (plugin is Plugin) {
                        plugin.load((AcraApplication.getActivity() as Context?) ?: context)
                    } else {
                        plugin.load()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Extension $scraperId load() failed: ${e.javaClass.simpleName}: ${e.message}")
                } catch (e: LinkageError) {
                    Log.w(TAG, "Extension $scraperId load() linkage error: ${e.javaClass.simpleName}: ${e.message}")
                } catch (e: NotImplementedError) {
                    Log.w(TAG, "Extension $scraperId load() not implemented")
                }
            }

            val providers = synchronized(APIHolder.allProviders) {
                APIHolder.allProviders.filterNot { it in providersBefore }.toMutableList()
            }
            val extractors = extractorApis.filterNot { it in extractorsBefore }.toMutableList()
            val scanProviders = plugin != null && providers.isEmpty()
            val scanExtractors = extractors.isEmpty()
            if (scanProviders || scanExtractors) {
                @Suppress("DEPRECATION")
                val classes = try {
                    dexClassNames(file)
                } catch (e: Exception) {
                    Log.w(TAG, "DEX scan failed for $scraperId: ${e.message}")
                    emptyList()
                }
                for (name in classes.filterNot { '$' in it }) {
                    try {
                        val clazz = loader.loadClass(name)
                        if (java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) continue
                        if (scanProviders && MainAPI::class.java.isAssignableFrom(clazz)) {
                            providers += clazz.getDeclaredConstructor().newInstance() as MainAPI
                        } else if (scanExtractors && ExtractorApi::class.java.isAssignableFrom(clazz)) {
                            extractors += clazz.getDeclaredConstructor().newInstance() as ExtractorApi
                        }
                    } catch (_: ReflectiveOperationException) {
                    } catch (_: LinkageError) {
                    }
                }
            }
            extractorRegistry.registerAll(extractors)
            if (providers.isNotEmpty() || extractors.isNotEmpty() || (plugin as? Plugin)?.openSettings != null) {
                loaded[cacheKey] = LoadedExtension(plugin, providers.toList(), extractors)
            }
            Log.d(
                TAG,
                "Loaded $scraperId (profile $profileId): ${providers.size} providers, ${extractors.size} extractors",
            )
            return providers
        } catch (e: Exception) {
            Log.w(TAG, "Could not load extension $scraperId", e)
        } catch (e: Error) {
            if (e is VirtualMachineError || e is ThreadDeath) throw e
            Log.w(TAG, "Could not load extension $scraperId: ${e.javaClass.simpleName}", e)
        }
        return emptyList()
    }

    @Synchronized
    fun settingsCallback(scraperId: String, profileId: Int): ((Context) -> Unit)? {
        loadExtension(scraperId, profileId)
        return (loaded[key(profileId, scraperId)]?.plugin as? Plugin)?.openSettings
    }

    fun ensureExtractorsLoaded(scraperIds: List<String>, profileId: Int) {
        for (id in scraperIds) synchronized(this) {
            if (preloaded.add(key(profileId, id))) loadExtension(id, profileId)
        }
    }

    @Synchronized
    fun deleteExtension(scraperId: String, profileId: Int) {
        evictCache(scraperId, profileId)
        extensionFile(scraperId, profileId).delete()
    }

    @Synchronized
    private fun evictCache(scraperId: String, profileId: Int) {
        preloaded.remove(key(profileId, scraperId))
        val extension = loaded.remove(key(profileId, scraperId)) ?: return
        try {
            extension.plugin?.beforeUnload()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            Log.w(TAG, "Extension unload hook failed", failure)
        } finally {
            synchronized(APIHolder.allProviders) {
                extension.providers.forEach { api ->
                    APIHolder.allProviders.remove(api)
                    APIHolder.removePluginMapping(api)
                }
            }
            extractorApis.removeAll(extension.extractors.toSet())
        }
    }

    private fun ensureDexReadOnly(dexFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dexFile.canWrite()) {
            dexFile.setReadOnly()
        }
    }

    private fun findAndLoadPlugin(classLoader: DexClassLoader, cs3File: File): BasePlugin? {
        val manifest = readManifest(cs3File)
        val pluginClassName = manifest?.optString("pluginClassName")?.takeIf { it.isNotBlank() }
        val plugin = instantiatePlugin(classLoader, cs3File, pluginClassName) ?: return null
        plugin.filename = cs3File.absolutePath
        if (plugin is Plugin && manifest?.optBoolean("requiresResources") == true) {
            val assets = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
            val cookie = assets.javaClass.getMethod("addAssetPath", String::class.java)
                .invoke(assets, cs3File.absolutePath) as Int
            check(cookie != 0) { "Could not load extension resources" }
            @Suppress("DEPRECATION")
            plugin.resources = android.content.res.Resources(
                assets,
                context.resources.displayMetrics,
                context.resources.configuration,
            )
        }
        return plugin
    }

    private fun instantiatePlugin(
        classLoader: DexClassLoader,
        cs3File: File,
        pluginClassName: String?,
    ): BasePlugin? {
        if (pluginClassName != null) {
            try {
                val clazz = classLoader.loadClass(pluginClassName)
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance is BasePlugin) return instance
                if (looksLikePlugin(instance)) return ReflectivePluginWrapper(instance)
                Log.w(TAG, "Class $pluginClassName is not a plugin")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load manifest class $pluginClassName: ${e.message}", e)
            } catch (e: Error) {
                Log.e(TAG, "Linkage error loading manifest class $pluginClassName: ${e.message}", e)
            }
        }
        return scanForPluginClass(classLoader, cs3File)
    }

    private fun readManifest(cs3File: File): JSONObject? = try {
        ZipFile(cs3File).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: return null
            JSONObject(zip.getInputStream(entry).bufferedReader().readText())
        }
    } catch (e: Exception) {
        Log.d(TAG, "Could not read manifest.json: ${e.message}")
        null
    }

    @Suppress("DEPRECATION")
    private fun dexClassNames(file: File): List<String> {
        val dex = dalvik.system.DexFile(file)
        return try {
            dex.entries().toList()
        } finally {
            dex.close()
        }
    }

    private fun scanForPluginClass(classLoader: DexClassLoader, cs3File: File): BasePlugin? {
        try {
            for (className in dexClassNames(cs3File)) {
                try {
                    val clazz = classLoader.loadClass(className)
                    if (clazz.isAnnotationPresent(CloudstreamPlugin::class.java)) {
                        val instance = clazz.getDeclaredConstructor().newInstance()
                        if (instance is BasePlugin) return instance
                        if (looksLikePlugin(instance)) return ReflectivePluginWrapper(instance)
                    }
                } catch (_: ClassNotFoundException) {
                } catch (_: NoClassDefFoundError) {
                } catch (e: Exception) {
                    Log.w(TAG, "Error inspecting class $className: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "DexFile scan fallback failed: ${e.message}")
        }
        return null
    }
}
