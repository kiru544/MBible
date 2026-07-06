package com.example.mbible

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.TextView

/**
 * Two-finger pinch-to-zoom for text size, shared by the Bible reader and the
 * note editor.
 *
 * How it works:
 *  - [ScaleGestureDetector] watches the raw touch stream. While two fingers
 *    move apart/together it reports a `scaleFactor` per frame (1.02, 0.97, ...).
 *  - We multiply the current size (in sp) by that factor, clamp it to a sane
 *    range, and hand it to the caller to apply with setTextSize().
 *  - When the pinch ends, the final size is saved to SharedPreferences under
 *    a per-screen key, so the choice survives app restarts.
 *
 * Touch-dispatch notes (the genuinely tricky Android part):
 *  - The listener returns FALSE for single-finger events, so scrolling,
 *    cursor placement, text selection, and link taps all keep working.
 *  - It returns TRUE for multi-finger events, which (a) stops the EditText /
 *    TextView from treating the pinch as a selection drag and (b) lets us call
 *    requestDisallowInterceptTouchEvent so ViewPager2 doesn't interpret the
 *    pinch as a page swipe and ScrollView doesn't treat it as a scroll.
 */
object TextZoom {

    const val KEY_BIBLE = "bible_text_sp"
    const val KEY_NOTE = "note_text_sp"

    private const val PREFS = "text_zoom_prefs"
    private const val MIN_SP = 12f
    private const val MAX_SP = 34f

    /** The saved size for [key], or [defaultSp] if the user never pinched. */
    fun savedSp(context: Context, key: String, defaultSp: Float): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(key, defaultSp)

    private fun save(context: Context, key: String, sp: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(key, sp).apply()
    }

    /** [TextView.getTextSize] returns px; convert back to sp for the math. */
    fun currentSp(textView: TextView): Float =
        textView.textSize / textView.resources.displayMetrics.scaledDensity

    /** Restores the saved size onto [textView] (no-op on first ever launch). */
    fun applySaved(textView: TextView, key: String) {
        val sp = savedSp(textView.context, key, currentSp(textView))
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    /**
     * Wires pinch handling onto one or more [touchTargets] that share a single
     * detector. We attach to more than one view because touch events go to the
     * deepest view that claims them: on a footnote-bearing chapter the TextView
     * (LinkMovementMethod) owns the stream, on a plain chapter the enclosing
     * ScrollView does — attaching to both means the pinch works either way.
     *
     * @param startSp  called when a pinch BEGINS; returns the size to scale from.
     * @param onScaled called continuously DURING the pinch with the new sp.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(
        key: String,
        startSp: () -> Float,
        onScaled: (Float) -> Unit,
        vararg touchTargets: View
    ) {
        if (touchTargets.isEmpty()) return
        val context = touchTargets.first().context

        var pinchSp = 0f
        val detector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                    pinchSp = startSp()
                    return true
                }

                override fun onScale(d: ScaleGestureDetector): Boolean {
                    pinchSp = (pinchSp * d.scaleFactor).coerceIn(MIN_SP, MAX_SP)
                    onScaled(pinchSp)
                    return true
                }

                override fun onScaleEnd(d: ScaleGestureDetector) {
                    save(context, key, pinchSp)
                }
            })

        for (target in touchTargets) {
            target.setOnTouchListener { v, ev ->
                detector.onTouchEvent(ev)
                val pinching = ev.pointerCount > 1 || detector.isInProgress
                if (pinching) v.parent?.requestDisallowInterceptTouchEvent(true)
                pinching // consume ONLY multi-touch; single finger behaves normally
            }
        }
    }
}
