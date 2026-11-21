package com.jarvis.edge.brain

/**
 * Intent checks on the raw user text. Used to decide whether the phone-status line
 * is relevant to a turn and whether a question about the user has no stored answer.
 */
object PersonalQuery {

    private val DEVICE_REGEX = Regex(
        """\b(battery|charge|charging|temperature|temp|hot|heat|ram|memory usage|storage|cpu|phone status|device)\b""" +
            """|\bhow are you(?: (?:feeling|doing))?\b|\byour (?:health|status)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Question or recall forms only: "what's my age", "tell me my name". Statements never match. */
    private val ATTRIBUTE_REGEX = Regex(
        """(?:what'?s|what is|what are|what|tell me|do you know|remember) my ([a-z]+(?: [a-z]+)?)""",
    )

    /** True when the user asks about the phone or assistant state (battery, temperature, memory, ...). */
    fun isAboutDevice(text: String): Boolean = DEVICE_REGEX.containsMatchIn(text)

    /** The attribute the user asks about themselves, e.g. "age" or "favourite colour"; null otherwise. */
    fun askedAttribute(text: String): String? {
        val s = normalize(text)
        if (s == "how old am i") return "age"
        if (s == "who am i") return "name"
        return ATTRIBUTE_REGEX.matchEntire(s)?.groupValues?.get(1)
    }

    private fun normalize(text: String): String = text
        .lowercase()
        .replace('’', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('?', '.', '!', ' ')
        .trim()
}
