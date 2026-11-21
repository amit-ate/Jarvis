package com.jarvis.edge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ChatLogTest {
    @Test
    fun `reply sentences of one turn become one message`() {
        val log = ChatLog(null)
        log.addUser("Tell me a joke")
        log.addReplyChunk("Why don't scientists trust atoms?")
        log.addReplyChunk("Because they make up everything!")
        log.addUser("Another one", typed = true)
        log.addReplyChunk("Sure.")
        val m = log.messages.value
        assertEquals(4, m.size)
        assertEquals("Why don't scientists trust atoms? Because they make up everything!", m[1].text)
        assertTrue(m[2].fromUser && m[2].typed)
        assertEquals("Sure.", m[3].text)
    }

    @Test
    fun `history survives a restart and clear removes it`(@TempDir dir: File) {
        val f = File(dir, "chat.jsonl")
        ChatLog(f).apply { addUser("Hi"); addReplyChunk("Hello, how can I help?") }
        val reloaded = ChatLog(f)
        assertEquals(listOf("Hi", "Hello, how can I help?"), reloaded.messages.value.map { it.text })
        reloaded.addUser("Again")
        assertEquals(3L, reloaded.messages.value.last().id)
        reloaded.clear()
        assertEquals(0, ChatLog(f).messages.value.size)
    }

    @Test
    fun `keeps only the newest messages`() {
        val log = ChatLog(null, maxMessages = 3)
        repeat(5) { log.addUser("q$it") }
        assertEquals(listOf("q2", "q3", "q4"), log.messages.value.map { it.text })
    }

    @Test
    fun restoreUndoesAClearAndKeepsNewerMessages() {
        val log = ChatLog(null)
        log.addUser("one")
        log.addReplyChunk("first answer")
        val before = log.messages.value
        log.clear()
        log.addUser("two")
        log.restore(before)
        org.junit.jupiter.api.Assertions.assertEquals(listOf("one", "first answer", "two"), log.messages.value.map { it.text })
        org.junit.jupiter.api.Assertions.assertEquals(3, log.messages.value.map { it.id }.toSet().size)
    }

    @Test
    fun clearedHistoryCanBeRestoredFromTheBackupFile(@TempDir dir: File) {
        val f = File(dir, "chat.jsonl")
        val log = ChatLog(f)
        log.addUser("hello")
        log.addReplyChunk("hi there")
        log.clear()
        assertTrue(log.hasBackup())
        log.addUser("after")
        // A fresh instance (as after an app restart) still finds the backup.
        val again = ChatLog(f)
        assertTrue(again.restoreBackup())
        assertEquals(listOf("hello", "hi there", "after"), again.messages.value.map { it.text })
        assertEquals(listOf(1L, 2L, 3L), again.messages.value.map { it.id })
        assertTrue(!again.hasBackup())
        assertEquals(3, ChatLog(f).messages.value.size)
    }
}
