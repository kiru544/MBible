package com.example.mbible

import android.os.Bundle
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.mbible.data.BibleBooks
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var adapter: SettingsBookAdapter
    private lateinit var aliasRepo: BookAliasRepository
    private val books = BibleBooks.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ThemeManager.applyStatusBarIcons(this)

        // After Section 3 you can swap this for: app.aliasRepository
        aliasRepo = BookAliasRepository(this)

        val booksList = findViewById<ListView>(R.id.booksList)
        // Start with an empty map so the list shows instantly; fill it in onResume.
        adapter = SettingsBookAdapter(this, books, emptyMap())
        booksList.adapter = adapter

        booksList.setOnItemClickListener { _, _, position, _ ->
            val book = books[position]
            startActivity(
                android.content.Intent(this, AliasEditorActivity::class.java).apply {
                    putExtra(AliasEditorActivity.EXTRA_BOOK, book)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Load all aliases in one query (off the main thread), then refresh the list.
        // Also re-runs after returning from the alias editor, so new aliases show up.
        loadAliases()
    }

    private fun loadAliases() {
        lifecycleScope.launch {
            adapter.update(aliasRepo.getAllAliases())
        }
    }
}