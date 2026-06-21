package com.example.mbible

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.fragment.app.Fragment

class DocsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_docs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ThemeManager.bindThemeToggle(view.findViewById(R.id.btnThemeToggle), requireActivity())
        val docsList = view.findViewById<ListView>(R.id.docsList)
        // val docs = listOf("Catechism of the Catholic Church")
        val docs = emptyList<String>()

        docsList.adapter = ArrayAdapter(
            requireContext(),
            R.layout.item_book,
            R.id.bookName,
            docs
        )

        docsList.setOnItemClickListener { _, _, position, _ ->
            when (docs[position]) {
                "Catechism of the Catholic Church" -> {
                    startActivity(Intent(requireContext(), CatechismActivity::class.java))
                }
            }
        }
    }
}