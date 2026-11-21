package com.jarvis.edge.audio

/**
 * Cleans transcript artefacts at the start of a turn:
 * - the tail of the wake word that the pre-roll audio carries into the turn ("Jarvis, what's ..." / "VIS WHAT'S ...");
 * - a clipped first word, where only "'S" of "what's" survived ("'S MY BATTERY" -> "WHAT'S MY BATTERY").
 */
object WakeRemnant {
    private val WAKE_PREFIX = Regex(
        """^\s*(?:(?:hey|hi|ok|okay)[\s,]+)?(?:jarvis|jarvas|jervis|jarvi|arvis|rvis|vis)\b[\s,.!?]*""",
        RegexOption.IGNORE_CASE,
    )
    private val CLIPPED_WHATS = Regex("""^\s*'s\b""", RegexOption.IGNORE_CASE)
    /** A one- or two-letter fragment of "Jarvis" left in front of a request ("Y WHAT DO YOU KNOW ABOUT ME"). */
    private val STRAY_FRAGMENT = Regex(
        """^\s*(?!(?:i|a|oh|ok|so|no|my|is|do|go|hi|to|we|me|us|be|an|or|on|in|it|if|at|as|up)\b)[a-z]{1,2}[\s,.]+(?=(?:what|who|whom|whose|where|when|why|how|which|tell|is|are|do|does|did|can|could|will|would|set|turn|open|call|play|remind|thank|thanks|bye|goodbye|and)\b)""",
        RegexOption.IGNORE_CASE,
    )

    /** Requests the recogniser regularly mishears, fixed before anything acts on them. */
    private val COMMON_MISHEARS = listOf(
        Regex("""\btell me a (?:joe|joek|choke|jog)\b""", RegexOption.IGNORE_CASE) to "tell me a joke",
    )

    fun clean(text: String): String {
        var t = text
        val stripped = WAKE_PREFIX.replace(t, "")
        if (stripped.isNotBlank() && stripped.length < t.length) t = stripped
        t = STRAY_FRAGMENT.replace(t, "")
        if (CLIPPED_WHATS.containsMatchIn(t)) {
            val upper = t.trim().let { it.length > 2 && it.substring(2).uppercase() == it.substring(2) }
            t = CLIPPED_WHATS.replace(t, if (upper) "WHAT'S" else "What's")
        }
        for ((wrong, right) in COMMON_MISHEARS) t = wrong.replace(t, right)
        return t.trim()
    }
}
