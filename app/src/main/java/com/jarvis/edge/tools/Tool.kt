package com.jarvis.edge.tools

data class ToolResult(
    val spoken: String,
    val ok: Boolean,
    val feedToModel: String? = null,
)

interface Tool {
    val name: String
    val description: String
    val params: Map<String, List<String>?>
    suspend fun run(args: Map<String, String>): ToolResult
}
