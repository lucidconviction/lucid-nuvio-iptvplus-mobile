package com.nuvio.app.features.livetv.data

import com.nuvio.app.features.livetv.model.CatchupInfo
import com.nuvio.app.features.livetv.model.DrmInfo
import com.nuvio.app.features.livetv.model.LiveChannel

/**
 * The channel list as it was last made, saved so the app can show it the moment it opens
 * instead of waiting while every playlist is read again. The saved copy is replaced quietly
 * after the playlists are re-read, and ignored when it can't be used.
 *
 * Plain text, one line per entry, fields split by tabs:
 * - `V <version>`
 * - `S <fingerprint>`: what the saved channels were made from
 * - `C <fields…>`: one channel
 * A file of another version, or one that can't be read, is simply not used.
 */
internal object SavedChannels {
    const val FILE = "channels_saved.txt"
    private const val VERSION = "1"
    /** Between a header's name and value, and between headers. */
    private const val KV = '\u001F'
    private const val ITEM = '\u001E'
    private const val NULL = "\\0"

    class Saved(
        val stamp: String?,
        val channels: List<LiveChannel>,
    )

    private fun esc(v: String?): String {
        if (v == null) return NULL
        if (v.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return v
        val sb = StringBuilder(v.length + 8)
        for (ch in v) when (ch) {
            '\\' -> sb.append("\\\\")
            '\t' -> sb.append("\\t")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    private fun unesc(v: String): String? {
        if (v == NULL) return null
        if ('\\' !in v) return v
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val ch = v[i]
            if (ch == '\\' && i + 1 < v.length) {
                when (v[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> sb.append(v[i + 1])
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun encodeMap(m: Map<String, String>): String =
        if (m.isEmpty()) "" else m.entries.joinToString(ITEM.toString()) { it.key + KV + it.value }

    private fun decodeMap(v: String): Map<String, String> {
        if (v.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        v.split(ITEM).forEach { p ->
            val i = p.indexOf(KV)
            if (i > 0) out[p.substring(0, i)] = p.substring(i + 1)
        }
        return out
    }

    /** The lines to write, given to [write] one piece at a time. */
    suspend fun write(stamp: String, channels: List<LiveChannel>, write: suspend (String) -> Unit) {
        write("V\t$VERSION\n")
        write("S\t${esc(stamp)}\n")
        val sb = StringBuilder(64 * 1024)
        for (c in channels) {
            sb.append('C')
            for (f in fieldsOf(c)) sb.append('\t').append(esc(f))
            sb.append('\n')
            if (sb.length > 48 * 1024) {
                write(sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) write(sb.toString())
    }

    private fun fieldsOf(c: LiveChannel): List<String?> = listOf(
        c.key, c.sourceId, c.sourceName, c.name, c.tvgId, c.tvgName, c.logo, c.thumbnail,
        c.groupId, c.group, c.number.toString(), c.url, encodeMap(c.headers),
        c.catchup?.let {
            listOf(it.type, it.source.orEmpty(), it.days.toString(), it.correctionHours.toString(), if (it.source == null) "0" else "1")
                .joinToString(ITEM.toString())
        },
        c.drm?.let {
            listOf(
                it.scheme,
                it.license,
                encodeMap(it.licenseHeaders).replace(ITEM, '\u001D').replace(KV, '\u001C'),
                it.manifestType ?: NULL,
            ).joinToString(ITEM.toString())
        },
    )

    private const val FIELDS = 15

    /** Reads a saved list; null when it can't be used (another version, broken, empty). */
    fun read(lines: Sequence<String>): Saved? {
        var stamp: String? = null
        val out = ArrayList<LiveChannel>()
        // Text shared by many channels (source names, groups, header sets) kept once in memory.
        val shared = HashMap<String, String>()
        fun one(v: String?): String? = v?.let { shared.getOrPut(it) { it } }
        val headerSets = HashMap<String, Map<String, String>>()
        var versionOk = false
        for (line in lines) {
            if (line.isEmpty()) continue
            val f = line.split('\t')
            when (f[0]) {
                "V" -> versionOk = f.getOrNull(1) == VERSION
                "S" -> stamp = f.getOrNull(1)?.let(::unesc)
                "C" -> {
                    if (!versionOk) return null
                    if (f.size != FIELDS + 1) return null
                    val v = f.drop(1).map(::unesc)
                    val number = v[10]?.toIntOrNull() ?: return null
                    val headersRaw = v[12].orEmpty()
                    val headers = headerSets.getOrPut(headersRaw) { decodeMap(headersRaw) }
                    val catchup = v[13]?.split(ITEM)?.takeIf { it.size >= 5 }?.let {
                        CatchupInfo(
                            type = it[0],
                            source = if (it[4] == "1") it[1] else null,
                            days = it[2].toIntOrNull() ?: 0,
                            correctionHours = it[3].toDoubleOrNull() ?: 0.0,
                        )
                    }
                    val drm = v[14]?.split(ITEM)?.takeIf { it.size >= 4 }?.let {
                        DrmInfo(
                            scheme = it[0],
                            license = it[1],
                            licenseHeaders = decodeMap(it[2].replace('\u001D', ITEM).replace('\u001C', KV)),
                            manifestType = it[3].takeIf { t -> t != NULL },
                        )
                    }
                    out += LiveChannel(
                        key = v[0] ?: return null,
                        sourceId = one(v[1]) ?: return null,
                        sourceName = one(v[2]).orEmpty(),
                        name = v[3].orEmpty(),
                        tvgId = v[4],
                        tvgName = v[5],
                        logo = v[6],
                        thumbnail = v[7],
                        groupId = one(v[8]).orEmpty(),
                        group = one(v[9]).orEmpty(),
                        number = number,
                        url = v[11].orEmpty(),
                        headers = headers,
                        catchup = catchup,
                        drm = drm,
                    )
                }
                else -> return null
            }
        }
        if (!versionOk || out.isEmpty()) return null
        return Saved(stamp, out)
    }
}
