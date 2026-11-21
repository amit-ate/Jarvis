package com.jarvis.edge.brain

/** Exact, attribute-keyed facts about the user ("age" -> "41"), answered without the LLM. */
interface PersonalFactStore {
    fun remember(attribute: String, value: String)
    fun recall(attribute: String): String?

    /** A fact the user taught about the world ("Rahul Gandhi's sister is Priyanka"); recalled through memory search. */
    fun rememberStatement(statement: String) {}

    /** Removes a taught statement, e.g. after the user said a name in it was misheard. */
    fun forgetStatement(statement: String) {}
}
