package com.jarvis.edge.tools

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class GetTimeTool(
    private val now: () -> LocalTime = { LocalTime.now() },
) : Tool {
    override val name = "get_time"
    override val description = "Speak the current local time"
    override val params: Map<String, List<String>?> = emptyMap()

    override suspend fun run(args: Map<String, String>): ToolResult {
        val formatted = now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        return ToolResult("It's $formatted.", ok = true)
    }
}

class GetDateTool(
    private val today: () -> LocalDate = { LocalDate.now() },
) : Tool {
    override val name = "get_date"
    override val description = "Speak today's date"
    override val params: Map<String, List<String>?> = emptyMap()

    override suspend fun run(args: Map<String, String>): ToolResult {
        val d = today()
        val prefix = d.format(DateTimeFormatter.ofPattern("EEEE, MMMM", Locale.US))
        val day = d.dayOfMonth
        val suffix = when {
            day in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }
        return ToolResult("It's $prefix $day$suffix.", ok = true)
    }
}
