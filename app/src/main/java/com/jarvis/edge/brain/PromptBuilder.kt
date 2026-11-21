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
            val phoneLine = selfStateLine?.trim()?.replace(SELF_PREFIX_REGEX, "")?.trim()
            if (!phoneLine.isNullOrBlank()) {
                append("[Phone status: ").append(phoneLine).append("]\n")
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
        // Window = the turns since the last multiple of MAX_HISTORY_TURNS (1, 2, 3, 1, 2, 3 ...): its start only moves
        // every third turn, so consecutive prompts share their history prefix and the KV cache is reused.
        val window = if (history.isEmpty()) 0 else ((history.size - 1) % MAX_HISTORY_TURNS) + 1
        val recent = history.takeLast(window)
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
        // An unclear transcript gets a request to repeat, not a made-up answer (the old "2+2?" example was copied
        // into replies to garbled speech).
        appendUserTurn(sb, "Ulin")
        appendAssistantTurn(sb, "SAY: Sorry, I didn't catch that. Could you say it again?")
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
        private val SELF_PREFIX_REGEX = Regex("^self:\\s*", RegexOption.IGNORE_CASE)
        private const val SYSTEM_INSTRUCTION =
            "You are Jarvis, a concise voice assistant. Answer directly in 1-2 sentences as 'SAY: <answer>' OR call a tool JSON. NEVER repeat or parrot the user's question. Use web_search if unsure."
    }
}
