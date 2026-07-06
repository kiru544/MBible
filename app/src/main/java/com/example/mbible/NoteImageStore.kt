package com.example.mbible

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Image feature — stores note images as files in the app's PRIVATE storage
 * (filesDir/note_images). Private storage needs zero permissions, is removed
 * with the app, and the database only ever holds the small file NAME, never
 * megabytes of pixels (SQLite rows should stay small).
 *
 * Photos are downscaled to at most [MAX_DIM] px on their longest side before
 * saving — a full 12 MP camera shot would otherwise chew memory every time the
 * note opens.
 */
object NoteImageStore {

    private const val DIR = "note_images"
    private const val MAX_DIM = 1280

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    fun file(context: Context, name: String) = File(dir(context), name)

    /** Save a drawing (lossless PNG keeps line art crisp). Returns the file name. */
    fun saveBitmap(context: Context, bmp: Bitmap): String? = try {
        val name = "img_${System.currentTimeMillis()}.png"
        FileOutputStream(file(context, name)).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        name
    } catch (_: Exception) {
        null
    }

    /** Import a gallery pick (content:// Uri) — decode downscaled, fix rotation, save. */
    fun importUri(context: Context, uri: Uri): String? {
        val bmp = decodeScaled { context.contentResolver.openInputStream(uri) } ?: return null
        val rotated = applyExifRotation(bmp) { context.contentResolver.openInputStream(uri) }
        return saveJpeg(context, rotated)
    }

    /** Import a camera capture (temp File) — decode downscaled, fix rotation, save. */
    fun importFile(context: Context, f: File): String? {
        val bmp = decodeScaled { FileInputStream(f) } ?: return null
        val rotated = applyExifRotation(bmp) { FileInputStream(f) }
        return saveJpeg(context, rotated)
    }

    /** Load a stored image, downscaled again so it's never wider than [maxW]. */
    fun loadScaled(context: Context, name: String, maxW: Int): Bitmap? {
        val f = file(context, name)
        if (!f.exists()) return null
        val bmp = decodeScaled { FileInputStream(f) } ?: return null
        if (bmp.width <= maxW) return bmp
        val h = (bmp.height * maxW.toFloat() / bmp.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, maxW, h, true)
    }

    private fun saveJpeg(context: Context, bmp: Bitmap): String? = try {
        val name = "img_${System.currentTimeMillis()}.jpg"
        FileOutputStream(file(context, name)).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        name
    } catch (_: Exception) {
        null
    }

    /**
     * Memory-safe decode: pass ONE — read only the dimensions
     * (inJustDecodeBounds). Pass TWO — decode with inSampleSize, which makes
     * the decoder skip pixels instead of loading everything and shrinking.
     * Streams can't be rewound, so the caller hands us a FACTORY that can
     * open a fresh stream for each pass.
     */
    private fun decodeScaled(open: () -> InputStream?): Bitmap? = try {
        // Pass one: dimensions only. NOTE: with inJustDecodeBounds=true,
        // decodeStream ALWAYS returns null by design — success is judged by
        // bounds.outWidth/outHeight being filled in, never by the return value.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = open() ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DIM ||
            bounds.outHeight / (sample * 2) >= MAX_DIM
        ) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        open()?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }

    /**
     * Cameras usually save sideways pixels plus an EXIF "orientation" tag.
     * Bitmaps ignore the tag, so without this step portrait photos insert
     * rotated 90°. We read the tag and physically rotate the pixels once.
     */
    private fun applyExifRotation(bmp: Bitmap, open: () -> InputStream?): Bitmap = try {
        val orientation = open()?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) bmp
        else Bitmap.createBitmap(
            bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(degrees) }, true
        )
    } catch (_: Exception) {
        bmp
    }
}
