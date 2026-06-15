package com.example.mbible.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * BibleSource backed by the bundled bible.db (KJV).
 *
 * The DB handle is opened lazily on first query rather than in the constructor,
 * so building this (and the first-run copy from assets inside
 * BibleDatabaseHelper.getDatabasePath) happens on Dispatchers.IO at the first
 * read instead of on the main thread when a fragment is created. Because the
 * owning BibleRepository is now an app-scoped singleton, this one handle is
 * reused for the app's lifetime instead of leaking a new connection per screen.
 */
class LocalBibleSource(private val context: Context) : BibleSource {

    private val db: SQLiteDatabase by lazy {
        SQLiteDatabase.openDatabase(
            BibleDatabaseHelper.getDatabasePath(context.applicationContext),
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }

    override suspend fun getBooks(testament: String): List<String> = withContext(Dispatchers.IO) {
        val books = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM books WHERE testament = ? ORDER BY book_order",
            arrayOf(testament)
        ).use { c ->
            while (c.moveToNext()) books.add(c.getString(0))
        }
        books
    }

    override suspend fun getChapterCount(bookName: String, testament: String): Int =
        withContext(Dispatchers.IO) {
            db.rawQuery(
                """
                SELECT MAX(v.chapter)
                FROM verses v
                JOIN books b ON b.id = v.book_id
                WHERE b.name = ? AND b.testament = ?
                """.trimIndent(),
                arrayOf(bookName, testament)
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0
            }
        }

    override suspend fun getVerses(bookName: String, testament: String, chapter: Int): List<Verse> =
        withContext(Dispatchers.IO) {
            val verses = mutableListOf<Verse>()
            db.rawQuery(
                """
                SELECT v.verse, v.text
                FROM verses v
                JOIN books b ON b.id = v.book_id
                WHERE b.name = ? AND b.testament = ? AND v.chapter = ?
                ORDER BY v.verse
                """.trimIndent(),
                arrayOf(bookName, testament, chapter.toString())
            ).use { c ->
                while (c.moveToNext()) verses.add(Verse(c.getInt(0), c.getString(1)))
            }
            verses
        }

    override suspend fun getVerseRange(
        bookName: String, chapter: Int, startVerse: Int, endVerse: Int
    ): List<Verse> = withContext(Dispatchers.IO) {
        val verses = mutableListOf<Verse>()
        db.rawQuery(
            """
            SELECT v.verse, v.text
            FROM verses v
            JOIN books b ON b.id = v.book_id
            WHERE b.name = ? AND v.chapter = ? AND v.verse BETWEEN ? AND ?
            ORDER BY v.verse
            """.trimIndent(),
            arrayOf(bookName, chapter.toString(), startVerse.toString(), endVerse.toString())
        ).use { c ->
            while (c.moveToNext()) verses.add(Verse(c.getInt(0), c.getString(1)))
        }
        verses
    }

    override suspend fun getVerseCount(bookName: String, chapter: Int): Int =
        withContext(Dispatchers.IO) {
            db.rawQuery(
                """
                SELECT MAX(v.verse)
                FROM verses v
                JOIN books b ON b.id = v.book_id
                WHERE b.name = ? AND v.chapter = ?
                """.trimIndent(),
                arrayOf(bookName, chapter.toString())
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0
            }
        }

    override suspend fun verseExists(bookName: String, chapter: Int, verse: Int): Boolean =
        withContext(Dispatchers.IO) {
            db.rawQuery(
                """
                SELECT 1
                FROM verses v
                JOIN books b ON b.id = v.book_id
                WHERE b.name = ? AND v.chapter = ? AND v.verse = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(bookName, chapter.toString(), verse.toString())
            ).use { it.moveToFirst() }
        }
}