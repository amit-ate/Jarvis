package com.jarvis.edge.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.WeakHashMap

enum class ExpertDomain {
    REFLEX,
    SHORTCUT,
    CONVERSATION,
    REASONING,
    TOOL_USE,
    MEMORY,
    VISION,
    KNOWLEDGE,
}

data class ExpertProfile(
    val domain: ExpertDomain,
    val loraPath: String? = null,
    val loraScale: Float = 1.0f,
    val temperature: Float = 0.45f,
    val repeatPenalty: Float = 1.18f,
    val keywords: List<String> = emptyList(),
)

class ExpertRouter(
    private val profiles: Map<ExpertDomain, ExpertProfile> = defaultProfiles(),
    private val adaptersDir: File? = null,
) {
    private val loadedLoras = WeakHashMap<LlmEngine, MutableMap<String, Long>>()
    private val activeLoras = WeakHashMap<LlmEngine, List<Pair<Long, Float>>>()

    fun classify(userText: String): ExpertDomain {
        val norm = userText.lowercase()
            .replace(Regex("[^a-z0-9'\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (norm.isEmpty()) return ExpertDomain.CONVERSATION

        var bestDomain: ExpertDomain? = null
        var bestHits = 0
        for (domain in CLASSIFICATION_ORDER) {
            val profile = profiles[domain] ?: continue
            if (profile.keywords.isEmpty()) continue
            var hits = 0
            for (kw in profile.keywords) {
                val cleanKw = kw.lowercase().trim()
                if (cleanKw.isNotEmpty() && containsKeyword(norm, cleanKw)) {
                    hits++
                }
            }
            if (hits > bestHits) {
                bestHits = hits
                bestDomain = domain
            }
        }
        return bestDomain ?: ExpertDomain.CONVERSATION
    }

    fun selectAndActivate(userText: String, engine: LlmEngine): SamplingConfig {
        val domain = classify(userText)
        val profile = profiles[domain] ?: DEFAULT_PROFILES[domain] ?: ExpertProfile(domain)

        val desiredAdapters = resolveAdapters(profile, engine)
        synchronized(this) {
            val current = activeLoras[engine]
            if (current != desiredAdapters) {
                engine.setActiveLoraAdapters(desiredAdapters)
                activeLoras[engine] = desiredAdapters
            }
        }

        return SamplingConfig(
            temperature = profile.temperature,
            repeatPenalty = profile.repeatPenalty,
        )
    }

    private fun resolveAdapters(profile: ExpertProfile, engine: LlmEngine): List<Pair<Long, Float>> {
        val path = profile.loraPath?.takeIf { it.isNotBlank() } ?: return emptyList()
        val rawFile = File(path)
        val resolved = if (rawFile.isAbsolute || adaptersDir == null) {
            rawFile
        } else {
            File(adaptersDir, path)
        }
        if (!resolved.isFile || resolved.length() <= 0L) {
            return emptyList()
        }

        val handle = synchronized(this) {
            val cache = loadedLoras.getOrPut(engine) { HashMap() }
            cache[resolved.absolutePath] ?: run {
                val loaded = engine.loadLoraAdapter(resolved)
                if (loaded != 0L) {
                    cache[resolved.absolutePath] = loaded
                }
                loaded
            }
        }
        return if (handle != 0L) listOf(handle to profile.loraScale) else emptyList()
    }

    private fun containsKeyword(normText: String, keyword: String): Boolean {
        if (' ' in keyword) {
            return " $normText ".contains(" $keyword ")
        }
        val words = normText.split(' ')
        return keyword in words
    }

    companion object {
        private val CLASSIFICATION_ORDER = listOf(
            ExpertDomain.VISION,
            ExpertDomain.MEMORY,
            ExpertDomain.TOOL_USE,
            ExpertDomain.KNOWLEDGE,
            ExpertDomain.REASONING,
            ExpertDomain.SHORTCUT,
            ExpertDomain.REFLEX,
            ExpertDomain.CONVERSATION,
        )

        private val DEFAULT_PROFILES: Map<ExpertDomain, ExpertProfile> = mapOf(
            ExpertDomain.REFLEX to ExpertProfile(
                domain = ExpertDomain.REFLEX,
                temperature = 0.15f,
                repeatPenalty = 1.12f,
            ),
            ExpertDomain.SHORTCUT to ExpertProfile(
                domain = ExpertDomain.SHORTCUT,
                temperature = 0.15f,
                repeatPenalty = 1.12f,
            ),
            ExpertDomain.CONVERSATION to ExpertProfile(
                domain = ExpertDomain.CONVERSATION,
                temperature = 0.55f,
                repeatPenalty = 1.18f,
            ),
            ExpertDomain.REASONING to ExpertProfile(
                domain = ExpertDomain.REASONING,
                temperature = 0.25f,
                repeatPenalty = 1.15f,
                keywords = listOf(
                    "why", "explain", "calculate", "solve", "compare",
                    "difference", "prove", "analyze", "how does", "how do",
                    "math", "equation",
                ),
            ),
            ExpertDomain.TOOL_USE to ExpertProfile(
                domain = ExpertDomain.TOOL_USE,
                temperature = 0.18f,
                repeatPenalty = 1.15f,
                keywords = listOf(
                    "turn on", "turn off", "flashlight", "torch", "timer",
                    "alarm", "volume", "brightness", "battery", "weather",
                    "tv", "ac", "thermostat", "ir",
                ),
            ),
            ExpertDomain.MEMORY to ExpertProfile(
                domain = ExpertDomain.MEMORY,
                temperature = 0.25f,
                repeatPenalty = 1.15f,
                keywords = listOf(
                    "remember", "recall", "note", "notes", "forgot",
                    "remind", "saved", "what did i",
                ),
            ),
            ExpertDomain.VISION to ExpertProfile(
                domain = ExpertDomain.VISION,
                temperature = 0.25f,
                repeatPenalty = 1.15f,
                keywords = listOf(
                    "look", "see", "camera", "holding", "read this",
                    "read the text", "in front", "photo", "picture", "image",
                ),
            ),
            ExpertDomain.KNOWLEDGE to ExpertProfile(
                domain = ExpertDomain.KNOWLEDGE,
                temperature = 0.30f,
                repeatPenalty = 1.20f,
                keywords = listOf(
                    "who is", "who was", "what is", "where is", "when was",
                    "capital of", "population", "invented", "discovered",
                    "history", "wikipedia", "search", "define", "meaning of",
                ),
            ),
        )

        fun defaultProfiles(): Map<ExpertDomain, ExpertProfile> = DEFAULT_PROFILES

        fun fromManifestFile(
            manifestFile: File,
            adaptersDir: File? = manifestFile.parentFile,
        ): ExpertRouter {
            val base = DEFAULT_PROFILES.toMutableMap()
            if (!manifestFile.isFile || manifestFile.length() <= 0L) {
                return ExpertRouter(base, adaptersDir)
            }

            runCatching {
                val root = JSONObject(manifestFile.readText())
                val arrayNode = root.optJSONArray("experts") ?: root.optJSONArray("profiles")
                if (arrayNode != null) {
                    parseArrayEntries(arrayNode, base)
                } else {
                    val mapNode = root.optJSONObject("experts")
                        ?: root.optJSONObject("profiles")
                        ?: root
                    parseObjectEntries(mapNode, base)
                }
            }
            return ExpertRouter(base, adaptersDir)
        }

        private fun parseArrayEntries(
            arr: JSONArray,
            out: MutableMap<ExpertDomain, ExpertProfile>,
        ) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val domainName = obj.optString("domain", obj.optString("name", "")).trim()
                val domain = parseDomain(domainName) ?: continue
                out[domain] = parseProfile(domain, obj, out[domain])
            }
        }

        private fun parseObjectEntries(
            obj: JSONObject,
            out: MutableMap<ExpertDomain, ExpertProfile>,
        ) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val domain = parseDomain(key) ?: continue
                val entry = obj.optJSONObject(key) ?: continue
                out[domain] = parseProfile(domain, entry, out[domain])
            }
        }

        private fun parseDomain(raw: String): ExpertDomain? =
            runCatching { ExpertDomain.valueOf(raw.trim().uppercase()) }.getOrNull()

        private fun parseProfile(
            domain: ExpertDomain,
            obj: JSONObject,
            fallback: ExpertProfile?,
        ): ExpertProfile {
            val loraPath = when {
                obj.has("loraPath") -> obj.optString("loraPath", "").ifBlank { null }
                obj.has("lora_path") -> obj.optString("lora_path", "").ifBlank { null }
                obj.has("lora") -> obj.optString("lora", "").ifBlank { null }
                else -> fallback?.loraPath
            }
            val loraScale = when {
                obj.has("loraScale") -> obj.optDouble("loraScale", 1.0).toFloat()
                obj.has("lora_scale") -> obj.optDouble("lora_scale", 1.0).toFloat()
                obj.has("scale") -> obj.optDouble("scale", 1.0).toFloat()
                else -> fallback?.loraScale ?: 1.0f
            }
            val temperature = when {
                obj.has("temperature") -> obj.optDouble("temperature", 0.45).toFloat()
                obj.has("temp") -> obj.optDouble("temp", 0.45).toFloat()
                else -> fallback?.temperature ?: 0.45f
            }
            val repeatPenalty = when {
                obj.has("repeatPenalty") -> obj.optDouble("repeatPenalty", 1.18).toFloat()
                obj.has("repeat_penalty") -> obj.optDouble("repeat_penalty", 1.18).toFloat()
                else -> fallback?.repeatPenalty ?: 1.18f
            }
            val kwArray = obj.optJSONArray("keywords")
            val keywords = if (kwArray != null) {
                List(kwArray.length()) { idx -> kwArray.optString(idx, "").trim() }
                    .filter { it.isNotEmpty() }
            } else {
                fallback?.keywords ?: emptyList()
            }
            return ExpertProfile(
                domain = domain,
                loraPath = loraPath,
                loraScale = loraScale,
                temperature = temperature,
                repeatPenalty = repeatPenalty,
                keywords = keywords,
            )
        }
    }
}
