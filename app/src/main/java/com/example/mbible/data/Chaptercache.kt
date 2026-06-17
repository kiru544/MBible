package com.example.mbible.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists chapters fetched from a remote provider (YouVersion Platform).
 * Chapters the user has read while online become available offline next time.
 *
 * Schema: one row per (translation, book USFM, chapter) holding a JSON-encoded
 * list of verses. JSON is used (rather than per-verse rows) so a chapter is
 * written atomically — no partially-cached chapters on a mid-fetch crash.
 */
class ChapterCache(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Cache only — safe to wipe & rebuild whenever the stored shape changes.
        db.execSQL("DROP TABLE IF EXISTS chapter_cache")
        onCreate(db)
    }

    fun get(versionId: Int, bookUsfm: String, chapter: Int): List<Verse>? {
        readableDatabase.rawQuery(
            "SELECT verses_json FROM chapter_cache WHERE version_id=? AND book_usfm=? AND chapter=?",
            arrayOf(versionId.toString(), bookUsfm, chapter.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return parseVerses(c.getString(0))
        }
    }

    fun put(versionId: Int, bookUsfm: String, chapter: Int, verses: List<Verse>) {
        val arr = JSONArray()
        for (v in verses) {
            val fArr = JSONArray()
            for (f in v.footnotes) {
                fArr.put(JSONObject().put("t", f.text).put("o", f.offset))
            }

            val o = JSONObject()
                .put("n", v.verse)
                .put("t", v.text)
                .put("f", fArr)
            if (v.heading != null) o.put("h", v.heading)
            if (v.segments.isNotEmpty()) {
                val segArr = JSONArray()
                for (seg in v.segments) segArr.put(JSONObject().put("s", seg.text).put("j", seg.isJesus))
                o.put("seg", segArr)
            }
            arr.put(o)   // add only after o is fully populated
        }
        val values = ContentValues().apply {
            put("version_id", versionId)
            put("book_usfm", bookUsfm)
            put("chapter", chapter)
            put("verses_json", arr.toString())
            put("fetched_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "chapter_cache", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun has(versionId: Int, bookUsfm: String, chapter: Int): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM chapter_cache WHERE version_id=? AND book_usfm=? AND chapter=? LIMIT 1",
            arrayOf(versionId.toString(), bookUsfm, chapter.toString())
        ).use { return it.moveToFirst() }
    }

    fun clearTranslation(versionId: Int) {
        writableDatabase.delete(
            "chapter_cache", "version_id=?", arrayOf(versionId.toString())
        )
    }

    private fun parseVerses(json: String): List<Verse> {
        val arr = JSONArray(json)
        val out = ArrayList<Verse>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)

            val verseText = o.getString("t")

            // Footnotes: new shape is {"t","o"}; tolerate the old bare-string shape
            // (offset unknown → pin to end of verse).
            val notes = mutableListOf<Footnote>()
            o.optJSONArray("f")?.let { fa ->
                for (j in 0 until fa.length()) {
                    val fn = fa.opt(j)
                    if (fn is JSONObject) {
                        notes.add(Footnote(fn.getString("t"), fn.optInt("o", verseText.length)))
                    } else {
                        notes.add(Footnote(fn.toString(), verseText.length))
                    }
                }
            }

            val heading = if (o.has("h")) o.getString("h") else null

            val segs = mutableListOf<VerseSegment>()
            o.optJSONArray("seg")?.let { sa ->
                for (k in 0 until sa.length()) {
                    val so = sa.getJSONObject(k)
                    segs.add(VerseSegment(so.getString("s"), so.getBoolean("j")))
                }
            }

            out.add(Verse(o.getInt("n"), verseText, notes, heading, segs))
        }
        return out
    }

    companion object {
        private const val DB_NAME = "remote_bible_cache.db"
        private const val DB_VERSION = 5
        private const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS chapter_cache (
                version_id INTEGER NOT NULL,
                book_usfm TEXT NOT NULL,
                chapter INTEGER NOT NULL,
                verses_json TEXT NOT NULL,
                fetched_at INTEGER NOT NULL,
                PRIMARY KEY (version_id, book_usfm, chapter)
            )
        """
    }
}