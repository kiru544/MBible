package com.example.mbible.data

data class Note(
    val id: Long,
    val title: String,
    val body: String,
    val updatedAt: Long,
    // Rich-text feature — JSON description of the note's formatting spans
    // (bold/italic/underline/size/alignment/paragraph styles). Null means a
    // plain-text note; every note created before DB v2 loads as plain text.
    val formatting: String? = null
)
