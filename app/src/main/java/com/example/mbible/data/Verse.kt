package com.example.mbible.data

data class Verse(
    val verse: Int,
    val text: String,
    val footnotes: List<Footnote> = emptyList(),
    val heading: String? = null,
    val segments: List<VerseSegment> = emptyList(),
    // NIV formatting — structure parsed from the chapter HTML. Defaults keep
    // the local KJV source (which never sets these) compiling and flowing
    // exactly as before.
    val superscription: String? = null, // e.g. "A psalm of David." (USFM "d")
    val leadingBreak: Int = 0,          // 0 = flow on, 1 = new line (poetry), 2 = new paragraph
    val indent: Boolean = false         // verse starts on an indented (q2) poetry line
)

/**
 * A footnote attached to a verse.
 *  - [text]   : the flattened note text, e.g. "4:1 Or The man".
 *  - [offset] : character index into the verse's clean [Verse.text] where the
 *               marker (a / b / c …) belongs. Defaults to 0 so any old/bare
 *               construction still compiles.
 */
data class Footnote(val text: String, val offset: Int = 0)

data class VerseSegment(val text: String, val isJesus: Boolean)
