package com.jarvis.edge.tools

import com.jarvis.edge.brain.ToolCall

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }
    private val ordered = tools.toList()

    fun all(): List<Tool> = ordered

    suspend fun run(call: ToolCall): ToolResult {
        val tool = byName[call.name]
            ?: return ToolResult(spoken = "I can't do that yet.", ok = false)

        for ((paramName, allowedValues) in tool.params) {
            if (allowedValues != null) {
                val actual = call.args[paramName]
                if (actual == null || actual !in allowedValues) {
                    return ToolResult(
                        spoken = "I can't set $paramName to ${actual ?: "nothing"}.",
                        ok = false,
                    )
                }
            }
        }
        return tool.run(call.args)
    }
}
