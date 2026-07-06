package com.example.mbible

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.chrisbanes.photoview.PhotoView

/**
 * Image feature — fullscreen viewer for an image inside a note.
 * Reuses PhotoView (already in the app for the catechism PDF), which gives
 * pinch-zoom, double-tap-zoom, and panning for free. Tap the image once to
 * close; back button works too.
 */
class ImageViewerDialog(
    context: Context,
    private val fileName: String
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }

        val photo = PhotoView(context).apply {
            // Load at (up to) full screen width — sharper than the inline copy,
            // which may have been shrunk by a Small/Medium size choice.
            val maxW = context.resources.displayMetrics.widthPixels
            val bmp = NoteImageStore.loadScaled(context, fileName, maxW)
            if (bmp != null) setImageBitmap(bmp)
            setOnPhotoTapListener { _, _, _ -> dismiss() }
        }

        root.addView(
            photo,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
    }
}
