package com.zerodelay.tv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** One CazéTV live game. */
data class Game(val videoId: String, val title: String)

/** Result of an HLS resolve: the URL, or null plus a short reason for the UI. */
data class HlsResult(val url: String?, val diagnostic: String? = null)

/**
 * YouTube live extraction, yt-dlp style but via the watch page (the InnerTube
 * `player` endpoint is gated behind PoToken as of 2026; see android/spike/FINDINGS.md).
 *
 * All requests must run off the main thread. They must also run on the SAME
 * device that plays: googlevideo manifest/segment URLs are tied to the
 * requesting IP.
 */
object YouTubeLive {

    // Public so the player can send the SAME browser UA when fetching manifests
    // and segments: googlevideo returns 403 for non-browser user agents.
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120 Safari/537.36"
    private const val CHANNEL = "@CazeTV"

    // CazéTV mixes World Cup football with basketball ("COPA DO MUNDO DE BASQUETE"),
    // table tennis, Wimbledon and studio shows. Every FIFA World Cup match title
    // carries this exact tag, so it isolates the games we want.
    private const val WORLD_CUP_TAG = "COPA DO MUNDO FIFA"

    // --- Public API ---------------------------------------------------------

    /**
     * CazéTV FIFA World Cup matches that are LIVE right now, in the order YouTube
     * lists them. Scheduled/upcoming matches are excluded: CazéTV creates a live
     * with the "AO VIVO: ..." title hours ahead (waiting room, no HLS yet), so
     * liveness must come from the thumbnail badge, not the title text. A live
     * lockup carries a standalone "AO VIVO" badge; a scheduled one carries
     * "Em breve" instead. The real match title is the string with the World Cup
     * tag (using the first "AO VIVO"-prefixed string would grab the bare badge).
     */
    fun listLiveGames(): List<Game> {
        val html = httpGet("https://www.youtube.com/$CHANNEL/streams")
        val json = extractAssignedJson(html, "ytInitialData") ?: return emptyList()
        val root = JSONObject(json)
        val games = LinkedHashMap<String, String>() // videoId -> title (dedup, keep order)
        walkObjects(root) { obj ->
            if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO") {
                val id = obj.optString("contentId")
                if (id.isNotEmpty() && !games.containsKey(id)) {
                    val strings = collectStrings(obj)
                    val liveNow = strings.any { it.trim().equals("AO VIVO", ignoreCase = true) } &&
                        strings.none { it.contains("Em breve", ignoreCase = true) }
                    val title = strings.firstOrNull { it.contains(WORLD_CUP_TAG, ignoreCase = true) }
                    if (liveNow && title != null) {
                        games[id] = title.removePrefix("AO VIVO").trimStart(':', ' ').trim()
                    }
                }
            }
        }
        return games.map { Game(it.key, it.value) }
    }

    /**
     * HLS master-playlist URL for a live video. Retries because the watch page
     * intermittently returns without streamingData (asks to reload). On failure
     * the result carries the YouTube playabilityStatus reason, so the UI can show
     * WHY (bot check vs. reload vs. unavailable) instead of a generic error.
     */
    fun resolveHls(videoId: String, attempts: Int = 6): HlsResult {
        val hlsRe = Regex("\"hlsManifestUrl\":\"(.*?)\"")
        var lastDiag = "sem resposta"
        repeat(attempts) { i ->
            val html = try {
                httpGet("https://www.youtube.com/watch?v=$videoId")
            } catch (e: Exception) {
                lastDiag = "rede: ${e.message}"
                if (i < attempts - 1) Thread.sleep(1000)
                return@repeat
            }
            hlsRe.find(html)?.let { return HlsResult(unescape(it.groupValues[1])) }
            lastDiag = playabilityDiag(html)
            if (i < attempts - 1) Thread.sleep(1000)
        }
        return HlsResult(null, lastDiag)
    }

    /** Short "status=X · reason" pulled from the watch page's playabilityStatus. */
    private fun playabilityDiag(html: String): String {
        val status = Regex("\"playabilityStatus\":\\{\"status\":\"([^\"]+)\"")
            .find(html)?.groupValues?.get(1) ?: "?"
        val reason = Regex("\"reason\":\"([^\"]{1,90})\"").find(html)?.groupValues?.get(1)
            ?: Regex("\"reason\":\\{\"simpleText\":\"([^\"]{1,90})\"").find(html)?.groupValues?.get(1)
            ?: Regex("\"reason\":\\{\"runs\":\\[\\{\"text\":\"([^\"]{1,90})\"").find(html)?.groupValues?.get(1)
        return "status=$status" + (reason?.let { " · $it" } ?: "")
    }

    /** The channel's primary current live videoId (fallback when listing fails). */
    fun resolvePrimaryLive(): String? {
        val html = httpGet("https://www.youtube.com/$CHANNEL/live")
        return Regex("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([\\w-]{11})\">")
            .find(html)?.groupValues?.get(1)
    }

    // --- Internals ----------------------------------------------------------

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
            // Consent cookie: without it the page is a consent interstitial with
            // no player response. hl/gl pin the locale so the "AO VIVO" badge is
            // deterministic.
            setRequestProperty("Cookie", "PREF=hl=pt&gl=BR; SOCS=CAI")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        conn.inputStream.use { raw ->
            val stream = if (conn.contentEncoding?.contains("gzip") == true) GZIPInputStream(raw) else raw
            return stream.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    /** Every string in a JSON subtree (badges, titles, labels). */
    private fun collectStrings(node: JSONObject): List<String> {
        val out = ArrayList<String>()
        walkStrings(node) { out.add(it) }
        return out
    }

    /**
     * Extract the JSON object assigned to `<name> = { ... }` in the page, using
     * brace balancing (a non-greedy regex breaks on the many nested braces).
     */
    private fun extractAssignedJson(html: String, name: String): String? {
        val assign = Regex(Regex.escape(name) + "\\s*=\\s*\\{").find(html) ?: return null
        val start = html.indexOf('{', assign.range.first)
        return if (start >= 0) balancedObject(html, start) else null
    }

    private fun balancedObject(s: String, start: Int): String? {
        var depth = 0
        var inStr = false
        var esc = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    private fun walkObjects(node: Any?, visit: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                visit(node)
                val keys = node.keys()
                while (keys.hasNext()) walkObjects(node.opt(keys.next()), visit)
            }
            is JSONArray -> for (i in 0 until node.length()) walkObjects(node.opt(i), visit)
        }
    }

    private fun walkStrings(node: Any?, visit: (String) -> Unit) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) walkStrings(node.opt(keys.next()), visit)
            }
            is JSONArray -> for (i in 0 until node.length()) walkStrings(node.opt(i), visit)
            is String -> visit(node)
        }
    }

    private fun unescape(s: String): String =
        s.replace("\\u0026", "&").replace("\\/", "/")
}
