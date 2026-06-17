package com.example.mbible.data

import com.youversion.platform.core.api.YouVersionApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * BibleSource backed by the YouVersion Platform SDK, with a local chapter
 * cache so the same passage isn't fetched twice.
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

    override suspend fun getBooks(testament: String): List<String> =
        BookMapping.namesInTestament(testament)

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

    private suspend fun loadChapter(bookUsfm: String, chapter: Int): List<Verse> =
        withContext(Dispatchers.IO) {
            cache.get(versionId, bookUsfm, chapter)?.let { return@withContext it }

            val verses = fetchChapterFromSdk(bookUsfm, chapter)
            if (verses.isNotEmpty()) {
                cache.put(versionId, bookUsfm, chapter, verses)
            }
            verses
        }

    private suspend fun fetchChapterFromSdk(bookUsfm: String, chapter: Int): List<Verse> {
        val passageId = "${bookUsfm.uppercase()}.$chapter"
        return try {
            Log.d("RemoteBible", "Fetching versionId=$versionId passageId=$passageId")
            val passage = YouVersionApi.bible.passage(versionId, passageId, "html")
            Log.d("RemoteBible", "Raw HTML length=${passage.content.length}")
            val verses = parseHtmlPassage(passage.content)
            Log.d("RemoteBible", "Parsed ${verses.size} verses")
            verses
        } catch (e: Exception) {
            Log.e("RemoteBible", "Fetch failed for $passageId", e)
            lastError = e
            emptyList()
        }
    }

    private val footnoteOpen = Regex("""<span class="[^"]*\byv-n\b[^"]*"[^>]*>""")
    private val spanToken    = Regex("""<span\b[^>]*>|</span>""")
    private val paraDivOpen  = Regex("""<div class="p">""")

    /** Sentinels that survive tag-strip + whitespace-collapse. */
    private val fnMark = '\u0001'     // where a footnote was
    private val paraMark = '\u0002'   // a <div class="p"> paragraph break

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
     * Removes every yv-n footnote from one verse's raw HTML, leaving the rest of
     * the markup (wj spans, divs, etc.) intact. A [fnMark] sentinel is left where
     * each footnote sat. Returns (noteFreeHtml, footnoteTexts) in document order.
     */
    private fun extractFootnotes(segment: String): Pair<String, List<String>> {
        val noteTexts = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < segment.length) {
            val m = footnoteOpen.find(segment, i)
            if (m == null) {
                sb.append(segment, i, segment.length)
                break
            }
            sb.append(segment, i, m.range.first)
            val innerStart = m.range.last + 1
            val closeIdx = matchingSpanClose(segment, innerStart)
            if (closeIdx == -1) {
                i = segment.length
            } else {
                val flat = stripTags(segment.substring(innerStart, closeIdx))
                if (flat.isNotEmpty()) {
                    noteTexts.add(flat)
                    sb.append(fnMark)
                }
                i = closeIdx + "</span>".length
            }
        }
        return sb.toString() to noteTexts
    }

    /** Result of a single body parse: red-aware segments + footnote offsets into the text. */
    private class ParsedBody(val segments: List<VerseSegment>, val fnOffsets: List<Int>)

    /**
     * Single pass over one verse's (footnote-free) HTML that produces everything
     * that has to stay aligned:
     *  - red/normal [VerseSegment]s (tracking span nesting so a non-wj span inside
     *    a wj span doesn't end the red region early),
     *  - the character offset of each [fnMark] into the emitted text,
     *  - a real '\n' for each [paraMark] (mid-verse paragraph break).
     * The verse text is the concatenation of the segment texts.
     */
    private fun parseBody(html: String): ParsedBody {
        val segs = mutableListOf<VerseSegment>()
        val cur = StringBuilder()
        var curRed = false
        var spanDepth = 0
        var inWj = false
        var wjCloseDepth = -1
        var lastWasSpace = true
        var emitted = 0
        val offsets = mutableListOf<Int>()

        fun flush() { if (cur.isNotEmpty()) { segs.add(VerseSegment(cur.toString(), curRed)); cur.setLength(0) } }
        fun setRed(red: Boolean) { if (red != curRed) { flush(); curRed = red } }
        fun emitChar(c: Char) {
            if (c.isWhitespace()) {
                if (!lastWasSpace) { cur.append(' '); lastWasSpace = true; emitted++ }
            } else { cur.append(c); lastWasSpace = false; emitted++ }
        }

        var i = 0
        while (i < html.length) {
            val c = html[i]
            when (c) {
                '<' -> {
                    val gt = html.indexOf('>', i)
                    val end = if (gt == -1) html.length else gt + 1
                    val tag = html.substring(i, end)
                    if (tag.startsWith("</")) {
                        if (tag.startsWith("</span")) {
                            if (spanDepth > 0) spanDepth--
                            if (inWj && spanDepth == wjCloseDepth) { inWj = false; setRed(false) }
                        }
                    } else if (tag.startsWith("<span")) {
                        if (isWjOpen(tag) && !inWj) { inWj = true; wjCloseDepth = spanDepth; setRed(true) }
                        spanDepth++
                    }
                    i = end
                }
                fnMark -> { offsets.add(emitted); i++ }
                paraMark -> {
                    while (cur.isNotEmpty() && cur.last() == ' ') { cur.deleteCharAt(cur.length - 1); emitted-- }
                    if (emitted > 0) { cur.append('\n'); emitted++; lastWasSpace = true }
                    i++
                }
                '&' -> {
                    val semi = html.indexOf(';', i)
                    val rep = if (semi != -1 && semi - i <= 5) when (html.substring(i, semi + 1)) {
                        "&nbsp;" -> " "
                        "&amp;"  -> "&"
                        "&quot;" -> "\""
                        "&#39;"  -> "'"
                        else     -> null
                    } else null
                    if (rep != null) { for (ch in rep) emitChar(ch); i = semi + 1 } else { emitChar('&'); i++ }
                }
                else -> { emitChar(c); i++ }
            }
        }
        flush()
        if (segs.isNotEmpty()) {
            val last = segs.last()
            segs[segs.size - 1] = last.copy(text = last.text.trimEnd())
            if (segs.last().text.isEmpty()) segs.removeAt(segs.size - 1)
        }
        return ParsedBody(segs, offsets)
    }

    private fun parseHtmlPassage(html: String): List<Verse> {
        // 1. Drop the printed verse-number labels.
        var cleaned = html.replace(
            Regex("""<span class="yv-vlbl">.*?</span>""", RegexOption.DOT_MATCHES_ALL),
            ""
        )

        val anchorRe = Regex("""<span class="yv-v" v="(\d+)"></span>""")

        // 2. Section headings: <div class="… yv-h">TEXT</div> sit BETWEEN verses.
        val headingDiv = Regex("""<div class="[^"]*yv-h[^"]*">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        val headingFor = HashMap<Int, String>()
        for (h in headingDiv.findAll(cleaned)) {
            val htext = stripTags(h.groupValues[1])
            if (htext.isEmpty()) continue
            val next = anchorRe.find(cleaned, h.range.last + 1) ?: continue
            next.groupValues[1].toIntOrNull()?.let { headingFor[it] = htext }
        }
        cleaned = cleaned.replace(headingDiv, "")

        // 3. Split on verse anchors and build each verse.
        val matches = anchorRe.findAll(cleaned).toList()
        val verses = mutableListOf<Verse>()
        for (i in matches.indices) {
            val verseNum = matches[i].groupValues[1].toIntOrNull() ?: continue
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else cleaned.length

            val rawSegment = cleaned.substring(start, end)

            // Pull footnotes out (tags otherwise intact), mark paragraph breaks,
            // then do ONE body parse that yields text + red segments + offsets.
            val (noteFreeHtml, noteTexts) = extractFootnotes(rawSegment)
            val vhtml = noteFreeHtml.replace(paraDivOpen, paraMark.toString())
            val parsed = parseBody(vhtml)

            val text = parsed.segments.joinToString("") { it.text }
            if (text.isEmpty()) continue

            val footnotes = noteTexts.mapIndexed { idx, t ->
                Footnote(t, parsed.fnOffsets.getOrElse(idx) { text.length })
            }
            val segs = if (parsed.segments.any { it.isJesus }) parsed.segments else emptyList()

            verses.add(Verse(verseNum, text, footnotes, headingFor[verseNum], segs))
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

    private fun isWjOpen(tag: String): Boolean =
        tag.startsWith("<span") && (tag.contains("class=\"wj\"") || tag.contains("class=\"wj "))
}

/**
 * Chapter counts for the 66-book Protestant canon (same across KJV, NIV, ESV...).
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