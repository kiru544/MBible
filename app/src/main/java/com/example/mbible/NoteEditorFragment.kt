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
    private var noteLoadJob: kotlinx.coroutines.Job? = null
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
                    requireContext(), getString(R.string.note_exported), android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    requireContext(), getString(R.string.export_failed, e.message), android.widget.Toast.LENGTH_LONG
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

        // Pinch-to-zoom on the note body: two fingers scale the base text size,
        // and the choice is persisted so every note opens at the reader's size.
        // (Formatting sizes like Title are RELATIVE spans, so they scale along.)
        TextZoom.applySaved(noteBody, TextZoom.KEY_NOTE)
        TextZoom.attach(
            TextZoom.KEY_NOTE,
            startSp = { TextZoom.currentSp(noteBody) },
            onScaled = { sp -> noteBody.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp) },
            noteBody
        )

        bindFormattingToolbar(view)

        // Load note — WRAP (§2): getById() is now suspend.
        currentNoteId?.let { id ->
            noteLoadJob = viewLifecycleOwner.lifecycleScope.launch {
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

    // Rich-text feature — the formatting toolbar. B/I/U act directly on the
    // selection (or the word under the cursor); Style, Size, and Align open
    // small PopupMenus anchored to their buttons. All of these edit SPANS, not
    // text, so the TextWatcher (verse highlighting) is never re-triggered.
    private fun bindFormattingToolbar(view: View) {
        val dim = requireContext().getColor(R.color.text_tertiary)

        view.findViewById<View>(R.id.btnFmtBold).setOnClickListener {
            NoteFormatting.toggleBold(noteBody)
        }
        view.findViewById<View>(R.id.btnFmtItalic).setOnClickListener {
            NoteFormatting.toggleItalic(noteBody)
        }
        view.findViewById<View>(R.id.btnFmtUnderline).setOnClickListener {
            NoteFormatting.toggleUnderline(noteBody)
        }

        view.findViewById<View>(R.id.btnFmtStyle).setOnClickListener { anchor ->
            val labels = listOf(
                NoteFormatting.LineStyle.TITLE to R.string.style_title,
                NoteFormatting.LineStyle.SUBTITLE to R.string.style_subtitle,
                NoteFormatting.LineStyle.HEADING to R.string.style_heading,
                NoteFormatting.LineStyle.BODY to R.string.style_body,
                NoteFormatting.LineStyle.NOTE to R.string.style_note
            )
            showPopup(anchor, labels) { style ->
                NoteFormatting.applyLineStyle(noteBody, style, dim)
            }
        }

        view.findViewById<View>(R.id.btnFmtAlign).setOnClickListener { anchor ->
            val labels = listOf(
                android.text.Layout.Alignment.ALIGN_NORMAL to R.string.align_left,
                android.text.Layout.Alignment.ALIGN_CENTER to R.string.align_center,
                android.text.Layout.Alignment.ALIGN_OPPOSITE to R.string.align_right
            )
            showPopup(anchor, labels) { align ->
                NoteFormatting.setAlignment(noteBody, align)
            }
        }

        // Image feature — photos (album or camera) under Image; Draw is its
        // own button since sketching is a different activity than attaching.
        view.findViewById<View>(R.id.btnFmtImage).setOnClickListener { anchor ->
            val labels = listOf(
                "album" to R.string.image_album,
                "camera" to R.string.image_camera
            )
            showPopup(anchor, labels) { which ->
                when (which) {
                    "album" -> pickImageLauncher.launch("image/*")
                    "camera" -> launchCamera()
                }
            }
        }

        view.findViewById<View>(R.id.btnFmtDraw).setOnClickListener {
            DrawingDialog(requireContext()) { bmp ->
                storeAndInsert { NoteImageStore.saveBitmap(appCtx(), bmp) }
            }.show()
        }
    }

    // ------------------------------------------------------------ image feature

    private fun appCtx() = requireContext().applicationContext

    /** Gallery picker: returns a content:// Uri, or null if the user backed out. */
    private val pickImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) storeAndInsert { NoteImageStore.importUri(appCtx(), uri) }
    }

    // Camera flow: WE create a temp file, wrap it in a FileProvider Uri (apps
    // can't hand raw file paths to each other since Android 7), and the camera
    // app writes the photo INTO it. The boolean result just says "did they
    // actually take a picture".
    //
    // Bug fix: the temp file has a FIXED name derived from nothing. While the
    // camera is open, Android often KILLS this app's process to free memory;
    // registerForActivityResult survives that and still delivers the result —
    // but any instance variable (like a remembered "pending file") comes back
    // null, so the photo silently vanished. A deterministic path means there
    // is nothing to remember.
    private fun cameraTempFile(): java.io.File {
        val dir = java.io.File(requireContext().cacheDir, "camera").apply { mkdirs() }
        return java.io.File(dir, "capture.jpg")
    }

    private val takePhotoLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { saved ->
        val tempFile = cameraTempFile()
        if (!saved || !tempFile.exists() || tempFile.length() == 0L) {
            tempFile.delete()
            return@registerForActivityResult
        }
        storeAndInsert {
            val name = NoteImageStore.importFile(appCtx(), tempFile)
            tempFile.delete() // temp copy no longer needed once imported
            name
        }
    }

    private fun launchCamera() {
        val ctx = requireContext()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", cameraTempFile()
        )
        takePhotoLauncher.launch(uri)
    }

    // Resize feature — tapping an inserted image opens view/size options.
    private fun onImageTapped(image: NoteFormatting.FImage) {
        val options = arrayOf(
            getString(R.string.image_view_full),
            getString(R.string.image_size_small),
            getString(R.string.image_size_medium),
            getString(R.string.image_size_full)
        )
        val scales = floatArrayOf(0f, 0.4f, 0.65f, 1f) // 0f = "view", not a size
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.image_size_title))
            .setItems(options) { _, index ->
                if (index == 0) {
                    ImageViewerDialog(requireContext(), image.fileName).show()
                } else {
                    NoteFormatting.setImageScale(
                        noteBody, requireContext(), image, scales[index], ::onImageTapped
                    )
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    /** Run the (disk-heavy) [store] step off the main thread, then insert on it. */
    private fun storeAndInsert(store: () -> String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            noteLoadJob?.join()
            val name = withContext(Dispatchers.IO) { store() }
            if (name != null) {
                NoteFormatting.insertImage(noteBody, requireContext(), name, ::onImageTapped)
            } else {
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.image_failed),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Generic PopupMenu: pairs of (value, label resource) → callback with the picked value. */
    private fun <T> showPopup(anchor: View, items: List<Pair<T, Int>>, onPick: (T) -> Unit) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        items.forEachIndexed { index, (_, labelRes) ->
            popup.menu.add(0, index, index, getString(labelRes))
        }
        popup.setOnMenuItemClickListener { item ->
            onPick(items[item.itemId].first)
            true
        }
        popup.show()
    }

    // Improvement #2 — the TextWatcher posts a delayed Runnable to highlightHandler.
    // If the view is destroyed while one is still pending, it would fire afterwards
    // and touch viewLifecycleOwner / noteBody (crash: IllegalStateException).
    // Removing it here pairs every postDelayed() with a guaranteed cleanup.
    override fun onDestroyView() {
        // null token = clear ALL callbacks on this handler, including the
        // untracked one posted from highlightVerseRefs' finally block.
        highlightHandler.removeCallbacksAndMessages(null)
        highlightRunnable = null
        super.onDestroyView()
    }

    // §4 — Autosave so edits survive system-back, app-switch, or process death.
    // Runs on the app scope (not the view scope) so the write completes even as
    // this fragment's view is being destroyed.
    override fun onPause() {
        super.onPause()
        val id = currentNoteId ?: return
        if (noteTitleEdit.visibility == View.VISIBLE) finishTitleEdit()
        val title = noteTitleText.text.toString().trim().ifEmpty { getString(R.string.default_note_title) }
        val body = noteBody.text.toString()
        // Serialize the spans HERE on the main thread — the Editable belongs to
        // the UI; only the resulting plain strings cross into the coroutine.
        val formatting = NoteFormatting.toJson(noteBody.text)
        requireContext().app.appScope.launch {
            notesRepo.update(id, title, body, formatting)
        }
    }

    private fun loadNote(note: Note) {
        noteTitleText.text = note.title
        noteTitleEdit.setText(note.title)
        noteTitleEdit.visibility = View.GONE
        noteTitleText.visibility = View.VISIBLE
        noteBody.setText(note.body)
        // Rich-text feature — re-attach the saved formatting spans. Must run
        // AFTER setText (which builds a fresh Editable) and is safe alongside
        // the verse highlighter, which only ever touches ClickableSpans.
        NoteFormatting.applyJson(
            requireContext(), noteBody.text, note.formatting,
            requireContext().getColor(R.color.text_tertiary),
            ::onImageTapped
        )
        viewLifecycleOwner.lifecycleScope.launch {
            highlightVerseRefs(noteBody.text)
            updateHighlightBox(noteBody.text)
        }
    }

    // §2/§4 — now suspend so callers run it inside a coroutine.
    private suspend fun saveNote() {
        val id = currentNoteId ?: return
        if (noteTitleEdit.visibility == View.VISIBLE) finishTitleEdit()
        val title = noteTitleText.text.toString().trim().ifEmpty { getString(R.string.default_note_title) }
        val body = noteBody.text.toString()
        val formatting = NoteFormatting.toJson(noteBody.text)
        notesRepo.update(id, title, body, formatting)
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
        val newTitle = noteTitleEdit.text.toString().trim().ifEmpty { getString(R.string.default_note_title) }
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
            for (s in oldSpans) {
                // Resize feature — the image tap-targets are ClickableSpans too;
                // they belong to the image, not to us, so leave them alone.
                if (s is NoteFormatting.ImageClick) continue
                editable.removeSpan(s)
            }

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