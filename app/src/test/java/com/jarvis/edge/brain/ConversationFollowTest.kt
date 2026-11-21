package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConversationFollowTest {

    @Test
    fun taughtFactStripsLeadInAndKeepsCasing() {
        assertEquals(
            TaughtFact("Rao Ghani", "sister", "Priyanka"),
            ConversationFollow.taughtFact("As I know, Rao Ghani's sister name is Priyanka.")
        )
        assertEquals(
            TaughtFact("Elon Musk", "company", "Tesla"),
            ConversationFollow.taughtFact("Actually, Elon Musk's company is Tesla.")
        )
    }

    @Test
    fun taughtFactHandlesSistersNameForm() {
        assertEquals(
            TaughtFact("Rahul Gandhi", "sister", "Priyanka Gandhi"),
            ConversationFollow.taughtFact("Rahul Gandhi's sister's name is Priyanka Gandhi")
        )
    }

    @Test
    fun taughtFactStatementFormat() {
        val fact = TaughtFact("Elon Musk", "company", "Tesla")
        assertEquals("Elon Musk's company is Tesla", fact.statement())
    }

    @Test
    fun taughtFactRejectsPossessiveSubjectAndQuestions() {
        assertNull(ConversationFollow.taughtFact("My age is 41"))
        assertNull(ConversationFollow.taughtFact("What is Rahul Gandhi's sister's name?"))
    }

    @Test
    fun entityFixReplacesMishearedName() {
        assertEquals(
            EntityFix("Raul Garnish", "Rahul Gandhi", "Who is Rahul Gandhi sister?"),
            ConversationFollow.entityFix("It is Rahul Gandhi's.", "Who is Raul Garnish sister?")
        )
    }

    @Test
    fun entityFixKeepsPossessiveOutsideSpan() {
        assertEquals(
            EntityFix("Rao Ghani", "Rahul Gandhi", "As I know, Rahul Gandhi's sister name is Priyanka."),
            ConversationFollow.entityFix("I said Rahul Gandhi", "As I know, Rao Ghani's sister name is Priyanka.")
        )
    }

    @Test
    fun entityFixNullWithoutCapitalizedCandidate() {
        assertNull(ConversationFollow.entityFix("It is raining today", "What is the weather?"))
    }

    @Test
    fun entityFixNullWithoutPreviousText() {
        assertNull(ConversationFollow.entityFix("it is Rahul Gandhi", null))
        assertNull(ConversationFollow.entityFix("it is Rahul Gandhi", "   "))
    }

    @Test
    fun entityFixNullForLongText() {
        assertNull(
            ConversationFollow.entityFix(
                "it is Rahul Gandhi and his other sister too",
                "Who is Raul Garnish sister?"
            )
        )
    }

    @Test
    fun entityFixAllUppercasePreviousText() {
        assertEquals(
            EntityFix("RAUL GARNISH", "Rahul Gandhi", "WHO IS Rahul Gandhi SISTER"),
            ConversationFollow.entityFix("I meant Rahul Gandhi", "WHO IS RAUL GARNISH SISTER")
        )
    }
}
