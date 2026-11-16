package com.jarvis.edge.tools.ir

import android.content.Context
import android.hardware.ConsumerIrManager
import com.jarvis.edge.tools.Tool
import com.jarvis.edge.tools.ToolResult

class IrTool(
    private val codesProvider: () -> IrCodes,
    private val transmit: (Int, IntArray) -> Unit,
) : Tool {

    constructor(codes: IrCodes, transmit: (Int, IntArray) -> Unit) :
        this(codesProvider = { codes }, transmit = transmit)

    override val name = "ir_send"
    override val description = "Control configured TV or AC via IR"
    override val params: Map<String, List<String>?>
        get() = mapOf(
            "device" to codesProvider().devices().ifEmpty { null },
            "command" to null,
        )

    override suspend fun run(args: Map<String, String>): ToolResult {
        val device = args["device"]?.lowercase()?.trim().orEmpty()
        val command = args["command"]?.lowercase()?.trim().orEmpty()
        if (device.isEmpty() || command.isEmpty()) {
            return ToolResult("Tell me which device and command to send.", ok = false)
        }

        val codes = codesProvider()
        val ir = codes.code(device, command)
            ?: return ToolResult(
                spoken = "No infrared profile is configured for $device $command. Long press the screen to set it up.",
                ok = false,
            )

        return try {
            transmit(ir.carrierHz, ir.pattern)
            ToolResult("Sent $command to the $device.", ok = true)
        } catch (t: Throwable) {
            ToolResult("Failed to send the infrared signal: ${t.message ?: "error"}.", ok = false)
        }
    }

    companion object {
        fun androidTransmitter(context: Context): (Int, IntArray) -> Unit {
            val irMgr = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
            return { carrierHz, pattern ->
                if (irMgr == null || !irMgr.hasIrEmitter()) {
                    throw IllegalStateException("Infrared emitter is unavailable")
                }
                irMgr.transmit(carrierHz, pattern)
            }
        }
    }
}
