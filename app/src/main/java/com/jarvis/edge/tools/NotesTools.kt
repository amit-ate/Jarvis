package com.jarvis.edge.tools

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NotesStore(context: Context) :
    SQLiteOpenHelper(context, "jarvis_notes.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(text: String) {
        val values = ContentValues().apply {
            put("text", text)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("notes", null, values)
    }

    fun search(query: String, limit: Int = 5): List<String> {
        val words = query.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length >= 2 }
            .distinct()
        if (words.isEmpty()) {
            return readableDatabase.rawQuery(
                "SELECT text FROM notes ORDER BY id DESC LIMIT ?",
                arrayOf(limit.toString()),
            ).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
        }
        val all = readableDatabase.rawQuery(
            "SELECT text FROM notes ORDER BY id DESC LIMIT 200",
            null,
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return all.map { note ->
            val lower = note.lowercase()
            note to words.count { it in lower }
        }.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}

class RememberTool(private val store: NotesStore) : Tool {
    override val name = "remember"
    override val description = "Save a note or fact for later"
    override val params = mapOf("text" to null)

    override suspend fun run(args: Map<String, String>): ToolResult {
        val text = (args["text"] ?: args["note"])?.trim().orEmpty()
        if (text.isEmpty()) {
            return ToolResult("What should I remember?", ok = false)
        }
        store.add(text)
        return ToolResult("Got it, I'll remember that.", ok = true)
    }
}

class RecallTool(private val store: NotesStore) : Tool {
    override val name = "recall"
    override val description = "Search saved notes by keywords"
    override val params = mapOf("query" to null)

    override suspend fun run(args: Map<String, String>): ToolResult {
        val query = args["query"]?.trim().orEmpty()
        val matches = store.search(query)
        if (matches.isEmpty()) {
            return ToolResult(
                spoken = "I don't have anything saved about that.",
                ok = true,
                feedToModel = "No matching notes found.",
            )
        }
        return ToolResult(
            spoken = matches.first(),
            ok = true,
            feedToModel = matches.joinToString("\n") { "- $it" },
        )
    }
}
