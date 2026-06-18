package com.example.mbible.data

import android.content.Context

/**
 * Public façade used by Fragments / ViewModels.
 *
 * Picks a [BibleSource] based on which translation the user has active in
 * [TranslationPrefs], then delegates every read.
 *
 * Behaviour notes:
 * - Methods are `suspend`. Call them from `lifecycleScope.launch { ... }`
 *   or a ViewModel scope.
 * - The active source is cached and only rebuilt when the active translation
 *   id changes, so reading [lastRemoteError] no longer constructs anything.
 * - Build this once (see MBibleApp) and share it; LocalBibleSource opens the
 *   bundled DB lazily on first query.
 */
class BibleRepository(private val context: Context) {

    private val prefs = TranslationPrefs(context)
    private val local: BibleSource = LocalBibleSource(context)

    // Created lazily so apps with no remote translation never open the cache DB.
    private val cache: ChapterCache by lazy { ChapterCache(context) }

    /** One remote source per translation id, so SDK plumbing isn't rebuilt per call. */
    private val remoteSources = mutableMapOf<String, RemoteBibleSource>()

    // Cache the active source so property reads (lastRemoteError) have no side effects.
    @Volatile private var cachedSource: BibleSource? = null
    @Volatile private var cachedTranslationId: String? = null

    private fun activeSource(): BibleSource {
        val t = prefs.activeTranslation
        if (t.id == cachedTranslationId) cachedSource?.let { return it }
        val src: BibleSource = when (t.kind) {
            Translation.Kind.LOCAL -> local
            Translation.Kind.REMOTE -> remoteSources.getOrPut(t.id) {
                RemoteBibleSource(t, cache)
            }
        }
        cachedSource = src
        cachedTranslationId = t.id
        return src
    }

    val activeTranslation: Translation get() = prefs.activeTranslation

    /** Most recent error from the active remote source, or null. No construction here. */
    val lastRemoteError: Exception?
        get() = (cachedSource as? RemoteBibleSource)?.lastError

    fun setActiveTranslation(id: String) {
        prefs.activeTranslationId = id
    }

    // --- BibleSource pass-through ---------------------------------------

    suspend fun getBooks(testament: String): List<String> =
        activeSource().getBooks(testament)

    suspend fun getChapterCount(bookName: String, testament: String): Int =
        activeSource().getChapterCount(bookName, testament)

    suspend fun getVerses(bookName: String, testament: String, chapter: Int): List<Verse> =
        activeSource().getVerses(bookName, testament, chapter)

    suspend fun getVerseRange(
        bookName: String, chapter: Int, startVerse: Int, endVerse: Int
    ): List<Verse> =
        activeSource().getVerseRange(bookName, chapter, startVerse, endVerse)

    suspend fun getVerseCount(bookName: String, chapter: Int): Int =
        activeSource().getVerseCount(bookName, chapter)

    suspend fun verseExists(bookName: String, chapter: Int, verse: Int): Boolean =
        activeSource().verseExists(bookName, chapter, verse)

    // --- Local-only validation (used by note highlighting) --------------
    // The canon (books + chapter/verse counts) is identical across translations,
    // so reference validation always uses the bundled local source and never the
    // network. Verse *text* still comes from the active translation above.

    suspend fun verseExistsLocal(bookName: String, chapter: Int, verse: Int): Boolean =
        local.verseExists(bookName, chapter, verse)

    suspend fun getVerseCountLocal(bookName: String, chapter: Int): Int =
        local.getVerseCount(bookName, chapter)
}