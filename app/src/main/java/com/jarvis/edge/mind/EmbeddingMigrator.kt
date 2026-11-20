package com.jarvis.edge.mind

/**
 * Re-embeds stored active facts in place when switching to an embedding model with a new vector dimension.
 */
object EmbeddingMigrator {

    fun migrateIfNeeded(
        store: MindRepository,
        targetDim: Int,
        embedFn: (String) -> FloatArray,
    ): Int {
        if (targetDim <= 0) return 0
        var migrated = 0
        val facts = store.listActiveFacts(500)
        for (fact in facts) {
            if (fact.embedding.size == targetDim) continue
            val newEmbedding = runCatching { embedFn(fact.text) }.getOrNull() ?: continue
            if (newEmbedding.size != targetDim) continue
            store.learnFact(
                text = fact.text,
                embedding = newEmbedding,
                sourceEpisodeId = fact.sourceEpisodeId,
                confidence = fact.confidence,
            )
            migrated++
        }
        return migrated
    }
}
