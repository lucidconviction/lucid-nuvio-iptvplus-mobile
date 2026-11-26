package com.nuvio.app.music

import com.nuvio.app.features.youtube.YouTubeHost
import com.nuvio.app.features.youtube.YouTubeVideoCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a Deezer track into something ExoPlayer can play:
 *  1. the YouTube engine already used by the YouTube tab (audio-only when it gives us a
 *     separate audio format, otherwise its DASH manifest),
 *  2. Deezer's 30-second preview as the last resort.
 *
 * Both paths run on the same engine as the rest of the app, so region, sign-in and stream
 * headers behave exactly like YouTube playback.
 */
object MusicSourceResolver {
    private val videoIds = ConcurrentHashMap<String, String>()

    suspend fun resolveAudio(track: MusicTrack): MusicStream? =
        resolveFromYouTube(track, audioOnly = true) ?: preview(track)

    suspend fun resolveVideo(track: MusicTrack): MusicStream? =
        resolveFromYouTube(track, audioOnly = false) ?: preview(track)

    private suspend fun resolveFromYouTube(track: MusicTrack, audioOnly: Boolean): MusicStream? =
        withContext(Dispatchers.IO) {
            val search = YouTubeHost.searchVideos
                ?: return@withContext null
            val resolve = YouTubeHost.resolveVideo
                ?: return@withContext null

            val videoId = videoIds[track.id] ?: run {
                val query = "${track.title} ${track.artist}".trim()
                val results = runCatching { search(query, 8) }.getOrDefault(emptyList())
                val best = pickTrackResult(results, track) ?: return@withContext null
                best.videoId.takeIf { it.isNotBlank() }?.also { videoIds[track.id] = it }
            } ?: return@withContext null

            val resolved = runCatching {
                resolve(videoId, "${track.title} · ${track.artist}", track.coverUrl, 0L)
            }.getOrNull()?.getOrNull() ?: return@withContext null

            if (audioOnly) {
                resolved.audioUrl?.takeIf { it.isNotBlank() }?.let { audio ->
                    return@withContext MusicStream(
                        url = audio,
                        headers = resolved.headers,
                        label = "YouTube audio",
                    )
                }
            }
            MusicStream(
                url = resolved.url,
                headers = resolved.headers,
                label = if (audioOnly) "YouTube audio" else "YouTube video",
                isDash = resolved.url.contains(".mpd", ignoreCase = true),
            )
        }

    private fun pickTrackResult(results: List<YouTubeVideoCard>, track: MusicTrack): YouTubeVideoCard? {
        if (results.isEmpty()) return null
        val title = normalize(track.title)
        val artist = normalize(track.artist)
        return results.firstOrNull { result ->
            val resultTitle = normalize(result.title)
            val resultAuthor = normalize(result.author.orEmpty())
            resultTitle.contains(title) && (artist.isBlank() || resultAuthor.contains(artist))
        } ?: results.firstOrNull { result ->
            normalize(result.title).contains(title)
        } ?: results.first()
    }

    private fun preview(track: MusicTrack): MusicStream? =
        track.previewUrl?.takeIf { it.isNotBlank() }?.let {
            MusicStream(
                url = it,
                headers = mapOf("User-Agent" to "Mozilla/5.0"),
                label = "Deezer preview · 30s",
            )
        }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
