package com.jarvis.edge.mind

import com.jarvis.edge.brain.Turn
import org.json.JSONArray
import org.json.JSONObject

/** Volatile working memory worth surviving a process kill. */
data class WorkingState(
    val version: Int = 1,
    val savedAtMs: Long,
    val history: List<Turn>,
    val lastTurnMs: Long,
    val pendingHeard: String?,
    val pendingCandidate: String?,
    val initiativeMutedUntilMs: Long,
    val openEpisodeId: Long?,
)

object WorkingStateCodec {
    private const val CURRENT_VERSION = 1

    fun encode(s: WorkingState): String {
        val turns = JSONArray()
        s.history.forEach { turns.put(JSONObject().put("u", it.user).put("a", it.assistant)) }
        return JSONObject()
            .put("version", s.version)
            .put("savedAtMs", s.savedAtMs)
            .put("history", turns)
            .put("lastTurnMs", s.lastTurnMs)
            .put("pendingHeard", s.pendingHeard ?: JSONObject.NULL)
            .put("pendingCandidate", s.pendingCandidate ?: JSONObject.NULL)
            .put("mutedUntilMs", s.initiativeMutedUntilMs)
            .put("openEpisodeId", s.openEpisodeId ?: JSONObject.NULL)
            .toString()
    }

    fun decode(json: String): WorkingState? = try {
        val o = JSONObject(json)
        if (o.getInt("version") != CURRENT_VERSION) {
            null
        } else {
            val arr = o.getJSONArray("history")
            WorkingState(
                version = CURRENT_VERSION,
                savedAtMs = o.getLong("savedAtMs"),
                history = (0 until arr.length()).map {
                    val t = arr.getJSONObject(it)
                    Turn(user = t.getString("u"), assistant = t.getString("a"))
                },
                lastTurnMs = o.getLong("lastTurnMs"),
                pendingHeard = if (o.isNull("pendingHeard")) null else o.getString("pendingHeard"),
                pendingCandidate = if (o.isNull("pendingCandidate")) null else o.getString("pendingCandidate"),
                initiativeMutedUntilMs = o.getLong("mutedUntilMs"),
                openEpisodeId = if (o.isNull("openEpisodeId")) null else o.getLong("openEpisodeId"),
            )
        }
    } catch (_: Exception) {
        null
    }
}

class WorkingStateStore(private val repo: MindRepository) {
    fun save(s: WorkingState) = repo.setMeta(KEY, WorkingStateCodec.encode(s))

    fun load(): WorkingState? = repo.getMeta(KEY)?.let { WorkingStateCodec.decode(it) }

    private companion object {
        const val KEY = "working_state"
    }
}
