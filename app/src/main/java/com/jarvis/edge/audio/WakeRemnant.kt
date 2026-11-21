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

    fun clean(text: String): String {
        var t = text
        val stripped = WAKE_PREFIX.replace(t, "")
        if (stripped.isNotBlank() && stripped.length < t.length) t = stripped
        if (CLIPPED_WHATS.containsMatchIn(t)) {
            val upper = t.trim().let { it.length > 2 && it.substring(2).uppercase() == it.substring(2) }
            t = CLIPPED_WHATS.replace(t, if (upper) "WHAT'S" else "What's")
        }
        return t.trim()
    }
}
