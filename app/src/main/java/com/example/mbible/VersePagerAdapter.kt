package com.example.mbible

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.example.mbible.data.BibleRepository
import kotlinx.coroutines.Job
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

        // Improvement #3 — the coroutine currently loading THIS holder's chapter.
        // ViewPager2 recycles holders: when a holder is rebound to a new chapter,
        // the old load may still be running and would overwrite the new chapter's
        // text when it finishes. Keeping the Job on the holder lets us cancel the
        // stale load the moment the holder is reused.
        var loadJob: Job? = null
    }

    // Pinch-to-zoom — the reader's text size in sp. Initialized from
    // SharedPreferences on the first page created, updated live during a pinch,
    // and applied to every page as it binds.
    private var textSizeSp: Float = -1f

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter_page, parent, false)
        val holder = VH(view)

        if (textSizeSp < 0f) {
            // Default = whatever the layout says (18.5sp), so the pref is a no-op
            // until the user actually pinches for the first time.
            textSizeSp = TextZoom.savedSp(
                parent.context, TextZoom.KEY_BIBLE, TextZoom.currentSp(holder.textView)
            )
        }

        // Attach to BOTH the page (ScrollView) and the TextView: on chapters
        // with tappable footnotes the TextView owns the touch stream, on plain
        // chapters the ScrollView does. One shared detector handles either.
        TextZoom.attach(
            TextZoom.KEY_BIBLE,
            startSp = { textSizeSp },
            onScaled = { sp ->
                textSizeSp = sp
                // ViewPager2 keeps neighbor pages attached and already bound, so
                // besides this page we push the new size to every attached page —
                // otherwise swiping right after a pinch shows the old size.
                (holder.itemView.parent as? RecyclerView)?.let { rv ->
                    for (i in 0 until rv.childCount) {
                        rv.getChildAt(i).findViewById<TextView>(R.id.chapterText)
                            ?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                    }
                }
            },
            holder.itemView, holder.textView
        )
        return holder
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chapter = position + 1
        holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        holder.textView.text = holder.itemView.context.getString(R.string.loading)

        // Improvement #3 — cancel any load left over from this holder's previous life.
        holder.loadJob?.cancel()
        holder.loadJob = lifecycleOwner.lifecycleScope.launch {
            val verses = bibleRepo.getVerses(bookName, testament, chapter)

            // Belt-and-braces: if the holder was rebound while we were suspended,
            // this coroutine was cancelled above — but a cheap position check
            // guards against any window between rebind and cancellation.
            // (adapterPosition, not bindingAdapterPosition: the newer name needs
            // RecyclerView 1.2+, and this project's transitive version is older.
            // With a single adapter per RecyclerView they behave identically.)
            if (holder.adapterPosition != position) return@launch

            // §5H — an empty result on a remote translation usually means the fetch
            // failed; tell the user instead of showing a blank page.
            if (verses.isEmpty()) {
                val ctx = holder.itemView.context
                holder.textView.text = if (bibleRepo.lastRemoteError != null)
                    ctx.getString(R.string.chapter_load_failed)
                else
                    ctx.getString(R.string.no_verses_found)
                return@launch
            }

            // Improvement #7 — plain builder instead of the old fake "VerseAdapter".
            val builder = ChapterSpannableBuilder(verses)
            holder.textView.text = builder.buildSpannable(holder.itemView.context)
            // Make the footnote marker tappable, but only when this chapter actually
            // has footnotes — leaves KJV / footnote-free chapters untouched.
            if (verses.any { it.footnotes.isNotEmpty() }) {
                holder.textView.movementMethod =
                    android.text.method.LinkMovementMethod.getInstance()
                holder.textView.highlightColor = android.graphics.Color.TRANSPARENT
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        // Improvement #3 — also stop the load when the holder goes off-screen
        // entirely; there's nothing left to display into.
        holder.loadJob?.cancel()
        holder.loadJob = null
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = chapterCount
}
