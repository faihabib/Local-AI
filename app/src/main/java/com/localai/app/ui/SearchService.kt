package com.localai.app.ui

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** A single search result. */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

/** Result of a search attempt. */
data class SearchResponse(
    val results: List<SearchResult>,
    val source: String,     // "DuckDuckGo" or "Tavily"
    val error: String? = null
)

/**
 * Free web search aggregator.
 *
 * Order:
 *   1. DuckDuckGo HTML scraper (no API key, real search results)
 *   2. Tavily keyless (no API key, official JSON API)
 *
 * No API keys required for either. If a backend fails or returns no results,
 * the next one is tried automatically.
 */
object SearchService {

    private const val TAG = "SearchService"

    suspend fun search(query: String): SearchResponse = withContext(Dispatchers.IO) {
        // 1. Tavily keyless — primary (reliable, official JSON API)
        try {
            val tavily = searchTavily(query)
            if (tavily.results.isNotEmpty()) return@withContext tavily
            Log.i(TAG, "Tavily returned 0 results, trying DuckDuckGo")
        } catch (t: Throwable) {
            Log.w(TAG, "Tavily failed: ${t.message}")
        }

        // 2. DuckDuckGo Lite — fallback
        try {
            val ddg = searchDuckDuckGo(query)
            if (ddg.results.isNotEmpty()) return@withContext ddg
        } catch (t: Throwable) {
            Log.w(TAG, "DuckDuckGo failed: ${t.message}")
        }

        SearchResponse(emptyList(), "none", "No search results found")
    }

    // ============================================================
    // DuckDuckGo Lite scraper
    // ============================================================
    private fun searchDuckDuckGo(query: String): SearchResponse {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://lite.duckduckgo.com/lite/?q=$encoded"

        val html = httpGet(url, mapOf(
            "User-Agent" to
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Safari/537.36",
            "Referer" to "https://lite.duckduckgo.com/",
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "en-US,en;q=0.9"
        )) ?: return SearchResponse(emptyList(), "DuckDuckGo")

        // Lite layout: <a class="result-link">Title</a> + <td class="result-snippet">...</td>
        val linkPattern = Regex(
            "<a[^>]*class=[\"']result-link[\"'][^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val snippetPattern = Regex(
            "<td[^>]*class=[\"']result-snippet[\"'][^>]*>(.*?)</td>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        val linkMatches = linkPattern.findAll(html).toList()
        val snippetMatches = snippetPattern.findAll(html).toList()

        val results = mutableListOf<SearchResult>()
        for ((i, m) in linkMatches.withIndex()) {
            if (results.size >= 5) break
            // The href is captured from the anchor tag — it appears before class in lite layout
            val anchorTag = m.value
            val hrefMatch = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(anchorTag)
            val realUrl = hrefMatch?.groupValues?.get(1)?.let { decodeDdgRedirect(it) } ?: ""
            val title = stripHtml(m.groupValues[1]).trim()
            val snippet = if (i < snippetMatches.size)
                stripHtml(snippetMatches[i].groupValues[1]).trim() else ""
            if (title.isNotEmpty()) {
                results.add(SearchResult(title, realUrl, snippet))
            }
        }
        Log.i(TAG, "DuckDuckGo Lite parsed ${results.size} results for: $query")
        return SearchResponse(results, "DuckDuckGo")
    }


    /** DDG wraps outbound links in a redirect: //duckduckgo.com/l/?uddg=<encoded>&rut=... */
    private fun decodeDdgRedirect(href: String): String {
        return try {
            val marker = "uddg="
            val idx = href.indexOf(marker)
            if (idx < 0) return href
            val after = href.substring(idx + marker.length)
            val amp = after.indexOf('&')
            val encoded = if (amp >= 0) after.substring(0, amp) else after
            URLDecoder.decode(encoded, "UTF-8")
        } catch (t: Throwable) {
            href
        }
    }

    private fun stripHtml(s: String): String {
        return s.replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }

    // ============================================================
    // Tavily keyless
    // ============================================================
    private fun searchTavily(query: String): SearchResponse {
        val url = "https://api.tavily.com/search"
        val body = JSONObject().apply {
            put("query", query)
            put("max_results", 5)
            put("search_depth", "basic")
            put("include_answer", false)
            put("topic", "general")
            put("days", 30)
        }.toString()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Tavily-Access-Mode", "keyless")
        }

        try {
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Tavily HTTP $code")
                return SearchResponse(emptyList(), "Tavily")
            }

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("results") ?: return SearchResponse(emptyList(), "Tavily")

            val results = mutableListOf<SearchResult>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                results.add(SearchResult(
                    title = item.optString("title", ""),
                    url = item.optString("url", ""),
                    snippet = item.optString("content", "")
                ))
            }
            Log.i(TAG, "Tavily parsed ${results.size} results for: $query")
            return SearchResponse(results, "Tavily")
        } finally {
            conn.disconnect()
        }
    }

    // ============================================================
    // HTTP helper
    // ============================================================
    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "LocalAI/1.0 (Android)")
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Formats results into a compact text block for the prompt. */
    fun formatForPrompt(query: String, response: SearchResponse): String {
        if (response.results.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("[Web search results for: \"$query\"]")
        sb.appendLine("Source: ${response.source}")
        sb.appendLine()
        response.results.take(5).forEachIndexed { i, r ->
            sb.appendLine("${i + 1}. ${r.title}")
            if (r.url.isNotEmpty()) sb.appendLine("   URL: ${r.url}")
            sb.appendLine("   ${r.snippet}")
            sb.appendLine()
        }
        sb.appendLine("[CRITICAL: Use ONLY the search results above to answer. " +
            "Do NOT rely on your training data or knowledge cutoff. " +
            "If the answer is in the results, state it directly with the source URL. " +
            "If the results don't contain the answer, say so explicitly.]")
        return sb.toString()
    }
}
