package com.example.mbible.data

data class Verse(
    val verse: Int,
    val text: String,
    val footnotes: List<Footnote> = emptyList(),
    val heading: String? = null,
    val segments: List<VerseSegment> = emptyList()
)

data class Footnote(val text: String)

data class VerseSegment(val text: String, val isJesus: Boolean)