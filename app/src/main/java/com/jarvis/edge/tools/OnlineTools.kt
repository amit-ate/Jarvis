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
        }.getOrNull()
        val ddgAnswer = ddgRaw?.let { raw ->
            runCatching {
                val obj = JSONObject(raw)
                obj.optString("Answer").ifBlank { obj.optString("AbstractText") }.trim()
            }.getOrNull()
        }.orEmpty()
        if (ddgAnswer.isNotEmpty()) {
            return@withContext ToolResult(summarizeForSpeech(ddgAnswer), ok = true)
        }

        val wikiSearchRaw = runCatching {
            fetcher("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json&srlimit=1")
        }.getOrNull()
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
            }.getOrNull()
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
