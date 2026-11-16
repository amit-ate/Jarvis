package com.jarvis.edge.tools.ir

import org.json.JSONArray
import org.json.JSONObject

data class IrCode(
    val carrierHz: Int,
    val pattern: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IrCode) return false
        return carrierHz == other.carrierHz && pattern.contentEquals(other.pattern)
    }

    override fun hashCode(): Int {
        return 31 * carrierHz + pattern.contentHashCode()
    }
}

data class IrBrandProfile(
    val brand: String,
    val commands: Map<String, IrCode>,
)

class IrCodes(json: String) {
    private val deviceMap = LinkedHashMap<String, Map<String, IrCode>>()
    private val brandCatalog = LinkedHashMap<String, List<IrBrandProfile>>()

    init {
        val root = JSONObject(json)
        val devicesObj = root.optJSONObject("devices")
        if (devicesObj != null) {
            val devKeys = devicesObj.keys()
            while (devKeys.hasNext()) {
                val devName = devKeys.next().lowercase()
                val cmdObj = devicesObj.optJSONObject(devName) ?: continue
                deviceMap[devName] = parseCommandMap(cmdObj)
            }
        }
        val brandsObj = root.optJSONObject("brands")
        if (brandsObj != null) {
            val typeKeys = brandsObj.keys()
            while (typeKeys.hasNext()) {
                val devType = typeKeys.next().lowercase()
                val arr = brandsObj.optJSONArray(devType) ?: continue
                val list = ArrayList<IrBrandProfile>(arr.length())
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val brand = item.optString("brand", "").trim()
                    val cmds = item.optJSONObject("commands") ?: continue
                    if (brand.isNotEmpty()) {
                        list.add(IrBrandProfile(brand, parseCommandMap(cmds)))
                    }
                }
                brandCatalog[devType] = list
            }
        }
    }

    fun code(device: String, command: String): IrCode? {
        val devKey = device.lowercase().trim()
        val cmdKey = command.lowercase().trim()
        return deviceMap[devKey]?.get(cmdKey)
    }

    fun devices(): List<String> = deviceMap.keys.sorted()

    fun commands(device: String): List<String> =
        deviceMap[device.lowercase().trim()]?.keys?.sorted().orEmpty()

    fun brandsFor(deviceType: String): List<IrBrandProfile> =
        brandCatalog[deviceType.lowercase().trim()].orEmpty()

    private fun parseCommandMap(cmdObj: JSONObject): Map<String, IrCode> {
        val out = LinkedHashMap<String, IrCode>()
        val keys = cmdObj.keys()
        while (keys.hasNext()) {
            val cmdName = keys.next().lowercase()
            val entry = cmdObj.optJSONObject(cmdName) ?: continue
            parseEntry(entry)?.let { out[cmdName] = it }
        }
        return out
    }

    private fun parseEntry(obj: JSONObject): IrCode? {
        val rawPattern = obj.optJSONArray("pattern")
        if (rawPattern != null) {
            val hz = obj.optInt("carrierHz", 38_000)
            val arr = IntArray(rawPattern.length()) { idx -> rawPattern.optInt(idx) }
            return if (arr.isNotEmpty()) IrCode(hz, arr) else null
        }

        val protocol = obj.optString("protocol", "").lowercase().trim()
        val addr = parseHexOrInt(obj.opt("address")) ?: return null
        val cmd = parseHexOrInt(obj.opt("command")) ?: return null
        val hz = obj.optInt("carrierHz", 38_000)
        return when (protocol) {
            "nec" -> IrCode(hz, IrEncoder.nec(addr, cmd))
            "samsung", "samsung32" -> IrCode(hz, IrEncoder.samsung(addr, cmd))
            else -> null
        }
    }

    private fun parseHexOrInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> {
            val s = value.trim().lowercase()
            if (s.startsWith("0x")) s.removePrefix("0x").toIntOrNull(16) else s.toIntOrNull()
        }
        else -> null
    }

    companion object {
        fun withUserOverrides(baseJson: String, overridesJson: String?): IrCodes {
            if (overridesJson.isNullOrBlank()) return IrCodes(baseJson)
            val root = JSONObject(baseJson)
            val overrideObj = runCatching { JSONObject(overridesJson) }.getOrNull() ?: return IrCodes(baseJson)
            val devices = root.optJSONObject("devices") ?: JSONObject().also { root.put("devices", it) }
            val keys = overrideObj.keys()
            while (keys.hasNext()) {
                val dev = keys.next()
                devices.put(dev, overrideObj.get(dev))
            }
            return IrCodes(root.toString())
        }

        fun serializeRawCommandMap(commands: Map<String, IrCode>): JSONObject {
            val out = JSONObject()
            for ((name, ir) in commands) {
                val entry = JSONObject()
                entry.put("carrierHz", ir.carrierHz)
                val arr = JSONArray()
                for (us in ir.pattern) arr.put(us)
                entry.put("pattern", arr)
                out.put(name, entry)
            }
            return out
        }
    }
}
