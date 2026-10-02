package io.github.andrasulthan.omninote

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Writes, reads and edits one note. Saves automatically when the screen is left. */
class EditorActivity : Activity() {

    private lateinit var store: NoteStore
    private lateinit var p: Ui.Palette
    private lateinit var titleView: EditText
    private lateinit var bodyView: EditText
    private lateinit var readScroll: ScrollView
    private lateinit var readView: TextView
    private lateinit var toolArea: LinearLayout
    private lateinit var modeButton: TextView
    private lateinit var countView: TextView
    private var noteId: String? = null
    private var savedText = ""
    private var deleted = false
    private var reading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        p = Ui.palette(this)
        noteId = savedInstanceState?.getString(KEY_ID) ?: intent.getStringExtra(EXTRA_ID)

        val scale = store.fontScale()
        val pad = Ui.dp(this, 20f)
        val small = Ui.dp(this, 10f)
        val match = LinearLayout.LayoutParams.MATCH_PARENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(pad, Ui.dp(context, 8f), pad, 0)
        }

        // Top bar: back, read/edit switch, more menu
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
        modeButton = Ui.text(this, 13f, p.text).apply {
            text = getString(R.string.mode_read).uppercase()
            letterSpacing = 0.1f
            setPadding(pad, pad / 2, pad / 2, pad / 2)
            setOnClickListener { if (reading) showEdit() else showRead() }
        }
        val more = Ui.text(this, 24f, p.text).apply {
            text = "⋯"
            contentDescription = getString(R.string.more)
            setPadding(pad / 2, pad / 2, 0, pad / 2)
            setOnClickListener { showMoreMenu(it) }
        }
        bar.addView(back)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(modeButton)
        bar.addView(more)

        titleView = EditText(this).apply {
            id = ID_TITLE
            hint = getString(R.string.title_hint)
            setHintTextColor(p.muted)
            setTextColor(p.text)
            textSize = 24f * scale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = null
            setPadding(0, pad / 2, 0, pad / 2)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setSingleLine(true)
        }

        val line = View(this).apply { setBackgroundColor(p.border) }

        countView = Ui.text(this, 11f, p.muted).apply {
            gravity = Gravity.END
            setPadding(0, small / 2, 0, small / 2)
        }

        bodyView = EditText(this).apply {
            id = ID_BODY
            hint = getString(R.string.body_hint)
            setHintTextColor(p.muted)
            setTextColor(p.text)
            textSize = 16f * scale
            typeface = Typeface.MONOSPACE
            background = null
            gravity = Gravity.TOP or Gravity.START
            setPadding(0, pad, 0, pad)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setLineSpacing(0f, 1.25f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s != null) {
                        Markdown.styleEditable(s, p)
                        updateCount()
                    }
                }
            })
        }

        readView = Ui.text(this, 16f * scale, p.text).apply {
            setLineSpacing(0f, 1.25f)
            setPadding(0, pad, 0, pad)
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        readScroll = ScrollView(this).apply {
            isFillViewport = true
            visibility = View.GONE
            addView(readView)
        }

        // Toolbar: one scrollable row with every command
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tool(label: String, description: Int, action: () -> Unit) {
            tools.addView(Ui.text(this, 16f, p.text, bold = true).apply {
                text = label
                contentDescription = getString(description)
                gravity = Gravity.CENTER
                minWidth = Ui.dp(this@EditorActivity, 44f)
                setPadding(small, small, small, small)
                setOnClickListener { action() }
            })
        }
        tool("H", R.string.tool_heading) { cycleHeading() }
        tool("B", R.string.tool_bold) { wrap("**") }
        tool("I", R.string.tool_italic) { wrap("*") }
        tool("S", R.string.tool_strike) { wrap("~~") }
        tool("`", R.string.tool_code) { wrap("`") }
        tool("•", R.string.tool_bullet) { bullet() }
        tool("☐", R.string.tool_task) { task() }
        tool(">", R.string.tool_quote) { quote() }
        tool("↗", R.string.tool_link) { link() }
        tool("▦", R.string.tool_table) { table() }
        tool("—", R.string.tool_rule) { rule() }

        toolArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(context).apply { setBackgroundColor(p.border) },
                LinearLayout.LayoutParams(match, Ui.dp(context, 1f)))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(tools)
            })
        }

        root.addView(bar)
        root.addView(titleView)
        root.addView(line, LinearLayout.LayoutParams(match, Ui.dp(this, 1f)))
        root.addView(bodyView, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(readScroll, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(countView)
        root.addView(toolArea)
        Ui.applyInsets(root)
        setContentView(root)

        val existing = noteId
        when {
            existing != null -> {
                try {
                    val (title, body) = NoteStore.split(store.read(existing))
                    titleView.setText(title)
                    bodyView.setText(body)
                    savedText = NoteStore.join(title, body)
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
                }
            }
            intent.action == Intent.ACTION_SEND -> {
                titleView.setText(intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "")
                bodyView.setText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: "")
            }
            else -> titleView.requestFocus()
        }
        updateCount()
    }

    override fun onPause() {
        super.onPause()
        if (!deleted) save()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ID, noteId)
    }

    // ---------- Saving ----------

    private fun save() {
        val title = titleView.text.toString()
        val body = bodyView.text.toString()
        val text = NoteStore.join(title, body)
        if (text == savedText) return
        if (noteId == null && title.isBlank() && body.isBlank()) return
        try {
            noteId = store.save(noteId, title, text)
            savedText = text
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateCount() {
        val body = bodyView.text.toString()
        val words = WORD.findAll(body).count()
        countView.text = getString(R.string.word_count, words, body.length)
    }

    // ---------- Read / edit modes ----------

    private fun showRead() {
        reading = true
        readView.text = Markdown.render(
            bodyView.text.toString(),
            p,
            resources.displayMetrics.density,
            { line -> toggleTask(line) },
            { url -> openLink(url) }
        )
        bodyView.visibility = View.GONE
        toolArea.visibility = View.GONE
        readScroll.visibility = View.VISIBLE
        modeButton.text = getString(R.string.mode_edit).uppercase()
        hideKeyboard()
    }

    private fun showEdit() {
        reading = false
        readScroll.visibility = View.GONE
        bodyView.visibility = View.VISIBLE
        toolArea.visibility = View.VISIBLE
        modeButton.text = getString(R.string.mode_read).uppercase()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(bodyView.windowToken, 0)
    }

    private fun toggleTask(lineIndex: Int) {
        val lines = bodyView.text.toString().split("\n").toMutableList()
        if (lineIndex !in lines.indices) return
        val line = lines[lineIndex]
        val m = TASK_BOX.find(line) ?: return
        val flipped = if (m.groupValues[2] == " ") "x" else " "
        lines[lineIndex] = m.groupValues[1] + flipped + m.groupValues[3] + line.substring(m.value.length)
        bodyView.setText(lines.joinToString("\n"))
        if (reading) showRead()
    }

    private fun openLink(url: String) {
        val full = if (url.contains("://") || url.startsWith("mailto:")) url else "https://$url"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_link, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- More menu: share, contents, delete ----------

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, M_SHARE, 0, R.string.share)
        popup.menu.add(0, M_TOC, 1, R.string.toc)
        popup.menu.add(0, M_DELETE, 2, R.string.delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_SHARE -> share()
                M_TOC -> showToc()
                M_DELETE -> confirmDelete()
            }
            true
        }
        popup.show()
    }

    private fun share() {
        val title = titleView.text.toString()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, NoteStore.join(title, bodyView.text.toString()))
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
    }

    private fun showToc() {
        val body = bodyView.text.toString()
        val levels = ArrayList<Int>()
        val names = ArrayList<String>()
        val offsets = ArrayList<Int>()
        var offset = 0
        for (line in body.split("\n")) {
            val m = TOC_HEADING.find(line)
            if (m != null) {
                levels.add(m.groupValues[1].length)
                names.add(m.groupValues[2].trim())
                offsets.add(offset)
            }
            offset += line.length + 1
        }
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.toc_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = names.indices.map { "  ".repeat(levels[it] - 1) + names[it] }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.toc)
            .setItems(labels) { _, which -> jumpTo(offsets[which], names[which]) }
            .show()
    }

    private fun jumpTo(offset: Int, heading: String) {
        if (reading) {
            val layout = readView.layout ?: return
            val pos = readView.text.indexOf(heading).coerceAtLeast(0)
            val top = layout.getLineTop(layout.getLineForOffset(pos)) + readView.paddingTop
            readScroll.smoothScrollTo(0, top)
        } else {
            bodyView.requestFocus()
            bodyView.setSelection(offset.coerceIn(0, bodyView.text.length))
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleted = true
                val existing = noteId
                if (existing != null) {
                    try {
                        store.delete(existing)
                    } catch (e: Exception) {
                        Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
                    }
                }
                finish()
            }
            .show()
    }

    // ---------- Toolbar actions ----------

    private fun wrap(prefix: String, suffix: String = prefix) {
        val e = bodyView.text
        val a = minOf(bodyView.selectionStart, bodyView.selectionEnd).coerceIn(0, e.length)
        val b = maxOf(bodyView.selectionStart, bodyView.selectionEnd).coerceIn(0, e.length)
        e.insert(b, suffix)
        e.insert(a, prefix)
        if (a == b) {
            bodyView.setSelection(a + prefix.length)
        } else {
            bodyView.setSelection(a + prefix.length, b + prefix.length)
        }
    }

    /** Start and end of the line the cursor is on. */
    private fun lineBounds(): IntArray {
        val e = bodyView.text
        val pos = bodyView.selectionStart.coerceIn(0, e.length)
        var start = pos
        while (start > 0 && e[start - 1] != '\n') start--
        var end = pos
        while (end < e.length && e[end] != '\n') end++
        return intArrayOf(start, end)
    }

    private fun cycleHeading() {
        val e = bodyView.text
        val start = lineBounds()[0]
        var hashes = 0
        while (start + hashes < e.length && e[start + hashes] == '#') hashes++
        val hasSpace = start + hashes < e.length && e[start + hashes] == ' '
        val current = if (hashes in 1..6 && hasSpace) hashes else 0
        if (current > 0) e.delete(start, start + current + 1)
        if (current < 3) e.insert(start, "#".repeat(current + 1) + " ")
    }

    private fun bullet() {
        val e = bodyView.text
        val bounds = lineBounds()
        val start = bounds[0]
        val line = e.substring(start, bounds[1])
        val task = TASK_PREFIXES.firstOrNull { line.startsWith(it) }
        when {
            task != null -> e.replace(start, start + task.length, "- ")
            line.startsWith("- ") -> e.delete(start, start + 2)
            line.startsWith("> ") -> e.replace(start, start + 2, "- ")
            else -> e.insert(start, "- ")
        }
    }

    private fun task() {
        val e = bodyView.text
        val bounds = lineBounds()
        val start = bounds[0]
        val line = e.substring(start, bounds[1])
        val task = TASK_PREFIXES.firstOrNull { line.startsWith(it) }
        when {
            task != null -> e.delete(start, start + task.length)
            line.startsWith("- ") -> e.replace(start, start + 2, "- [ ] ")
            else -> e.insert(start, "- [ ] ")
        }
    }

    private fun quote() {
        val e = bodyView.text
        val bounds = lineBounds()
        val start = bounds[0]
        val line = e.substring(start, bounds[1])
        if (line.startsWith("> ")) e.delete(start, start + 2) else e.insert(start, "> ")
    }

    private fun link() {
        val e = bodyView.text
        val a = minOf(bodyView.selectionStart, bodyView.selectionEnd).coerceIn(0, e.length)
        val b = maxOf(bodyView.selectionStart, bodyView.selectionEnd).coerceIn(0, e.length)
        val label = if (a == b) getString(R.string.link_text) else e.substring(a, b)
        e.replace(a, b, "[$label](https://)")
        bodyView.setSelection((a + label.length + 11).coerceAtMost(e.length))
    }

    private fun table() {
        val c = getString(R.string.table_col)
        val e = bodyView.text
        val pos = bodyView.selectionStart.coerceIn(0, e.length)
        val lead = if (pos > 0 && e[pos - 1] != '\n') "\n" else ""
        e.insert(pos, "$lead| $c 1 | $c 2 |\n| --- | --- |\n|  |  |\n")
    }

    private fun rule() {
        val e = bodyView.text
        val pos = bodyView.selectionStart.coerceIn(0, e.length)
        val lead = if (pos > 0 && e[pos - 1] != '\n') "\n" else ""
        e.insert(pos, "$lead---\n")
    }

    companion object {
        const val EXTRA_ID = "note_id"
        private const val KEY_ID = "note_id"
        private const val ID_TITLE = 101
        private const val ID_BODY = 102
        private const val M_SHARE = 1
        private const val M_TOC = 2
        private const val M_DELETE = 3
        private val WORD = Regex("\\S+")
        private val TOC_HEADING = Regex("^(#{1,6})\\s+(.*)")
        private val TASK_BOX = Regex("^(\\s*[-*+]\\s\\[)([ xX])(]\\s)")
        private val TASK_PREFIXES = listOf("- [ ] ", "- [x] ", "- [X] ")
    }
}
