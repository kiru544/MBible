package com.example.mbible

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.Layout
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Rich-text engine for notes — a small WYSIWYG built on Android spans.
 *
 * The core idea:
 *  - The note BODY stays plain text in the database (so the verse-reference
 *    regex, search, and JSON export all keep working unchanged).
 *  - Formatting lives as spans attached to the EditText's Editable while
 *    editing, and is serialized to a compact JSON array (the `formatting`
 *    column) on save: [{"t":"B","s":0,"e":5}, {"t":"S","s":0,"e":5,"v":1.9}].
 *  - Spans automatically MOVE with the text as the user types (that's built
 *    into Editable), so we only serialize positions at save time.
 *
 * Every span we add is a private subclass (FBold, FSize, ...) rather than the
 * framework class directly. That's the trick that lets us find, remove, and
 * serialize exactly OUR spans without disturbing anything else living in the
 * same Editable — most importantly the verse-reference ClickableSpans.
 */
object NoteFormatting {

    // Inline spans grow when you type at their right edge (EXCLUSIVE_INCLUSIVE),
    // which is how people expect bold to behave while typing.
    private const val INLINE_FLAGS = Spanned.SPAN_EXCLUSIVE_INCLUSIVE

    // ---------------------------------------------------------------- spans

    class FBold : StyleSpan(Typeface.BOLD)
    class FItalic : StyleSpan(Typeface.ITALIC)
    class FUnderline : UnderlineSpan()
    class FSize(val scale: Float) : RelativeSizeSpan(scale)
    class FDim(color: Int) : ForegroundColorSpan(color)
    class FAlign(val align: Layout.Alignment) : AlignmentSpan.Standard(align)

    // Image feature — an inline image anchored to one placeholder character
    // (\uFFFC, the Unicode "object replacement character"). The span carries
    // the file name of the image inside the app's private note_images folder;
    // that name is what gets serialized, never the pixels.
    class FImage(drawable: Drawable, val fileName: String, val scale: Float = 1f) :
        android.text.style.ImageSpan(drawable)

    /**
     * Resize feature — an invisible ClickableSpan riding on the image's
     * placeholder character. LinkMovementMethod (already active for verse
     * links) delivers the tap; [onTap] hands the tapped FImage to the UI.
     * Never serialized: it's rebuilt whenever the image span is.
     */
    class ImageClick(val image: FImage, private val onTap: (FImage) -> Unit) :
        android.text.style.ClickableSpan() {
        override fun onClick(widget: android.view.View) = onTap(image)
        override fun updateDrawState(ds: android.text.TextPaint) {
            // no underline/color — the character is hidden behind the image anyway
        }
    }

    private fun isOurs(span: Any) =
        span is FBold || span is FItalic || span is FUnderline ||
                span is FSize || span is FDim || span is FAlign ||
                span is FImage || span is ImageClick

    // ------------------------------------------------------- paragraph styles

    /**
     * Named paragraph styles, Word-style. Each is a recipe of size + weight +
     * tone applied over one paragraph. NOTE renders as a small dim italic
     * annotation; BODY is the "clear formatting" option.
     */
    enum class LineStyle(val scale: Float?, val bold: Boolean, val italic: Boolean, val dim: Boolean) {
        TITLE(1.9f, true, false, false),
        SUBTITLE(1.45f, true, false, false),
        HEADING(1.2f, true, false, false),
        BODY(null, false, false, false),
        NOTE(0.85f, false, true, true)
    }

    // --------------------------------------------------------- serialization

    /** Serialize our spans to JSON, or null if the note has no formatting. */
    fun toJson(text: Spanned): String? {
        val arr = JSONArray()
        for (span in text.getSpans(0, text.length, Any::class.java)) {
            if (!isOurs(span)) continue
            if (span is ImageClick) continue // runtime-only helper, rebuilt on load
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            if (s < 0 || e <= s) continue // zero-length spans carry no styling

            val o = JSONObject().put("s", s).put("e", e)
            when (span) {
                is FBold -> o.put("t", "B")
                is FItalic -> o.put("t", "I")
                is FUnderline -> o.put("t", "U")
                is FDim -> o.put("t", "D")
                is FSize -> o.put("t", "S").put("v", span.scale.toDouble())
                is FImage -> {
                    o.put("t", "G").put("v", span.fileName)
                    if (span.scale != 1f) o.put("sc", span.scale.toDouble())
                }
                is FAlign -> o.put("t", "A").put(
                    "v", when (span.align) {
                        Layout.Alignment.ALIGN_CENTER -> "C"
                        Layout.Alignment.ALIGN_OPPOSITE -> "R" // "opposite" = end = right in LTR
                        else -> "L"
                    }
                )
            }
            arr.put(o)
        }
        return if (arr.length() == 0) null else arr.toString()
    }

    /**
     * Re-attach saved spans onto a freshly loaded note. Positions are clamped
     * to the current text length, and corrupt JSON degrades gracefully to a
     * plain-text note instead of crashing.
     *
     * [dimColor] is passed in (rather than stored) so the NOTE style always
     * uses the CURRENT theme's dim color — saved notes follow light/dark mode.
     */
    fun applyJson(
        context: Context,
        editable: Editable,
        json: String?,
        dimColor: Int,
        onImageTap: (FImage) -> Unit = {}
    ) {
        clearAll(editable)
        if (json.isNullOrBlank()) return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = o.getInt("s").coerceIn(0, editable.length)
                val e = o.getInt("e").coerceIn(0, editable.length)
                if (e <= s) continue
                val span: Any = when (o.getString("t")) {
                    "B" -> FBold()
                    "I" -> FItalic()
                    "U" -> FUnderline()
                    "D" -> FDim(dimColor)
                    "G" -> {
                        // Missing file (e.g. note imported on another device
                        // without its images) → skip the span; the placeholder
                        // character stays as an invisible char, nothing crashes.
                        val name = o.optString("v")
                        val scale = o.optDouble("sc", 1.0).toFloat()
                        val drawable = imageDrawable(context, name, scale) ?: continue
                        FImage(drawable, name, scale)
                    }
                    "S" -> FSize(o.optDouble("v", 1.0).toFloat())
                    "A" -> FAlign(
                        when (o.optString("v")) {
                            "C" -> Layout.Alignment.ALIGN_CENTER
                            "R" -> Layout.Alignment.ALIGN_OPPOSITE
                            else -> Layout.Alignment.ALIGN_NORMAL
                        }
                    )
                    else -> continue
                }
                if (span is FImage) {
                    // Images must NOT grow while typing next to them, and each
                    // one gets its invisible tap-target for the resize menu.
                    editable.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    editable.setSpan(
                        ImageClick(span, onImageTap), s, e,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else {
                    editable.setSpan(span, s, e, INLINE_FLAGS)
                }
            }
        } catch (_: JSONException) {
            clearAll(editable) // corrupt formatting → plain text, never a crash
        }
    }

    private fun clearAll(editable: Editable) {
        for (span in editable.getSpans(0, editable.length, Any::class.java)) {
            if (isOurs(span)) editable.removeSpan(span)
        }
    }

    // -------------------------------------------------------- inline toggles

    fun toggleBold(edit: EditText) = toggleInline(edit, FBold::class.java) { FBold() }
    fun toggleItalic(edit: EditText) = toggleInline(edit, FItalic::class.java) { FItalic() }
    fun toggleUnderline(edit: EditText) = toggleInline(edit, FUnderline::class.java) { FUnderline() }

    /**
     * Word-processor toggle semantics:
     *  - selection fully styled  → un-style it (splitting any span that
     *    extends past the selection, so only the selected part changes)
     *  - selection partly/un-styled → style all of it
     *  - no selection → operate on the word under the cursor
     */
    private fun <T : Any> toggleInline(edit: EditText, cls: Class<T>, make: () -> T) {
        val text = edit.text ?: return
        val range = targetRange(edit) ?: return
        val (s, e) = range

        if (isFullyCovered(text, s, e, cls)) {
            removeTypeInRange(text, s, e, cls) { make() }
        } else {
            removeTypeInRange(text, s, e, cls) { make() } // absorb partial overlaps
            text.setSpan(make(), s, e, INLINE_FLAGS)
        }
    }

    // ------------------------------------------------------- paragraph tools

    /** The paragraph (line) containing [pos]: from after the previous \n to the next \n. */
    private fun paragraphBounds(text: CharSequence, pos: Int): Pair<Int, Int> {
        val p = pos.coerceIn(0, text.length)
        var start = p
        while (start > 0 && text[start - 1] != '\n') start--
        var end = p
        while (end < text.length && text[end] != '\n') end++
        return start to end
    }

    /**
     * The paragraphs covered by the selection: from the START of the paragraph
     * containing selectionStart to the END of the paragraph containing
     * selectionEnd. With no selection this collapses to the cursor's paragraph,
     * so the old single-line behavior still works.
     */
    private fun selectionParagraphRange(edit: EditText): Pair<Int, Int>? {
        val text = edit.text ?: return null
        var selS = edit.selectionStart
        var selE = edit.selectionEnd
        if (selS < 0 || selE < 0) return null
        if (selS > selE) { val t = selS; selS = selE; selE = t }
        val start = paragraphBounds(text, selS).first
        val end = paragraphBounds(text, selE).second
        return start to end
    }

    /** Left / Center / Right for every paragraph touched by the selection. */
    fun setAlignment(edit: EditText, align: Layout.Alignment) {
        val text = edit.text ?: return
        val (s, e) = selectionParagraphRange(edit) ?: return
        for (span in text.getSpans(s, e, FAlign::class.java)) text.removeSpan(span)
        // Left is the default — representing it as "no span" keeps the saved
        // JSON minimal and means unformatted paragraphs stay untouched.
        if (align != Layout.Alignment.ALIGN_NORMAL && e > s) {
            text.setSpan(FAlign(align), s, e, INLINE_FLAGS)
        }
    }

    /**
     * Apply a named style (Title / Subtitle / Heading / Body / Note).
     *
     * WITH a selection: styles exactly the selected characters — nothing more.
     * WITHOUT a selection: styles the paragraph the cursor is in (tapping
     * "Title" with just a cursor on a line should still make it a title).
     *
     * BODY doubles as "clear formatting" for the range.
     */
    fun applyLineStyle(edit: EditText, style: LineStyle, dimColor: Int) {
        val text = edit.text ?: return
        var s = edit.selectionStart
        var e = edit.selectionEnd
        if (s < 0 || e < 0) return
        if (s > e) { val t = s; s = e; e = t }
        if (s == e) {
            // No selection → the cursor's paragraph.
            val p = paragraphBounds(text, s)
            s = p.first; e = p.second
        }
        if (e > s) styleRange(text, s, e, style, dimColor)
    }

    private fun styleRange(text: Editable, s: Int, e: Int, style: LineStyle, dimColor: Int) {
        // Clear existing formatting INSIDE the range only. removeTypeInRange
        // splits spans that stick out past the range, so styling a selection
        // in the middle of an already-styled line leaves the outside intact.
        removeTypeInRange(text, s, e, FSize::class.java) { old -> FSize(old.scale) }
        removeTypeInRange(text, s, e, FDim::class.java) { FDim(dimColor) }
        removeTypeInRange(text, s, e, FBold::class.java) { FBold() }
        removeTypeInRange(text, s, e, FItalic::class.java) { FItalic() }

        style.scale?.let { text.setSpan(FSize(it), s, e, INLINE_FLAGS) }
        if (style.bold) text.setSpan(FBold(), s, e, INLINE_FLAGS)
        if (style.italic) text.setSpan(FItalic(), s, e, INLINE_FLAGS)
        if (style.dim) text.setSpan(FDim(dimColor), s, e, INLINE_FLAGS)
    }

    // ------------------------------------------------------------------ images

    /**
     * Insert the stored image [fileName] at the cursor, on its own line.
     * The image is ONE character (\uFFFC) wearing an FImage span — delete the
     * character and the image goes with it, exactly like any letter.
     */
    fun insertImage(
        edit: EditText,
        context: Context,
        fileName: String,
        onTap: (FImage) -> Unit = {}
    ) {
        val drawable = imageDrawable(context, fileName) ?: return
        val text = edit.text ?: return
        var pos = edit.selectionEnd
        if (pos < 0) pos = text.length
        pos = pos.coerceIn(0, text.length)

        val sb = StringBuilder()
        if (pos > 0 && text[pos - 1] != '\n') sb.append('\n')
        val imgOffset = sb.length
        sb.append('\uFFFC').append('\n')

        text.insert(pos, sb)
        val imgStart = pos + imgOffset
        val span = FImage(drawable, fileName)
        text.setSpan(
            span, imgStart, imgStart + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE // an image never "grows" while typing
        )
        text.setSpan(
            ImageClick(span, onTap), imgStart, imgStart + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        edit.setSelection((imgStart + 2).coerceAtMost(text.length))
    }

    /**
     * Resize feature — swap an inserted image to a new size. The image's spans
     * are replaced in place; the placeholder character and the stored file
     * never change, only the drawable and the serialized "sc" factor.
     */
    fun setImageScale(
        edit: EditText,
        context: Context,
        image: FImage,
        scale: Float,
        onTap: (FImage) -> Unit
    ) {
        val text = edit.text ?: return
        val s = text.getSpanStart(image)
        val e = text.getSpanEnd(image)
        if (s < 0 || e <= s) return

        val drawable = imageDrawable(context, image.fileName, scale) ?: return
        // remove the old pair (image + its tap target)
        for (click in text.getSpans(s, e, ImageClick::class.java)) {
            if (click.image === image) text.removeSpan(click)
        }
        text.removeSpan(image)

        val newSpan = FImage(drawable, image.fileName, scale)
        text.setSpan(newSpan, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(ImageClick(newSpan, onTap), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Load a stored image scaled to scale x 85% of screen width, ready for an ImageSpan. */
    private fun imageDrawable(context: Context, name: String, scale: Float = 1f): Drawable? {
        if (name.isBlank()) return null
        val maxW = (context.resources.displayMetrics.widthPixels * 0.85f * scale.coerceIn(0.1f, 1f)).toInt()
        val bmp = NoteImageStore.loadScaled(context, name, maxW) ?: return null
        return BitmapDrawable(context.resources, bmp).apply {
            setBounds(0, 0, bmp.width, bmp.height) // ImageSpan sizes from bounds
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The selection, or the word under the cursor when nothing is selected. */
    private fun targetRange(edit: EditText): Pair<Int, Int>? {
        val text = edit.text ?: return null
        var s = edit.selectionStart
        var e = edit.selectionEnd
        if (s < 0 || e < 0) return null
        if (s > e) { val t = s; s = e; e = t }
        if (s != e) return s to e
        // No selection → expand to the surrounding word.
        var ws = s
        var we = s
        while (ws > 0 && !text[ws - 1].isWhitespace()) ws--
        while (we < text.length && !text[we].isWhitespace()) we++
        return if (we > ws) ws to we else null
    }

    /** True if every character in [s, e) is covered by at least one span of [cls]. */
    private fun <T : Any> isFullyCovered(text: Editable, s: Int, e: Int, cls: Class<T>): Boolean {
        var pos = s
        val spans = text.getSpans(s, e, cls).sortedBy { text.getSpanStart(it) }
        for (span in spans) {
            if (text.getSpanStart(span) > pos) return false // gap found
            pos = maxOf(pos, text.getSpanEnd(span))
            if (pos >= e) return true
        }
        return pos >= e
    }

    /**
     * Remove spans of one type from [s, e), SPLITTING any span that sticks out
     * past the range: the outside portions are re-created via [remake] so only
     * the selected characters lose the style. This is the core trick behind
     * "unbold just this word in the middle of a bold sentence".
     */
    private fun <T : Any> removeTypeInRange(
        text: Editable, s: Int, e: Int, cls: Class<T>, remake: (T) -> Any
    ) {
        for (span in text.getSpans(s, e, cls)) {
            val ss = text.getSpanStart(span)
            val se = text.getSpanEnd(span)
            text.removeSpan(span)
            if (ss < s) text.setSpan(remake(span), ss, s, INLINE_FLAGS)
            if (se > e) text.setSpan(remake(span), e, se, INLINE_FLAGS)
        }
    }
}
