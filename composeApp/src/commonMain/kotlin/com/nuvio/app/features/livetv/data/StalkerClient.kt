package com.nuvio.app.features.livetv.data

import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.core.network.readBoundedResponseBody
import com.nuvio.app.features.livetv.model.PlaylistSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.encodeURLParameter
import io.ktor.http.contentLength
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One live channel from a Stalker portal. */
internal data class StalkerChannel(
    val id: String,
    /** The id its guide uses, when the portal supplies one. */
    val xmltvId: String?,
    val name: String,
    val logo: String?,
    val url: String,
    val genreId: String?,
    val number: Int?,
)

/** A portal's channels as an M3U, plus the identity its guide needs. */
internal data class StalkerPlaylist(
    val m3u: String,
    val channels: List<StalkerChannel>,
    /** The handshake token, reused for the portal's guide in the same refresh. */
    val token: String,
    /** The time zone the portal reported, when it has one. */
    val timezone: String,
)

/**
 * The Stalker/MAG portal API (server/load.php), the way a set-top box talks to a portal:
 * handshake with the registered MAC, genres, the live channel list, and the portal's EPG.
 *
 * Portals are read into an M3U (with the Cookie/User-Agent/Referer each stream needs) so the
 * rest of Live TV treats a portal exactly like any other playlist.
 */
internal class StalkerClient(
    private val client: HttpClient = createApiHttpClient().config {
        followRedirects = true
        install(HttpRedirect) { allowHttpsDowngrade = true }
    },
) {

    suspend fun livePlaylistM3u(
        pl: PlaylistSource,
        onProgress: ((Int) -> Unit)? = null,
    ): StalkerPlaylist {
        require(pl.isStalker) { "Not a Stalker portal" }
        require(PlaylistSource.isValidMac(pl.stalkerMac)) { "Enter the portal's MAC address (00:1A:79:…)" }
        val session = handshake(pl)
        val genres = runCatching { genres(pl, session.token) }.getOrDefault(emptyMap())
        val raw = channels(pl, session.token, onProgress)
        if (raw.isEmpty()) error("The portal returned no live channels")
        val channels = raw.mapNotNull { channelOf(pl, it) }.distinctBy { it.id }
        if (channels.isEmpty()) error("The portal returned no playable channels")
        val headers = buildJsonObject {
            put("User-Agent", pl.stalkerUserAgent())
            put("X-User-Agent", "Model: MAG254; Link: Ethernet")
            put("Referer", pl.stalkerBase() + "/")
            put("Cookie", pl.stalkerCookie())
        }.toString()
        val out = StringBuilder(channels.size * 220 + 32)
        out.append("#EXTM3U\n")
        channels.forEach { channel ->
            val group = genres[channel.genreId] ?: "Uncategorized"
            out.append("#EXTINF:-1")
            attr(out, "tvg-id", channel.xmltvId ?: channel.id)
            attr(out, "tvg-name", channel.name)
            attr(out, "tvg-logo", channel.logo)
            attr(out, "group-title", group)
            channel.number?.takeIf { it > 0 }?.let { attr(out, "tvg-chno", it.toString()) }
            out.append(',').append(channel.name.replace('\n', ' ')).append('\n')
            // Every portal stream needs the box's MAC cookie; many also check the MAG UA and
            // the portal as Referer. #EXTHTTP is read by M3uParser into the channel headers.
            out.append("#EXTHTTP:").append(headers).append('\n')
            out.append(channel.url).append('\n')
        }
        return StalkerPlaylist(out.toString(), channels, session.token, session.timezone)
    }

    /**
     * The portal's guide as XMLTV. Portals disagree about the shape of get_epg_info (object
     * keyed by channel, or a flat list), so the reader accepts both and ignores anything it
     * cannot date. A portal with no guide simply returns null.
     */
    suspend fun epgXml(
        pl: PlaylistSource,
        playlist: StalkerPlaylist,
        onProgress: ((Int) -> Unit)? = null,
    ): String? {
        if (!pl.useEmbeddedEpg) return null
        val body = runCatching {
            getText(pl, "type=itv&action=get_epg_info&period=3", playlist.token)
        }.getOrNull() ?: return null
        val data = jsObject(body)?.get("data") ?: return null
        val known = playlist.channels.associateBy { it.id }
        val byXmltv = playlist.channels.mapNotNull { c -> c.xmltvId?.let { it to c } }.toMap()
        val programs = LinkedHashMap<String, MutableList<StalkerProgram>>()

        fun add(channelKey: String, element: JsonElement) {
            val parsed = (element as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?: listOfNotNull(element as? JsonObject)
            parsed.forEach { o ->
                val chId = o.optString("ch_id").ifBlank { channelKey }
                val channel = known[chId] ?: byXmltv[chId] ?: known[channelKey] ?: byXmltv[channelKey]
                val xmltvId = channel?.xmltvId ?: channel?.id ?: chId
                val start = epochMs(o.optString("start_timestamp"), o.optString("time"), playlist.timezone)
                    ?: epochMs(o.optString("start_time"), o.optString("start"), playlist.timezone)
                    ?: return@forEach
                val stop = epochMs(o.optString("stop_timestamp"), o.optString("time_to"), playlist.timezone)
                    ?: epochMs(o.optString("stop_time"), o.optString("end"), playlist.timezone)
                    ?: return@forEach
                if (stop <= start) return@forEach
                val title = firstNonBlank(o.optString("name"), o.optString("title")).ifBlank { "No information" }
                programs.getOrPut(xmltvId) { ArrayList() } += StalkerProgram(
                    startMs = start,
                    stopMs = stop,
                    title = title,
                    description = firstNonBlank(o.optString("descr"), o.optString("description")),
                    category = firstNonBlank(o.optString("category"), o.optString("genre")),
                )
            }
            onProgress?.invoke(programs.values.sumOf { it.size })
        }

        when (data) {
            is JsonObject -> data.forEach { (key, value) ->
                if (key == "data") add("", value) else add(key, value)
            }
            is JsonArray -> data.forEach { add("", it) }
            else -> return null
        }
        if (programs.isEmpty()) return null

        val out = StringBuilder(programs.size * 380 + 256)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n<tv generator-info-name=\"Nuvio\">\n")
        programs.keys.forEach { xmltvId ->
            val channel = playlist.channels.firstOrNull { (it.xmltvId ?: it.id) == xmltvId }
            out.append("<channel id=\"").append(xml(xmltvId)).append("\">")
            channel?.name?.takeIf { it.isNotBlank() }?.let {
                out.append("<display-name>").append(xml(it)).append("</display-name>")
            }
            channel?.logo?.takeIf { it.startsWith("http") }?.let {
                out.append("<icon src=\"").append(xml(it)).append("\"/>")
            }
            out.append("</channel>\n")
        }
        programs.forEach { (xmltvId, list) ->
            list.sortedBy { it.startMs }.forEach { p ->
                out.append("<programme start=\"").append(xmltvTime(p.startMs))
                    .append("\" stop=\"").append(xmltvTime(p.stopMs))
                    .append("\" channel=\"").append(xml(xmltvId)).append("\">")
                out.append("<title>").append(xml(p.title)).append("</title>")
                p.description?.takeIf { it.isNotBlank() }?.let {
                    out.append("<desc>").append(xml(it)).append("</desc>")
                }
                p.category?.takeIf { it.isNotBlank() }?.let {
                    out.append("<category>").append(xml(it)).append("</category>")
                }
                out.append("</programme>\n")
            }
        }
        out.append("</tv>\n")
        return out.toString()
    }

    // ---------------------------------------------------------------- portal calls

    private data class Session(val token: String, val timezone: String)

    private suspend fun handshake(pl: PlaylistSource): Session {
        val body = getText(pl, "type=stb&action=handshake", "")
        val js = jsObject(body) ?: error("The portal did not answer like a Stalker portal")
        val token = firstNonBlank(js.optString("token"), js.optString("auth_token"))
            .ifBlank { error("The portal refused this MAC. Check the MAC and portal address.") }
        // get_profile teaches some portals who the box is and returns their time zone. It is
        // allowed to fail: the handshake token is enough for the channel list.
        val profile = runCatching {
            jsObject(getText(pl, "type=stb&action=get_profile", token))
        }.getOrNull()
        val timezone = firstNonBlank(
            profile?.optString("timezone").orEmpty(),
            js.optString("timezone"),
        )
        return Session(token, timezone)
    }

    private suspend fun genres(pl: PlaylistSource, token: String): Map<String, String> {
        val data = jsObject(getText(pl, "type=itv&action=get_genres", token))?.get("data")
        return items(data).mapNotNull { o ->
            val id = o.optString("id").ifBlank { o.optString("genre_id") }
            if (id.isBlank()) null else id to o.optString("title").ifBlank { o.optString("name") }
        }.toMap()
    }

    private suspend fun channels(
        pl: PlaylistSource,
        token: String,
        onProgress: ((Int) -> Unit)?,
    ): List<JsonObject> {
        // Some portals answer get_all_channels, others only the paged ordered list. Try the
        // cheap full list first, then walk the pages of the ordered list.
        val all = runCatching {
            items(jsObject(getText(pl, "type=itv&action=get_all_channels", token))?.get("data"))
        }.getOrDefault(emptyList())
        if (all.isNotEmpty()) {
            onProgress?.invoke(all.size)
            return all
        }
        val out = ArrayList<JsonObject>()
        var page = 1
        var genre = "*"
        while (page <= MAX_PAGES) {
            val js = runCatching {
                jsObject(
                    getText(
                        pl,
                        "type=itv&action=get_ordered_list&genre=$genre&p=$page&p_size=$PAGE_SIZE",
                        token,
                    ),
                )
            }.getOrNull() ?: break
            val pageItems = items(js["data"])
            if (pageItems.isEmpty()) {
                // A few portals reject "*" but answer genre 0.
                if (page == 1 && genre == "*") {
                    genre = "0"
                    continue
                }
                break
            }
            out += pageItems
            onProgress?.invoke(out.size)
            val total = js.optInt("total_items", 0)
            if (total > 0 && out.size >= total) break
            if (pageItems.size < PAGE_SIZE) break
            page++
        }
        return out
    }

    private fun channelOf(pl: PlaylistSource, o: JsonObject): StalkerChannel? {
        val id = firstNonBlank(o.optString("id"), o.optString("ch_id"))
        val name = firstNonBlank(o.optString("name"), o.optString("title"))
        val cmd = firstNonBlank(
            o.optString("cmd"),
            o.optString("url"),
            o.optString("stream_cmd"),
            o.optString("stream_url"),
        )
        if (id.isBlank() || name.isBlank() || cmd.isBlank()) return null
        val url = streamUrl(pl, cmd) ?: return null
        return StalkerChannel(
            id = id,
            xmltvId = firstNonBlank(
                o.optString("xmltv_id"),
                o.optString("epg_channel_id"),
                o.optString("tv_epg_id"),
            ).ifBlank { null },
            name = name,
            logo = firstNonBlank(o.optString("logo"), o.optString("tv_icon"), o.optString("icon"))
                .ifBlank { null },
            url = url,
            genreId = firstNonBlank(o.optString("tv_genre_id"), o.optString("genre_id")).ifBlank { null },
            number = o.optInt("number", 0).takeIf { it > 0 }
                ?: o.optInt("ch_number", 0).takeIf { it > 0 },
        )
    }

    /** The portal's stream command made into a plain URL: "ffmpeg http://…" / "/media/…". */
    private fun streamUrl(pl: PlaylistSource, raw: String): String? {
        var value = raw.trim()
        if (value.startsWith("ffmpeg", ignoreCase = true)) value = value.substring(6).trim()
        val http = Regex("""https?://\S+""", RegexOption.IGNORE_CASE).find(value)?.value
        value = http ?: run {
            if (!value.startsWith("/")) return null
            val base = runCatching { io.ktor.http.Url(pl.stalkerBase()) }.getOrNull() ?: return null
            val port = if (base.specifiedPort > 0) ":${base.specifiedPort}" else ""
            "${base.protocol.name.lowercase()}://${base.host}$port$value"
        }
        value = value.substringBefore('|').trim()
        return value.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private suspend fun getText(pl: PlaylistSource, query: String, token: String?): String {
        val url = buildString {
            append(pl.stalkerEndpoint()).append('?').append(query)
            if (token != null) append("&token=").append(token.encodeURLParameter())
            append("&JsHttpRequest=1-xml")
        }
        return client.prepareGet(url) {
            header("User-Agent", pl.stalkerUserAgent())
            header("X-User-Agent", "Model: MAG254; Link: Ethernet")
            header("Cookie", pl.stalkerCookie())
            header("Referer", pl.stalkerBase() + "/")
            header("Accept", "*/*")
            token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            timeout {
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 60_000
            }
        }.execute { response ->
            val code = response.status.value
            if (code !in 200..299) error(XtreamClient.httpError(code))
            val text = readBoundedResponseBody(response.bodyAsChannel(), response.contentLength(), API_MAX_BYTES)
            if (text.isEmpty()) error("Empty response")
            text
        }
    }

    private fun jsObject(body: String): JsonObject? {
        val root = runCatching { parseObject(body) }.getOrNull() ?: return null
        return root["js"] as? JsonObject ?: root
    }

    /** get_*_list data as objects, whether a list or an object keyed by number. */
    private fun items(value: JsonElement?): List<JsonObject> = when (value) {
        is JsonArray -> value.mapNotNull { it as? JsonObject }
        is JsonObject -> {
            val nested = value["data"]
            if (nested != null) items(nested) else value.values.mapNotNull { it as? JsonObject }
        }
        else -> emptyList()
    }

    private companion object {
        const val PAGE_SIZE = 1000
        const val MAX_PAGES = 100
        const val API_MAX_BYTES = 256 * 1024 * 1024

        fun firstNonBlank(vararg values: String): String =
            values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

        fun attr(out: StringBuilder, key: String, value: String?) {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return
            out.append(' ').append(key).append("=\"").append(v.replace('"', '\'').replace('\n', ' ')).append('"')
        }

        fun xml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        fun xmltvTime(epochMs: Long): String {
            val c = CivilTime.atUtc(epochMs)
            fun pad(n: Int) = n.toString().padStart(2, '0')
            return "${c.year}${pad(c.month)}${pad(c.day)}${pad(c.hour)}${pad(c.minute)}${pad(c.second)} +0000"
        }

        private val dateTime = Regex(
            """(\d{4})[-/](\d{1,2})[-/](\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?""",
        )

        /** start_timestamp (seconds or ms), or a portal date/time parsed in the portal's zone. */
        fun epochMs(timestamp: String, text: String, timezone: String): Long? {
            timestamp.trim().toLongOrNull()?.takeIf { it > 0 }?.let { raw ->
                return if (raw > 10_000_000_000L) raw else raw * 1000L
            }
            val match = dateTime.find(text.trim()) ?: return null
            val values = match.groupValues
            val zone = timezone.trim().takeIf { it.isNotBlank() && LiveTvTimeZones.isKnown(it) }
                ?: LiveTvTimeZones.UTC
            return runCatching {
                CivilTime.toEpochMs(
                    year = values[1].toInt(),
                    month = values[2].toInt(),
                    day = values[3].toInt(),
                    hour = values[4].toInt(),
                    minute = values[5].toInt(),
                    second = values[6].toIntOrNull() ?: 0,
                    zone = zone,
                )
            }.getOrNull()
        }
    }
}

private data class StalkerProgram(
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String?,
    val category: String?,
)
