package com.jarvis.edge.brain

object FastIntents {

    private val numberWords = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
        "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
        "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90, "hundred" to 100,
    )

    private val timeRegex = Regex(
        "^(?:what(?:'s| is)? the time|what time is it(?: right now| now)?|tell me the time|current time)$"
    )
    private val dateRegex = Regex(
        "^(?:what(?:'s| is)? (?:the |today'?s )?date|what day is (?:it|today)|tell me the date)$"
    )
    private val timerMinSecRegex = Regex(
        "^(?:set (?:a )?timer(?: for)?|timer(?: for)?|countdown(?: for)?)\\s+(\\d+)\\s*(?:min|mins|minute|minutes)\\s*(?:and\\s*)?(\\d+)\\s*(?:sec|secs|second|seconds)$"
    )
    private val timerSingleUnitRegex = Regex(
        "^(?:set (?:a )?timer(?: for)?|timer(?: for)?|countdown(?: for)?)\\s+(\\d+)\\s*(hour|hours|hr|hrs|min|mins|minute|minutes|sec|secs|second|seconds)$"
    )
    private val alarmRegex = Regex(
        "^(?:set (?:an )?alarm(?: for)?|wake me(?: up)? at)\\s+(\\d{1,2})(?:[:\\s](\\d{2}))?\\s*(am|pm)?$"
    )
    private val cancelTimersRegex = Regex(
        "^(?:cancel|stop|clear|delete)\\s+(?:all\\s+)?(?:my\\s+)?timers?$"
    )
    private val torchRegex = Regex(
        "^(?:turn|switch)?\\s*(on|off)\\s+(?:the\\s+)?(?:flashlight|torch|flash)|^(?:flashlight|torch)\\s+(on|off)$"
    )
    private val volumeRegex = Regex(
        "^(?:set\\s+)?(?:the\\s+)?(?:media\\s+)?volume\\s+(?:to\\s+)?(\\d{1,3})\\s*(?:percent|%)?$"
    )
    private val brightnessRegex = Regex(
        "^(?:set\\s+)?(?:the\\s+)?(?:screen\\s+)?brightness\\s+(?:to\\s+)?(\\d{1,3})\\s*(?:percent|%)?$"
    )
    private val batteryRegex = Regex(
        "^(?:what(?:'s| is)? (?:the |your )?battery(?: level| status| percentage| temp| temperature)?|battery(?: level| status)?|how much battery(?: is left)?)$"
    )
    private val lightRegex = Regex(
        "^(?:what(?:'s| is)? the (?:ambient )?light level|how bright is it(?: in here)?|light level|ambient light)$"
    )
    private val tvPowerRegex = Regex(
        "^(?:turn|switch)\\s+(?:on|off)\\s+(?:the\\s+)?tv$|^tv\\s+(?:power|on|off)$"
    )
    private val tvVolumeUpRegex = Regex(
        "^(?:tv\\s+volume\\s+up|turn\\s+up\\s+(?:the\\s+)?tv(?:\\s+volume)?|increase\\s+(?:the\\s+)?tv\\s+volume)$"
    )
    private val tvVolumeDownRegex = Regex(
        "^(?:tv\\s+volume\\s+down|turn\\s+down\\s+(?:the\\s+)?tv(?:\\s+volume)?|decrease\\s+(?:the\\s+)?tv\\s+volume)$"
    )
    private val tvMuteRegex = Regex("^(?:mute\\s+(?:the\\s+)?tv|tv\\s+mute)$")
    private val tvChannelUpRegex = Regex("^(?:tv\\s+channel\\s+up|next\\s+tv\\s+channel)$")
    private val tvChannelDownRegex = Regex("^(?:tv\\s+channel\\s+down|previous\\s+tv\\s+channel)$")
    private val tvInputRegex = Regex("^(?:tv\\s+input|switch\\s+(?:the\\s+)?tv\\s+input)$")
    private val acPowerRegex = Regex(
        "^(?:turn|switch)\\s+(?:on|off)\\s+(?:the\\s+)?(?:ac|air conditioner)$|^(?:ac|air conditioner)\\s+(?:power|on|off)$"
    )
    private val readThisRegex = Regex(
        "^(?:read\\s+this|read\\s+(?:the\\s+)?text|read\\s+what(?:'s|\\s+is)\\s+(?:in\\s+front|written)(?:\\s+of\\s+you)?)$"
    )
    private val lookFrontHoldingRegex = Regex(
        "^(?:what\\s+am\\s+i\\s+holding)$"
    )
    private val lookFrontSeeRegex = Regex(
        "^(?:what\\s+do\\s+you\\s+see(?:\\s+in\\s+front)?|look\\s+at\\s+(?:this|me))$"
    )

    fun match(text: String): ToolCall? {
        val norm = normalize(text)
        if (norm.isEmpty()) return null

        if (timeRegex.matches(norm)) return ToolCall("get_time", emptyMap())
        if (dateRegex.matches(norm)) return ToolCall("get_date", emptyMap())

        timerMinSecRegex.matchEntire(norm)?.let { m ->
            return ToolCall(
                "set_timer",
                mapOf("minutes" to m.groupValues[1], "seconds" to m.groupValues[2]),
            )
        }
        timerSingleUnitRegex.matchEntire(norm)?.let { m ->
            val value = m.groupValues[1].toIntOrNull() ?: return null
            val unit = m.groupValues[2]
            return when {
                unit.startsWith("h") -> ToolCall(
                    "set_timer",
                    mapOf("minutes" to (value * 60).toString(), "seconds" to "0"),
                )
                unit.startsWith("m") -> ToolCall(
                    "set_timer",
                    mapOf("minutes" to value.toString(), "seconds" to "0"),
                )
                else -> ToolCall(
                    "set_timer",
                    mapOf("minutes" to "0", "seconds" to value.toString()),
                )
            }
        }

        alarmRegex.matchEntire(norm)?.let { m ->
            var hour = m.groupValues[1].toIntOrNull() ?: return null
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = m.groupValues[3]
            if (ampm == "pm" && hour in 1..11) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            if (hour !in 0..23 || minute !in 0..59) return null
            return ToolCall("set_alarm", mapOf("hour" to hour.toString(), "minute" to minute.toString()))
        }

        if (cancelTimersRegex.matches(norm)) return ToolCall("cancel_timers", emptyMap())

        torchRegex.matchEntire(norm)?.let { m ->
            val state = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (state == "on" || state == "off") {
                return ToolCall("torch", mapOf("state" to state))
            }
        }

        volumeRegex.matchEntire(norm)?.let { m ->
            val pct = m.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: return null
            return ToolCall("set_volume", mapOf("percent" to pct.toString()))
        }

        brightnessRegex.matchEntire(norm)?.let { m ->
            val pct = m.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: return null
            return ToolCall("set_brightness", mapOf("percent" to pct.toString()))
        }

        if (batteryRegex.matches(norm)) return ToolCall("battery_status", emptyMap())
        if (lightRegex.matches(norm)) return ToolCall("light_level", emptyMap())
        if (tvPowerRegex.matches(norm)) return ToolCall("ir_send", mapOf("device" to "tv", "command" to "power"))
        if (tvVolumeUpRegex.matches(norm)) return ToolCall("ir_send", mapOf("device" to "tv", "command" to "volume_up"))
        if (tvVolumeDownRegex.matches(norm)) return ToolCall("ir_send", mapOf("device" to "tv", "command" to "volume_down"))
        if (tvMuteRegex.matches(norm)) return ToolCall("ir_send", mapOf("device" to "tv", "command" to "mute"))
        if (tvChannelUpRegex.matches(norm)) return ToolCall("ir_send", mapOf("device" to "tv", "command" to "channel_up"))
        if (tvChannelDownRegex.matches(norm)) return ToolCall("ir_send", mapOf("device" to "tv", "command" to "channel_down"))
        if (tvInputRegex.matches(norm)) return ToolCall("ir_send", mapOf("device" to "tv", "command" to "input"))
        if (acPowerRegex.matches(norm)) return ToolCall("ir_send", mapOf("device" to "ac", "command" to "power"))
        if (readThisRegex.matches(norm)) return ToolCall("read_text", mapOf("camera" to "back"))
        if (lookFrontHoldingRegex.matches(norm)) {
            return ToolCall("look", mapOf("camera" to "front", "question" to "what am i holding"))
        }
        if (lookFrontSeeRegex.matches(norm)) {
            return ToolCall("look", mapOf("camera" to "back", "question" to norm))
        }

        return null
    }

    internal fun normalize(raw: String): String {
        val cleaned = raw.lowercase()
            .replace(Regex("[^a-z0-9:%\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("hey jarvis ")
            .removePrefix("jarvis ")
            .removePrefix("please ")
            .removeSuffix(" please")
            .trim()

        if (cleaned.isEmpty()) return ""
        val tokens = cleaned.split(" ")
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val w = tokens[i]
            val n = numberWords[w]
            if (n != null) {
                val next = if (i + 1 < tokens.size) numberWords[tokens[i + 1]] else null
                if (n in listOf(20, 30, 40, 50, 60, 70, 80, 90) && next != null && next in 1..9) {
                    out.add((n + next).toString())
                    i += 2
                    continue
                }
                out.add(n.toString())
            } else {
                out.add(w)
            }
            i++
        }
        return out.joinToString(" ")
    }
}
