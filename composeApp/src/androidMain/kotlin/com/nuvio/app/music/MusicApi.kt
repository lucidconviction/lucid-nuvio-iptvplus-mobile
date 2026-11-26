package com.nuvio.app.music

import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Deezer's public catalogue: charts, search and artist/album/playlist pages.
 * No key and no login. Playback URLs come from [MusicSourceResolver].
 */
object MusicApi {
    private const val BASE = "https://api.deezer.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

    private val homeMutex = Mutex()
    @Volatile private var homeCache: MusicHome? = null
    @Volatile private var homeAt = 0L
    private const val HOME_TTL_MS = 10 * 60 * 1000L

    suspend fun home(force: Boolean = false): MusicHome = withContext(Dispatchers.IO) {
        if (!force && homeCache != null && System.currentTimeMillis() - homeAt < HOME_TTL_MS) {
            return@withContext homeCache!!
        }
        homeMutex.withLock {
            if (!force && homeCache != null && System.currentTimeMillis() - homeAt < HOME_TTL_MS) {
                return@withLock homeCache!!
            }
            val sections = LinkedHashMap<String, List<MusicTrack>>()
            runCatching { chartTracks(25) }.getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let { sections["Top 25 right now"] = it }

            val genreQueries = linkedMapOf(
                "Pop" to "Top Pop Hits",
                "Hip-Hop & Rap" to "Hip Hop Hits",
                "Electronic & Dance" to "Electronic Dance",
                "Rock" to "Rock Essentials",
                "Chill & Lofi" to "Lofi Beats Chill",
                "Soundtracks" to "Hans Zimmer Soundtracks",
            )
            coroutineScope {
                genreQueries.entries.map { (label, query) ->
                    async { label to runCatching { searchTracks(query, 15) }.getOrDefault(emptyList()) }
                }.awaitAll().forEach { (label, tracks) ->
                    if (tracks.isNotEmpty()) sections[label] = tracks
                }
            }

            val artists = runCatching { parseArtists(json("/chart/0/artists?limit=25")) }.getOrDefault(emptyList())
            val albums = runCatching { parseAlbums(json("/chart/0/albums?limit=20")) }.getOrDefault(emptyList())
            val playlists = runCatching { parsePlaylists(json("/chart/0/playlists?limit=20")) }.getOrDefault(emptyList())
            val result = MusicHome(sections, artists, albums, playlists)
            homeCache = result
            homeAt = System.currentTimeMillis()
            result
        }
    }

    suspend fun search(query: String): MusicSearchData = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext MusicSearchData()
        coroutineScope {
            val tracks = async { runCatching { searchTracks(q, 40) }.getOrDefault(emptyList()) }
            val artists = async { runCatching { searchArtists(q, 20) }.getOrDefault(emptyList()) }
            val albums = async { runCatching { searchAlbums(q, 20) }.getOrDefault(emptyList()) }
            val playlists = async { runCatching { searchPlaylists(q, 20) }.getOrDefault(emptyList()) }
            MusicSearchData(tracks.await(), artists.await(), albums.await(), playlists.await())
        }
    }

    suspend fun artist(id: String): MusicArtistDetails? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        val obj = json("/artist/$id") ?: return@withContext null
        val artist = MusicArtist.fromJson(obj)
        val top = runCatching {
            val data = json("/artist/$id/top?limit=50")?.optJSONArray("data")
            MusicTrack.listFrom(data)
        }.getOrDefault(emptyList())
        val albums = runCatching {
            val data = json("/artist/$id/albums?limit=30")?.optJSONArray("data")
            MusicAlbum.listFrom(data)
        }.getOrDefault(emptyList())
        MusicArtistDetails(artist, top, albums)
    }

    suspend fun album(id: String): MusicAlbumDetails? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        val obj = json("/album/$id") ?: return@withContext null
        val album = MusicAlbum.fromJson(obj)
        val tracks = if (album.tracks.isNotEmpty()) {
            album.tracks
        } else {
            MusicTrack.listFrom(json("/album/$id")?.optJSONObject("tracks")?.optJSONArray("data"))
        }
        MusicAlbumDetails(album, tracks)
    }

    suspend fun playlist(id: String): MusicPlaylistDetails? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        val obj = json("/playlist/$id") ?: return@withContext null
        val playlist = MusicPlaylist.fromJson(obj)
        val tracks = if (playlist.tracks.isNotEmpty()) {
            playlist.tracks
        } else {
            MusicTrack.listFrom(json("/playlist/$id")?.optJSONObject("tracks")?.optJSONArray("data"))
        }
        MusicPlaylistDetails(playlist, tracks)
    }

    private suspend fun chartTracks(limit: Int): List<MusicTrack> =
        MusicTrack.listFrom(json("/chart/0/tracks?limit=$limit")?.optJSONArray("data"))

    private suspend fun searchTracks(query: String, limit: Int): List<MusicTrack> =
        MusicTrack.listFrom(json("/search/track?q=${encode(query)}&limit=$limit")?.optJSONArray("data"))

    private suspend fun searchArtists(query: String, limit: Int): List<MusicArtist> =
        MusicArtist.listFrom(json("/search/artist?q=${encode(query)}&limit=$limit")?.optJSONArray("data"))

    private suspend fun searchAlbums(query: String, limit: Int): List<MusicAlbum> =
        MusicAlbum.listFrom(json("/search/album?q=${encode(query)}&limit=$limit")?.optJSONArray("data"))

    private suspend fun searchPlaylists(query: String, limit: Int): List<MusicPlaylist> =
        MusicPlaylist.listFrom(json("/search/playlist?q=${encode(query)}&limit=$limit")?.optJSONArray("data"))

    private fun parseArtists(obj: JSONObject?): List<MusicArtist> =
        MusicArtist.listFrom(obj?.optJSONArray("data"))

    private fun parseAlbums(obj: JSONObject?): List<MusicAlbum> =
        MusicAlbum.listFrom(obj?.optJSONArray("data"))

    private fun parsePlaylists(obj: JSONObject?): List<MusicPlaylist> =
        MusicPlaylist.listFrom(obj?.optJSONArray("data"))

    private suspend fun json(path: String): JSONObject? = runCatching {
        val text = httpGetTextWithHeaders("$BASE$path", mapOf("User-Agent" to USER_AGENT))
        JSONObject(text)
    }.getOrNull()

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
