package com.example.mbible

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Image feature — a finger-drawing pad. White canvas; pen color chosen from
 * swatches; the eraser is simply a fat white pen (on a white canvas, painting
 * white IS erasing — no fancy blend modes needed).
 *
 * Each stroke is stored as its own (Path, Paint) pair, which is what makes
 * Undo trivial: remove the last pair and redraw.
 */
class DrawingDialog(
    context: Context,
    private val onDone: (Bitmap) -> Unit
) : Dialog(context, android.R.style.Theme_Material_Light_NoActionBar) {

    private lateinit var drawingView: DrawingView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Keep the controls clear of the front camera: pad by the LARGER of the
        // status-bar inset and the display-cutout inset (punch-hole cameras
        // report as cutouts), plus 12dp of breathing room. Note the theme above
        // is NOT the "_Fullscreen" variant — that one hides the status bar,
        // and a hidden status bar reports a top inset of ZERO, which is exactly
        // how the buttons ended up under the camera before.
        val extra = (12 * context.resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            v.setPadding(0, maxOf(bars, cutout) + extra, 0, 0)
            insets
        }

        drawingView = DrawingView(context)

        // Row 1: actions
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 0)
        }
        fun makeButton(label: String, onClick: () -> Unit) = Button(context).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        actionRow.addView(makeButton(context.getString(R.string.drawing_undo)) { drawingView.undo() }, lp)
        actionRow.addView(makeButton(context.getString(R.string.drawing_clear)) { drawingView.clear() }, lp)
        actionRow.addView(makeButton(context.getString(R.string.action_cancel)) { dismiss() }, lp)
        actionRow.addView(makeButton(context.getString(R.string.drawing_done)) {
            onDone(drawingView.export())
            dismiss()
        }, lp)

        // Row 2: color swatches + eraser. Selection is shown with a dark ring.
        val colors = listOf(
            Color.BLACK, 0xFFD32F2F.toInt(), 0xFF1976D2.toInt(),
            0xFF388E3C.toInt(), 0xFFF57C00.toInt(), 0xFF7B1FA2.toInt()
        )
        val swatchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(24, 8, 24, 12)
        }
        val swatchViews = mutableListOf<View>()

        // Selected swatch = black outer ring + white gap + the color core.
        // The black/white ring pair is readable over EVERY fill color — a
        // single dark ring disappeared on the black swatch.
        fun swatch(fill: Int, selected: Boolean): android.graphics.drawable.Drawable {
            fun oval(c: Int) = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
            if (!selected) return oval(fill).apply { setStroke(3, 0xFFBBBBBB.toInt()) }
            return android.graphics.drawable.LayerDrawable(
                arrayOf(oval(Color.BLACK), oval(Color.WHITE), oval(fill))
            ).apply {
                setLayerInset(1, 6, 6, 6, 6)   // white gap ring
                setLayerInset(2, 12, 12, 12, 12) // color core
            }
        }

        lateinit var refreshSwatches: () -> Unit
        colors.forEach { color ->
            val dot = View(context)
            dot.setOnClickListener {
                drawingView.penColor = color
                drawingView.eraser = false
                refreshSwatches()
            }
            swatchViews.add(dot)
            swatchRow.addView(dot, LinearLayout.LayoutParams(72, 72).apply { marginEnd = 20 })
        }

        val eraserBtn = android.widget.ImageButton(context).apply {
            setImageResource(R.drawable.ic_eraser)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            contentDescription = context.getString(R.string.drawing_eraser)
            setOnClickListener {
                drawingView.eraser = !drawingView.eraser
                refreshSwatches()
            }
        }
        swatchRow.addView(eraserBtn, LinearLayout.LayoutParams(72, 72))

        refreshSwatches = {
            colors.forEachIndexed { i, color ->
                val selected = !drawingView.eraser && drawingView.penColor == color
                swatchViews[i].background = swatch(color, selected)
            }
            // The eraser is drawn as a WHITE swatch (it literally paints white);
            // when active it gets the same black/white selection rings.
            eraserBtn.background = swatch(Color.WHITE, drawingView.eraser)
        }
        refreshSwatches()

        root.addView(actionRow)
        root.addView(swatchRow)
        root.addView(
            drawingView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    private class DrawingView(context: Context) : View(context) {

        var penColor: Int = Color.BLACK
        var eraser: Boolean = false

        // One (Path, Paint) pair PER stroke — the paint captures the color and
        // width at the moment the stroke started, so Undo/redraw is exact.
        private class Stroke(val path: Path, val paint: Paint)

        private val strokes = mutableListOf<Stroke>()

        private fun newPaint() = Paint().apply {
            color = if (eraser) Color.WHITE else penColor
            style = Paint.Style.STROKE
            strokeWidth = if (eraser) 42f else 6f // fat eraser, fine pen
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.WHITE)
            for (s in strokes) canvas.drawPath(s.path, s.paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val p = Path().apply { moveTo(event.x, event.y) }
                    p.lineTo(event.x + 0.1f, event.y) // a tap still leaves a dot
                    strokes.add(Stroke(p, newPaint()))
                }
                MotionEvent.ACTION_MOVE ->
                    strokes.lastOrNull()?.path?.lineTo(event.x, event.y)
                else -> return super.onTouchEvent(event).let { true }
            }
            invalidate()
            return true
        }

        fun undo() {
            strokes.removeLastOrNull()
            invalidate()
        }

        fun clear() {
            strokes.clear()
            invalidate()
        }

        fun export(): Bitmap {
            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            draw(Canvas(bmp))
            return bmp
        }
    }
}
