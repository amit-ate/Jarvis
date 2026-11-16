package com.jarvis.edge.tools

import com.jarvis.edge.service.HealthGuard
import com.jarvis.edge.vision.CameraSnapshot
import com.jarvis.edge.vision.Facing
import com.jarvis.edge.vision.Ocr
import com.jarvis.edge.vision.Vlm

private fun parseFacing(raw: String?): Facing =
    if (raw.equals("front", ignoreCase = true)) Facing.FRONT else Facing.BACK

class ReadTextTool(
    private val captureAndRead: suspend (Facing, Boolean) -> String,
    private val healthGuard: HealthGuard? = null,
    private val luxProvider: () -> Float = { 100f },
    private val onBeforeCapture: suspend () -> Unit = {},
) : Tool {

    constructor(
        capturer: CameraSnapshot,
        ocr: Ocr,
        healthGuard: HealthGuard? = null,
        luxProvider: () -> Float = { 100f },
        onBeforeCapture: suspend () -> Unit = {},
    ) : this(
        captureAndRead = { facing, lowLight ->
            val bitmap = capturer.capture(facing, maxEdge = 768, lowLight = lowLight)
            try {
                ocr.read(bitmap)
            } finally {
                bitmap.recycle()
            }
        },
        healthGuard = healthGuard,
        luxProvider = luxProvider,
        onBeforeCapture = onBeforeCapture,
    )

    override val name = "read_text"
    override val description = "Read visible text with camera"
    override val params = mapOf("camera" to listOf("front", "back"))

    override suspend fun run(args: Map<String, String>): ToolResult {
        if (healthGuard != null && healthGuard.level() != HealthGuard.Level.NORMAL) {
            return ToolResult(TOO_WARM_MSG, ok = false)
        }
        onBeforeCapture()
        val facing = parseFacing(args["camera"])
        val lowLight = luxProvider() < LOW_LIGHT_LUX
        val text = captureAndRead(facing, lowLight).trim()
        if (text.isEmpty()) {
            return ToolResult("I don't see any text.", ok = true)
        }
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (words.size > 40) {
            ToolResult(
                spoken = "I found quite a bit of text. Here is a summary.",
                ok = true,
                feedToModel = text,
            )
        } else {
            ToolResult(
                spoken = text,
                ok = true,
                feedToModel = text,
            )
        }
    }

    companion object {
        const val LOW_LIGHT_LUX = 15f
        const val TOO_WARM_MSG = "I'm too warm for that right now. Give me a few minutes."
    }
}

class LookTool(
    private val captureAndDescribe: (suspend (Facing, Boolean, String, (String) -> Boolean) -> Unit)?,
    private val healthGuard: HealthGuard? = null,
    private val luxProvider: () -> Float = { 100f },
    private val onBeforeCapture: suspend () -> Unit = {},
) : Tool {

    constructor(
        capturer: CameraSnapshot,
        vlm: Vlm?,
        healthGuard: HealthGuard? = null,
        luxProvider: () -> Float = { 100f },
        onBeforeCapture: suspend () -> Unit = {},
    ) : this(
        captureAndDescribe = if (vlm == null) null else { facing, lowLight, question, onToken ->
            val bitmap = capturer.capture(facing, maxEdge = 384, lowLight = lowLight)
            try {
                vlm.describe(bitmap, question, onToken)
            } finally {
                bitmap.recycle()
            }
        },
        healthGuard = healthGuard,
        luxProvider = luxProvider,
        onBeforeCapture = onBeforeCapture,
    )

    override val name = "look"
    override val description = "Inspect scene via camera"
    override val params = mapOf(
        "camera" to listOf("front", "back"),
        "question" to null,
    )

    override suspend fun run(args: Map<String, String>): ToolResult {
        if (healthGuard != null && healthGuard.level() != HealthGuard.Level.NORMAL) {
            return ToolResult(ReadTextTool.TOO_WARM_MSG, ok = false)
        }
        val runner = captureAndDescribe
            ?: return ToolResult("My vision model isn't loaded yet.", ok = false)

        onBeforeCapture()
        val facing = parseFacing(args["camera"])
        val question = args["question"]?.takeIf { it.isNotBlank() } ?: "Describe what you see briefly."
        val lowLight = luxProvider() < ReadTextTool.LOW_LIGHT_LUX
        val out = StringBuilder()
        runner(facing, lowLight, question) { piece ->
            out.append(piece)
            true
        }
        val answer = out.toString().trim()
        return if (answer.isEmpty()) {
            ToolResult("I couldn't make out what's in front of the camera.", ok = false)
        } else {
            ToolResult(answer, ok = true)
        }
    }
}
