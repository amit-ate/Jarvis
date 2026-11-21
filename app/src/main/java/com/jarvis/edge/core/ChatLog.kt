package com.jarvis.edge.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** One line of the conversation, as shown on the chat screen. */
data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val timeMs: Long,
    /** True when the user typed it (text mode) rather than said it. */
    val typed: Boolean = false,
)

/**
 * The conversation shown on the chat screen: what the user said or typed and what Jarvis answered, kept across
 * restarts in a small JSON-lines file. A reply spoken as several sentences becomes one message.
 */
class ChatLog(
    private val file: File?,
    private val maxMessages: Int = 300,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private var nextId = 1L
    /** Set when the user speaks or types: the next reply chunk starts a new Jarvis message. */
    private var replyOpen = false

    init {
        val loaded = file?.takeIf { it.isFile }?.let { f ->
            runCatching { f.readLines().mapNotNull(::parse) }.getOrDefault(emptyList())
        }.orEmpty().takeLast(maxMessages)
        nextId = (loaded.maxOfOrNull { it.id } ?: 0L) + 1
        _messages.value = loaded
    }

    fun addUser(text: String, typed: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        synchronized(lock) {
            replyOpen = false
            append(ChatMessage(nextId++, fromUser = true, text = t, timeMs = clock(), typed = typed))
        }
    }

    /** Adds a sentence of Jarvis's reply, joining it to the reply already being shown for this turn. */
    fun addReplyChunk(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        synchronized(lock) {
            val list = _messages.value
            val last = list.lastOrNull()
            if (replyOpen && last != null && !last.fromUser) {
                val merged = last.copy(text = last.text + " " + t)
                _messages.value = list.dropLast(1) + merged
                save()
            } else {
                append(ChatMessage(nextId++, fromUser = false, text = t, timeMs = clock()))
                replyOpen = true
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            _messages.value = emptyList()
            replyOpen = false
            runCatching { file?.delete() }
        }
    }

    private fun append(m: ChatMessage) {
        _messages.value = (_messages.value + m).takeLast(maxMessages)
        save()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(_messages.value.joinToString("\n") { toJson(it) } + "\n")
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private fun toJson(m: ChatMessage): String = JSONObject()
        .put("id", m.id).put("user", m.fromUser).put("text", m.text).put("t", m.timeMs).put("typed", m.typed)
        .toString()

    private fun parse(line: String): ChatMessage? {
        if (line.isBlank()) return null
        return runCatching {
            val o = JSONObject(line)
            ChatMessage(o.getLong("id"), o.getBoolean("user"), o.getString("text"), o.getLong("t"), o.optBoolean("typed"))
        }.getOrNull()
    }
}
