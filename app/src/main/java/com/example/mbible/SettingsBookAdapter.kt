package com.example.mbible

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView

/** Settings book rows: badge + name + existing short names shown as tags. */
class SettingsBookAdapter(
    context: Context,
    private val books: List<String>,
    aliasMap: Map<String, List<String>>
) : ArrayAdapter<String>(context, 0, books) {

    // Aliases are loaded once (off the main thread) and handed in, so getView
    // never touches the database while the list scrolls.
    private var aliasMap: Map<String, List<String>> = aliasMap

    /** Swap in a freshly loaded alias map (e.g. after returning from the editor). */
    fun update(newMap: Map<String, List<String>>) {
        aliasMap = newMap
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_settings_book, parent, false)

        val book = books[position]
        view.findViewById<TextView>(R.id.bookAbbrev).text =
            book.filter { !it.isWhitespace() }.take(3)
        view.findViewById<TextView>(R.id.bookName).text = book

        val tagRow = view.findViewById<LinearLayout>(R.id.aliasTagRow)
        val noAlias = view.findViewById<TextView>(R.id.noAliasText)
        tagRow.removeAllViews()

        // Read from the in-memory map instead of querying per row.
        val aliases = aliasMap[book].orEmpty()
        if (aliases.isEmpty()) {
            tagRow.visibility = View.GONE
            noAlias.visibility = View.VISIBLE
        } else {
            tagRow.visibility = View.VISIBLE
            noAlias.visibility = View.GONE
            for (alias in aliases) {
                val chip = TextView(context).apply {
                    text = alias
                    setBackgroundResource(R.drawable.bg_chip_accent)
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(
                        context, R.font.archivo_semibold
                    )
                    setTextColor(context.getColor(R.color.accent_red))
                    textSize = 12f
                    setPadding(22, 8, 22, 8)
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 14 }
                tagRow.addView(chip, lp)
            }
        }
        return view
    }
}