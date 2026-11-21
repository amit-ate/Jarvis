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

    private val STATED_MY_REGEX = Regex("""^my ([a-z]+(?: [a-z]+)?) is (.{1,40})$""", RegexOption.IGNORE_CASE)
    private val STATED_AGE_REGEX = Regex("""^i(?: am|'m) (\d{1,3})(?: years? old)?$""")
    private val STATED_NAME_REGEX = Regex("""^call me (.{1,30})$""", RegexOption.IGNORE_CASE)

    /** Words that make "my X is Y" a command or device remark rather than a fact about the user. */
    private val NOT_PERSONAL = setOf("battery", "phone", "volume", "alarm", "timer", "screen", "brightness", "internet", "wifi")

    /**
     * The (attribute, value) when the user states a fact about themselves: "My age is 41." -> ("age", "41"),
     * "I'm 41" -> ("age", "41"), "call me Sam" -> ("name", "Sam"). Null for questions and commands.
     */
    fun statedAttribute(text: String): Pair<String, String>? {
        val original = text.trim().replace('’', '\'').replace(Regex("\\s+"), " ").trimEnd('?', '.', '!', ' ').trim()
        val s = original.lowercase()
        STATED_AGE_REGEX.matchEntire(s)?.let { return "age" to it.groupValues[1] }
        STATED_NAME_REGEX.matchEntire(original)?.let { return "name" to it.groupValues[1].trim() }
        val m = STATED_MY_REGEX.matchEntire(original) ?: return null
        val attr = canonicalAttribute(m.groupValues[1])
        if (attr.split(' ').any { it in NOT_PERSONAL }) return null
        val value = m.groupValues[2].trim()
        if (value.isEmpty() || value.lowercase().startsWith("what") || value.lowercase() in setOf("low", "high", "full", "dead")) return null
        return attr to value
    }

    /** One spelling per attribute so "favorite color" and "favourite colour" are the same fact. */
    fun canonicalAttribute(raw: String): String = raw.lowercase().trim()
        .replace(Regex("\\bfavorite\\b"), "favourite")
        .replace(Regex("\\bcolor\\b"), "colour")

    /** True when the user asks about the phone or assistant state (battery, temperature, memory, ...). */
    fun isAboutDevice(text: String): Boolean = DEVICE_REGEX.containsMatchIn(text)

    /** The attribute the user asks about themselves, e.g. "age" or "favourite colour"; null otherwise. */
    fun askedAttribute(text: String): String? {
        val s = normalize(text)
        if (s == "how old am i") return "age"
        if (s == "who am i") return "name"
        return ATTRIBUTE_REGEX.matchEntire(s)?.groupValues?.get(1)?.let { canonicalAttribute(it) }
    }

    private fun normalize(text: String): String = text
        .lowercase()
        .replace('’', '\'')
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('?', '.', '!', ' ')
        .trim()
}
