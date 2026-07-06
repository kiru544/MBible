package com.example.mbible

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Make the status-bar icons readable on the current theme.
        ThemeManager.applyStatusBarIcons(this)

        // 3. Initialize bottomNav FIRST
        bottomNav = findViewById(R.id.bottomNav)

        // Edge-to-edge: draw behind the system bars and apply the insets ourselves
        // so the bottom nav's background extends behind the system navigation bar
        // (covers the bottom consistently in both light and dark themes).
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.rootMain)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())

            // Keyboard handling: when the IME is open, pad the root by the
            // keyboard's height so the content shrinks to the space above it —
            // that's what makes the note editor's bottom formatting bar sit
            // directly on top of the keyboard. The tab bar is useless while
            // typing, so it's hidden until the keyboard goes away.
            val imeVisible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            val imeBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom

            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, if (imeVisible) imeBottom else 0)
            bottomNav.visibility = if (imeVisible) View.GONE else View.VISIBLE
            bottomNav.setPadding(
                bottomNav.paddingLeft, bottomNav.paddingTop, bottomNav.paddingRight, bars.bottom
            )
            insets
        }

        // 4. Load default fragment
        if (savedInstanceState == null) {
            switchFragment(BibleFragment.newInstance("Old"))
            bottomNav.selectedItemId = R.id.nav_ot
        }

        // 5. Tab listener
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_ot -> {
                    switchFragment(BibleFragment.newInstance("Old"))
                }
                R.id.nav_nt -> {
                    switchFragment(BibleFragment.newInstance("New"))
                }
                R.id.nav_docs -> {
                    switchFragment(DocsFragment())
                }
                R.id.nav_notes -> {
                    switchFragment(NotesFragment())
                }
            }
            true
        }

        // 6. Back button
        onBackPressedDispatcher.addCallback(this) {
            val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            if (current is BibleFragment && current.onBackPressed()) return@addCallback
            if (current is NotesFragment) {
                val handled = current.childFragmentManager.popBackStackImmediate()
                if (handled) return@addCallback
            }
            if (bottomNav.selectedItemId != R.id.nav_ot) {
                bottomNav.selectedItemId = R.id.nav_ot
            } else {
                finish()
            }
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}