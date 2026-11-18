package com.nuvio.app.features.web

import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders

/**
 * Brings a userscript in from a URL instead of making the user paste its source.
 *
 * Two shapes are accepted, because those are the two a user actually has:
 *
 *  - a direct script (`https://…/foo.user.js`, a raw gist, a repo blob's raw
 *    URL) — fetched and returned as-is;
 *  - a Greasy Fork / OpenUserJS script PAGE. Those pages do not serve the
 *    script; they link to it ("Install this script"). The link is found and
 *    followed, and the result must still look like a userscript, so a wrong
 *    page fails with a sentence instead of saving HTML as code.
 *
 * The caller puts the result in the editor rather than saving it silently: an
 * import is a convenience, not a reason to trust something unseen.
 */
internal object WebUserscriptImport {

    private const val MAX_PAGE_BYTES = 1_500_000
    private const val MAX_SCRIPT_BYTES = 2_500_000

    private val client by lazy {
        createApiHttpClient().config { followRedirects = true }
    }

    suspend fun fetch(rawUrl: String): String {
        var target = rawUrl.trim()
        if (target.isEmpty()) throw IllegalArgumentException("Enter a script URL")
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            target = "https://$target"
        }
        val body = download(target, MAX_PAGE_BYTES)
        if (looksLikeUserscript(body)) return body

        val href = firstScriptHref(body)
            ?: throw IllegalStateException(
                "That page does not link to a .user.js script — open the script's own page and try again.",
            )
        val absolute = resolve(target, href)
        val script = download(absolute, MAX_SCRIPT_BYTES)
        if (!looksLikeUserscript(script)) {
            throw IllegalStateException("The link at that address is not a userscript.")
        }
        return script
    }

    private suspend fun download(url: String, maxBytes: Int): String {
        val response = runCatching { client.get(url) }
            .getOrElse { throw IllegalStateException(it.message ?: "Could not download the script") }
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > maxBytes) {
            throw IllegalStateException("That file is larger than a userscript should be.")
        }
        val text = runCatching { response.bodyAsText() }
            .getOrElse { throw IllegalStateException(it.message ?: "Could not read the script") }
        if (text.length > maxBytes) {
            throw IllegalStateException("That file is larger than a userscript should be.")
        }
        return text
    }

    private fun looksLikeUserscript(text: String): Boolean =
        text.contains("==UserScript==") ||
            (text.contains("@name") && text.contains("@match", ignoreCase = true)) ||
            (text.contains("@name") && text.contains("@include", ignoreCase = true))

    /** The "Install this script" link on a Greasy Fork / OpenUserJS page. */
    private fun firstScriptHref(html: String): String? {
        // Greasy Fork's own install button first, then any .user.js href.
        val patterns = listOf(
            Regex("""href="([^"]*/code/[^"]*\.user\.js[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""href="([^"]+\.user\.js[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s"'<>]+\.user\.js)""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    /**
     * Resolves the one shape a script page actually uses: an absolute URL, a
     * scheme-relative `//host/…`, a root path, or a path relative to the page.
     * (No java.net.URI in commonMain — and a full RFC 3986 resolver would be
     * more surface than this needs.)
     */
    private fun resolve(base: String, href: String): String {
        val clean = href.replace("&amp;", "&").trim()
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean
        val scheme = base.substringBefore("://", "https")
        if (clean.startsWith("//")) return "$scheme:$clean"
        val afterScheme = base.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val hostEnd = base.indexOf('/', afterScheme).let { if (it < 0) base.length else it }
        val origin = base.substring(0, hostEnd)
        if (clean.startsWith("/")) return origin + clean
        val path = base.substringBefore('?').substringBefore('#')
        val dir = path.substringBeforeLast('/', "")
        return "$dir/$clean"
    }
}
