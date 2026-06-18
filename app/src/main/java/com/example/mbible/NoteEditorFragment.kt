package com.example.mbible

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.mbible.data.BibleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.mbible.data.Note
import com.example.mbible.data.NotesRepository

class NoteEditorFragment : Fragment() {

    private lateinit var notesRepo: NotesRepository
    private lateinit var bibleRepo: BibleRepository
    private lateinit var aliasRepo: BookAliasRepository

    private lateinit var noteTitleText: TextView
    private lateinit var noteTitleEdit: EditText
    private lateinit var noteBody: EditText
    private lateinit var btnSaveNote: Button

    private var currentNoteId: Long? = null
    private var isHighlighting = false
    private val highlightHandler = Handler(Looper.getMainLooper())
    private var highlightRunnable: Runnable? = null
    private var pendingHighlight = false
    private lateinit var verseHighlightScroll: View
    private lateinit var verseHighlightBox: android.widget.LinearLayout

    // Lets the user choose where to save the single-note export. No storage permission needed.
    private val exportLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val id = currentNoteId
        if (uri == null || id == null) return@registerForActivityResult
        // WRAP (§2) — exportOneToJson() + file write are now off the main thread.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val json = notesRepo.exportOneToJson(id)
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray())
                    }
                }
                android.widget.Toast.makeText(
                    requireContext(), "Note exported", android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    requireContext(), "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val refRegex =
        Regex("""(?i)\b([1-3]?\s*[a-z\.]+)\s*(\d{1,3})\s*:(\s*(\d{1,3})(?:\s*-\s*(\d{1,3}))?)?(?=\s|${'$'}|\W)""")

    companion object {
        private const val ARG_NOTE_ID = "note_id"

        fun newInstance(noteId: Long): NoteEditorFragment {
            val fragment = NoteEditorFragment()
            val args = Bundle()
            args.putLong(ARG_NOTE_ID, noteId)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // §3 — shared singletons instead of per-fragment instances.
        notesRepo = requireContext().app.notesRepository
        bibleRepo = requireContext().app.bibleRepository
        aliasRepo = requireContext().app.aliasRepository
        // §1 — getLong() returns 0L (not null) when the key is missing; gate on the key
        // so currentNoteId is genuinely null for a brand-new editor.
        currentNoteId = arguments
            ?.takeIf { it.containsKey(ARG_NOTE_ID) }
            ?.getLong(ARG_NOTE_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_note_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        noteTitleText = view.findViewById(R.id.noteTitleText)
        noteTitleEdit = view.findViewById(R.id.noteTitleEdit)
        noteBody = view.findViewById(R.id.noteBody)
        btnSaveNote = view.findViewById(R.id.btnSaveNote)

        noteBody.movementMethod = LinkMovementMethod.getInstance()
        noteBody.highlightColor = 0x00000000

        verseHighlightScroll = view.findViewById(R.id.verseHighlightScroll)
        verseHighlightBox = view.findViewById(R.id.verseHighlightBox)

        // Load note — WRAP (§2): getById() is now suspend.
        currentNoteId?.let { id ->
            viewLifecycleOwner.lifecycleScope.launch {
                val note = notesRepo.getById(id)
                if (note != null) loadNote(note)
            }
        }

        noteBody.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s == null) return
                highlightRunnable?.let { highlightHandler.removeCallbacks(it) }
                highlightRunnable = Runnable {
                    if (isHighlighting) { pendingHighlight = true; return@Runnable }
                    viewLifecycleOwner.lifecycleScope.launch {
                        highlightVerseRefs(noteBody.text)
                        updateHighlightBox(noteBody.text)
                    }
                }
                highlightHandler.postDelayed(highlightRunnable!!, 300)
            }
        })

        btnSaveNote.setOnClickListener {
            // WRAP (§2) — saveNote() is now suspend.
            viewLifecycleOwner.lifecycleScope.launch {
                saveNote()
                parentFragmentManager.popBackStack()
            }
        }

        // Back chevron returns to the notes list (same as system back).
        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Export just this note via the system file picker (no permissions needed).
        view.findViewById<View>(R.id.btnExportNote).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                saveNote()
                val safeName = noteTitleText.text.toString().trim()
                    .replace(Regex("[^A-Za-z0-9 _-]"), "")
                    .ifEmpty { "note" }
                exportLauncher.launch("$safeName.json")
            }
        }

        noteTitleText.setOnClickListener { startTitleEdit() }

        noteTitleEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                finishTitleEdit(); true
            } else false
        }

        noteTitleEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && noteTitleEdit.visibility == View.VISIBLE) finishTitleEdit()
        }
    }

    // §4 — Autosave so edits survive system-back, app-switch, or process death.
    // Runs on the app scope (not the view scope) so the write completes even as
    // this fragment's view is being destroyed.
    override fun onPause() {
        super.onPause()
        val id = currentNoteId ?: return
        if (noteTitleEdit.visibility == View.VISIBLE) finishTitleEdit()
        val title = noteTitleText.text.toString().trim().ifEmpty { "New Note" }
        val body = noteBody.text.toString()
        requireContext().app.appScope.launch {
            notesRepo.update(id, title, body)
        }
    }

    private fun loadNote(note: Note) {
        noteTitleText.text = note.title
        noteTitleEdit.setText(note.title)
        noteTitleEdit.visibility = View.GONE
        noteTitleText.visibility = View.VISIBLE
        noteBody.setText(note.body)
        viewLifecycleOwner.lifecycleScope.launch {
            highlightVerseRefs(noteBody.text)
            updateHighlightBox(noteBody.text)
        }
    }

    // §2/§4 — now suspend so callers run it inside a coroutine.
    private suspend fun saveNote() {
        val id = currentNoteId ?: return
        if (noteTitleEdit.visibility == View.VISIBLE) finishTitleEdit()
        val title = noteTitleText.text.toString().trim().ifEmpty { "New Note" }
        val body = noteBody.text.toString()
        notesRepo.update(id, title, body)
    }

    private fun startTitleEdit() {
        noteTitleEdit.visibility = View.VISIBLE
        noteTitleText.visibility = View.GONE
        noteTitleEdit.setText(noteTitleText.text.toString())
        noteTitleEdit.requestFocus()
        noteTitleEdit.setSelection(noteTitleEdit.text.length)
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
        imm.showSoftInput(noteTitleEdit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun finishTitleEdit() {
        val newTitle = noteTitleEdit.text.toString().trim().ifEmpty { "New Note" }
        noteTitleText.text = newTitle
        noteTitleText.visibility = View.VISIBLE
        noteTitleEdit.visibility = View.GONE
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as InputMethodManager
        imm.hideSoftInputFromWindow(noteTitleEdit.windowToken, 0)
    }

    private suspend fun highlightVerseRefs(editable: Editable) {
        if (isHighlighting) return
        isHighlighting = true
        try {
            val oldSpans = editable.getSpans(0, editable.length, ClickableSpan::class.java)
            for (s in oldSpans) editable.removeSpan(s)

            for (m in refRegex.findAll(editable.toString())) {
                val bookToken = m.groupValues[1]
                val chapterStr = m.groupValues[2]
                val verseStartStr = m.groupValues[4]
                val verseEndStr = m.groupValues[5]

                val canonical = aliasRepo.resolveBookToken(bookToken) ?: continue
                val ch = chapterStr.toIntOrNull() ?: continue
                val isFullChapter = verseStartStr.isBlank()
                val vsStart = if (isFullChapter) 1 else verseStartStr.toIntOrNull() ?: continue
                // §5F — validate against the bundled canon (instant, offline) instead of
                // the active translation, so typing never triggers a network call.
                val vsEnd = when {
                    isFullChapter -> bibleRepo.getVerseCountLocal(canonical, ch)
                    verseEndStr.isNotBlank() -> verseEndStr.toIntOrNull() ?: vsStart
                    else -> vsStart
                }

                if (ch <= 0 || vsStart <= 0 || vsEnd <= 0 || vsEnd < vsStart) continue
                if (!isFullChapter) {
                    if (!bibleRepo.verseExistsLocal(canonical, ch, vsStart)) continue
                    if (!bibleRepo.verseExistsLocal(canonical, ch, vsEnd)) continue
                }

                val start = m.range.first
                val end = m.range.last + 1

                val clickSpan = object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val verses = bibleRepo.getVerseRange(canonical, ch, vsStart, vsEnd)
                            val label = "$canonical $ch:$vsStart${if (vsEnd != vsStart) "-$vsEnd" else ""}"
                            VerseSheet.show(requireContext(), label, verses)
                        }
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = false
                        ds.color = requireContext().getColor(R.color.accent_red)
                    }
                }

                editable.setSpan(clickSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } finally {
            isHighlighting = false
            if (pendingHighlight) {
                pendingHighlight = false
                highlightHandler.post {
                    viewLifecycleOwner.lifecycleScope.launch { highlightVerseRefs(noteBody.text) }
                }
            }
        }
    }

    private suspend fun updateHighlightBox(editable: Editable) {
        verseHighlightBox.removeAllViews()
        val refs = mutableListOf<Triple<String, Int, Pair<Int,Int>>>() // canonical, chapter, verse range

        for (m in refRegex.findAll(editable.toString())) {
            val bookToken = m.groupValues[1]
            val chapterStr = m.groupValues[2]
            val verseStartStr = m.groupValues[4]
            val verseEndStr = m.groupValues[5]

            val canonical = aliasRepo.resolveBookToken(bookToken) ?: continue
            val ch = chapterStr.toIntOrNull() ?: continue
            val isFullChapter = verseStartStr.isBlank()
            val vsStart = if (isFullChapter) 1 else verseStartStr.toIntOrNull() ?: continue
            // §5F — local validation here too.
            val vsEnd = when {
                isFullChapter -> bibleRepo.getVerseCountLocal(canonical, ch)
                verseEndStr.isNotBlank() -> verseEndStr.toIntOrNull() ?: vsStart
                else -> vsStart
            }

            if (ch <= 0 || vsStart <= 0 || vsEnd <= 0 || vsEnd < vsStart) continue
            if (!isFullChapter) {
                if (!bibleRepo.verseExistsLocal(canonical, ch, vsStart)) continue
                if (!bibleRepo.verseExistsLocal(canonical, ch, vsEnd)) continue
            }

            refs.add(Triple(canonical, ch, Pair(vsStart, vsEnd)))
        }

        if (refs.isEmpty()) {
            verseHighlightScroll.visibility = View.GONE
            return
        }

        verseHighlightScroll.visibility = View.VISIBLE

        for ((index, ref) in refs.withIndex()) {
            val (canonical, ch, range) = ref
            val (vsStart, vsEnd) = range
            val label = "$canonical $ch:$vsStart${if (vsEnd != vsStart) "-$vsEnd" else ""}"

            val chip = TextView(requireContext()).apply {
                text = label
                textSize = 13f
                setTextColor(requireContext().getColor(R.color.accent_red))
                typeface = androidx.core.content.res.ResourcesCompat.getFont(
                    requireContext(), R.font.archivo_semibold
                )
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(28, 14, 28, 14)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val verses = bibleRepo.getVerseRange(canonical, ch, vsStart, vsEnd)
                        VerseSheet.show(requireContext(), label, verses)
                    }
                }
            }

            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index < refs.size - 1) lp.marginEnd = 16
            verseHighlightBox.addView(chip, lp)
        }
    }
}