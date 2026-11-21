package com.jarvis.edge.mind

import com.jarvis.edge.brain.PersonalFactStore

/**
 * Stores personal facts as ordinary journaled facts ("User's age is 41"), so they show on the Mind screen,
 * feed embedding recall, and can be undone. A new value for the same attribute replaces the old one.
 */
class PersonalFacts(
    private val store: MindRepository,
    private val embed: (String) -> FloatArray,
) : PersonalFactStore {

    override fun remember(attribute: String, value: String) {
        val prefix = prefixFor(attribute)
        store.listActiveFacts(500).filter { it.text.startsWith(prefix, ignoreCase = true) }.forEach { store.deleteFact(it.id) }
        val text = "$prefix$value"
        store.learnFact(text, embed(text), sourceEpisodeId = null, confidence = 1.0f)
    }

    override fun recall(attribute: String): String? {
        val prefix = prefixFor(attribute)
        return store.listActiveFacts(500).firstOrNull { it.text.startsWith(prefix, ignoreCase = true) }
            ?.text?.substring(prefix.length)?.trim()?.ifEmpty { null }
    }

    override fun rememberStatement(statement: String) {
        val text = statement.trim().trimEnd('.')
        if (text.isEmpty()) return
        forgetStatement(text)
        store.learnFact(text, embed(text), sourceEpisodeId = null, confidence = 0.9f)
    }

    override fun forgetStatement(statement: String) {
        val text = statement.trim().trimEnd('.')
        store.listActiveFacts(500).filter { it.text.trim().trimEnd('.').equals(text, ignoreCase = true) }
            .forEach { store.deleteFact(it.id) }
    }

    private fun prefixFor(attribute: String) = "User's ${attribute.lowercase().trim()} is "
}
