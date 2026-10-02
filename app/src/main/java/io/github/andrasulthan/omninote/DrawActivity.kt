package io.github.andrasulthan.omninote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Free-hand drawing on a black dot-grid canvas. Saves a PNG plus editable stroke data. */
class DrawActivity : Activity() {

    private lateinit var drawView: DrawView
    private lateinit var eraserButton: TextView
    private val inkViews = ArrayList<Pair<Int, View>>()
    private val sizeViews = ArrayList<Pair<Float, View>>()
    private var path: String? = null
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH)
        darkSystemBars()

        val pad = Ui.dp(this, 16f)
        val small = Ui.dp(this, 8f)
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Top bar: ✕   DRAW ●   SAVE
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, small, pad, small)
        }
        bar.addView(Ui.text(this, 20f, INK_WHITE).apply {
            text = "✕"
            contentDescription = getString(R.string.draw_close)
            setPadding(0, small, pad, small)
            setOnClickListener { confirmClose() }
        })
        bar.addView(Ui.text(this, 15f, INK_WHITE, bold = true).apply {
            text = getString(R.string.draw_title)
            letterSpacing = 0.12f
        })
        val dot = Ui.dp(this, 7f)
        bar.addView(View(this).apply { background = Ui.circle(INK_RED) },
            LinearLayout.LayoutParams(dot, dot).apply { marginStart = dot })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(Ui.text(this, 13f, BG, bold = true).apply {
            text = getString(R.string.draw_save)
            letterSpacing = 0.1f
            val h = Ui.dp(context, 18f)
            setPadding(h, small, h, small)
            background = Ui.rounded(INK_WHITE, INK_WHITE, Ui.dp(context, 20f).toFloat(), 0)
            setOnClickListener { save() }
        })

        drawView = DrawView(this)

        // Tools: inks, sizes, eraser, undo, redo, clear
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, small, pad, small)
        }
        addInk(tools, INK_WHITE, R.string.draw_white)
        addInk(tools, INK_RED, R.string.draw_red)
        addInk(tools, INK_GREY, R.string.draw_grey)
        tools.addView(divider())
        addSize(tools, SIZES[0], 6f)
        addSize(tools, SIZES[1], 10f)
        addSize(tools, SIZES[2], 16f)
        tools.addView(divider())
        eraserButton = textTool(tools, getString(R.string.draw_eraser), R.string.draw_eraser) {
            drawView.erasing = !drawView.erasing
            updateTools()
        }
        textTool(tools, "↶", R.string.draw_undo) { drawView.undo() }
        textTool(tools, "↷", R.string.draw_redo) { drawView.redo() }
        textTool(tools, "⌧", R.string.draw_clear) { confirmClear() }

        root.addView(bar)
        root.addView(drawView, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(match, Ui.dp(this, 1f)))
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        }, LinearLayout.LayoutParams(match, wrap))
        Ui.applyInsets(root)
        setContentView(root)

        loadExisting()
        updateTools()
    }

    @Suppress("DEPRECATION")
    private fun darkSystemBars() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            window.decorView.systemUiVisibility = 0
        }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 1f), Ui.dp(context, 24f)).apply {
            marginStart = Ui.dp(context, 10f)
            marginEnd = Ui.dp(context, 10f)
        }
    }

    private fun addInk(parent: LinearLayout, color: Int, description: Int) {
        val size = Ui.dp(this, 26f)
        val view = View(this).apply {
            contentDescription = getString(description)
            setOnClickListener {
                drawView.color = color
                drawView.erasing = false
                updateTools()
            }
        }
        parent.addView(view, LinearLayout.LayoutParams(size, size).apply {
            marginEnd = Ui.dp(this@DrawActivity, 12f)
        })
        inkViews.add(color to view)
    }

    private fun addSize(parent: LinearLayout, width: Float, dotDp: Float) {
        val box = Ui.dp(this, 32f)
        val frame = FrameLayout(this).apply {
            contentDescription = getString(R.string.draw_size)
            setOnClickListener {
                drawView.strokeWidth = width
                drawView.erasing = false
                updateTools()
            }
        }
        val d = Ui.dp(this, dotDp)
        val inner = View(this)
        frame.addView(inner, FrameLayout.LayoutParams(d, d, Gravity.CENTER))
        parent.addView(frame, LinearLayout.LayoutParams(box, box).apply {
            marginEnd = Ui.dp(this@DrawActivity, 4f)
        })
        sizeViews.add(width to inner)
    }

    private fun textTool(parent: LinearLayout, label: String, description: Int, action: () -> Unit): TextView {
        val view = Ui.text(this, 15f, INK_WHITE, bold = true).apply {
            text = label
            contentDescription = getString(description)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            minWidth = Ui.dp(context, 40f)
            val h = Ui.dp(context, 10f)
            setPadding(h, h, h, h)
            setOnClickListener { action() }
        }
        parent.addView(view)
        return view
    }

    private fun updateTools() {
        val ring = Ui.dp(this, 3f)
        for ((color, view) in inkViews) {
            val selected = !drawView.erasing && drawView.color == color
            view.background = if (selected) {
                Ui.rounded(BG, color, Ui.dp(this, 13f).toFloat(), ring)
            } else {
                Ui.circle(color)
            }
        }
        for ((width, view) in sizeViews) {
            val selected = !drawView.erasing && drawView.strokeWidth == width
            view.background = Ui.circle(if (selected) INK_WHITE else MUTED)
        }
        eraserButton.setTextColor(if (drawView.erasing) INK_RED else INK_WHITE)
    }

    private fun loadExisting() {
        val existing = path ?: return
        try {
            val root = NotesRoot(this, NoteStore(this).treeUri)
            val text = root.open(jsonFor(existing))?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
            drawView.load(JSONObject(text))
        } catch (e: Exception) {
            // Without stroke data the drawing starts empty; the old image stays until saved over.
        }
    }

    private fun confirmClear() {
        if (drawView.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(R.string.draw_clear_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.draw_clear) { _, _ -> drawView.clear() }
            .show()
    }

    private fun confirmClose() {
        if (!drawView.changed) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.draw_discard)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.draw_discard_yes) { _, _ -> finish() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmClose()
    }

    private fun save() {
        if (saving) return
        val png = drawView.exportPng()
        if (png == null) {
            Toast.makeText(this, R.string.draw_empty, Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        val json = drawView.toJson().toString()
        val target = path ?: ("attachments/drawing-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png")
        Thread {
            val ok = try {
                val root = NotesRoot(this, NoteStore(this).treeUri)
                root.write(target, png.inputStream())
                root.writeText(jsonFor(target), json)
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                saving = false
                if (ok) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_PATH, target))
                    finish()
                } else {
                    Toast.makeText(this, R.string.draw_error, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** The drawing surface. Points are kept in units where the canvas is 1000 wide. */
    private class DrawView(ctx: Context) : View(ctx) {

        class Stroke(val color: Int, val width: Float, val points: FloatArray)

        var color = INK_WHITE
        var strokeWidth = SIZES[1]
        var erasing = false
        var changed = false

        private val strokes = ArrayList<Stroke>()
        private val undoStack = ArrayList<List<Stroke>>()
        private val redoStack = ArrayList<List<Stroke>>()
        private var current: ArrayList<Float>? = null
        private var erasedThisTouch = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRID }
        private val path = Path()

        private fun scale(): Float = if (width > 0) width / UNITS else 1f

        fun isEmpty(): Boolean = strokes.isEmpty()

        private fun remember() {
            undoStack.add(ArrayList(strokes))
            if (undoStack.size > 100) undoStack.removeAt(0)
            redoStack.clear()
        }

        fun undo() {
            if (undoStack.isEmpty()) return
            redoStack.add(ArrayList(strokes))
            strokes.clear()
            strokes.addAll(undoStack.removeAt(undoStack.size - 1))
            changed = true
            invalidate()
        }

        fun redo() {
            if (redoStack.isEmpty()) return
            undoStack.add(ArrayList(strokes))
            strokes.clear()
            strokes.addAll(redoStack.removeAt(redoStack.size - 1))
            changed = true
            invalidate()
        }

        fun clear() {
            remember()
            strokes.clear()
            changed = true
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val s = scale()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (erasing) {
                        remember()
                        erasedThisTouch = false
                        eraseAt(event.x / s, event.y / s)
                    } else {
                        current = arrayListOf(event.x / s, event.y / s)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (erasing) {
                        for (h in 0 until event.historySize) eraseAt(event.getHistoricalX(h) / s, event.getHistoricalY(h) / s)
                        eraseAt(event.x / s, event.y / s)
                    } else {
                        val points = current ?: return true
                        for (h in 0 until event.historySize) {
                            points.add(event.getHistoricalX(h) / s)
                            points.add(event.getHistoricalY(h) / s)
                        }
                        points.add(event.x / s)
                        points.add(event.y / s)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (erasing) {
                        if (!erasedThisTouch && undoStack.isNotEmpty()) undoStack.removeAt(undoStack.size - 1)
                    } else {
                        val points = current
                        if (points != null && points.size >= 2) {
                            if (points.size == 2) {
                                points.add(points[0] + 0.1f)
                                points.add(points[1])
                            }
                            remember()
                            strokes.add(Stroke(color, strokeWidth, points.toFloatArray()))
                            changed = true
                        }
                        current = null
                    }
                    invalidate()
                }
            }
            return true
        }

        private fun eraseAt(x: Float, y: Float) {
            val removed = strokes.removeAll { stroke ->
                val reach = ERASER + stroke.width / 2
                var hit = false
                var i = 0
                while (i < stroke.points.size - 1) {
                    val dx = stroke.points[i] - x
                    val dy = stroke.points[i + 1] - y
                    if (dx * dx + dy * dy <= reach * reach) {
                        hit = true
                        break
                    }
                    i += 2
                }
                hit
            }
            if (removed) {
                erasedThisTouch = true
                changed = true
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(BG)
            val s = scale()
            canvas.save()
            canvas.scale(s, s)
            val step = 40f
            val radius = resources.displayMetrics.density * 1.2f / s
            val rows = (height / s / step).toInt() + 1
            for (r in 1..rows) {
                var x = step / 2
                while (x < UNITS) {
                    canvas.drawCircle(x, r * step - step / 2, radius, dotPaint)
                    x += step
                }
            }
            for (stroke in strokes) drawStroke(canvas, stroke.color, stroke.width, stroke.points)
            current?.let { drawStroke(canvas, color, strokeWidth, it.toFloatArray()) }
            canvas.restore()
        }

        private fun drawStroke(canvas: Canvas, color: Int, width: Float, points: FloatArray) {
            if (points.size < 2) return
            path.reset()
            path.moveTo(points[0], points[1])
            var px = points[0]
            var py = points[1]
            var i = 2
            while (i < points.size - 1) {
                val x = points[i]
                val y = points[i + 1]
                path.quadTo(px, py, (px + x) / 2, (py + y) / 2)
                px = x
                py = y
                i += 2
            }
            path.lineTo(px, py)
            paint.color = color
            paint.strokeWidth = width
            canvas.drawPath(path, paint)
        }

        fun toJson(): JSONObject {
            val list = JSONArray()
            for (stroke in strokes) {
                val pts = JSONArray()
                for (v in stroke.points) pts.put(Math.round(v * 10) / 10.0)
                list.put(
                    JSONObject()
                        .put("c", String.format("#%06X", 0xFFFFFF and stroke.color))
                        .put("w", stroke.width.toDouble())
                        .put("p", pts)
                )
            }
            return JSONObject().put("version", 1).put("width", UNITS.toDouble()).put("strokes", list)
        }

        fun load(json: JSONObject) {
            val list = json.optJSONArray("strokes") ?: return
            strokes.clear()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val pts = o.optJSONArray("p") ?: continue
                val points = FloatArray(pts.length()) { pts.optDouble(it, 0.0).toFloat() }
                val color = try {
                    Color.parseColor(o.optString("c", "#FFFFFF"))
                } catch (e: Exception) {
                    INK_WHITE
                }
                strokes.add(Stroke(color, o.optDouble("w", SIZES[1].toDouble()).toFloat(), points))
            }
            undoStack.clear()
            redoStack.clear()
            changed = false
            invalidate()
        }

        /** Renders the drawing, cropped to its content, as PNG bytes. */
        fun exportPng(): ByteArray? {
            if (strokes.isEmpty()) return null
            var minY = Float.MAX_VALUE
            var maxY = 0f
            var widest = 0f
            for (stroke in strokes) {
                widest = maxOf(widest, stroke.width)
                var i = 1
                while (i < stroke.points.size) {
                    minY = minOf(minY, stroke.points[i])
                    maxY = maxOf(maxY, stroke.points[i])
                    i += 2
                }
            }
            val margin = widest + 30f
            val top = maxOf(0f, minY - margin)
            val bottom = maxY + margin
            val s = OUTPUT_WIDTH / UNITS
            val heightPx = maxOf(200, ((bottom - top) * s).toInt())
            val bitmap = Bitmap.createBitmap(OUTPUT_WIDTH.toInt(), heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(BG)
            canvas.scale(s, s)
            canvas.translate(0f, -top)
            for (stroke in strokes) drawStroke(canvas, stroke.color, stroke.width, stroke.points)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            return out.toByteArray()
        }
    }

    companion object {
        const val EXTRA_PATH = "drawing_path"
        private const val UNITS = 1000f
        private const val OUTPUT_WIDTH = 1080f
        private const val ERASER = 18f
        private val SIZES = floatArrayOf(4f, 8f, 16f)
        private const val BG = 0xFF000000.toInt()
        private const val GRID = 0xFF262626.toInt()
        private const val LINE = 0xFF333333.toInt()
        private const val MUTED = 0xFF5C5C5C.toInt()
        private const val INK_WHITE = 0xFFFFFFFF.toInt()
        private const val INK_RED = 0xFFD71921.toInt()
        private const val INK_GREY = 0xFF8A8A8A.toInt()

        /** Stroke data lives next to the image, for editing later. */
        fun jsonFor(png: String): String = png.removeSuffix(".png") + ".json"

        fun isDrawing(path: String): Boolean =
            path.substringAfterLast('/').startsWith("drawing-") && path.endsWith(".png")
    }
}
