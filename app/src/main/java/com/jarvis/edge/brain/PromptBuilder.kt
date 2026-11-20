package com.jarvis.edge.brain

import com.jarvis.edge.tools.Tool

data class Turn(
    val user: String,
    val assistant: String,
)

class PromptBuilder @JvmOverloads constructor(
    private val tools: List<Tool>,
    private val templateName: String = "chatml",
    private val includeFewShot: Boolean = true,
) {
    fun system(): String {
        val body = buildString {
            append(SYSTEM_INSTRUCTION)
            if (tools.isNotEmpty()) {
                append("\nTools: ")
                append(
                    tools.joinToString(", ") { tool ->
                        val argsSig = tool.params.entries.joinToString("|") { (k, allowed) ->
                            if (allowed != null) "$k:${allowed.joinToString("/")}" else k
                        }
                        "${tool.name}($argsSig)"
                    },
                )
            }
        }.trimEnd()

        return when (templateName.lowercase()) {
            "llama3" -> "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$body<|eot_id|>\n"
            "gemma" -> "<bos><start_of_turn>system\n$body\n<end_of_turn>\n"
            else -> "<|im_start|>system\n$body\n<|im_end|>\n"
        }
    }

    fun prompt(history: List<Turn>, user: String): String =
        prompt(
            history = history,
            user = user,
            selfStateLine = null,
            memoryContext = null,
            extraNudge = null,
            maxHistoryChars = Int.MAX_VALUE,
        )

    fun prompt(
        history: List<Turn>,
        user: String,
        selfStateLine: String? = null,
        memoryContext: String? = null,
        extraNudge: String? = null,
        maxHistoryChars: Int = 1200,
    ): String = buildString {
        append(system())
        if (includeFewShot) {
            appendFewShots(this)
        }
        val selectedHistory = selectHistory(history, maxHistoryChars)
        for (turn in selectedHistory) {
            appendUserTurn(this, turn.user)
            appendAssistantTurn(this, turn.assistant)
        }
        val userPayload = buildString {
            if (!selfStateLine.isNullOrBlank()) {
                append("[Self: ").append(selfStateLine.trim()).append("]\n")
            }
            if (!memoryContext.isNullOrBlank()) {
                append("[Memory: ").append(memoryContext.trim()).append("]\n")
            }
            if (!extraNudge.isNullOrBlank()) {
                append("[Rule: ").append(extraNudge.trim()).append("]\n")
            }
            append(user)
        }
        appendUserTurn(this, userPayload)
        appendAssistantPrefix(this)
    }

    fun grammar(): String = buildString {
        if (tools.isEmpty()) {
            appendLine("root ::= say")
        } else {
            appendLine("root ::= say | toolcall")
            append("toolcall ::= ")
            appendLine(tools.indices.joinToString(" | ") { "tool-$it" })
            tools.forEachIndexed { index, tool ->
                append("tool-$index ::= \"{\\\"tool\\\":\\\"${escapeGbnfLiteral(tool.name)}\\\",\\\"args\\\":{")
                val entries = tool.params.entries.toList()
                entries.forEachIndexed { paramIdx, (paramName, allowed) ->
                    if (paramIdx > 0) append(",")
                    append("\\\"${escapeGbnfLiteral(paramName)}\\\":\"")
                    if (allowed != null && allowed.isNotEmpty()) {
                        append(" (")
                        append(
                            allowed.joinToString(" | ") { v ->
                                "\"\\\"${escapeGbnfLiteral(v)}\\\"\""
                            },
                        )
                        append(") \"")
                    } else {
                        append(" arg-string \"")
                    }
                }
                appendLine("}}\"")
            }
            appendLine("""arg-string ::= "\"" [^"\\\x7F\x00-\x1F]* "\"" """)
        }
        appendLine("""say ::= "SAY: " [^\r\n]+""")
    }.trimEnd()

    fun sayOnlyGrammar(): String = """
        root ::= say
        say ::= "SAY: " [^\r\n]+
    """.trimIndent()

    private fun selectHistory(history: List<Turn>, maxHistoryChars: Int): List<Turn> {
        val recent = history.takeLast(MAX_HISTORY_TURNS)
        if (maxHistoryChars == Int.MAX_VALUE) return recent
        if (maxHistoryChars <= 0 || recent.isEmpty()) return emptyList()
        val chosen = ArrayList<Turn>(recent.size)
        var usedChars = 0
        for (turn in recent.asReversed()) {
            val chars = turn.user.length + turn.assistant.length
            if (usedChars + chars > maxHistoryChars) break
            chosen.add(turn)
            usedChars += chars
        }
        chosen.reverse()
        return chosen
    }

    private fun appendFewShots(sb: StringBuilder) {
        appendUserTurn(sb, "Hi")
        appendAssistantTurn(sb, "SAY: Hello, how can I help?")
        appendUserTurn(sb, "2+2?")
        appendAssistantTurn(sb, "SAY: 4.")
    }

    private fun appendUserTurn(sb: StringBuilder, text: String) {
        when (templateName.lowercase()) {
            "llama3" -> sb.append("<|start_header_id|>user<|end_header_id|>\n\n$text<|eot_id|>\n")
            "gemma" -> sb.append("<start_of_turn>user\n$text<end_of_turn>\n")
            else -> sb.append("<|im_start|>user\n$text<|im_end|>\n")
        }
    }

    private fun appendAssistantTurn(sb: StringBuilder, text: String) {
        when (templateName.lowercase()) {
            "llama3" -> sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n$text<|eot_id|>\n")
            "gemma" -> sb.append("<start_of_turn>model\n$text<end_of_turn>\n")
            else -> sb.append("<|im_start|>assistant\n$text<|im_end|>\n")
        }
    }

    private fun appendAssistantPrefix(sb: StringBuilder) {
        when (templateName.lowercase()) {
            "llama3" -> sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            "gemma" -> sb.append("<start_of_turn>model\n")
            else -> sb.append("<|im_start|>assistant\n")
        }
    }

    private fun escapeGbnfLiteral(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    companion object {
        const val MAX_HISTORY_TURNS = 3
        private const val SYSTEM_INSTRUCTION =
            "You are Jarvis, a concise voice assistant. Answer directly in 1-2 sentences as 'SAY: <answer>' OR call a tool JSON. NEVER repeat or parrot the user's question. Use web_search if unsure."
    }
}
