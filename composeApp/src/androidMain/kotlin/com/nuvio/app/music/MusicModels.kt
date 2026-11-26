package com.nuvio.app.music

import org.json.JSONArray
import org.json.JSONObject

/** One Deezer track. */
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "",
    val album: String = "Single",
    val albumId: String = "",
    val coverUrl: String = "",
    val durationSeconds: Int = 0,
    val previewUrl: String? = null,
) {
    val formattedDuration: String
        get() {
            if (durationSeconds <= 0) return "--:--"
            return "%d:%02d".format(durationSeconds / 60, durationSeconds % 60)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        put("artistId", artistId)
        put("album", album)
        put("albumId", albumId)
        put("coverUrl", coverUrl)
        put("durationSeconds", durationSeconds)
        previewUrl?.let { put("previewUrl", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): MusicTrack {
            fun optString(vararg keys: String): String {
                for (key in keys) {
                    val value = o.optString(key)
                    if (value.isNotBlank() && value != "null") return value
                }
                return ""
            }

            fun optInt(vararg keys: String): Int {
                for (key in keys) {
                    val value = o.optInt(key, Int.MIN_VALUE)
                    if (value != Int.MIN_VALUE) return value
                }
                return 0
            }

            val albumObj = o.optJSONObject("album") ?: JSONObject()
            val artistObj = o.optJSONObject("artist") ?: JSONObject()
            var cover = if (albumObj.length() > 0) {
                listOf("cover_xl", "cover_big", "cover_medium", "cover_small")
                    .firstNotNullOfOrNull { albumObj.optString(it).takeIf(String::isNotBlank) }
                    .orEmpty()
            } else {
                listOf("cover_xl", "cover_big", "cover_medium", "cover_small", "cover")
                    .firstNotNullOfOrNull { o.optString(it).takeIf(String::isNotBlank) }
                    .orEmpty()
            }
            if (cover.startsWith("http://")) cover = "https://" + cover.removePrefix("http://")
            return MusicTrack(
                id = optString("id"),
                title = optString("title", "title_short").ifBlank { "Unknown Track" },
                artist = artistObj.optString("name").ifBlank { optString("artist") }
                    .ifBlank { "Unknown Artist" },
                artistId = artistObj.optString("id").ifBlank { optString("artistId") },
                album = albumObj.optString("title").ifBlank { optString("album") }
                    .ifBlank { "Single" },
                albumId = albumObj.optString("id").ifBlank { optString("albumId") },
                coverUrl = cover,
                durationSeconds = optInt("duration", "durationSeconds"),
                previewUrl = o.optString("preview").ifBlank { o.optString("previewUrl") }
                    .takeIf(String::isNotBlank),
            )
        }

        fun listFrom(array: JSONArray?): List<MusicTrack> = buildList {
            if (array == null) return@buildList
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(fromJson(it)) }
            }
        }
    }
}

data class MusicArtist(
    val id: String,
    val name: String,
    val pictureUrl: String = "",
) {
    companion object {
        fun fromJson(o: JSONObject): MusicArtist {
            var picture = ""
            for (key in listOf("picture_xl", "picture_big", "picture_medium", "picture_small", "picture")) {
                val value = o.optString(key)
                if (value.isNotBlank()) {
                    picture = value
                    break
                }
            }
            if (picture.startsWith("http://")) picture = "https://" + picture.removePrefix("http://")
            return MusicArtist(
                id = o.optString("id"),
                name = o.optString("name").ifBlank { "Artist" },
                pictureUrl = picture,
            )
        }

        fun listFrom(array: JSONArray?): List<MusicArtist> = buildList {
            if (array == null) return@buildList
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(fromJson(it)) }
            }
        }
    }
}

data class MusicAlbum(
    val id: String,
    val title: String,
    val artistName: String = "",
    val artistId: String = "",
    val coverUrl: String = "",
    val trackCount: Int = 0,
    val tracks: List<MusicTrack> = emptyList(),
) {
    companion object {
        fun fromJson(o: JSONObject): MusicAlbum {
            val artist = o.optJSONObject("artist") ?: JSONObject()
            var cover = ""
            for (key in listOf("cover_xl", "cover_big", "cover_medium", "cover_small", "cover")) {
                val value = o.optString(key)
                if (value.isNotBlank()) {
                    cover = value
                    break
                }
            }
            if (cover.startsWith("http://")) cover = "https://" + cover.removePrefix("http://")
            val tracks = o.optJSONObject("tracks")?.optJSONArray("data")
            return MusicAlbum(
                id = o.optString("id"),
                title = o.optString("title").ifBlank { "Album" },
                artistName = artist.optString("name"),
                artistId = artist.optString("id"),
                coverUrl = cover,
                trackCount = o.optInt("nb_tracks"),
                tracks = MusicTrack.listFrom(tracks),
            )
        }

        fun listFrom(array: JSONArray?): List<MusicAlbum> = buildList {
            if (array == null) return@buildList
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(fromJson(it)) }
            }
        }
    }
}

data class MusicPlaylist(
    val id: String,
    val title: String,
    val creator: String = "",
    val coverUrl: String = "",
    val trackCount: Int = 0,
    val tracks: List<MusicTrack> = emptyList(),
) {
    companion object {
        fun fromJson(o: JSONObject): MusicPlaylist {
            val creator = o.optJSONObject("creator") ?: JSONObject()
            var cover = ""
            for (key in listOf("picture_xl", "picture_big", "picture_medium", "picture_small", "picture")) {
                val value = o.optString(key)
                if (value.isNotBlank()) {
                    cover = value
                    break
                }
            }
            if (cover.startsWith("http://")) cover = "https://" + cover.removePrefix("http://")
            val tracks = o.optJSONObject("tracks")?.optJSONArray("data")
            return MusicPlaylist(
                id = o.optString("id"),
                title = o.optString("title").ifBlank { "Playlist" },
                creator = creator.optString("name"),
                coverUrl = cover,
                trackCount = o.optInt("nb_tracks"),
                tracks = MusicTrack.listFrom(tracks),
            )
        }

        fun listFrom(array: JSONArray?): List<MusicPlaylist> = buildList {
            if (array == null) return@buildList
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(fromJson(it)) }
            }
        }
    }
}

data class MusicHome(
    val sections: LinkedHashMap<String, List<MusicTrack>>,
    val artists: List<MusicArtist>,
    val albums: List<MusicAlbum>,
    val playlists: List<MusicPlaylist>,
) {
    val hero: MusicTrack? get() = sections.values.firstOrNull()?.firstOrNull()
}

data class MusicSearchData(
    val tracks: List<MusicTrack> = emptyList(),
    val artists: List<MusicArtist> = emptyList(),
    val albums: List<MusicAlbum> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
)

data class MusicArtistDetails(
    val artist: MusicArtist,
    val topTracks: List<MusicTrack>,
    val albums: List<MusicAlbum>,
)

data class MusicAlbumDetails(
    val album: MusicAlbum,
    val tracks: List<MusicTrack>,
)

data class MusicPlaylistDetails(
    val playlist: MusicPlaylist,
    val tracks: List<MusicTrack>,
)

/** One playable URL for a track. */
data class MusicStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val label: String = "",
    val lossless: Boolean = false,
    val isDash: Boolean = false,
    val isHls: Boolean = false,
)
