package com.example.mbible

import android.content.ContentValues
import android.content.Context
import com.example.mbible.data.BibleBooks
import com.example.mbible.data.NotesDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookAliasRepository(context: Context) {
    private val helper = NotesDbHelper(context.applicationContext)
    private val canonicalBooks = BibleBooks.ALL

    suspend fun getAliasesForBook(book: String): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        helper.readableDatabase.rawQuery(
            "SELECT alias FROM book_aliases WHERE canonical_book=? ORDER BY alias",
            arrayOf(book)
        ).use { while (it.moveToNext()) out.add(it.getString(0)) }
        out
    }

    // Load every book's aliases in one query — used by the Settings list so it
    // doesn't fire a query per row while scrolling (review §2 / performance).
    suspend fun getAllAliases(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val map = HashMap<String, MutableList<String>>()
        helper.readableDatabase.rawQuery(
            "SELECT canonical_book, alias FROM book_aliases ORDER BY alias", null
        ).use { c ->
            while (c.moveToNext()) {
                map.getOrPut(c.getString(0)) { mutableListOf() }.add(c.getString(1))
            }
        }
        map
    }

    suspend fun addAlias(book: String, aliasRaw: String): Boolean = withContext(Dispatchers.IO) {
        val alias = normalize(aliasRaw)
        if (alias.isEmpty()) return@withContext false
        try {
            val values = ContentValues().apply {
                put("canonical_book", book)
                put("alias", alias)
            }
            helper.writableDatabase.insertOrThrow("book_aliases", null, values)
            true
        } catch (_: Exception) {
            false // unique‑index violation or other constraint
        }
    }

    suspend fun deleteAlias(aliasRaw: String) {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.delete("book_aliases", "alias=?", arrayOf(normalize(aliasRaw)))
        }
    }

    suspend fun resolveBookToken(tokenRaw: String): String? = withContext(Dispatchers.IO) {
        val token = normalize(tokenRaw)
        if (token.isEmpty()) return@withContext null

        helper.readableDatabase.rawQuery(
            "SELECT canonical_book FROM book_aliases WHERE alias=? LIMIT 1",
            arrayOf(token)
        ).use { if (it.moveToFirst()) return@withContext it.getString(0) }

        canonicalBooks.firstOrNull { normalize(it) == token }
    }

    fun normalize(s: String): String =
        s.trim().lowercase().replace(" ", "").replace(".", "")
}