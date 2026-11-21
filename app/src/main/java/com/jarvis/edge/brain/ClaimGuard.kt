package com.jarvis.edge.brain

/**
 * Catches chat replies that claim tools, abilities or actions the app does not
 * have. Pure Kotlin: no Android types, no I/O.
 */
object ClaimGuard {

    /** snake_case identifiers, e.g. get_weather, update_knowledge */
    private val SNAKE_IDENT = Regex("\\b[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+\\b")

    /** name( tokens, e.g. web_search(...) or sqrt(2); captures the name */
    private val CALL_IDENT = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\(")

    /** Claims of an ongoing or finished action, first person. */
    private val ACTION_CLAIMS = listOf(
        Regex("\\bI(?:'m| am)\\s+(?:(?:still|currently)\\s+)?(?:running|processing|analyzing|updating|searching|downloading|installing)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bI(?:'m| am)\\s+(?:still|currently)\\s+learning\\b", RegexOption.IGNORE_CASE),
        Regex("\\bI(?:'ve| have)\\s+(?:(?:just|now|already)\\s+)?(?:updated|saved|changed|set|sent|turned|deleted|installed)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bI(?:'ll| will)\\s+remember\\b", RegexOption.IGNORE_CASE),
    )

    /** Promises to report back later. */
    private val LATER_REPORT_PROMISES = listOf(
        Regex("\\bI(?:'ll| will)\\s+(?:let you know|notify you|get back to you)\\b", RegexOption.IGNORE_CASE),
    )

    /**
     * Why [reply] makes a claim the assistant cannot back up, or null if it is fine.
     * [toolNames]: the real tool names; [toolRan]: whether a tool actually ran this turn.
     */
    fun problem(reply: String, toolNames: Set<String>, toolRan: Boolean): String? {
        // Normalise curly apostrophes so the straight-quote patterns match.
        val text = reply.replace('\u2019', '\'').replace('\u2018', '\'')

        // a) Tool names must never be spoken.
        val idents = (SNAKE_IDENT.findAll(text).map { it.value } +
            CALL_IDENT.findAll(text).map { it.groupValues[1] }).toSet()
        if (idents.any { it !in toolNames }) return "names a tool that does not exist"
        if (idents.isNotEmpty()) return "speaks a tool name"

        // b) Without a tool run, no claimed action and no promise of a later report.
        if (!toolRan) {
            if (ACTION_CLAIMS.any { it.containsMatchIn(text) }) return "claims an action that did not happen"
            if (LATER_REPORT_PROMISES.any { it.containsMatchIn(text) }) return "promises a report that cannot happen"
        }
        return null
    }
}
