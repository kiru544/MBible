package com.example.mbible

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.example.mbible.data.BibleRepository
import kotlinx.coroutines.launch

class VersePagerAdapter(
    private val bookName: String,
    private val testament: String,
    private val chapterCount: Int,
    private val bibleRepo: BibleRepository,
    private val lifecycleOwner: LifecycleOwner
) : RecyclerView.Adapter<VersePagerAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(R.id.chapterText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter_page, parent, false)
        return VH(view)
    }
    @Suppress("WrongConstant")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val chapter = position + 1
        holder.textView.text = "Loading…"

        lifecycleOwner.lifecycleScope.launch {
            val verses = bibleRepo.getVerses(bookName, testament, chapter)

            // §5H — an empty result on a remote translation usually means the fetch
            // failed; tell the user instead of showing a blank page.
            if (verses.isEmpty()) {
                holder.textView.text = if (bibleRepo.lastRemoteError != null)
                    "Couldn't load this chapter.\nCheck your connection and try again."
                else
                    "No verses found."
                return@launch
            }

            val adapter = VerseAdapter(verses)
            holder.textView.text = adapter.buildSpannable(holder.itemView.context)
            // Make the footnote "*" tappable, but only when this chapter actually
            // has footnotes — leaves KJV / footnote-free chapters untouched.
            if (verses.any { it.footnotes.isNotEmpty() }) {
                holder.textView.movementMethod =
                    android.text.method.LinkMovementMethod.getInstance()
                holder.textView.highlightColor = android.graphics.Color.TRANSPARENT
            }

        }
    }

    override fun getItemCount() = chapterCount
}