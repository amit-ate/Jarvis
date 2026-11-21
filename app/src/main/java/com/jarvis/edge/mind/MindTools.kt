package com.jarvis.edge.mind

import com.jarvis.edge.tools.Tool
import com.jarvis.edge.tools.ToolResult
import java.util.Calendar

/**
 * Schedules a persistent reminder from relative ("5 minutes", "2 hours") or clock ("6 pm", "18:30") time.
 */
class RemindTool(
    private val store: MindRepository,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : Tool {
    override val name = "remind"
    override val description = "Set a persistent reminder for a relative duration or clock time"
    override val params = mapOf("when" to null, "task" to null)

    override suspend fun run(args: Map<String, String>): ToolResult {
        val whenSpec = args["when"]?.trim().orEmpty()
        val task = args["task"]?.trim().orEmpty()
        if (task.isEmpty()) {
            return ToolResult("What should I remind you about?", ok = false)
        }
        val now = clockMs()
        val triggerAtMs = parseWhenMs(whenSpec, now)
            ?: return ToolResult("I didn't understand when to set that reminder.", ok = false)
        store.addReminder(task, triggerAtMs)
        return ToolResult("Okay, I'll remind you to $task.", ok = true)
    }

    internal fun parseWhenMs(spec: String, nowMs: Long): Long? {
        val s = spec.trim().lowercase()
        if (s.isEmpty()) return null

        // Relative durations: e.g. "5 minutes", "in 2 hours", "1 hour 30 minutes", "30 sec"
        var relOffsetMs = 0L
        var matchedRel = false
        for (m in RELATIVE_UNIT.findAll(s)) {
            val value = m.groupValues[1].toLongOrNull() ?: continue
            val unit = m.groupValues[2]
            matchedRel = true
            relOffsetMs += when {
                unit.startsWith("h") -> value * 3600_000L
                unit.startsWith("m") -> value * 60_000L
                unit.startsWith("s") -> value * 1000L
                else -> 0L
            }
        }
        if (matchedRel && relOffsetMs > 0L) {
            return nowMs + relOffsetMs
        }

        if ("half an hour" in s || "half hour" in s) {
            return nowMs + 30L * 60_000L
        }

        // Clock time: "6 pm", "6:30 pm", "18:30", "at 7 am"
        CLOCK_TIME.find(s)?.let { m ->
            val rawHour = m.groupValues[1].toIntOrNull() ?: return null
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = m.groupValues[3]
            if (minute !in 0..59) return null

            val hour24 = when {
                ampm == "am" -> if (rawHour == 12) 0 else if (rawHour in 1..11) rawHour else return null
                ampm == "pm" -> if (rawHour == 12) 12 else if (rawHour in 1..11) rawHour + 12 else return null
                else -> if (rawHour in 0..23) rawHour else return null
            }
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, hour24)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= nowMs) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            return cal.timeInMillis
        }

        return null
    }

    companion object {
        private val RELATIVE_UNIT = Regex("(\\d+)\\s*(hours?|hrs?|hr|minutes?|mins?|min|seconds?|secs?|sec)\\b")
        private val CLOCK_TIME = Regex("(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b")
    }
}

/**
 * Stores a user-stated fact into semantic memory and the audit journal.
 */
class MindRememberTool(
    private val store: MindRepository,
    private val embed: (String) -> FloatArray = { VectorMath.simpleHashEmbed(it) },
) : Tool {
    override val name = "remember"
    override val description = "Save a fact or preference to Jarvis long-term memory"
    override val params = mapOf("text" to null)

    override suspend fun run(args: Map<String, String>): ToolResult {
        val text = (args["text"] ?: args["note"])?.trim().orEmpty()
        if (text.isEmpty()) {
            return ToolResult("What should I remember?", ok = false)
        }
        store.learnFact(text, embed(text), sourceEpisodeId = null, confidence = 1.0f)
        return ToolResult("Got it, I'll remember that.", ok = true)
    }
}

/**
 * Retrieves relevant saved facts via embedding similarity with keyword fallback.
 */
class MindRecallTool(
    private val store: MindRepository,
    private val embed: (String) -> FloatArray = { VectorMath.simpleHashEmbed(it) },
) : Tool {
    override val name = "recall"
    override val description = "Search saved facts and memories"
    override val params = mapOf("query" to null)

    override suspend fun run(args: Map<String, String>): ToolResult {
        val query = args["query"]?.trim().orEmpty()
        if (query.isEmpty()) {
            val recent = store.listActiveFacts(5)
            if (recent.isEmpty()) {
                return ToolResult("I don't have anything saved yet.", ok = true, feedToModel = "No facts saved.")
            }
            return ToolResult(
                spoken = recent.first().text,
                ok = true,
                feedToModel = recent.joinToString("\n") { "- ${it.text}" },
            )
        }

        val vecMatches = store.retrieveRelevantFacts(embed(query), topK = 4, minSim = 0.20f)
        val matches = if (vecMatches.isNotEmpty()) {
            vecMatches
        } else {
            store.listActiveFacts(200)
                .map { it to MindJsonExporter.keywordScore(query, it.text) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(4)
                .map { it.first }
        }
        if (matches.isEmpty()) {
            return ToolResult(
                spoken = "I don't have anything saved about that.",
                ok = true,
                feedToModel = "No matching memories found.",
            )
        }
        return ToolResult(
            spoken = matches.first().text,
            ok = true,
            feedToModel = matches.joinToString("\n") { "- ${it.text}" },
        )
    }
}

/**
 * Forgets a matching learned fact or undoes the most recent learning.
 */
class MindForgetTool(
    private val store: MindRepository,
    private val embed: (String) -> FloatArray = { VectorMath.simpleHashEmbed(it) },
) : Tool {
    override val name = "forget"
    override val description = "Forget a saved fact or undo the last thing learned"
    override val params = mapOf("target" to null)

    override suspend fun run(args: Map<String, String>): ToolResult {
        val target = args["target"]?.trim().orEmpty()
        if (target.isEmpty() || target.lowercase() in LAST_ALIASES) {
            val undone = store.undoLastLearning()
                ?: return ToolResult("There is nothing recent to undo.", ok = false)
            return ToolResult("Okay, I undid that: ${undone.summary}.", ok = true)
        }
        val forgotten = store.forgetMatchingFact(target, embed(target))
            ?: return ToolResult("I couldn't find a matching memory to forget.", ok = false)
        return ToolResult("Okay, I've forgotten: ${forgotten.text}.", ok = true)
    }

    companion object {
        private val LAST_ALIASES = setOf("last", "that", "last thing", "undo")
    }
}

/**
 * Inspects and controls Jarvis's self-model, learning state, initiative muting, and brain rollback.
 */
class MindStatusTool(
    private val store: MindRepository,
    private val selfSnapshotProvider: () -> SelfStateSnapshot,
    private val onMuteRequested: (Long) -> Unit = {},
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val onVersionActivated: (ModelVersion) -> Unit = {},
    /** Starts a learning (reflection) pass in the background; false when that isn't possible right now. */
    private val onLearnNow: () -> Boolean = { false },
) : Tool {
    override val name = "mind_status"
    override val description = "Inspect Jarvis self-status, learned knowledge, pause/resume learning, quiet mode, or brain rollback"
    override val params = mapOf("action" to ACTIONS)

    override suspend fun run(args: Map<String, String>): ToolResult {
        val action = args["action"]?.trim()?.lowercase().orEmpty()
        return when (action) {
            "learned_today" -> {
                val cutoff = clockMs() - 24L * 3600_000L
                val items = store.listJournal(100).filter {
                    !it.undone && it.createdAtMs >= cutoff && it.type in LEARNING_TYPES
                }
                if (items.isEmpty()) {
                    ToolResult("I haven't learned anything new in the last 24 hours.", ok = true)
                } else {
                    val summary = items.take(3).joinToString("; ") { spokenFact(it.summary) }
                    ToolResult(
                        spoken = "In the last 24 hours I learned ${countNoun(items.size, "item", "items")}, including: $summary.",
                        ok = true,
                        feedToModel = items.joinToString("\n") { "- ${it.summary}" },
                    )
                }
            }
            "know_about_me" -> {
                val facts = store.listActiveFacts(10)
                if (facts.isEmpty()) {
                    ToolResult("I don't have any personal facts stored yet.", ok = true)
                } else {
                    val top = facts.take(3).joinToString("; ") { spokenFact(it.text) }
                    ToolResult(
                        spoken = "I remember ${countNoun(facts.size, "fact", "facts")} about you, such as: $top.",
                        ok = true,
                        feedToModel = facts.joinToString("\n") { "- ${it.text}" },
                    )
                }
            }
            "self_status" -> {
                val snap = selfSnapshotProvider()
                ToolResult(
                    spoken = SelfModel.toSpokenSummary(snap),
                    ok = true,
                    feedToModel = SelfModel.toPromptLine(snap),
                )
            }
            "pause_learning" -> {
                store.setLearningPaused(true)
                ToolResult("Learning is now paused.", ok = true)
            }
            "learn_now" -> {
                if (store.isLearningPaused()) {
                    ToolResult("Learning is paused. Say resume learning first.", ok = false)
                } else if (onLearnNow()) {
                    ToolResult("Okay, I'm going through our recent conversations now. I'll tell you what I learned.", ok = true)
                } else {
                    ToolResult("I can't run learning right now.", ok = false)
                }
            }
            "resume_learning" -> {
                store.setLearningPaused(false)
                ToolResult("Learning has resumed.", ok = true)
            }
            "be_quiet" -> {
                onMuteRequested(3600_000L)
                ToolResult("Got it, I'll stay quiet for the next hour.", ok = true)
            }
            "rollback_brain" -> {
                val rolledBack = store.rollbackModelVersion()
                    ?: return ToolResult("No previous brain version is available to roll back to.", ok = false)
                onVersionActivated(rolledBack)
                ToolResult(
                    spoken = "Rolled back brain to ${rolledBack.baseModel}.",
                    ok = true,
                )
            }
            else -> ToolResult("Unknown mind_status action: $action", ok = false)
        }
    }

    companion object {
        /** Stored facts say "User's ..."; spoken aloud they should address the user as "your ...". */
        internal fun spokenFact(text: String): String =
            if (text.startsWith("User's ")) "your " + text.removePrefix("User's ") else text

        internal fun countNoun(count: Int, singular: String, plural: String): String =
            if (count == 1) "$count $singular" else "$count $plural"

        val ACTIONS = listOf(
            "learned_today",
            "know_about_me",
            "self_status",
            "pause_learning",
            "learn_now",
            "resume_learning",
            "be_quiet",
            "rollback_brain",
        )
        private val LEARNING_TYPES = setOf("fact", "vocab", "correction", "shortcut")
    }
}
