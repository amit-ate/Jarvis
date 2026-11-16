package com.jarvis.edge.brain

import org.json.JSONObject

data class ToolCall(
    val name: String,
    val args: Map<String, String>,
)

sealed interface ModelOutput {
    data class Say(val text: String) : ModelOutput
    data class Call(val call: ToolCall) : ModelOutput
}

fun parseModelOutput(raw: String): ModelOutput {
    val trimmed = raw.trim()
    if (trimmed.startsWith("SAY:", ignoreCase = true)) {
        return ModelOutput.Say(trimmed.substring(4).trim())
    }
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        val parsed = runCatching {
            val obj = JSONObject(trimmed)
            val tool = obj.optString("tool", "").trim()
            if (tool.isEmpty()) return@runCatching null
            val argsObj = obj.optJSONObject("args")
            val args = LinkedHashMap<String, String>()
            if (argsObj != null) {
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    args[key] = argsObj.opt(key)?.toString() ?: ""
                }
            }
            ModelOutput.Call(ToolCall(tool, args))
        }.getOrNull()
        if (parsed != null) return parsed
    }
    return ModelOutput.Say(trimmed)
}
