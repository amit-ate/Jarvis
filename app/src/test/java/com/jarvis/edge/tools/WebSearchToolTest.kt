package com.jarvis.edge.tools

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebSearchToolTest {
    private val wikiSearch = """{"query":{"search":[{"title":"2024 Men's T20 World Cup final"}]}}"""
    private val wikiIntro = """{"query":{"pages":{"123":{"extract":"The final was played between South Africa and India.\nIndia claimed the victory by 7 runs."}}}}"""

    private fun tool(snippetsHtml: String?, wiki: Boolean = true) = WebSearchTool(
        fetcher = { url ->
            when {
                "duckduckgo" in url -> """{"Answer":"","AbstractText":""}"""
                "list=search" in url -> if (wiki) wikiSearch else """{"query":{"search":[]}}"""
                "prop=extracts" in url -> wikiIntro
                else -> null
            }
        },
        postFetcher = { _, _ -> snippetsHtml },
    )

    @Test
    fun `the wikipedia intro is added to the evidence beside the snippets`() = runBlocking {
        val html = "<td class='result-snippet'>The 2024 final was played in Bridgetown, Barbados on 29 June 2024.</td>"
        val r = tool(html).run(mapOf("query" to "who won the 2024 t20 world cup final"))
        assertTrue(r.ok)
        assertEquals("The 2024 final was played in Bridgetown, Barbados on 29 June 2024.", r.spoken)
        assertTrue("India claimed the victory by 7 runs." in r.feedToModel.orEmpty(), r.feedToModel)
        assertTrue(r.feedToModel.orEmpty().startsWith("The 2024 final was played"), r.feedToModel)
    }

    @Test
    fun `wikipedia alone still answers when the snippets fail`() = runBlocking {
        val r = tool(null).run(mapOf("query" to "who won the 2024 t20 world cup final"))
        assertTrue(r.ok)
        assertTrue("India claimed the victory" in r.spoken, r.spoken)
    }

    @Test
    fun `nothing found is reported as a failure`() = runBlocking {
        val r = tool(null, wiki = false).run(mapOf("query" to "zzzz"))
        assertFalse(r.ok)
    }
}
