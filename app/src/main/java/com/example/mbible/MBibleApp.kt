package com.example.mbible

import android.app.Application
import android.content.Context
import com.example.mbible.data.BibleRepository
import com.example.mbible.data.NotesRepository
import com.youversion.platform.core.YouVersionPlatformConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MBibleApp : Application() {

    // Outlives any single screen. Used for work that must finish even while a
    // fragment is being torn down — e.g. the note editor's onPause autosave.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // App-scoped singletons. Built once and shared, so the bundled bible.db is
    // opened a single time (instead of one leaked handle per fragment) and the
    // notes/alias helpers aren't rebuilt on every screen.
    val bibleRepository by lazy { BibleRepository(this) }
    val notesRepository by lazy { NotesRepository(this) }
    val aliasRepository by lazy { BookAliasRepository(this) }

    override fun onCreate() {
        super.onCreate()
        // Apply the user's saved theme (light/dark) before any screen is shown.
        ThemeManager.applySavedTheme(this)
        YouVersionPlatformConfiguration.configure(
            context = this,
            appKey = BuildConfig.YOUVERSION_APP_KEY
        )
    }
}

/** Convenience accessor: requireContext().app.bibleRepository, etc. */
val Context.app: MBibleApp get() = applicationContext as MBibleApp