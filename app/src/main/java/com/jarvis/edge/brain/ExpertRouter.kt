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
    val cvecPath: String? = null,
    val cvecScale: Float = 0.0f,
    val cvecLayerStart: Int = 8,
    val cvecLayerEnd: Int = 21,
)

class ExpertRouter(
    private val profiles: Map<ExpertDomain, ExpertProfile> = defaultProfiles(),
    private val adaptersDir: File? = null,
) {
    private val loadedLoras = WeakHashMap<LlmEngine, MutableMap<String, Long>>()
    private val activeLoras = WeakHashMap<LlmEngine, List<Pair<Long, Float>>>()
    private val loadedCvecs = HashMap<String, ControlVector>()
    private val activeCvecSignatures = WeakHashMap<LlmEngine, String>()

    @Volatile
    private var evolvedCvec: Pair<ControlVector, Float>? = null

    fun setEvolvedControlVector(vec: ControlVector?, scale: Float = 0.35f) {
        evolvedCvec = if (vec != null && scale != 0.0f && scale.isFinite()) {
            vec to scale
        } else {
            null
        }
    }

    /** What was last applied to [engine] (LoRA handles+scales and control-vector signature); "none" when nothing is. */
    fun appliedSignature(engine: LlmEngine): String = synchronized(this) {
        // Handles are native pointers that change per run; name adapters by file path so the signature is stable.
        val pathByHandle = loadedLoras[engine].orEmpty().entries.associate { it.value to it.key }
        val loras = activeLoras[engine].orEmpty().joinToString(",") { "${pathByHandle[it.first] ?: "?"}@${it.second}" }
        "lora[$loras]|cvec[${activeCvecSignatures[engine] ?: ""}]"
    }

    fun activeExpertSummary(domain: ExpertDomain): String {
        val profile = profiles[domain] ?: DEFAULT_PROFILES[domain] ?: ExpertProfile(domain)
        val hasDomainCvec = resolveControlVector(profile) != null && profile.cvecScale != 0.0f
        val hasEvolved = evolvedCvec != null
        return buildString {
            append(domain.name)
            if (hasDomainCvec) append("+cvec")
            if (hasEvolved) append("+evolved")
        }
    }

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
        val domainVec = if (profile.cvecScale != 0.0f && profile.cvecScale.isFinite()) {
            resolveControlVector(profile)
        } else {
            null
        }
        val evolved = evolvedCvec

        val entries = ArrayList<Pair<ControlVector, Float>>(2)
        val sigParts = ArrayList<String>(2)
        if (domainVec != null) {
            entries.add(domainVec to profile.cvecScale)
            sigParts.add("d:${profile.cvecPath}@${profile.cvecScale}:${domainVec.ilStart}-${domainVec.ilEnd}")
        }
        if (evolved != null) {
            val (eVec, eScale) = evolved
            if (domainVec == null || (eVec.nEmbd == domainVec.nEmbd && eVec.nLayersMinus1 == domainVec.nLayersMinus1)) {
                entries.add(eVec to eScale)
                sigParts.add("e:${eVec.nEmbd}x${eVec.nLayersMinus1}:${eVec.ilStart}-${eVec.ilEnd}:${eVec.data.contentHashCode()}@$eScale")
            }
        }
        val blended = if (entries.isEmpty()) null else ControlVectorCodec.blend(entries)
        val desiredCvecSig = if (blended != null) sigParts.joinToString("|") else null

        synchronized(this) {
            val currentLoras = activeLoras[engine]
            if (currentLoras != desiredAdapters) {
                engine.setActiveLoraAdapters(desiredAdapters)
                activeLoras[engine] = desiredAdapters
            }

            val currentCvecSig = activeCvecSignatures[engine]
            if (desiredCvecSig != currentCvecSig) {
                if (blended != null && desiredCvecSig != null) {
                    engine.applyControlVector(blended.data, blended.nEmbd, blended.ilStart, blended.ilEnd)
                    activeCvecSignatures[engine] = desiredCvecSig
                } else if (currentCvecSig != null) {
                    engine.applyControlVector(null)
                    activeCvecSignatures.remove(engine)
                }
            }
        }

        return SamplingConfig(
            temperature = profile.temperature,
            repeatPenalty = profile.repeatPenalty,
        )
    }

    private fun resolveFile(rawPath: String?): File? {
        val path = rawPath?.takeIf { it.isNotBlank() } ?: return null
        val rawFile = File(path)
        val resolved = if (rawFile.isAbsolute || adaptersDir == null) {
            rawFile
        } else {
            File(adaptersDir, path)
        }
        return resolved.takeIf { it.isFile && it.length() > 0L }
    }

    private fun resolveAdapters(profile: ExpertProfile, engine: LlmEngine): List<Pair<Long, Float>> {
        val resolved = resolveFile(profile.loraPath) ?: return emptyList()

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

    private fun resolveControlVector(profile: ExpertProfile): ControlVector? {
        val resolved = resolveFile(profile.cvecPath) ?: return null
        return synchronized(this) {
            loadedCvecs[resolved.absolutePath] ?: ControlVectorCodec.read(resolved)?.also {
                loadedCvecs[resolved.absolutePath] = it
            }
        }
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

        private fun optNonBlankString(obj: JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return obj.optString(key, "").trim().ifBlank { null }
        }

        private fun parseProfile(
            domain: ExpertDomain,
            obj: JSONObject,
            fallback: ExpertProfile?,
        ): ExpertProfile {
            val loraPath = when {
                obj.has("loraPath") -> optNonBlankString(obj, "loraPath")
                obj.has("lora_path") -> optNonBlankString(obj, "lora_path")
                obj.has("lora") -> optNonBlankString(obj, "lora")
                else -> fallback?.loraPath
            }
            val loraScale = when {
                obj.has("loraScale") && !obj.isNull("loraScale") -> obj.optDouble("loraScale", 1.0).toFloat()
                obj.has("lora_scale") && !obj.isNull("lora_scale") -> obj.optDouble("lora_scale", 1.0).toFloat()
                obj.has("scale") && !obj.isNull("scale") -> obj.optDouble("scale", 1.0).toFloat()
                else -> fallback?.loraScale ?: 1.0f
            }
            val temperature = when {
                obj.has("temperature") && !obj.isNull("temperature") -> obj.optDouble("temperature", 0.45).toFloat()
                obj.has("temp") && !obj.isNull("temp") -> obj.optDouble("temp", 0.45).toFloat()
                else -> fallback?.temperature ?: 0.45f
            }
            val repeatPenalty = when {
                obj.has("repeatPenalty") && !obj.isNull("repeatPenalty") -> obj.optDouble("repeatPenalty", 1.18).toFloat()
                obj.has("repeat_penalty") && !obj.isNull("repeat_penalty") -> obj.optDouble("repeat_penalty", 1.18).toFloat()
                else -> fallback?.repeatPenalty ?: 1.18f
            }
            val kwArray = obj.optJSONArray("keywords")
            val keywords = if (kwArray != null) {
                List(kwArray.length()) { idx -> kwArray.optString(idx, "").trim() }
                    .filter { it.isNotEmpty() }
            } else {
                fallback?.keywords ?: emptyList()
            }
            val cvecPath = when {
                obj.has("cvecPath") -> optNonBlankString(obj, "cvecPath")
                obj.has("cvec_path") -> optNonBlankString(obj, "cvec_path")
                obj.has("cvec") -> optNonBlankString(obj, "cvec")
                else -> fallback?.cvecPath
            }
            val cvecScale = when {
                obj.has("cvecScale") && !obj.isNull("cvecScale") -> obj.optDouble("cvecScale", 0.0).toFloat()
                obj.has("cvec_scale") && !obj.isNull("cvec_scale") -> obj.optDouble("cvec_scale", 0.0).toFloat()
                else -> fallback?.cvecScale ?: 0.0f
            }
            val cvecLayerStart = when {
                obj.has("cvecLayerStart") && !obj.isNull("cvecLayerStart") -> obj.optInt("cvecLayerStart", 8)
                obj.has("cvec_layer_start") && !obj.isNull("cvec_layer_start") -> obj.optInt("cvec_layer_start", 8)
                else -> fallback?.cvecLayerStart ?: 8
            }
            val cvecLayerEnd = when {
                obj.has("cvecLayerEnd") && !obj.isNull("cvecLayerEnd") -> obj.optInt("cvecLayerEnd", 21)
                obj.has("cvec_layer_end") && !obj.isNull("cvec_layer_end") -> obj.optInt("cvec_layer_end", 21)
                else -> fallback?.cvecLayerEnd ?: 21
            }
            return ExpertProfile(
                domain = domain,
                loraPath = loraPath,
                loraScale = loraScale,
                temperature = temperature,
                repeatPenalty = repeatPenalty,
                keywords = keywords,
                cvecPath = cvecPath,
                cvecScale = cvecScale,
                cvecLayerStart = cvecLayerStart,
                cvecLayerEnd = cvecLayerEnd,
            )
        }
    }
}
