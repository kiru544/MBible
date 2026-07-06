package com.example.mbible.data

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NotesRepository(context: Context) {
    // applicationContext so the helper never holds a Fragment/Activity context.
    private val helper = NotesDbHelper(context.applicationContext)

    suspend fun getAll(): List<Note> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Note>()
        helper.readableDatabase.rawQuery(
            "SELECT id, title, body, updated_at, formatting FROM notes ORDER BY updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(Note(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),
                    if (c.isNull(4)) null else c.getString(4)))
            }
        }
        list
    }

    suspend fun getById(id: Long): Note? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT id, title, body, updated_at, formatting FROM notes WHERE id = ? LIMIT 1",
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return@withContext null
            Note(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3),
                if (c.isNull(4)) null else c.getString(4))
        }
    }

    suspend fun create(title: String): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("title", title)
            put("body", "")
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.insert("notes", null, values)
    }

    // Rich-text feature — [formatting] is deliberately a required parameter
    // (no default): every caller must decide what happens to the formatting,
    // so a forgotten argument can never silently wipe a user's styling.
    suspend fun update(id: Long, title: String, body: String, formatting: String?) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("title", title)
                put("body", body)
                put("updated_at", System.currentTimeMillis())
                put("formatting", formatting) // null clears the column (plain note)
            }
            helper.writableDatabase.update("notes", values, "id=?", arrayOf(id.toString()))
        }
    }

    suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.delete("notes", "id=?", arrayOf(id.toString()))
        }
    }

    suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        val jsonArray = org.json.JSONArray()
        for (note in getAll()) {
            jsonArray.put(
                org.json.JSONObject()
                    .put("title", note.title)
                    .put("body", note.body)
                    .put("updated_at", note.updatedAt)
                    // JSONObject skips null values, so plain notes simply omit the key.
                    .putOpt("formatting", note.formatting)
            )
        }
        jsonArray.toString(2)
    }

    suspend fun exportOneToJson(id: Long): String = withContext(Dispatchers.IO) {
        val note = getById(id) ?: return@withContext "[]"
        val obj = org.json.JSONObject()
            .put("title", note.title)
            .put("body", note.body)
            .put("updated_at", note.updatedAt)
            .putOpt("formatting", note.formatting)
        org.json.JSONArray().put(obj).toString(2)
    }

    // Folds in the earlier cleanup: ONE insert per note instead of insert+update,
    // and it now preserves the original updated_at on round‑trip import/export.
    // Improvement #8 — the whole import now runs inside ONE transaction:
    //  * Atomic: a crash mid-import rolls back instead of leaving half the notes.
    //  * Fast: SQLite syncs to disk once at commit instead of once per insert
    //    (importing hundreds of notes goes from seconds to milliseconds).
    //  * De-duplicated: a note whose title AND updated_at already exist is
    //    skipped, so re-importing the same export file doesn't double everything.
    suspend fun importFromJson(json: String): Int = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray(json)
        var count = 0
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val title = obj.optString("title", "Imported Note")
                val updatedAt = obj.optLong("updated_at", System.currentTimeMillis())

                val exists = db.rawQuery(
                    "SELECT 1 FROM notes WHERE title = ? AND updated_at = ? LIMIT 1",
                    arrayOf(title, updatedAt.toString())
                ).use { it.moveToFirst() }
                if (exists) continue

                val values = ContentValues().apply {
                    put("title", title)
                    put("body", obj.optString("body", ""))
                    put("updated_at", updatedAt)
                    // Rich-text feature — restore formatting when the export has it.
                    if (obj.has("formatting") && !obj.isNull("formatting")) {
                        put("formatting", obj.getString("formatting"))
                    }
                }
                if (db.insert("notes", null, values) != -1L) count++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        count
    }
}