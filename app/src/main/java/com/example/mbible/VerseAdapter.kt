package com.example.mbible

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.mbible.data.Verse
import com.example.mbible.data.VerseSegment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class VerseAdapter(
    private val verses: List<Verse>
) : RecyclerView.Adapter<VerseAdapter.VH>() {

    class VH(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_verse, parent, false) as TextView
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Only one item — the whole chapter as one paragraph
    }

    override fun getItemCount() = 1

    private val letters = "abcdefghijklmnopqrstuvwxyz"

    /** Insert dimmed superscript a/b/c markers into [sb] at each footnote's offset. */
    private fun appendFootnoteLetters(
        sb: SpannableStringBuilder,
        base: Int,
        verse: Verse,
        dimColor: Int
    ) {
        // Insert back-to-front so earlier offsets don't shift as we add characters.
        verse.footnotes
            .withIndex()
            .sortedByDescending { it.value.offset }
            .forEach { (idx, fn) ->
                val at = (base + fn.offset).coerceIn(0, sb.length)
                val label = letters.getOrElse(idx) { '*' }.toString()
                sb.insert(at, label)
                sb.setSpan(SuperscriptSpan(), at, at + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.7f), at, at + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(dimColor), at, at + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
    }

    /** Builds the popup body: the verse text with a/b/c markers, then the labeled notes. */
    private fun buildFootnoteDialogBody(context: android.content.Context, verse: Verse): SpannableStringBuilder {
        val dim = context.getColor(R.color.text_tertiary)
        val body = SpannableStringBuilder()

        // 1) verse text with inline markers at their offsets
        body.append(verse.text)
        appendFootnoteLetters(body, 0, verse, dim)

        // 2) blank line, then each note as "a   <text>"
        body.append("\n\n")
        verse.footnotes.forEachIndexed { idx, fn ->
            val label = letters.getOrElse(idx) { '*' }.toString()
            val ls = body.length
            body.append(label)
            body.setSpan(StyleSpan(android.graphics.Typeface.BOLD), ls, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            body.setSpan(ForegroundColorSpan(dim), ls, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            body.append("   ")
            body.append(fn.text)
            if (idx < verse.footnotes.size - 1) body.append("\n\n")
        }
        return body
    }

    fun buildSpannable(context: android.content.Context): SpannableStringBuilder {
        val spannable = SpannableStringBuilder()
        val numColor = context.getColor(R.color.accent_red)
        val dimColor = context.getColor(R.color.text_tertiary)

        for ((index, verse) in verses.withIndex()) {
            val numStr = "${verse.verse}"

            // Section heading on its own line above the verse.
            verse.heading?.let { h ->
                if (spannable.isNotEmpty()) spannable.append("\n\n")
                val hStart = spannable.length
                spannable.append(h)
                val hEnd = spannable.length
                spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(RelativeSizeSpan(1.15f), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(ForegroundColorSpan(numColor), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.append("\n")
            }

            // Verse number (capture start AFTER the heading so spans land on the number).
            val numStart = spannable.length
            spannable.append(numStr)
            val numEnd = numStart + numStr.length
            spannable.setSpan(SuperscriptSpan(), numStart, numEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(RelativeSizeSpan(0.62f), numStart, numEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(numColor), numStart, numEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), numStart, numEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            spannable.append(" ")

            // Verse body (red-letter aware).
            val textStart = spannable.length
            if (verse.segments.isEmpty()) {
                spannable.append(verse.text)
            } else {
                val wjColor = context.getColor(R.color.wj_red)
                for (seg in verse.segments) {
                    val segStart = spannable.length
                    spannable.append(seg.text)
                    if (seg.isJesus) {
                        spannable.setSpan(ForegroundColorSpan(wjColor), segStart, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            // Inline footnote letters (a/b/c) at their positions within this verse.
            // textStart is the verse body's start; offsets are relative to verse.text.
            if (verse.footnotes.isNotEmpty()) {
                appendFootnoteLetters(spannable, textStart, verse, numColor)
            }

            // Footnote tap target: a single dimmed "※" at the END of the verse.
            // Tapping it opens a popup showing the verse with markers + the notes.
            if (verse.footnotes.isNotEmpty()) {
                val capturedVerse = verse
                val mStart = spannable.length
                spannable.append(" 🗒️")
                val mEnd = spannable.length
                spannable.setSpan(RelativeSizeSpan(1.2f), mStart, mEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(ForegroundColorSpan(dimColor), mStart, mEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        MaterialAlertDialogBuilder(widget.context, R.style.ThemeOverlay_MBible_Dialog)
                            .setTitle(if (capturedVerse.footnotes.size > 1) "Footnotes" else "Footnote")
                            .setMessage(buildFootnoteDialogBody(widget.context, capturedVerse))
                            .setPositiveButton("Close", null)
                            .show()
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        // Style only — keep the marker's dim color, no link underline/tint.
                        ds.isUnderlineText = false
                    }
                }, mStart, mEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (index < verses.size - 1) spannable.append(" ")
        }

        return spannable
    }
}