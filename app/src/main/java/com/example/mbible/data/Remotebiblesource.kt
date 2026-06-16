package com.example.mbible.data

import com.youversion.platform.core.api.YouVersionApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * BibleSource backed by the YouVersion Platform SDK, with a local chapter
 * cache so the same passage isn't fetched twice.
 *
 * Translations served this way (e.g. NIV) require an internet connection
 * the *first* time the user opens any given chapter; after that, the
 * chapter is replayed from [ChapterCache] and works offline.
 *
 * IMPORTANT — field names below marked with TODOs are best-guesses from the
 * SDK's public API reference. After adding the SDK dependency, let Android
 * Studio's autocomplete confirm them and adjust as needed:
 *   - BibleVerse: verse number field & content field
 *   - BiblePassage: content field
 *
 * Reference:
 *   https://developers.youversion.com/sdks/kotlin
 *   https://mintlify.wiki/youversion/platform-sdk-kotlin/api/bible-api
 */
class RemoteBibleSource(
    private val translation: Translation,
    private val cache: ChapterCache
) : BibleSource {

    private val versionId: Int =
        requireNotNull(translation.youVersionId) {
            "REMOTE translation ${translation.id} must have a youVersionId"
        }
    @Volatile var lastError: Exception? = null
        private set

    /**
     * Books and the canon structure don't depend on the translation at our level
     * of abstraction — we always show the same 66 Protestant books. So instead of
     * an API round-trip on every screen, we serve book lists from BookMapping.
     *
     * If you later add translations from other canons (Catholic, Orthodox),
     * this is the place to revisit.
     */
    override suspend fun getBooks(testament: String): List<String> =
        BookMapping.namesInTestament(testament)

    /**
     * Chapter counts are likewise canon-stable across the translations we
     * currently support. Hardcoded fast-path: ask the cached version if we
     * have any verses for this book, otherwise fall back to a single API
     * call. (For NIV specifically, chapter counts match KJV exactly.)
     *
     * A future improvement: pre-seed chapter counts from
     * YouVersionApi.bible.versionIndex(versionId) on first launch.
     */
    override suspend fun getChapterCount(bookName: String, testament: String): Int {
        val meta = BookMapping.byName(bookName) ?: return 0
        return ChapterCounts.forBook(meta.usfm)
    }

    override suspend fun getVerses(
        bookName: String,
        testament: String,
        chapter: Int
    ): List<Verse> {
        val meta = BookMapping.byName(bookName) ?: return emptyList()
        return loadChapter(meta.usfm, chapter)
    }

    override suspend fun getVerseRange(
        bookName: String,
        chapter: Int,
        startVerse: Int,
        endVerse: Int
    ): List<Verse> {
        val meta = BookMapping.byName(bookName) ?: return emptyList()
        return loadChapter(meta.usfm, chapter)
            .filter { it.verse in startVerse..endVerse }
    }

    override suspend fun getVerseCount(bookName: String, chapter: Int): Int {
        val meta = BookMapping.byName(bookName) ?: return 0
        return loadChapter(meta.usfm, chapter).maxOfOrNull { it.verse } ?: 0
    }

    override suspend fun verseExists(bookName: String, chapter: Int, verse: Int): Boolean {
        val meta = BookMapping.byName(bookName) ?: return false
        return loadChapter(meta.usfm, chapter).any { it.verse == verse }
    }

    /**
     * Fetch a chapter, going through the cache.
     * Cache miss → call YouVersion SDK → parse → write cache → return.
     */
    private suspend fun loadChapter(bookUsfm: String, chapter: Int): List<Verse> =
        withContext(Dispatchers.IO) {
            cache.get(versionId, bookUsfm, chapter)?.let { return@withContext it }

            val verses = fetchChapterFromSdk(bookUsfm, chapter)
            if (verses.isNotEmpty()) {
                cache.put(versionId, bookUsfm, chapter, verses)
            }
            verses
        }

    /**
     * Fetches the chapter via the passage endpoint (returns HTML), then parses
     * individual verses out of it.
     *
     * BibleVerse (the SDK model) only carries id/passageId/title — no text.
     * Actual verse text lives in BiblePassage.content as HTML:
     *   <span class="yv-v" v="1"></span><span class="yv-vlbl">1</span>verse text…
     * We split on the v="N" markers and strip tags from each segment.
     */
    private suspend fun fetchChapterFromSdk(bookUsfm: String, chapter: Int): List<Verse> {
        val passageId = "${bookUsfm.uppercase()}.$chapter"
        return try {
            Log.d("RemoteBible", "Fetching versionId=$versionId passageId=$passageId")
            val passage = YouVersionApi.bible.passage(versionId, passageId, "html")
            Log.d("RemoteBible", "Raw HTML length=${passage.content.length}")
            Log.d("RemoteBible", "Raw HTML: ${passage.content.take(1000)}")
            val verses = parseHtmlPassage(passage.content)
            Log.d("RemoteBible", "Parsed ${verses.size} verses")
            verses
        } catch (e: Exception) {
            Log.e("RemoteBible", "Fetch failed for $passageId", e)
            lastError = e
            emptyList()
        }
    }
    private val footnoteOpen = Regex("""<span class="yv-n[^"]*">""")
    private val spanToken    = Regex("""<span\b[^>]*>|</span>""")

    /**
     * Given the index just AFTER an outer <span ...> open tag, return the index of
     * the bracket of its MATCHING </span>, accounting for nested spans
     * (fr / ft / fqa / ref / xt). Returns -1 if the markup is unbalanced.
     */
    private fun matchingSpanClose(s: String, from: Int): Int {
        var depth = 1
        var idx = from
        while (true) {
            val m = spanToken.find(s, idx) ?: return -1
            if (m.value.startsWith("</span")) {
                depth--
                if (depth == 0) return m.range.first
            } else {
                depth++
            }
            idx = m.range.last + 1
        }
    }

    /**
     * Removes every <span class="yv-n ...>…</span> footnote from one verse's raw HTML.
     * Returns the HTML with footnotes removed, plus the flattened footnote texts
     * (e.g. "6:7 Greek take two hundred denarii").
     */
    private fun extractFootnotes(segment: String): Pair<String, List<Footnote>> {
        val notes = mutableListOf<Footnote>()
        val sb = StringBuilder()
        var i = 0
        while (i < segment.length) {
            val m = footnoteOpen.find(segment, i)
            if (m == null) {
                sb.append(segment, i, segment.length)
                break
            }
            sb.append(segment, i, m.range.first)          // keep text before the footnote
            val innerStart = m.range.last + 1
            val closeIdx = matchingSpanClose(segment, innerStart)
            if (closeIdx == -1) {
                i = segment.length                          // malformed: drop remainder of note
            } else {
                val flat = stripTags(segment.substring(innerStart, closeIdx))
                if (flat.isNotEmpty()) notes.add(Footnote(flat))
                i = closeIdx + "</span>".length             // resume after the footnote
            }
        }
        return sb.toString() to notes
    }
    private fun parseHtmlPassage(html: String): List<Verse> {
        // 1. Drop the printed verse-number labels.
        var cleaned = html.replace(
            Regex("""<span class="yv-vlbl">.*?</span>""", RegexOption.DOT_MATCHES_ALL),
            ""
        )

        val anchorRe = Regex("""<span class="yv-v" v="(\d+)"></span>""")

        // 2. NEW — Section headings: <div class="… yv-h">TEXT</div> sit BETWEEN verses.
        //    Attach each heading to the FIRST verse anchor that follows it, then remove
        //    the heading divs so their text never leaks into a verse.
        val headingDiv = Regex("""<div class="[^"]*yv-h[^"]*">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        val headingFor = HashMap<Int, String>()
        for (h in headingDiv.findAll(cleaned)) {
            val htext = stripTags(h.groupValues[1])
            if (htext.isEmpty()) continue
            val next = anchorRe.find(cleaned, h.range.last + 1) ?: continue   // the verse that follows
            next.groupValues[1].toIntOrNull()?.let { headingFor[it] = htext } // nearest heading wins
        }
        cleaned = cleaned.replace(headingDiv, "")   // strip headings from inline text

        // 3. Split on verse anchors and build each verse.
        val matches = anchorRe.findAll(cleaned).toList()
        val verses = mutableListOf<Verse>()
        for (i in matches.indices) {
            val verseNum = matches[i].groupValues[1].toIntOrNull() ?: continue
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else cleaned.length

            val rawSegment = cleaned.substring(start, end)
            val (noteFree, notes) = extractFootnotes(rawSegment)   // footnote feature — unchanged
            val text = stripTags(noteFree)
            val segs = parseVerseSegments(noteFree)
            val safeSegs = if (segs.joinToString("") { it.text } == text && segs.any { it.isJesus }) segs else emptyList()
            if (text.isNotEmpty()) verses.add(Verse(verseNum, text, notes, headingFor[verseNum], safeSegs))
        }
        return verses
    }
    private fun stripTags(s: String): String {
        return s
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
    /** Strip verse-label spans, all other HTML tags, then collapse whitespace. */
    private fun String.cleaned(): String =
        replace(Regex("""<span[^>]*yv-vlbl[^>]*>\d+</span>"""), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    private fun isWjOpen(tag: String): Boolean =
        tag.startsWith("<span") && (tag.contains("class=\"wj\"") || tag.contains("class=\"wj "))

    /**
     * Splits footnote-free verse HTML into red/normal segments, cleaning text the
     * SAME way stripTags does (remove tags, unescape the 4 entities, collapse
     * whitespace, trim) so the concatenation equals stripTags(html).
     */
    private fun parseVerseSegments(html: String): List<VerseSegment> {
        val segs = mutableListOf<VerseSegment>()
        val cur = StringBuilder()
        var curRed = false
        var spanDepth = 0          // count of all currently-open <span>s
        var inWj = false
        var wjCloseDepth = -1       // span depth at which the active wj span will close
        var lastWasSpace = true     // drop leading whitespace, like trim()

        fun flush() { if (cur.isNotEmpty()) { segs.add(VerseSegment(cur.toString(), curRed)); cur.setLength(0) } }
        fun setRed(red: Boolean) { if (red != curRed) { flush(); curRed = red } }
        fun emit(s: String) {
            for (c in s) {
                if (c.isWhitespace()) { if (!lastWasSpace) { cur.append(' '); lastWasSpace = true } }
                else { cur.append(c); lastWasSpace = false }
            }
        }

        var i = 0
        while (i < html.length) {
            val c = html[i]
            when {
                c == '<' -> {
                    val gt = html.indexOf('>', i)
                    val end = if (gt == -1) html.length else gt + 1
                    val tag = html.substring(i, end)
                    if (tag.startsWith("</")) {
                        if (tag.startsWith("</span")) {
                            if (spanDepth > 0) spanDepth--
                            if (inWj && spanDepth == wjCloseDepth) { inWj = false; setRed(false) }
                        }
                        // other closing tags (</div> etc.) are ignored
                    } else if (tag.startsWith("<span")) {
                        if (isWjOpen(tag) && !inWj) { inWj = true; wjCloseDepth = spanDepth; setRed(true) }
                        spanDepth++          // count every span, wj or not
                    }
                    // other opening tags (<div ...>) are ignored, not counted
                    i = end
                }
                c == '&' -> {
                    val semi = html.indexOf(';', i)
                    val rep = if (semi != -1 && semi - i <= 5) when (html.substring(i, semi + 1)) {
                        "&nbsp;" -> " "
                        "&amp;"  -> "&"
                        "&quot;" -> "\""
                        "&#39;"  -> "'"
                        else     -> null
                    } else null
                    if (rep != null) { emit(rep); i = semi + 1 } else { emit("&"); i++ }
                }
                else -> { emit(c.toString()); i++ }
            }
        }
        flush()
        if (segs.isNotEmpty()) {                      // mirror trailing trim()
            val last = segs.last()
            segs[segs.size - 1] = last.copy(text = last.text.trimEnd())
            if (segs.last().text.isEmpty()) segs.removeAt(segs.size - 1)
        }
        return segs
    }
}

/**
 * Chapter counts for the 66-book Protestant canon (same across KJV, NIV, ESV...).
 * Used to answer getChapterCount() for remote translations without an API call.
 */
private object ChapterCounts {
    private val byUsfm: Map<String, Int> = mapOf(
        "GEN" to 50, "EXO" to 40, "LEV" to 27, "NUM" to 36, "DEU" to 34,
        "JOS" to 24, "JDG" to 21, "RUT" to  4, "1SA" to 31, "2SA" to 24,
        "1KI" to 22, "2KI" to 25, "1CH" to 29, "2CH" to 36, "EZR" to 10,
        "NEH" to 13, "EST" to 10, "JOB" to 42, "PSA" to 150,"PRO" to 31,
        "ECC" to 12, "SNG" to  8, "ISA" to 66, "JER" to 52, "LAM" to  5,
        "EZK" to 48, "DAN" to 12, "HOS" to 14, "JOL" to  3, "AMO" to  9,
        "OBA" to  1, "JON" to  4, "MIC" to  7, "NAM" to  3, "HAB" to  3,
        "ZEP" to  3, "HAG" to  2, "ZEC" to 14, "MAL" to  4,
        "MAT" to 28, "MRK" to 16, "LUK" to 24, "JHN" to 21, "ACT" to 28,
        "ROM" to 16, "1CO" to 16, "2CO" to 13, "GAL" to  6, "EPH" to  6,
        "PHP" to  4, "COL" to  4, "1TH" to  5, "2TH" to  3, "1TI" to  6,
        "2TI" to  4, "TIT" to  3, "PHM" to  1, "HEB" to 13, "JAS" to  5,
        "1PE" to  5, "2PE" to  3, "1JN" to  5, "2JN" to  1, "3JN" to  1,
        "JUD" to  1, "REV" to 22
    )

    fun forBook(usfm: String): Int = byUsfm[usfm] ?: 0
}