package com.example.mbible

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.mbible.data.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotesListFragment : Fragment() {

    private lateinit var notesRepo: NotesRepository
    private lateinit var notesAdapter: NotesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // §3 — shared singleton instead of a per-fragment instance.
        notesRepo = requireContext().app.notesRepository
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_notes_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ThemeManager.bindThemeToggle(view.findViewById(R.id.btnThemeToggle), requireActivity())

        val notesRecycler = view.findViewById<RecyclerView>(R.id.notesRecycler)
        val btnNewNote = view.findViewById<Button>(R.id.btnNewNote)

        val btnExport = view.findViewById<Button>(R.id.btnExportNotes)
        val btnImport = view.findViewById<Button>(R.id.btnImportNotes)
        val btnAliasSettings = view.findViewById<android.widget.ImageButton>(R.id.btnAliasSettings)
        btnAliasSettings.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), SettingsActivity::class.java))
        }

        btnExport.setOnClickListener {
            exportNotes()
        }

        btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain"))
        }

        notesAdapter = NotesAdapter(
            onOpen = { note -> openNote(note.id) },
            onDelete = { note ->
                MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_MBible_Dialog)
                    .setTitle("Delete note?")
                    .setMessage("This will permanently delete \"${note.title}\".")
                    .setPositiveButton("Delete") { _, _ ->
                        // WRAP #1 — delete() is now suspend
                        viewLifecycleOwner.lifecycleScope.launch {
                            notesRepo.delete(note.id)
                            refreshNotes()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        notesRecycler.layoutManager = LinearLayoutManager(requireContext())
        notesRecycler.adapter = notesAdapter

        btnNewNote.setOnClickListener {
            val input = EditText(requireContext()).apply { hint = "Note name" }
            MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_MBible_Dialog)
                .setTitle("New note")
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val title = input.text.toString().trim().ifEmpty { "New Note" }
                    // WRAP #2 — create() is now suspend
                    viewLifecycleOwner.lifecycleScope.launch {
                        val newId = notesRepo.create(title)
                        refreshNotes()
                        openNote(newId)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        refreshNotes()
    }

    override fun onResume() {
        super.onResume()
        refreshNotes()
    }

    private fun refreshNotes() {
        // WRAP #3 — getAll() is now suspend
        viewLifecycleOwner.lifecycleScope.launch {
            notesAdapter.submitList(notesRepo.getAll())
        }
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        // WRAP #4 — file read on IO, importFromJson() is now suspend
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    requireContext().contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        ?.readText()
                } ?: return@launch

                val count = notesRepo.importFromJson(json)
                refreshNotes()
                android.widget.Toast.makeText(
                    requireContext(),
                    "Imported $count notes",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Import failed: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Asks for storage permission on Android 7-9, then writes the file if granted.
    private val exportPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            writeLegacyExport()
        } else {
            android.widget.Toast.makeText(
                requireContext(),
                "Storage permission is needed to export",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun exportNotes() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Android 10+ : write into Downloads via MediaStore (no permission needed).
            exportViaMediaStore()
        } else {
            // Android 7-9 : MediaStore.Downloads doesn't exist; write a real File,
            // which needs WRITE_EXTERNAL_STORAGE.
            val perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(), perm
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) writeLegacyExport() else exportPermissionLauncher.launch(perm)
        }
    }

    // Only ever called on API 29+, so the MediaStore.Downloads fields are safe here.
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun exportViaMediaStore() {
        // WRAP #5a — exportToJson() is now suspend (and takes no Context arg)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val json = notesRepo.exportToJson()
                val fileName = "mbible_notes_${System.currentTimeMillis()}.json"

                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                }

                val resolver = requireContext().contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                uri?.let {
                    withContext(Dispatchers.IO) {
                        resolver.openOutputStream(it)?.use { stream ->
                            stream.write(json.toByteArray())
                        }
                    }
                    toastExported(fileName)
                }
            } catch (e: Exception) {
                toastExportFailed(e)
            }
        }
    }

    // Legacy path for Android 7-9: write a File into the public Downloads folder.
    private fun writeLegacyExport() {
        // WRAP #5b — exportToJson() is now suspend (and takes no Context arg)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val json = notesRepo.exportToJson()
                val fileName = "mbible_notes_${System.currentTimeMillis()}.json"

                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                    if (!downloads.exists()) downloads.mkdirs()

                    val file = java.io.File(downloads, fileName)
                    java.io.FileOutputStream(file).use { it.write(json.toByteArray()) }
                }
                toastExported(fileName)
            } catch (e: Exception) {
                toastExportFailed(e)
            }
        }
    }

    private fun toastExported(fileName: String) {
        android.widget.Toast.makeText(
            requireContext(),
            "Exported to Downloads/$fileName",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun toastExportFailed(e: Exception) {
        android.widget.Toast.makeText(
            requireContext(),
            "Export failed: ${e.message}",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun openNote(id: Long) {
        (parentFragment as? NotesFragment)?.openEditor(id)
    }
}