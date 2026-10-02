package io.github.andrasulthan.omninote

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Every checklist item from every note in one list. */
class TasksActivity : Activity() {

    private class Task(
        val noteId: String,
        val noteTitle: String,
        val line: Int,
        val raw: String,
        val text: String,
        val done: Boolean
    )

    private lateinit var store: NoteStore
    private lateinit var p: Ui.Palette
    private lateinit var countView: TextView
    private lateinit var emptyView: TextView
    private val tasksAdapter = TasksAdapter()
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        p = Ui.palette(this)
        val pad = Ui.dp(this, 20f)
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(pad, Ui.dp(context, 8f), pad, 0)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Ui.text(this, 22f, p.text).apply {
            text = "←"
            contentDescription = getString(R.string.back)
            setPadding(0, pad / 2, pad, pad / 2)
            setOnClickListener { finish() }
        }
        val title = Ui.text(this, 22f, p.text, bold = true).apply {
            text = getString(R.string.tasks).uppercase()
            letterSpacing = 0.12f
        }
        countView = Ui.text(this, 13f, p.muted).apply {
            setPadding(Ui.dp(context, 10f), 0, 0, 0)
        }
        bar.addView(back)
        bar.addView(title)
        bar.addView(countView)

        val list = ListView(this).apply {
            divider = ColorDrawable(p.border)
            dividerHeight = Ui.dp(context, 1f)
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 40f))
            adapter = tasksAdapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                toggle(tasksAdapter.getItem(position))
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
                openNote(tasksAdapter.getItem(position))
                true
            }
        }

        emptyView = Ui.text(this, 14f, p.muted).apply {
            text = getString(R.string.tasks_empty)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            visibility = View.GONE
        }

        val body = FrameLayout(this)
        body.addView(list, FrameLayout.LayoutParams(match, match))
        body.addView(emptyView, FrameLayout.LayoutParams(match, match))

        root.addView(bar)
        root.addView(body, LinearLayout.LayoutParams(match, 0, 1f))
        Ui.applyInsets(root)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun reload() {
        io.execute {
            val tasks = ArrayList<Task>()
            try {
                for (note in store.list()) {
                    if (note.meta.archived) continue
                    val lines = store.read(note.id).replace("\r\n", "\n").split("\n")
                    lines.forEachIndexed { index, line ->
                        val m = TASK.find(line)
                        if (m != null && m.groupValues[4].isNotBlank()) {
                            tasks.add(
                                Task(
                                    note.id, note.title, index, line,
                                    m.groupValues[4].trim(), m.groupValues[2] != " "
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, R.string.error_folder, Toast.LENGTH_LONG).show() }
            }
            val sorted = tasks.sortedBy { it.done }
            runOnUiThread {
                if (!isDestroyed) {
                    tasksAdapter.items = sorted
                    tasksAdapter.notifyDataSetChanged()
                    val open = sorted.count { !it.done }
                    countView.text = if (sorted.isEmpty()) "" else "$open / ${sorted.size}"
                    emptyView.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun toggle(task: Task) {
        io.execute {
            try {
                val lines = store.read(task.noteId).replace("\r\n", "\n").split("\n").toMutableList()
                val line = lines.getOrNull(task.line)
                val m = if (line == task.raw) TASK.find(line) else null
                if (m != null) {
                    val flipped = if (m.groupValues[2] == " ") "x" else " "
                    lines[task.line] = m.groupValues[1] + flipped + m.groupValues[3] + m.groupValues[4]
                    store.save(task.noteId, "", lines.joinToString("\n"))
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, R.string.error_save, Toast.LENGTH_SHORT).show() }
            }
            runOnUiThread { if (!isDestroyed) reload() }
        }
    }

    private fun openNote(task: Task) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, task.noteId))
    }

    private inner class TasksAdapter : BaseAdapter() {
        var items: List<Task> = emptyList()

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Task = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: buildRow()
            val task = items[position]
            val box = row.getChildAt(0) as TextView
            box.text = if (task.done) "☑" else "☐"
            val column = row.getChildAt(1) as LinearLayout
            val text = column.getChildAt(0) as TextView
            text.text = Markdown.plain(task.text)
            text.setTextColor(if (task.done) p.muted else p.text)
            text.paintFlags = if (task.done) {
                text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            (column.getChildAt(1) as TextView).text = task.noteTitle
            return row
        }

        private fun buildRow(): LinearLayout {
            val ctx = this@TasksActivity
            val v = Ui.dp(ctx, 12f)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, v, 0, v)
                addView(Ui.text(ctx, 20f, Ui.RED).apply {
                    setPadding(0, 0, Ui.dp(ctx, 14f), 0)
                })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(ctx, 15f, p.text).apply {
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(Ui.text(ctx, 11f, p.muted).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, Ui.dp(ctx, 4f), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    companion object {
        private val TASK = Regex("^(\\s*[-*+]\\s\\[)([ xX])(]\\s)(.*)$")
    }
}
