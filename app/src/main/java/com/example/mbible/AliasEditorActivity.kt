package com.example.mbible

import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.mbible.BookAliasRepository
import kotlinx.coroutines.launch

class AliasEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK = "extra_book"
    }

    private lateinit var bookTitle: TextView
    private lateinit var aliasInput: EditText
    private lateinit var btnAddAlias: Button
    private lateinit var aliasesList: ListView
    private lateinit var aliasBadge: TextView
    private lateinit var aliasExplainer: TextView
    private lateinit var aliasCountLabel: TextView
    private lateinit var btnAliasBack: ImageButton
    private lateinit var btnAliasTheme: ImageButton

    private val aliases = mutableListOf<String>()
    private lateinit var adapter: AliasListAdapter

    private lateinit var aliasRepo: BookAliasRepository
    private lateinit var book: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alias_editor)
        ThemeManager.applyStatusBarIcons(this)

        // Improvement #1 — shared app-scoped singleton (one DB connection app-wide).
        aliasRepo = app.aliasRepository
        book = intent.getStringExtra(EXTRA_BOOK) ?: "Book"

        bookTitle = findViewById(R.id.bookTitle)
        aliasInput = findViewById(R.id.aliasInput)
        btnAddAlias = findViewById(R.id.btnAddAlias)
        aliasesList = findViewById(R.id.aliasesList)
        aliasBadge = findViewById(R.id.aliasBadge)
        aliasExplainer = findViewById(R.id.aliasExplainer)
        aliasCountLabel = findViewById(R.id.aliasCountLabel)
        btnAliasBack = findViewById(R.id.btnAliasBack)
        btnAliasTheme = findViewById(R.id.btnAliasTheme)

        bookTitle.text = book
        aliasBadge.text = book.take(3)
        aliasExplainer.text = "Type any of these in a note and it resolves to $book."

        btnAliasBack.setOnClickListener { finish() }

        btnAliasTheme.setImageResource(
            if (ThemeManager.isDark(this)) R.drawable.ic_sun else R.drawable.ic_moon
        )
        btnAliasTheme.setOnClickListener {
            ThemeManager.toggleTheme(this)
            recreate()
        }

        adapter = AliasListAdapter(
            this,
            aliases,
            onDelete = { alias ->
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.delete_alias_title))
                    .setMessage(getString(R.string.delete_alias_message, alias, book))
                    .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                        // WRAP — deleteAlias() is now suspend
                        lifecycleScope.launch {
                            aliasRepo.deleteAlias(alias)
                            loadAliases()
                        }
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show()
            }
        )
        aliasesList.adapter = adapter

        loadAliases()

        btnAddAlias.setOnClickListener {
            val raw = aliasInput.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener

            // WRAP — addAlias() is now suspend (returns Boolean)
            lifecycleScope.launch {
                val ok = aliasRepo.addAlias(book, raw)
                if (!ok) {
                    // NB: inside launch, `this` is the coroutine scope — qualify the Activity.
                    Toast.makeText(
                        this@AliasEditorActivity,
                        getString(R.string.alias_exists_or_invalid),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                aliasInput.setText("")
                loadAliases()
            }
        }
    }

    private fun loadAliases() {
        // WRAP — getAliasesForBook() is now suspend. Fetch first, then touch the
        // list + views (back on the main thread automatically).
        lifecycleScope.launch {
            val result = aliasRepo.getAliasesForBook(book)
            aliases.clear()
            aliases.addAll(result)
            adapter.notifyDataSetChanged()
            aliasCountLabel.text = "\u25C6 Alias \u00B7 ${aliases.size}"
        }
    }
}