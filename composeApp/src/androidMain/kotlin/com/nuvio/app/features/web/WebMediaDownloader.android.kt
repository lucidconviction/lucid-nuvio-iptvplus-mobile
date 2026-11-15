package com.nuvio.app.features.web

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * A small, dependency-free HLS downloader for Web-Apps.
 *
 * VOD playlists are downloaded into a single file (fMP4 -> .mp4, MPEG-TS -> .ts). Live playlists
 * are recorded for a short window by polling the playlist and appending new segments. Encrypted
 * and DRM-protected playlists are refused with a clear message.
 *
 * This is intentionally not a general-purpose downloader: no DRM bypass, no paywall bypass, and
 * no live buffer beyond the user's request. Content rights remain the user's responsibility.
 */
internal object WebMediaDownloader {

    private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
    private const val LIVE_RECORD_MS = 60_000L
    private const val LIVE_POLL_MS = 2_000L

    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "nuvio-web-download").apply { isDaemon = true }
    }

    fun downloadHls(
        context: Context,
        source: WebMediaSource,
        onMessage: (String) -> Unit,
    ) {
        if (isPrivateWebHost(webHost(source.url))) {
            onMessage("Local network downloads are blocked.")
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            try {
                val headers = source.headers
                val master = fetchText(source.url, headers) ?: run {
                    onMessage("Could not read the HLS playlist.")
                    return@execute
                }
                val mediaUrl: String
                val mediaText: String
                if (master.contains("#EXT-X-STREAM-INF")) {
                    mediaUrl = bestVariantUrl(source.url, master) ?: run {
                        onMessage("No playable HLS variant was found.")
                        return@execute
                    }
                    mediaText = fetchText(mediaUrl, headers) ?: run {
                        onMessage("Could not read the HLS media playlist.")
                        return@execute
                    }
                } else {
                    mediaUrl = source.url
                    mediaText = master
                }
                if (hasEncryption(mediaText)) {
                    onMessage("Encrypted HLS streams cannot be downloaded yet.")
                    return@execute
                }
                if (!mediaText.contains("#EXT-X-ENDLIST")) {
                    recordLive(
                        appContext,
                        source,
                        mediaUrl,
                        headers,
                        isFmp4 = mediaText.contains("#EXT-X-MAP") || mediaText.contains(".m4s"),
                        onMessage = onMessage,
                    )
                    return@execute
                }
                downloadVod(appContext, source, mediaUrl, mediaText, headers, onMessage)
            } catch (error: Throwable) {
                onMessage("Download failed: ${error.message?.take(120) ?: "unknown error"}")
            }
        }
    }

    private fun downloadVod(
        context: Context,
        source: WebMediaSource,
        mediaUrl: String,
        mediaText: String,
        headers: Map<String, String>,
        onMessage: (String) -> Unit,
    ) {
        val initUrl = parseMapUri(mediaUrl, mediaText)
        val segments = parseSegments(mediaUrl, mediaText)
        if (segments.isEmpty()) {
            onMessage("The HLS playlist has no media segments.")
            return
        }
        val extension = if (initUrl != null || segments.any { it.contains(".m4s") || it.contains(".mp4") }) {
            "mp4"
        } else {
            "ts"
        }
        val file = outputFile(context, source, extension)
        onMessage("Downloading ${segments.size} segments…")
        FileOutputStream(file).use { output ->
            initUrl?.let { output.write(fetchBytes(it, headers) ?: return@use) }
            val step = (segments.size / 4).coerceAtLeast(1)
            segments.forEachIndexed { index, segment ->
                val bytes = fetchBytes(segment, headers) ?: return@use
                output.write(bytes)
                if (index % step == 0 || index == segments.lastIndex) {
                    onMessage("Downloading ${index + 1}/${segments.size} segments…")
                }
            }
        }
        finish(context, file, onMessage)
    }

    private fun recordLive(
        context: Context,
        source: WebMediaSource,
        mediaUrl: String,
        headers: Map<String, String>,
        isFmp4: Boolean,
        onMessage: (String) -> Unit,
    ) {
        val file = outputFile(context, source, if (isFmp4) "mp4" else "ts")
        onMessage("Recording live stream for up to 60 seconds…")
        val seen = HashSet<String>()
        var initWritten = false
        val started = System.currentTimeMillis()
        FileOutputStream(file).use { output ->
            while (System.currentTimeMillis() - started < LIVE_RECORD_MS) {
                val text = fetchText(mediaUrl, headers) ?: break
                val init = parseMapUri(mediaUrl, text)
                if (!initWritten && init != null) {
                    fetchBytes(init, headers)?.let { output.write(it); initWritten = true }
                }
                val fresh = parseSegments(mediaUrl, text).filter { seen.add(it) }
                for (segment in fresh) {
                    val bytes = fetchBytes(segment, headers) ?: continue
                    output.write(bytes)
                }
                if (fresh.isNotEmpty()) {
                    val seconds = (System.currentTimeMillis() - started) / 1000
                    onMessage("Recording live stream… ${seconds}s")
                }
                Thread.sleep(LIVE_POLL_MS)
            }
        }
        finish(context, file, onMessage)
    }

    private fun finish(context: Context, file: File, onMessage: (String) -> Unit) {
        if (!file.exists() || file.length() <= 0L) {
            onMessage("The download did not produce a playable file.")
            return
        }
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(if (file.extension == "mp4") "video/mp4" else "video/mp2t"),
                null,
            )
        }
        onMessage("Saved ${file.name} (${formatBytes(file.length())}) to Movies/Nuvio Web.")
    }

    private fun outputFile(context: Context, source: WebMediaSource, extension: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dir = File(root, "Nuvio Web").apply { mkdirs() }
        val base = source.title
            .ifBlank { webHost(source.pageUrl) }
            .replace(Regex("""[\\/:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
            .ifBlank { "web-video" }
        var file = File(dir, "$base.$extension")
        var counter = 2
        while (file.exists()) {
            file = File(dir, "$base ($counter).$extension")
            counter += 1
        }
        return file
    }

    private fun bestVariantUrl(baseUrl: String, master: String): String? {
        var bestUrl: String? = null
        var bestScore = Long.MIN_VALUE
        var pendingScore = 0L
        for (line in master.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                val bandwidth = Regex("""BANDWIDTH=(\d+)""").find(trimmed)
                    ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""").find(trimmed)
                val pixels = resolution?.let {
                    (it.groupValues[1].toLongOrNull() ?: 0L) *
                        (it.groupValues[2].toLongOrNull() ?: 0L)
                } ?: 0L
                pendingScore = max(bandwidth, pixels)
            } else if (trimmed.isNotBlank() && !trimmed.startsWith("#") && pendingScore != Long.MIN_VALUE) {
                if (pendingScore > bestScore) {
                    bestScore = pendingScore
                    bestUrl = resolveUrl(baseUrl, trimmed)
                }
                pendingScore = Long.MIN_VALUE
            }
        }
        return bestUrl
    }

    private fun parseSegments(baseUrl: String, playlist: String): List<String> =
        playlist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { resolveUrl(baseUrl, it) }
            .toList()

    private fun parseMapUri(baseUrl: String, playlist: String): String? =
        Regex("""#EXT-X-MAP:URI="([^"]+)"""")
            .find(playlist)
            ?.groupValues
            ?.get(1)
            ?.let { resolveUrl(baseUrl, it) }

    private fun hasEncryption(playlist: String): Boolean =
        playlist.lineSequence().any { line ->
            line.startsWith("#EXT-X-KEY:") && !line.contains("METHOD=NONE", ignoreCase = true)
        }

    private fun resolveUrl(baseUrl: String, reference: String): String =
        runCatching { URI(baseUrl).resolve(reference).toString() }.getOrDefault(reference)

    private fun fetchText(url: String, headers: Map<String, String>): String? =
        runCatching {
            val connection = open(url, headers)
            try {
                if (connection.responseCode !in 200..299) return null
                val bytes = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (out.size() < MAX_PLAYLIST_BYTES) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
                String(bytes, Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? =
        runCatching {
            val connection = open(url, headers)
            try {
                if (connection.responseCode !in 200..299) return null
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            headers.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) setRequestProperty(key, value)
            }
        }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format("%.1f MB", mb) else "${bytes / 1024} KB"
    }
}
