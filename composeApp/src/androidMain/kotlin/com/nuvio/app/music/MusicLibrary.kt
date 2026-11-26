package com.nuvio.app.music

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class UserMusicPlaylist(
    val id: String,
    val title: String,
    val tracks: List<MusicTrack> = emptyList(),
    val createdAtMs: Long = 0L,
)

/** Liked songs, recently played, user playlists and the audio/video choice. */
object MusicLibrary {
    private const val PREFS = "nuvio_music"
    private const val KEY_LIKED = "liked"
    private const val KEY_RECENT = "recent"
    private const val KEY_PLAYLISTS = "playlists"
    private const val KEY_VIDEO_MODE = "video_mode"

    private var prefs: SharedPreferences? = null

    private val _liked = MutableStateFlow<List<MusicTrack>>(emptyList())
    val liked: StateFlow<List<MusicTrack>> = _liked.asStateFlow()

    private val _recent = MutableStateFlow<List<MusicTrack>>(emptyList())
    val recent: StateFlow<List<MusicTrack>> = _recent.asStateFlow()

    private val _playlists = MutableStateFlow<List<UserMusicPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserMusicPlaylist>> = _playlists.asStateFlow()

    private val _videoMode = MutableStateFlow(false)
    val videoMode: StateFlow<Boolean> = _videoMode.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            _liked.value = decode(p.getString(KEY_LIKED, null))
            _recent.value = decode(p.getString(KEY_RECENT, null))
            _playlists.value = decodePlaylists(p.getString(KEY_PLAYLISTS, null))
            _videoMode.value = p.getBoolean(KEY_VIDEO_MODE, false)
        }
    }

    fun isLiked(trackId: String): Boolean = _liked.value.any { it.id == trackId }

    fun toggleLike(track: MusicTrack) {
        val next = _liked.value.toMutableList()
        val index = next.indexOfFirst { it.id == track.id }
        if (index >= 0) next.removeAt(index) else next.add(0, track)
        _liked.value = next
        prefs?.edit()?.putString(KEY_LIKED, encode(next))?.apply()
    }

    fun addRecent(track: MusicTrack) {
        val next = (listOf(track) + _recent.value.filterNot { it.id == track.id }).take(50)
        _recent.value = next
        prefs?.edit()?.putString(KEY_RECENT, encode(next))?.apply()
    }

    fun createPlaylist(title: String): UserMusicPlaylist? {
        val name = title.trim().ifBlank { return null }
        val playlist = UserMusicPlaylist(
            id = "pl_${System.currentTimeMillis()}",
            title = name,
            createdAtMs = System.currentTimeMillis(),
        )
        _playlists.value = listOf(playlist) + _playlists.value
        persistPlaylists()
        return playlist
    }

    fun addToPlaylist(playlistId: String, track: MusicTrack): Boolean {
        var changed = false
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != playlistId || playlist.tracks.any { it.id == track.id }) {
                playlist
            } else {
                changed = true
                playlist.copy(tracks = playlist.tracks + track)
            }
        }
        if (changed) persistPlaylists()
        return changed
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(tracks = playlist.tracks.filterNot { it.id == trackId })
            } else {
                playlist
            }
        }
        persistPlaylists()
    }

    fun deletePlaylist(playlistId: String) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        persistPlaylists()
    }

    fun setVideoMode(enabled: Boolean) {
        if (_videoMode.value == enabled) return
        _videoMode.value = enabled
        prefs?.edit()?.putBoolean(KEY_VIDEO_MODE, enabled)?.apply()
    }

    private fun persistPlaylists() {
        val array = JSONArray()
        _playlists.value.forEach { playlist ->
            array.put(
                JSONObject().apply {
                    put("id", playlist.id)
                    put("title", playlist.title)
                    put("createdAtMs", playlist.createdAtMs)
                    put(
                        "tracks",
                        JSONArray().apply { playlist.tracks.forEach { put(it.toJson()) } },
                    )
                },
            )
        }
        prefs?.edit()?.putString(KEY_PLAYLISTS, array.toString())?.apply()
    }

    private fun decodePlaylists(raw: String?): List<UserMusicPlaylist> = runCatching {
        if (raw.isNullOrBlank()) return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val tracks = obj.optJSONArray("tracks")
                add(
                    UserMusicPlaylist(
                        id = obj.optString("id"),
                        title = obj.optString("title").ifBlank { "Playlist" },
                        createdAtMs = obj.optLong("createdAtMs"),
                        tracks = buildList {
                            if (tracks != null) {
                                for (trackIndex in 0 until tracks.length()) {
                                    tracks.optJSONObject(trackIndex)?.let { add(MusicTrack.fromJson(it)) }
                                }
                            }
                        },
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun encode(tracks: List<MusicTrack>): String = JSONArray().apply {
        tracks.forEach { put(it.toJson()) }
    }.toString()

    private fun decode(raw: String?): List<MusicTrack> = runCatching {
        if (raw.isNullOrBlank()) return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(MusicTrack.fromJson(it)) }
            }
        }
    }.getOrDefault(emptyList())
}
