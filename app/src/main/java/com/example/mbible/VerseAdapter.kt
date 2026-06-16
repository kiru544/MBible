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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.mbible.data.VerseSegment

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

    fun buildSpannable(context: android.content.Context): SpannableStringBuilder {
        val spannable = SpannableStringBuilder()
        val numColor = context.getColor(R.color.accent_red)



        for ((index, verse) in verses.withIndex()) {
            val numStr = "${verse.verse}"
            verse.heading?.let { h ->
                val hStart = spannable.length
                spannable.append(h)
                val hEnd = spannable.length
                spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(RelativeSizeSpan(1.15f), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(ForegroundColorSpan(numColor), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.append("\n")
            }
            val numStart = spannable.length
            spannable.append(numStr)
            val numEnd = numStart + numStr.length
            spannable.setSpan(SuperscriptSpan(), numStart, numEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(RelativeSizeSpan(0.62f), numStart, numEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(numColor), numStart, numEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), numStart, numEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            spannable.append(" ")

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

            // Raised initial on the first letter of verse 1.
            if (index == 0 && verse.text.isNotEmpty()) {
                spannable.setSpan(RelativeSizeSpan(2.0f), textStart, textStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(ForegroundColorSpan(numColor), textStart, textStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            // Footnote marker: a single dimmed "※" at the END of the verse.
            // Baseline-aligned (no superscript) and slightly enlarged so it's an
            // easy tap target. A leading space widens the touch area. Tapping it
            // opens an AlertDialog with this verse's footnote text(s).
            if (verse.footnotes.isNotEmpty()) {
                val capturedNotes = verse.footnotes
                val mStart = spannable.length
                spannable.append(" *")
                val mEnd = spannable.length
                spannable.setSpan(RelativeSizeSpan(1.2f), mStart, mEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(
                    ForegroundColorSpan(context.getColor(R.color.text_tertiary)),
                    mStart, mEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        MaterialAlertDialogBuilder(widget.context, R.style.ThemeOverlay_MBible_Dialog)
                            .setTitle(if (capturedNotes.size > 1) "Footnotes" else "Footnote")
                            .setMessage(capturedNotes.joinToString("\n\n") { it.text })
                            .setPositiveButton("Close", null)
                            .show()
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        // Style only — do NOT build spannable content in here.
                        // Not calling super: keep the marker's own dim color and
                        // suppress the default link underline/tint.
                        ds.isUnderlineText = false
                    }
                }, mStart, mEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            if (index < verses.size - 1) spannable.append("\n\n")
        }

        return spannable
    }
}