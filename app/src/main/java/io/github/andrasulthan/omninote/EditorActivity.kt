package io.github.andrasulthan.omninote

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
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
import java.io.File

/** Writes, reads and edits one note. Saves automatically when the screen is left. */
class EditorActivity : Activity() {

    private lateinit var store: NoteStore
    private lateinit var p: Ui.Palette
    private lateinit var titleView: EditText
    private lateinit var metaView: TextView
    private lateinit var bodyView: EditText
    private lateinit var readScroll: ScrollView
    private lateinit var readView: TextView
    private lateinit var toolArea: LinearLayout
    private lateinit var modeButton: TextView
    private lateinit var countView: TextView
    private var noteId: String? = null
    private var meta = NoteMeta()
    private var folder = ""
    private var savedText = ""
    private var deleted = false
    private var reading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        p = Ui.palette(this)
        noteId = savedInstanceState?.getString(KEY_ID) ?: intent.getStringExtra(EXTRA_ID)
        folder = intent.getStringExtra(EXTRA_FOLDER) ?: ""

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
            setPadding(0, pad / 2, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setSingleLine(true)
        }

        metaView = Ui.text(this, 11f, p.muted).apply {
            letterSpacing = 0.05f
            setPadding(0, small / 2, 0, small)
            visibility = View.GONE
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
        tool("[[", R.string.tool_note_link) { pickNoteLink() }
        tool("IMG", R.string.tool_image) { pickImage() }
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
        root.addView(metaView)
        root.addView(line, LinearLayout.LayoutParams(match, Ui.dp(this, 1f)))
        root.addView(bodyView, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(readScroll, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(countView)
        root.addView(toolArea)
        Ui.applyInsets(root)
        setContentView(root)

        val existing = noteId
        if (existing != null) {
            try {
                val (m, content) = NoteMeta.parse(store.read(existing))
                val (title, body) = NoteStore.split(content)
                meta = m
                folder = store.folderOf(existing)
                titleView.setText(title)
                bodyView.setText(body)
                savedText = buildText()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
            }
        } else {
            meta = NoteMeta(created = System.currentTimeMillis(), color = store.defaultColor)
            when {
                intent.action == Intent.ACTION_SEND -> {
                    titleView.setText(intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "")
                    bodyView.setText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: "")
                }
                intent.getStringExtra(EXTRA_TITLE) != null -> {
                    titleView.setText(intent.getStringExtra(EXTRA_TITLE))
                    bodyView.requestFocus()
                }
                else -> titleView.requestFocus()
            }
        }
        updateCount()
        updateMetaLine()
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

    private fun buildText(): String =
        NoteMeta.build(meta, NoteStore.join(titleView.text.toString(), bodyView.text.toString()))

    private fun save() {
        val title = titleView.text.toString()
        val body = bodyView.text.toString()
        val text = buildText()
        if (text == savedText) return
        if (noteId == null && title.isBlank() && body.isBlank()) return
        try {
            noteId = store.save(noteId, title, text, folder)
            savedText = text
        } catch (e: Exception) {
            keepDraft(text)
            Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
        }
    }

    /** Keeps a safety copy on this phone when saving to the folder fails. */
    private fun keepDraft(text: String) {
        try {
            val dir = File(filesDir, "drafts").apply { mkdirs() }
            File(dir, "draft-" + System.currentTimeMillis() + ".md").writeText(text)
        } catch (e: Exception) {
            // Nothing more we can do here.
        }
    }

    private fun updateCount() {
        val body = bodyView.text.toString()
        val words = WORD.findAll(body).count()
        countView.text = getString(R.string.word_count, words, body.length)
    }

    /** Small line under the title: colour, notebook, tags and pinned or archived state. */
    private fun updateMetaLine() {
        val out = SpannableStringBuilder()
        val colour = NoteMeta.colorValue(meta.color)
        if (colour != null) {
            out.append("● ")
            out.setSpan(ForegroundColorSpan(colour), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val parts = ArrayList<String>()
        if (meta.pinned) parts.add(getString(R.string.pinned).uppercase())
        if (meta.archived) parts.add(getString(R.string.filter_archive).uppercase())
        if (folder.isNotBlank() && folder != NoteStore.TRASH) parts.add(folder)
        if (folder == NoteStore.TRASH) parts.add(getString(R.string.filter_trash).uppercase())
        if (meta.tags.isNotEmpty()) parts.add(meta.tags.joinToString(" ") { "#$it" })
        out.append(parts.joinToString("  ·  "))
        metaView.text = out
        metaView.visibility = if (out.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun changeMeta(change: (NoteMeta) -> NoteMeta) {
        meta = change(meta)
        updateMetaLine()
        save()
    }

    // ---------- Read / edit modes ----------

    private fun showRead() {
        reading = true
        readView.text = Markdown.render(
            bodyView.text.toString(),
            p,
            resources.displayMetrics.density,
            { line -> toggleTask(line) },
            { url -> openLink(url) },
            { title -> openNoteByTitle(title) },
            { path -> loadImage(path) },
            getString(R.string.image_missing)
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

    /** Opens the note with this title, or starts a new one when it does not exist yet. */
    private fun openNoteByTitle(title: String) {
        save()
        val found = try {
            store.findByTitle(title)
        } catch (e: Exception) {
            null
        }
        val next = Intent(this, EditorActivity::class.java)
        if (found != null) {
            next.putExtra(EXTRA_ID, found.id)
        } else {
            next.putExtra(EXTRA_TITLE, title)
            next.putExtra(EXTRA_FOLDER, folder)
        }
        startActivity(next)
    }

    private fun loadImage(path: String): Drawable? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            store.openAttachment(path)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            val maxWidth = resources.displayMetrics.widthPixels - Ui.dp(this, 40f)
            var sample = 1
            while (bounds.outWidth / sample > maxWidth * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = store.openAttachment(path)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            val width = minOf(maxWidth, bitmap.width)
            val height = (bitmap.height.toFloat() * width / bitmap.width).toInt()
            BitmapDrawable(resources, bitmap).apply { setBounds(0, 0, width, height) }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Images and note links ----------

    private fun pickImage() {
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(pick, REQ_IMAGE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_image, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val path = try {
                store.saveAttachment(uri)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (path == null) {
                    Toast.makeText(this, R.string.error_image, Toast.LENGTH_LONG).show()
                } else {
                    insertBlock("![]($path)")
                }
            }
        }.start()
    }

    private fun pickNoteLink() {
        val current = titleView.text.toString().trim()
        val titles = try {
            store.list().map { it.title }.filter { !it.equals(current, ignoreCase = true) }
        } catch (e: Exception) {
            emptyList()
        }
        if (titles.isEmpty()) {
            Toast.makeText(this, R.string.no_other_notes, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pick_note)
            .setItems(titles.toTypedArray()) { _, which -> insertAtCursor("[[${titles[which]}]]") }
            .show()
    }

    // ---------- More menu ----------

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, M_PIN, 0, if (meta.pinned) R.string.unpin else R.string.pin)
        popup.menu.add(0, M_COLOR, 1, R.string.color)
        popup.menu.add(0, M_TAGS, 2, R.string.tags)
        popup.menu.add(0, M_MOVE, 3, R.string.move)
        popup.menu.add(0, M_ARCHIVE, 4, if (meta.archived) R.string.unarchive else R.string.archive)
        popup.menu.add(0, M_SHARE, 5, R.string.share)
        popup.menu.add(0, M_TOC, 6, R.string.toc)
        popup.menu.add(0, M_DELETE, 7, R.string.delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_PIN -> changeMeta { it.copy(pinned = !it.pinned) }
                M_COLOR -> chooseColor()
                M_TAGS -> editTags()
                M_MOVE -> chooseNotebook()
                M_ARCHIVE -> changeMeta { it.copy(archived = !it.archived) }
                M_SHARE -> share()
                M_TOC -> showToc()
                M_DELETE -> confirmDelete()
            }
            true
        }
        popup.show()
    }

    private fun chooseColor() {
        val names = listOf(
            R.string.color_none, R.string.color_red, R.string.color_orange, R.string.color_yellow,
            R.string.color_green, R.string.color_blue, R.string.color_purple, R.string.color_gray
        ).map { getString(it) }
        val values = listOf<String?>(null) + NoteMeta.COLORS
        val checked = values.indexOf(meta.color).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.color)
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                changeMeta { it.copy(color = values[which]) }
                dialog.dismiss()
            }
            .show()
    }

    private fun editTags() {
        val input = EditText(this).apply {
            hint = getString(R.string.tags_hint)
            setText(meta.tags.joinToString(", "))
            setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val box = LinearLayout(this).apply {
            val pad = Ui.dp(this@EditorActivity, 20f)
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tags)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                changeMeta { it.copy(tags = NoteMeta.splitList(input.text.toString()).distinct()) }
            }
            .show()
    }

    private fun chooseNotebook() {
        save()
        val current = noteId
        if (current == null) {
            Toast.makeText(this, R.string.error_move, Toast.LENGTH_SHORT).show()
            return
        }
        val books = try {
            store.notebooks()
        } catch (e: Exception) {
            emptyList()
        }
        val labels = listOf(getString(R.string.top_level)) + books + getString(R.string.menu_new_notebook)
        AlertDialog.Builder(this)
            .setTitle(R.string.move)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> moveTo("")
                    labels.size - 1 -> askNewNotebook()
                    else -> moveTo(books[which - 1])
                }
            }
            .show()
    }

    private fun askNewNotebook() {
        val input = EditText(this).apply {
            hint = getString(R.string.notebook_hint)
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            val pad = Ui.dp(this@EditorActivity, 20f)
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_new_notebook)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val path = NoteStore.cleanPath(input.text.toString())
                if (path.isNotBlank()) moveTo(path)
            }
            .show()
    }

    private fun moveTo(target: String) {
        val current = noteId ?: return
        try {
            noteId = store.move(current, target)
            folder = NoteStore.cleanPath(target)
            updateMetaLine()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_move, Toast.LENGTH_LONG).show()
        }
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
                save()
                deleted = true
                val existing = noteId
                if (existing != null) {
                    try {
                        if (folder == NoteStore.TRASH) {
                            store.deleteForever(existing)
                        } else {
                            store.delete(existing)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
                    }
                }
                finish()
            }
            .show()
    }

    // ---------- Toolbar actions ----------

    private fun insertAtCursor(text: String) {
        val e = bodyView.text
        val pos = bodyView.selectionStart.coerceIn(0, e.length)
        e.insert(pos, text)
        bodyView.setSelection((pos + text.length).coerceAtMost(e.length))
    }

    /** Inserts text on its own line. */
    private fun insertBlock(text: String) {
        val e = bodyView.text
        val pos = bodyView.selectionStart.coerceIn(0, e.length)
        val lead = if (pos > 0 && e[pos - 1] != '\n') "\n" else ""
        insertAtCursor("$lead$text\n")
    }

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
        insertBlock("| $c 1 | $c 2 |\n| --- | --- |\n|  |  |")
    }

    private fun rule() {
        insertBlock("---")
    }

    companion object {
        const val EXTRA_ID = "note_id"
        const val EXTRA_TITLE = "note_title"
        const val EXTRA_FOLDER = "note_folder"
        private const val KEY_ID = "note_id"
        private const val ID_TITLE = 101
        private const val ID_BODY = 102
        private const val REQ_IMAGE = 7
        private const val M_PIN = 1
        private const val M_COLOR = 2
        private const val M_TAGS = 3
        private const val M_MOVE = 4
        private const val M_ARCHIVE = 5
        private const val M_SHARE = 6
        private const val M_TOC = 7
        private const val M_DELETE = 8
        private val WORD = Regex("\\S+")
        private val TOC_HEADING = Regex("^(#{1,6})\\s+(.*)")
        private val TASK_BOX = Regex("^(\\s*[-*+]\\s\\[)([ xX])(]\\s)")
        private val TASK_PREFIXES = listOf("- [ ] ", "- [x] ", "- [X] ")
    }
}
