package com.jarvis.edge.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private fun httpGetText(urlStr: String, userAgent: String, timeoutMs: Int = 4500): String? {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        setRequestProperty("User-Agent", userAgent)
        setRequestProperty("Accept", "application/json, text/plain")
    }
    return try {
        val code = conn.responseCode
        if (code !in 200..299) null else conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

private fun httpPostText(urlStr: String, postData: String, userAgent: String, timeoutMs: Int = 4500): String? {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        setRequestProperty("User-Agent", userAgent)
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    }
    return try {
        conn.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) null else conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

class GetWeatherTool(
    private val fetcher: (String) -> String? = { url -> httpGetText(url, "curl/8.0") },
) : Tool {
    override val name = "get_weather"
    override val description = "Live weather"
    override val params = mapOf("location" to null)

    override suspend fun run(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val rawLoc = args["location"]?.trim().orEmpty()
        val loc = if (rawLoc.lowercase() in setOf("", "here", "current", "local", "outside", "today")) {
            ""
        } else {
            rawLoc
        }
        val pathPart = if (loc.isEmpty()) "" else URLEncoder.encode(loc, "UTF-8").replace("+", "%20")
        val url = "https://wttr.in/$pathPart?format=%l:+%C,+%t,+humidity+%h"

        val body = runCatching { fetcher(url) }.getOrNull()?.trim().orEmpty()
        if (body.isEmpty() || body.startsWith("<") || body.contains("Unknown location", ignoreCase = true)) {
            return@withContext ToolResult("Sorry, I couldn't fetch the weather online right now.", ok = false)
        }

        val cleaned = body
            .replace(Regex("\\+(\\d)"), "$1")
            .replace("°C", " degrees Celsius")
            .replace("°F", " degrees Fahrenheit")
            .replace("%", " percent")
            .replace(Regex("\\s+"), " ")
            .trim()
        ToolResult("Current weather in $cleaned.", ok = true)
    }
}

class WebSearchTool(
    private val fetcher: (String) -> String? = { url -> httpGetText(url, "JarvisEdge/1.0") },
    private val postFetcher: (String, String) -> String? = { url, data ->
        httpPostText(url, data, "Mozilla/5.0 (Linux; Android 9; Redmi Note 5 Pro)")
    },
) : Tool {
    override val name = "web_search"
    override val description = "Search web/Wikipedia"
    override val params = mapOf("query" to null)

    override suspend fun run(args: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val query = args["query"]?.trim().orEmpty()
            .replace(Regex("^(?:for|about|on)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        if (query.isEmpty()) {
            return@withContext ToolResult("Please say what you want me to search for.", ok = false)
        }

        val encoded = URLEncoder.encode(query, "UTF-8")
        val ddgRaw = runCatching {
            fetcher("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
        }.onFailure { System.err.println("JarvisWeb: fetch failed: $it") }.getOrNull()
        val ddgAnswer = ddgRaw?.let { raw ->
            runCatching {
                val obj = JSONObject(raw)
                obj.optString("Answer").ifBlank { obj.optString("AbstractText") }.trim()
            }.getOrNull()
        }.orEmpty()
        // DuckDuckGo Lite result snippets: question-specific text (the instant answer is often just a definition, e.g.
        // "The prime minister of India is the head of government..." without the name), handed to the model as evidence.
        val ddgLiteRaw = runCatching {
            postFetcher("https://lite.duckduckgo.com/lite/", "q=$encoded")
        }.onFailure { System.err.println("JarvisWeb: fetch failed: $it") }.getOrNull()
        val ddgLiteSnippets = ddgLiteRaw?.let { html ->
            Regex("<td class=[\"']result-snippet[\"']>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).findAll(html).take(3).map { m ->
                m.groupValues[1].replace(Regex("<.*?>"), "")
                    .replace("&#x27;", "'")
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace(Regex("\\[[0-9a-zA-Z]+\\]"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }.filter { it.length > 20 }.toList()
        }.orEmpty()
        val evidence = (ddgLiteSnippets + listOf(ddgAnswer).filter { it.isNotEmpty() }).joinToString(" | ").ifBlank { null }
        if (ddgAnswer.isNotEmpty()) {
            return@withContext ToolResult(summarizeForSpeech(ddgAnswer), ok = true, feedToModel = evidence)
        }
        if (ddgLiteSnippets.isNotEmpty()) {
            return@withContext ToolResult(summarizeForSpeech(ddgLiteSnippets.first()), ok = true, feedToModel = evidence)
        }

        val wikiSearchRaw = runCatching {
            fetcher("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json&srlimit=1")
        }.onFailure { System.err.println("JarvisWeb: fetch failed: $it") }.getOrNull()
        val topTitle = wikiSearchRaw?.let { raw ->
            runCatching {
                val arr = JSONObject(raw).getJSONObject("query").getJSONArray("search")
                if (arr.length() > 0) arr.getJSONObject(0).optString("title").trim() else ""
            }.getOrNull()
        }.orEmpty()
        if (topTitle.isNotEmpty()) {
            val titleSlug = URLEncoder.encode(topTitle.replace(' ', '_'), "UTF-8").replace("+", "%20")
            val summaryRaw = runCatching {
                fetcher("https://en.wikipedia.org/api/rest_v1/page/summary/$titleSlug")
            }.onFailure { System.err.println("JarvisWeb: fetch failed: $it") }.getOrNull()
            val extract = summaryRaw?.let { raw ->
                runCatching { JSONObject(raw).optString("extract").trim() }.getOrNull()
            }.orEmpty()
            if (extract.isNotEmpty()) {
                return@withContext ToolResult(summarizeForSpeech(extract), ok = true)
            }
        }

        ToolResult("Sorry, I couldn't find an online answer for $query.", ok = false)
    }

    private fun summarizeForSpeech(text: String): String {
        val collapsed = text.replace(Regex("\\s+"), " ").trim()
        val sentences = collapsed.split(Regex("(?<=[.!?])\\s+"))
        val two = sentences.take(2).joinToString(" ").trim()
        return if (two.length <= 260) {
            two
        } else {
            val cut = two.take(257)
            val space = cut.lastIndexOf(' ')
            (if (space > 120) cut.substring(0, space) else cut) + "..."
        }
    }
}
