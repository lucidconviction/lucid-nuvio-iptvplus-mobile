package com.nuvio.app.features.trailer

import android.util.Log
import com.liskovsoft.mediaserviceinterfaces.ServiceManager
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import com.nuvio.app.AppActivityTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TAG = "NativeTrailerResolver"
private const val MAX_CLIENT_SWITCHES = 1
private const val LOOKUP_TIMEOUT_SECONDS = 12L

private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
private val VIDEO_ID_FROM_URL_REGEX =
    Regex("(?:[?&]v=|youtu\\.be/|/embed/|/shorts/|/live/)([A-Za-z0-9_-]{11})")

internal actual object NativeTrailerResolver {
    actual suspend fun resolve(youtubeUrl: String): TrailerPlaybackSource? = withContext(Dispatchers.IO) {
        val context = AppActivityTracker.current?.applicationContext ?: return@withContext null
        val videoId = extractVideoId(youtubeUrl) ?: return@withContext null
        runCatching {
            // SmartTube reads its configuration through this singleton.
            GlobalPreferences.instance(context)
            val service = YouTubeServiceManager.instance()
            var info = lookup(service, videoId) ?: return@runCatching null
            var source = info.toPlaybackSource()
            var switches = 0
            while (source == null && switches++ < MAX_CLIENT_SWITCHES) {
                service.switchNextClientNow()
                info = lookup(service, videoId) ?: break
                source = info.toPlaybackSource()
            }
            if (source == null) {
                Log.w(
                    TAG,
                    "No playable trailer for $videoId " +
                        "(unplayable=${info.isUnplayable} reason=${info.playabilityReason} " +
                        "sabr=${info.containsSabrFormats()} dash=${info.containsDashFormats()})",
                )
            }
            source
        }.getOrNull()
    }

    private fun lookup(service: ServiceManager, videoId: String): MediaItemFormatInfo? =
        runCatching {
            service.mediaItemService.getFormatInfoObserve(videoId)
                // blockingFirst() cannot be interrupted by a coroutine timeout.
                .timeout(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .blockingFirst()
        }.getOrNull()

    private fun MediaItemFormatInfo.toPlaybackSource(): TrailerPlaybackSource? {
        val userAgent = clientInfo?.userAgent?.takeIf { it.isNotBlank() }
        val headers = userAgent?.let { mapOf("User-Agent" to it) }.orEmpty()

        hlsManifestUrl?.takeIf { it.isNotBlank() }?.let { url ->
            return TrailerPlaybackSource(videoUrl = url, headers = headers)
        }

        // Progressive formats carry picture and sound together and need no merging.
        val progressive = urlFormats.orEmpty()
            .filter { !it.url.isNullOrBlank() && (it.mimeType ?: "").startsWith("video/") }
            .maxByOrNull { it.height }
        progressive?.url?.takeIf { it.isNotBlank() }?.let { url ->
            return TrailerPlaybackSource(videoUrl = url, headers = headers)
        }

        val adaptive = adaptiveFormats.orEmpty()
        val video = adaptive
            .filter { !it.url.isNullOrBlank() && (it.mimeType ?: "").startsWith("video/") }
            .maxByOrNull { it.height }
        val audio = adaptive
            .filter { !it.url.isNullOrBlank() && (it.mimeType ?: "").startsWith("audio/") }
            .maxByOrNull { (it.bitrate ?: "").toLongOrNull() ?: 0L }
        video?.url?.takeIf { it.isNotBlank() }?.let { url ->
            return TrailerPlaybackSource(
                videoUrl = url,
                audioUrl = audio?.url?.takeIf { it.isNotBlank() },
                headers = headers,
            )
        }

        return null
    }
}

private fun extractVideoId(input: String): String? {
    val trimmed = input.trim()
    if (VIDEO_ID_REGEX.matches(trimmed)) return trimmed
    return VIDEO_ID_FROM_URL_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
}
