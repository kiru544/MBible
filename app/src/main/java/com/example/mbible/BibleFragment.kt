package com.example.mbible

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.mbible.data.BibleRepository
import kotlinx.coroutines.launch

class BibleFragment : Fragment() {

    private lateinit var bibleRepo: BibleRepository
    private lateinit var bookPager: ViewPager2
    private lateinit var chaptersRecycler: RecyclerView
    private lateinit var chaptersTopBar: View
    private lateinit var selectedBookTitle: TextView

    private var testament: String = "Old"
    private var currentBook: String? = null
    private var currentChapter: Int? = null
    private lateinit var translationPicker: TextView
    private var inChaptersView = false
    private var inVersesView = false
    private lateinit var books: List<String>

    private lateinit var btnSwitchMode: Button
    private lateinit var btnThemeToggle: ImageButton
    private var isCardMode = true
    private lateinit var bookList: android.widget.ListView
    private lateinit var bookListContainer: View
    private lateinit var bookListHeader: TextView

    private lateinit var versePager: androidx.viewpager2.widget.ViewPager2

    // §5E — one callback, registered once, reading the up-to-date currentBook.
    // The old code registered a fresh callback on every showVerses(), stacking
    // duplicates so onPageSelected fired multiple times.
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            currentChapter = position + 1
            selectedBookTitle.text = "${currentBook ?: ""} ${position + 1}"
        }
    }

    companion object {
        private const val ARG_TESTAMENT = "testament"

        // Improvement #4 — keys for onSaveInstanceState, so rotation (or the
        // theme toggle recreating the activity) doesn't dump the reader back
        // to the book picker.
        private const val KEY_CARD_MODE = "state_card_mode"
        private const val KEY_IN_CHAPTERS = "state_in_chapters"
        private const val KEY_IN_VERSES = "state_in_verses"
        private const val KEY_BOOK = "state_book"
        private const val KEY_CHAPTER = "state_chapter"

        fun newInstance(testament: String): BibleFragment {
            val fragment = BibleFragment()
            val args = Bundle()
            args.putString(ARG_TESTAMENT, testament)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        testament = arguments?.getString(ARG_TESTAMENT) ?: "Old"
        // §3 — shared singleton; opens the bundled bible.db once for the whole app.
        bibleRepo = requireContext().app.bibleRepository
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_bible, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bookPager = view.findViewById(R.id.bookPager)
        chaptersRecycler = view.findViewById(R.id.chaptersRecycler)
        chaptersTopBar = view.findViewById(R.id.chaptersTopBar)
        selectedBookTitle = view.findViewById(R.id.selectedBookTitle)
        btnSwitchMode = view.findViewById(R.id.btnSwitchMode)
        bookList = view.findViewById(R.id.bookList)
        bookListContainer = view.findViewById(R.id.bookListContainer)
        bookListHeader = view.findViewById(R.id.bookListHeader)
        bookListHeader.text = getString(
            if (testament == "New") R.string.testament_header_new else R.string.testament_header_old
        )
        translationPicker = view.findViewById(R.id.translationPicker)
        versePager = view.findViewById(R.id.versePager)

        // §5E — register the page-change callback exactly once.
        versePager.registerOnPageChangeCallback(pageChangeCallback)

        // Improvement #5 — ThemeManager.toggleTheme() already recreates the
        // activity (setDefaultNightMode does it for us), so the old extra
        // requireActivity().recreate() here caused a double recreation.
        // bindThemeToggle sets the right icon AND the single-toggle listener.
        btnThemeToggle = view.findViewById(R.id.btnThemeToggle)
        ThemeManager.bindThemeToggle(btnThemeToggle, requireActivity())

        updateTranslationLabel()
        translationPicker.setOnClickListener { showTranslationMenu() }

        viewLifecycleOwner.lifecycleScope.launch {
            books = bibleRepo.getBooks(testament)

            bookPager.adapter = BookPagerAdapter(books) { bookName ->
                showChapters(bookName)
            }

            // Improvement #6 — the old code called getChapterCount() once per
            // book: up to 39 sequential DB queries just to draw this list (a
            // classic N+1). The canon never changes, so read the static
            // ChapterCounts table instead — zero DB work.
            val rows = books.map { name ->
                BookListAdapter.BookRow(
                    name = name,
                    abbrev = abbrevFor(name),
                    chapters = com.example.mbible.data.ChapterCounts.forBook(
                        com.example.mbible.data.BookMapping.byName(name)?.usfm ?: ""
                    )
                )
            }
            bookList.adapter = BookListAdapter(requireContext(), rows)
            bookList.setOnItemClickListener { _, _, position, _ ->
                showChapters(books[position])
            }
        }

        // Switch mode toggle
        btnSwitchMode.setOnClickListener {
            isCardMode = !isCardMode
            if (isCardMode) {
                bookPager.visibility = View.VISIBLE
                bookListContainer.visibility = View.GONE
            } else {
                bookPager.visibility = View.GONE
                bookListContainer.visibility = View.VISIBLE
            }
        }

        // Improvement #4 — restore where the reader was before recreation.
        // The old code always called showBookPager(), so any rotation lost
        // the user's place. showChapters()/showVerses() query the repository
        // themselves, so they can be re-entered directly with restored args.
        if (savedInstanceState != null) {
            isCardMode = savedInstanceState.getBoolean(KEY_CARD_MODE, true)
            currentBook = savedInstanceState.getString(KEY_BOOK)
            currentChapter = savedInstanceState.getInt(KEY_CHAPTER, -1)
                .takeIf { it > 0 }
        }
        val restoredBook = currentBook
        when {
            savedInstanceState?.getBoolean(KEY_IN_VERSES) == true &&
                    restoredBook != null && currentChapter != null ->
                showVerses(restoredBook, currentChapter!!)

            savedInstanceState?.getBoolean(KEY_IN_CHAPTERS) == true &&
                    restoredBook != null ->
                showChapters(restoredBook)

            else -> showBookPager()
        }
    }

    // Improvement #4 — persist the reader's position across recreation.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CARD_MODE, isCardMode)
        outState.putBoolean(KEY_IN_CHAPTERS, inChaptersView)
        outState.putBoolean(KEY_IN_VERSES, inVersesView)
        outState.putString(KEY_BOOK, currentBook)
        outState.putInt(KEY_CHAPTER, currentChapter ?: -1)
    }

    override fun onDestroyView() {
        // §5E — pair the single registration with an unregister.
        versePager.unregisterOnPageChangeCallback(pageChangeCallback)
        super.onDestroyView()
    }

    private fun showBookPager() {
        inChaptersView = false
        inVersesView = false
        chaptersTopBar.visibility = View.GONE
        chaptersRecycler.visibility = View.GONE
        versePager.visibility = View.GONE
        btnSwitchMode.visibility = View.VISIBLE

        if (isCardMode) {
            bookPager.visibility = View.VISIBLE
            bookListContainer.visibility = View.GONE
        } else {
            bookPager.visibility = View.GONE
            bookListContainer.visibility = View.VISIBLE
        }
    }

    private fun showChapters(bookName: String) {
        inChaptersView = true
        inVersesView = false
        currentBook = bookName

        selectedBookTitle.text = bookName
        bookPager.visibility = View.GONE
        versePager.visibility = View.GONE
        chaptersTopBar.visibility = View.VISIBLE
        chaptersRecycler.visibility = View.VISIBLE

        chaptersRecycler.layoutManager = GridLayoutManager(requireContext(), 6)
        btnSwitchMode.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val count = bibleRepo.getChapterCount(bookName, testament)
            chaptersRecycler.adapter = ChapterAdapter((1..count).toList()) { chapter ->
                showVerses(bookName, chapter)
            }
        }

        bookListContainer.visibility = View.GONE
    }

    private fun showVerses(bookName: String, chapter: Int) {
        inChaptersView = false
        inVersesView = true
        currentBook = bookName
        currentChapter = chapter

        selectedBookTitle.text = "$bookName $chapter"
        btnSwitchMode.visibility = View.GONE

        // Hide the recycler, show the pager
        chaptersRecycler.visibility = View.GONE
        versePager.visibility = View.VISIBLE
        chaptersTopBar.visibility = View.VISIBLE
        bookPager.visibility = View.GONE
        bookListContainer.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val chapterCount = bibleRepo.getChapterCount(bookName, testament)

            versePager.adapter = VersePagerAdapter(
                bookName, testament, chapterCount, bibleRepo, viewLifecycleOwner
            )

            // §5E — no per-call registration here anymore; the single callback
            // registered in onViewCreated reads currentBook (set just above).
            versePager.setCurrentItem(chapter - 1, false)
        }
    }

    private fun updateTranslationLabel() {
        translationPicker.text = "${bibleRepo.activeTranslation.abbreviation} ▾"
    }

    /** 3-char badge label, e.g. "Genesis" -> "Gen", "1 Samuel" -> "1Sa". */
    private fun abbrevFor(name: String): String =
        name.filter { !it.isWhitespace() }.take(3)

    private fun showTranslationMenu() {
        // Accent "open" look on the pill while the popup is up.
        translationPicker.setBackgroundResource(R.drawable.bg_pill_accent)

        val inflater = LayoutInflater.from(requireContext())
        val content = inflater.inflate(R.layout.popup_translation, null)
        val rows = content.findViewById<android.widget.LinearLayout>(R.id.translationRows)

        val widthPx = (300 * resources.displayMetrics.density).toInt()
        val popup = android.widget.PopupWindow(
            content, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true
        )
        popup.elevation = 14f
        popup.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        popup.isOutsideTouchable = true

        val activeId = bibleRepo.activeTranslation.id
        val accent = requireContext().getColor(R.color.accent_red)

        for (t in com.example.mbible.data.Translations.ALL) {
            val row = inflater.inflate(R.layout.item_translation, rows, false)
            val abbrev = row.findViewById<TextView>(R.id.trAbbrev)
            val name = row.findViewById<TextView>(R.id.trName)
            val check = row.findViewById<android.widget.ImageView>(R.id.trCheck)

            abbrev.text = t.abbreviation
            name.text = t.displayName

            if (t.id == activeId) {
                row.setBackgroundResource(R.drawable.bg_row_active)
                abbrev.setTextColor(accent)
                name.setTextColor(accent)
                check.visibility = View.VISIBLE
            }

            row.setOnClickListener {
                bibleRepo.setActiveTranslation(t.id)
                updateTranslationLabel()
                refreshCurrentView()
                popup.dismiss()
            }
            rows.addView(row)
        }

        popup.setOnDismissListener {
            translationPicker.setBackgroundResource(R.drawable.bg_pill)
        }

        // Center the card under the pill, dropped 8dp below it.
        val yOff = (8 * resources.displayMetrics.density).toInt()
        val xOff = (translationPicker.width - widthPx) / 2
        popup.showAsDropDown(translationPicker, xOff, yOff)
    }

    private fun refreshCurrentView() {
        when {
            inVersesView -> {
                val book = currentBook ?: return
                val chapter = currentChapter ?: return
                showVerses(book, chapter)
            }
            inChaptersView -> {
                val book = currentBook ?: return
                showChapters(book)
            }
            else -> {
                // On the book pager — book names are the same across
                // translations, so nothing needs reloading.
            }
        }
    }

    fun onBackPressed(): Boolean {
        return when {
            inVersesView -> {
                val book = currentBook ?: return false
                showChapters(book)
                true
            }
            inChaptersView -> {
                showBookPager()
                true
            }
            else -> false
        }
    }
}