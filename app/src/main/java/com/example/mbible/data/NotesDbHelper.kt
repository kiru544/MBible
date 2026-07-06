package com.example.mbible.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Rich-text feature — DB version bumped 1 → 2. SQLiteOpenHelper compares this
// number with what's stored inside the existing notes.db file: if the file is
// older, Android calls onUpgrade() below exactly once. That's how you evolve
// a schema without wiping user data.
class NotesDbHelper(context: Context) : SQLiteOpenHelper(context, "notes.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                formatting TEXT
            );
            """.trimIndent()
        )
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS book_aliases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                canonical_book TEXT NOT NULL,
                alias TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_alias_unique
            ON book_aliases(alias)
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_alias_by_book
            ON book_aliases(canonical_book)
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS book_aliases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                canonical_book TEXT NOT NULL,
                alias TEXT NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_alias_unique
            ON book_aliases(alias)
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_alias_by_book
            ON book_aliases(canonical_book)
        """.trimIndent())

        // v2: rich-text formatting stored as a JSON sidecar per note. The body
        // stays plain text (verse-ref regex + export unchanged); this column
        // records spans as {"t":"B","s":0,"e":5} entries. Nullable, so every
        // pre-existing note remains valid with no data rewrite.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN formatting TEXT")
        }
    }
}
