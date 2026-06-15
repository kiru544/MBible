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
            "SELECT id, title, body, updated_at FROM notes ORDER BY updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                list.add(Note(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
            }
        }
        list
    }

    suspend fun getById(id: Long): Note? = withContext(Dispatchers.IO) {
        helper.readableDatabase.rawQuery(
            "SELECT id, title, body, updated_at FROM notes WHERE id = ? LIMIT 1",
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return@withContext null
            Note(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3))
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

    suspend fun update(id: Long, title: String, body: String) {
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("title", title)
                put("body", body)
                put("updated_at", System.currentTimeMillis())
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
        org.json.JSONArray().put(obj).toString(2)
    }

    // Folds in the earlier cleanup: ONE insert per note instead of insert+update,
    // and it now preserves the original updated_at on round‑trip import/export.
    suspend fun importFromJson(json: String): Int = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray(json)
        var count = 0
        val db = helper.writableDatabase
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val values = ContentValues().apply {
                put("title", obj.optString("title", "Imported Note"))
                put("body", obj.optString("body", ""))
                put("updated_at", obj.optLong("updated_at", System.currentTimeMillis()))
            }
            if (db.insert("notes", null, values) != -1L) count++
        }
        count
    }
}