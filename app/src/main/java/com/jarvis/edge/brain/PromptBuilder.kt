package com.jarvis.edge.brain

import com.jarvis.edge.tools.Tool

data class Turn(
    val user: String,
    val assistant: String,
)

class PromptBuilder(
    private val tools: List<Tool>,
    private val templateName: String = "chatml",
) {
    fun system(): String {
        val body = buildString {
            append("You are Jarvis, a concise voice assistant. Reply with 'SAY: <short answer>' or JSON tool call. Use web_search for unknown facts.")
            if (tools.isNotEmpty()) {
                append("\nTools:\n")
                for (tool in tools) {
                    val argsSig = tool.params.entries.joinToString("|") { (k, allowed) ->
                        if (allowed != null) "$k:${allowed.joinToString("/")}" else k
                    }
                    append("${tool.name}($argsSig): ${tool.description}\n")
                }
            }
        }.trimEnd()

        return when (templateName.lowercase()) {
            "llama3" -> "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$body<|eot_id|>\n"
            else -> "<|im_start|>system\n$body\n<|im_end|>\n"
        }
    }

    fun prompt(history: List<Turn>, user: String): String = buildString {
        append(system())
        for (turn in history.takeLast(MAX_HISTORY_TURNS)) {
            appendUserTurn(this, turn.user)
            appendAssistantTurn(this, turn.assistant)
        }
        appendUserTurn(this, user)
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
                            }
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

    private fun appendUserTurn(sb: StringBuilder, text: String) {
        when (templateName.lowercase()) {
            "llama3" -> sb.append("<|start_header_id|>user<|end_header_id|>\n\n$text<|eot_id|>\n")
            else -> sb.append("<|im_start|>user\n$text<|im_end|>\n")
        }
    }

    private fun appendAssistantTurn(sb: StringBuilder, text: String) {
        when (templateName.lowercase()) {
            "llama3" -> sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n$text<|eot_id|>\n")
            else -> sb.append("<|im_start|>assistant\n$text<|im_end|>\n")
        }
    }

    private fun appendAssistantPrefix(sb: StringBuilder) {
        when (templateName.lowercase()) {
            "llama3" -> sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            else -> sb.append("<|im_start|>assistant\n")
        }
    }

    private fun escapeGbnfLiteral(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    companion object {
        const val MAX_HISTORY_TURNS = 3
    }
}
