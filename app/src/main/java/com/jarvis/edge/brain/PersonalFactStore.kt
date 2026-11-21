package com.jarvis.edge.brain

/** Exact, attribute-keyed facts about the user ("age" -> "41"), answered without the LLM. */
interface PersonalFactStore {
    fun remember(attribute: String, value: String)
    fun recall(attribute: String): String?
}
