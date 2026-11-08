package io.github.andrasulthan.omninote

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
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
import java.util.Calendar

/** Writes, reads and edits one note. Saves automatically when the screen is left. */
class EditorActivity : Activity() {

    private class Snap(val text: String, val cursor: Int)

    private lateinit var store: NoteStore
    private lateinit var p: Ui.Palette
    private lateinit var titleView: EditText
    private lateinit var metaView: TextView
    private lateinit var conflictBar: LinearLayout
    private lateinit var bodyView: EditText
    private lateinit var readScroll: ScrollView
    private lateinit var readView: TextView
    private lateinit var toolArea: LinearLayout
    private lateinit var modeButton: TextView
    private lateinit var undoButton: TextView
    private lateinit var redoButton: TextView
    private lateinit var countView: TextView
    private var noteId: String? = null
    private var meta = NoteMeta()
    private var folder = ""
    private var savedText = ""
    private var deleted = false
    private var reading = false
    private var locked = false
    private var editingDrawing = false

    // Undo and redo for the note text. Typing is grouped into bursts.
    private val undoStack = ArrayList<Snap>()
    private val redoStack = ArrayList<Snap>()
    private var restoring = false
    private var typingBurst = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val endBurst = Runnable { typingBurst = false }

    // Images shown inside the editor, loaded once per path.
    private val imageCache = HashMap<String, Drawable?>()
    private val loadingImages = HashSet<String>()
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var loadingNote = false
    private var resolving = false
    /** A result from another screen (drawing, export) that waits until the note is on screen. */
    private var pendingResult: (() -> Unit)? = null
    private var resumedOnce = false
    private var revealed = false
    /** True once the cursor was placed in the note text (by a tap or by this screen). */
    private var bodyEverFocused = false
    /** Position of a newline just typed, so a list can be continued on the next line. */
    private var listNewline = -1
    /** Read mode and cursor of the previous screen (rotation), applied once the note is shown. */
    private var restoreReading = false
    private var restoreCursor = -1
    private val restyle = Runnable {
        if (::bodyView.isInitialized) {
            Markdown.styleEditable(bodyView.text, p)
            showInlineImages(bodyView.text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        p = Ui.palette(this)
        noteId = savedInstanceState?.getString(KEY_ID) ?: intent.getStringExtra(EXTRA_ID)
        editingDrawing = savedInstanceState?.getBoolean(KEY_EDITING_DRAWING) ?: false
        restoreReading = savedInstanceState?.getBoolean(KEY_READING) ?: false
        restoreCursor = savedInstanceState?.getInt(KEY_CURSOR, -1) ?: -1
        // Other apps can start this screen; only open files that are really notes of this app.
        val asked = noteId
        if (asked != null && !store.owns(asked)) {
            Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
            noteId = null
            deleted = true
            finish()
            return
        }
        // New notes never go into the trash or a hidden settings folder (links from a trashed note, other apps).
        folder = NoteStore.cleanPath(intent.getStringExtra(EXTRA_FOLDER) ?: "")
            .takeIf { path -> path.split("/").none { it.startsWith(".") } } ?: ""

        val scale = store.fontScale()
        val pad = Ui.dp(this, 20f)
        val small = Ui.dp(this, 10f)
        val match = LinearLayout.LayoutParams.MATCH_PARENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            setPadding(pad, Ui.dp(context, 8f), pad, 0)
        }

        // Top bar: back, undo, redo, read/edit switch, more menu
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
        undoButton = Ui.text(this, 20f, p.text).apply {
            text = "↶"
            contentDescription = getString(R.string.draw_undo)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setOnClickListener { undo() }
        }
        redoButton = Ui.text(this, 20f, p.text).apply {
            text = "↷"
            contentDescription = getString(R.string.draw_redo)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setOnClickListener { redo() }
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
        bar.addView(undoButton)
        bar.addView(redoButton)
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
            // A pasted or shared title with line breaks would split into title and text when saved.
            filters = arrayOf<InputFilter>(InputFilter { source, start, end, _, _, _ ->
                val piece = source.subSequence(start, end).toString()
                if (piece.indexOf('\n') < 0 && piece.indexOf('\r') < 0) {
                    null
                } else {
                    piece.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
                }
            })
            isSaveEnabled = false
        }

        metaView = Ui.text(this, 11f, p.muted).apply {
            letterSpacing = 0.05f
            setPadding(0, small / 2, 0, small)
            visibility = View.GONE
        }

        conflictBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(small, small, small, small / 2)
            background = Ui.rounded(p.surface, Ui.RED, Ui.dp(context, 12f).toFloat(), Ui.dp(context, 1f))
        }

        val line = View(this).apply { setBackgroundColor(p.border) }

        countView = Ui.text(this, 11f, p.muted).apply {
            gravity = Gravity.END
            setPadding(0, small / 2, 0, small / 2)
        }

        bodyView = EditText(this).apply {
            id = ID_BODY
            isSaveEnabled = false
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
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) bodyEverFocused = true }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    if (!restoring && !typingBurst) {
                        pushUndo(Snap(s?.toString() ?: "", selectionStart.coerceAtLeast(0)))
                        typingBurst = true
                    }
                }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // One new line typed (Enter, or a keyboard committing "word" plus Enter).
                    val last = start + count - 1
                    listNewline = if (s != null && !restoring && !loadingNote && count == before + 1 &&
                        last in 0 until s.length && s[last] == '\n'
                    ) last else -1
                }
                override fun afterTextChanged(s: Editable?) {
                    val newline = listNewline
                    listNewline = -1
                    // The list change runs this listener again, which styles the text.
                    if (s != null && newline >= 0 && continueList(s, newline)) return
                    if (s != null) {
                        uiHandler.removeCallbacks(restyle)
                        if (s.length < STYLE_NOW_LIMIT) {
                            Markdown.styleEditable(s, p)
                            showInlineImages(s)
                        } else {
                            uiHandler.postDelayed(restyle, STYLE_DELAY_MS)
                        }
                        updateCount()
                    }
                    if (!restoring) {
                        uiHandler.removeCallbacks(endBurst)
                        uiHandler.postDelayed(endBurst, BURST_MS)
                    }
                    updateUndoButtons()
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
                setOnClickListener {
                    if (!loadingNote && bodyView.isEnabled) {
                        // Work on the note text even when the title has the cursor, as its own undo step.
                        focusBody()
                        endTypingBurst()
                        action()
                        endTypingBurst()
                    }
                }
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
        tool("✎", R.string.tool_draw) { openDraw(null) }
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
        root.addView(conflictBar, LinearLayout.LayoutParams(match, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = small
        })
        root.addView(line, LinearLayout.LayoutParams(match, Ui.dp(this, 1f)))
        root.addView(bodyView, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(readScroll, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(countView)
        root.addView(toolArea)
        Ui.applyInsets(root)
        setContentView(root)

        val existing = noteId
        if (existing != null) {
            loadExisting(existing)
        } else {
            meta = NoteMeta(created = System.currentTimeMillis(), color = store.defaultColor)
            restoring = true
            when {
                intent.action == Intent.ACTION_SEND -> {
                    titleView.setText(intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "")
                    val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    bodyView.setText(shared ?: "")
                    // File apps share a text or Markdown file instead of text.
                    if (shared == null) readSharedFile()
                }
                intent.getStringExtra(EXTRA_TITLE) != null -> {
                    titleView.setText(intent.getStringExtra(EXTRA_TITLE))
                    bodyView.requestFocus()
                }
                intent.hasExtra(Widgets.EXTRA_CHECKLIST) -> {
                    bodyView.setText("- [ ] ")
                    bodyView.requestFocus()
                    bodyView.setSelection(bodyView.text.length)
                }
                else -> titleView.requestFocus()
            }
            restoring = false
            clearUndo()
            updateCount()
            updateMetaLine()
            applyRestoredState()
        }
        updateUndoButtons()
    }

    /** Reads a shared text file into this new note, off the main thread. */
    private fun readSharedFile() {
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        inBackground({
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_SHARED_BYTES) return@use null
                    }
                    out.toString("UTF-8")
                }
            } catch (e: Exception) {
                null
            }
        }) { text ->
            if (text == null) {
                Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
                return@inBackground
            }
            // Only fill a note nothing was typed into yet.
            if (noteId != null || bodyView.text.isNotEmpty()) return@inBackground
            val (m, content) = NoteMeta.parse(text)
            restoring = true
            if (m.vault) {
                bodyView.setText(text)
            } else {
                val (t, b) = NoteStore.split(content)
                meta = m.copy(
                    created = if (m.created > 0) m.created else meta.created,
                    color = m.color ?: meta.color,
                    trashedFrom = null,
                    remind = 0L
                )
                if (titleView.text.isBlank()) titleView.setText(t)
                bodyView.setText(b)
            }
            restoring = false
            clearUndo()
            updateCount()
            updateMetaLine()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        val id = noteId ?: return
        if (deleted || loadingNote) return
        if (locked) {
            // The vault closed while the note was open: hide the text and ask for the password again.
            if (revealed && !Vault.isOpen()) {
                revealed = false
                restoring = true
                titleView.setText("")
                bodyView.setText("")
                restoring = false
                clearUndo()
                savedText = buildText()
                if (reading) showRead()
                loadExisting(id)
            }
            return
        }
        // The note changed on disk (sync, widget, another window) while nothing was edited here.
        if (loadingNote || buildText() != savedText) return
        val known = savedText
        inBackground({
            try {
                store.read(id)
            } catch (e: Exception) {
                null
            }
        }) { raw ->
            val normal = raw?.let {
                val (m, c) = NoteMeta.parse(it)
                val (t, b) = NoteStore.split(c)
                NoteMeta.build(m, NoteStore.join(t, b))
            }
            if (raw != null && normal != known && noteId == id && !locked && buildText() == known && !Vault.isLocked(raw)) {
                showPlain(raw)
                if (reading) showRead()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!deleted) save()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(endBurst)
        uiHandler.removeCallbacks(restyle)
        io.shutdown()
    }

    /** Reads or searches the notes folder off the main thread, then continues on screen. */
    private fun <T> inBackground(work: () -> T, then: (T) -> Unit) {
        if (io.isShutdown) return
        io.execute {
            val result = work()
            runOnUiThread { if (!isDestroyed && !isFinishing) then(result) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ID, noteId)
        outState.putBoolean(KEY_EDITING_DRAWING, editingDrawing)
        if (::bodyView.isInitialized && !loadingNote && (!locked || revealed)) {
            outState.putBoolean(KEY_READING, reading)
            if (bodyEverFocused) outState.putInt(KEY_CURSOR, bodyView.selectionStart)
        } else {
            // The note is not on screen yet: keep what the previous screen asked for.
            outState.putBoolean(KEY_READING, restoreReading)
            outState.putInt(KEY_CURSOR, restoreCursor)
        }
    }

    /** Puts back read mode and the cursor after the screen was rebuilt (rotation, dark mode). */
    private fun applyRestoredState() {
        val cursor = restoreCursor
        restoreCursor = -1
        if (cursor >= 0) {
            bodyView.setSelection(cursor.coerceIn(0, bodyView.text.length))
            bodyEverFocused = true
        }
        if (restoreReading) {
            restoreReading = false
            showRead()
        }
    }

    // ---------- Images inside the editor ----------

    /** Shows each image link as the picture itself; the Markdown stays in the file. */
    private fun showInlineImages(s: Editable) {
        for (span in s.getSpans(0, s.length, ImageSpan::class.java)) s.removeSpan(span)
        for (m in IMAGE_LINK.findAll(s)) {
            val path = m.groupValues[1]
            val picture = cachedImage(path) ?: continue
            s.setSpan(
                ImageSpan(picture, ImageSpan.ALIGN_BOTTOM),
                m.range.first,
                m.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** Returns a decoded picture, or starts decoding it off the main thread and returns null for now. */
    private fun cachedImage(path: String): Drawable? {
        if (imageCache.containsKey(path)) return imageCache[path]
        if (!loadingImages.add(path)) return null
        Thread {
            val picture = loadImage(path)
            runOnUiThread {
                loadingImages.remove(path)
                imageCache[path] = picture
                if (picture != null && !isDestroyed) {
                    refreshInlineImages()
                    if (reading) showRead()
                }
            }
        }.start()
        return null
    }

    private fun refreshInlineImages() {
        showInlineImages(bodyView.text)
        bodyView.requestLayout()
    }

    // ---------- Undo and redo ----------

    private fun pushUndo(snap: Snap) {
        undoStack.add(snap)
        if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun currentSnap(): Snap =
        Snap(bodyView.text.toString(), bodyView.selectionStart.coerceAtLeast(0))

    private fun undo() {
        if (loadingNote || undoStack.isEmpty()) return
        uiHandler.removeCallbacks(endBurst)
        typingBurst = false
        redoStack.add(currentSnap())
        restoreSnap(undoStack.removeAt(undoStack.size - 1))
    }

    private fun redo() {
        if (loadingNote || redoStack.isEmpty()) return
        uiHandler.removeCallbacks(endBurst)
        typingBurst = false
        undoStack.add(currentSnap())
        restoreSnap(redoStack.removeAt(redoStack.size - 1))
    }

    private fun restoreSnap(snap: Snap) {
        restoring = true
        bodyView.setText(snap.text)
        bodyView.setSelection(snap.cursor.coerceIn(0, bodyView.text.length))
        restoring = false
        if (reading) showRead()
        updateUndoButtons()
    }

    private fun clearUndo() {
        undoStack.clear()
        redoStack.clear()
        typingBurst = false
        updateUndoButtons()
    }

    private fun updateUndoButtons() {
        if (!::undoButton.isInitialized) return
        undoButton.alpha = if (undoStack.isEmpty()) 0.3f else 1f
        redoButton.alpha = if (redoStack.isEmpty()) 0.3f else 1f
    }

    // ---------- Loading ----------

    private fun loadExisting(id: String) {
        loadingNote = true
        setEditable(false)
        inBackground({
            try {
                Triple(store.read(id), store.folderOf(id), NoteStore.isConflictName(store.fileNameOf(id)))
            } catch (e: Exception) {
                null
            }
        }) { found ->
            loadingNote = false
            showLoaded(found)
        }
    }

    private fun showLoaded(found: Triple<String, String, Boolean>?) {
        val raw = found?.first
        if (raw == null) {
            Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
            deleted = true
            finish()
            return
        }
        folder = found.second
        if (found.third) showConflictBar()
        if (!Vault.isLocked(raw)) {
            showPlain(raw)
            setEditable(true)
            applyRestoredState()
            runPendingResult()
            return
        }
        locked = true
        setEditable(false)
        val reveal = {
            try {
                showPlain(Vault.unseal(raw))
                revealed = true
                setEditable(true)
                applyRestoredState()
                runPendingResult()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
                finish()
            }
        }
        when {
            Vault.isOpen() -> reveal()
            Vault.isSetUp(store) -> VaultUi.open(this, store, onCancel = { finish() }) { reveal() }
            else -> {
                Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun showPlain(text: String) {
        val (m, content) = NoteMeta.parse(text)
        val (title, body) = NoteStore.split(content)
        meta = m
        restoring = true
        titleView.setText(title)
        bodyView.setText(body)
        restoring = false
        clearUndo()
        savedText = buildText()
        updateCount()
        updateMetaLine()
    }

    private fun setEditable(on: Boolean) {
        titleView.isEnabled = on
        bodyView.isEnabled = on
        toolArea.visibility = if (on && !reading) View.VISIBLE else View.GONE
    }

    // ---------- Saving ----------

    private fun buildText(): String =
        NoteMeta.build(meta, NoteStore.join(titleView.text.toString(), bodyView.text.toString()))

    private fun historyKey(id: String): String = History.keyFor(id, meta)

    /** Turns note text into what is written to the file (encrypted for locked notes). */
    private fun fileTextFor(plain: String): String = if (locked) Vault.seal(plain) else plain

    private fun save() {
        if (loadingNote) return
        val title = titleView.text.toString()
        val body = bodyView.text.toString()
        var plain = buildText()
        if (plain == savedText) return
        if (meta.remind in 1..System.currentTimeMillis()) {
            // The reminder already rang; do not write it back into the note.
            meta = meta.copy(remind = 0L)
            plain = buildText()
        }
        if (noteId == null && title.isBlank() && body.isBlank()) return
        if (noteId == null && title.isBlank() && body.trim() == "- [ ]") return
        val isNew = noteId == null
        if (locked && !Vault.isOpen()) {
            Toast.makeText(this, R.string.vault_closed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val existing = noteId
            if (existing != null) {
                val previous = try {
                    store.read(existing)
                } catch (e: Exception) {
                    ""
                }
                History.record(this, historyKey(existing), previous)
            }
            val id = store.save(noteId, title, fileTextFor(plain), folder)
            noteId = id
            savedText = plain
            if (!locked && Reminders.isPinned(this, id)) {
                Reminders.pinNotification(this, id, title.ifBlank { getString(R.string.app_name) }, previewText())
            }
            Widgets.refreshNotes(this)
            if (isNew && !locked && title.isNotBlank()) warnIfTitleTaken(id, title)
        } catch (e: Exception) {
            if (!locked) keepDraft(plain)
            Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
        }
    }

    /** Tells, without blocking typing, when another note already has this title. */
    private fun warnIfTitleTaken(id: String, title: String) {
        inBackground({
            try {
                store.list().any {
                    it.id != id && !it.meta.vault && !it.isConflict && it.title.equals(title.trim(), ignoreCase = true)
                }
            } catch (e: Exception) {
                false
            }
        }) { clash ->
            if (clash) Toast.makeText(this, R.string.duplicate_title, Toast.LENGTH_SHORT).show()
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

    private fun previewText(): String =
        Markdown.plain(bodyView.text.toString().trim().take(300))

    private fun updateCount() {
        val body = bodyView.text.toString()
        // A word has at least one letter or digit, so list marks like "-" and "[ ]" are not counted.
        val words = WORD.findAll(body).count { m -> m.value.any { it.isLetterOrDigit() } }
        countView.text = getString(R.string.word_count, words, body.length)
    }

    /** Small line under the title: lock, colour, reminder, notebook, tags and state. */
    private fun updateMetaLine() {
        val out = SpannableStringBuilder()
        val colour = NoteMeta.colorValue(meta.color)
        if (colour != null) {
            out.append("● ")
            out.setSpan(ForegroundColorSpan(colour), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val parts = ArrayList<String>()
        if (locked) parts.add("🔒 " + getString(R.string.locked_note).uppercase())
        if (meta.remind > System.currentTimeMillis()) parts.add("⏰ " + formatTime(meta.remind))
        if (meta.pinned) parts.add(getString(R.string.pinned).uppercase())
        if (meta.archived) parts.add(getString(R.string.filter_archive).uppercase())
        if (folder.isNotBlank() && folder != NoteStore.TRASH) parts.add(folder)
        if (folder == NoteStore.TRASH) parts.add(getString(R.string.filter_trash).uppercase())
        if (meta.tags.isNotEmpty()) parts.add(meta.tags.joinToString(" ") { "#$it" })
        out.append(parts.joinToString("  ·  "))
        metaView.text = out
        metaView.visibility = if (out.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun formatTime(millis: Long): String =
        DateUtils.formatDateTime(
            this, millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        )

    private fun changeMeta(change: (NoteMeta) -> NoteMeta) {
        meta = change(meta)
        updateMetaLine()
        save()
    }

    // ---------- Drawings ----------

    /** Drawings used in this note, like "attachments/drawing-20261002-191500.png". */
    private fun drawingsInNote(): List<String> =
        IMAGE_LINK.findAll(bodyView.text.toString())
            .map { it.groupValues[1] }
            .filter { DrawActivity.isDrawing(it) }
            .distinct()
            .toList()

    private fun openDraw(path: String?) {
        save()
        editingDrawing = path != null
        val intent = Intent(this, DrawActivity::class.java)
        if (path != null) intent.putExtra(DrawActivity.EXTRA_PATH, path)
        startActivityForResult(intent, REQ_DRAW)
    }

    private fun editDrawing() {
        val drawings = drawingsInNote()
        when {
            drawings.isEmpty() -> return
            drawings.size == 1 -> openDraw(drawings[0])
            else -> {
                val labels = drawings.map { it.substringAfterLast('/') }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(R.string.draw_pick)
                    .setItems(labels) { _, which -> openDraw(drawings[which]) }
                    .show()
            }
        }
    }

    // ---------- Export ----------

    private fun exportNote() {
        save()
        val options = arrayOf(
            getString(R.string.export_md),
            getString(R.string.export_html),
            getString(R.string.export_pdf)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.export_note)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> createExportFile("text/markdown", ".md", REQ_EXPORT_MD)
                    1 -> createExportFile("text/html", ".html", REQ_EXPORT_HTML)
                    else -> {
                        val title = titleView.text.toString().ifBlank { getString(R.string.app_name) }
                        val text = buildText()
                        // Embedding the pictures can take a while; never freeze the screen for it.
                        inBackground({
                            try {
                                HtmlExport.page(store, text)
                            } catch (e: Exception) {
                                null
                            } catch (e: OutOfMemoryError) {
                                null
                            }
                        }) { page ->
                            if (page == null) {
                                Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
                            } else {
                                HtmlExport.print(this, page, "OmniNote - $title")
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun createExportFile(mime: String, extension: String, requestCode: Int) {
        val name = NoteStore.fileNameFor(titleView.text.toString()).removeSuffix(".md") + extension
        val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try {
            startActivityForResult(create, requestCode)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Writes an export off the main thread; [make] builds the file text there too. */
    private fun writeExport(uri: Uri, make: () -> String) {
        inBackground({
            try {
                val bytes = make().toByteArray(Charsets.UTF_8)
                // Some folder apps (cloud drives) do not know the "truncate" mode; the new file is empty anyway.
                val out = try {
                    contentResolver.openOutputStream(uri, "wt")
                } catch (e: Exception) {
                    contentResolver.openOutputStream(uri, "w")
                }
                if (out != null) {
                    out.use { it.write(bytes) }
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            } catch (e: OutOfMemoryError) {
                false
            }
        }) { ok ->
            Toast.makeText(this, if (ok) R.string.export_saved else R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Version history ----------

    private fun showHistory() {
        save()
        val id = noteId ?: return
        val versions = History.versions(this, historyKey(id))
        if (versions.isEmpty()) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_LONG).show()
            return
        }
        val labels = versions.map {
            DateUtils.formatDateTime(
                this, it.time,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
                    DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
            ) + "  ·  " + DateUtils.getRelativeTimeSpanString(it.time)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.history)
            .setItems(labels.toTypedArray()) { _, which -> previewVersion(versions[which], labels[which]) }
            .show()
    }

    private fun previewVersion(version: History.Version, label: String) {
        val id = noteId ?: return
        val plain = try {
            val raw = History.read(version)
            if (Vault.isLocked(raw)) Vault.unseal(raw) else raw
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_open, Toast.LENGTH_SHORT).show()
            return
        }
        val text = Ui.text(this, 13f, p.text).apply {
            this.text = NoteMeta.parse(plain).second.take(4000)
            setLineSpacing(0f, 1.2f)
            val pad = Ui.dp(context, 20f)
            setPadding(pad, pad / 2, pad, pad / 2)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle(label)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.history_restore) { _, _ ->
                save()
                // Keep the current text as a version too, so restoring can be undone from history.
                try {
                    History.record(this, historyKey(id), store.read(id), force = true)
                } catch (e: Exception) {
                    // Nothing to keep.
                }
                showPlain(plain)
                savedText = ""
                save()
                if (reading) showRead()
                Toast.makeText(this, R.string.history_restored, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------- Sync conflicts ----------

    private fun showConflictBar() {
        conflictBar.removeAllViews()
        conflictBar.addView(Ui.text(this, 12f, Ui.RED).apply {
            text = getString(R.string.conflict_banner)
            setLineSpacing(0f, 1.2f)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: Int, run: () -> Unit) {
            actions.addView(Ui.text(this, 11f, p.text, bold = true).apply {
                text = getString(label).uppercase()
                letterSpacing = 0.06f
                val h = Ui.dp(context, 8f)
                setPadding(0, h, h * 2, h)
                setOnClickListener { run() }
            })
        }
        action(R.string.conflict_keep_this) { keepThisVersion() }
        action(R.string.conflict_keep_original) { keepOriginal() }
        action(R.string.conflict_merge) { mergeWithOriginal() }
        action(R.string.conflict_open_original) { openOriginal() }
        conflictBar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        })
        conflictBar.visibility = View.VISIBLE
    }

    /** Finds the note this sync conflict belongs to, off the main thread. */
    private fun withOriginal(then: (Note) -> Unit) {
        val id = noteId ?: return
        // One conflict action at a time: a second tap while the first runs is ignored.
        if (resolving) return
        resolving = true
        inBackground({
            try {
                store.findOriginal(id)
            } catch (e: Exception) {
                null
            }
        }) { found ->
            if (found == null) {
                resolving = false
                Toast.makeText(this, R.string.conflict_no_original, Toast.LENGTH_LONG).show()
            } else {
                then(found)
                if (!isFinishing) resolving = false
            }
        }
    }

    private fun finishConflict(openId: String?) {
        val conflictId = noteId ?: return
        try {
            store.deleteForever(conflictId)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
            return
        }
        deleted = true
        Widgets.refreshNotes(this)
        Toast.makeText(this, R.string.conflict_resolved, Toast.LENGTH_SHORT).show()
        if (openId != null) {
            startActivity(Intent(this, EditorActivity::class.java).putExtra(EXTRA_ID, openId))
        }
        finish()
    }

    private fun keepThisVersion() = withOriginal { original ->
        try {
            History.record(this, History.keyFor(original.id, original.meta), store.read(original.id))
            store.save(original.id, "", fileTextFor(buildText()))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
            return@withOriginal
        }
        finishConflict(original.id)
    }

    private fun keepOriginal() = withOriginal { original ->
        finishConflict(original.id)
    }

    private fun mergeWithOriginal() = withOriginal { original ->
        try {
            val raw = store.read(original.id)
            val originalPlain = if (Vault.isLocked(raw)) Vault.unseal(raw) else raw
            val (originalMeta, originalContent) = NoteMeta.parse(originalPlain)
            val extra = "\n\n---\n\n## " + getString(R.string.conflict_merged_heading) + "\n\n" +
                bodyView.text.toString().trim() + "\n"
            val merged = NoteMeta.build(
                originalMeta.copy(tags = (originalMeta.tags + meta.tags).distinctBy { it.lowercase() }),
                originalContent.trimEnd() + extra
            )
            History.record(this, History.keyFor(original.id, originalMeta), raw)
            store.save(original.id, "", fileTextFor(merged))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
            return@withOriginal
        }
        finishConflict(original.id)
    }

    private fun openOriginal() = withOriginal { original ->
        save()
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EXTRA_ID, original.id))
    }

    // ---------- Vault ----------

    private fun lockNote() {
        VaultUi.ensureOpen(this, store) {
            save()
            val id = noteId
            if (id == null) {
                Toast.makeText(this, R.string.error_save, Toast.LENGTH_SHORT).show()
                return@ensureOpen
            }
            val plainKey = historyKey(id)
            locked = true
            revealed = true
            savedText = ""
            save()
            val renamed = store.rename(id, Vault.lockedTitle())
            followNewId(id, renamed)
            noteId = renamed
            // A waiting reminder must not show the title of a locked note when it rings.
            if (renamed == id && meta.remind > System.currentTimeMillis()) {
                Reminders.schedule(this, id, reminderTitle(), meta.remind)
            }
            Reminders.unpinNotification(this, renamed)
            // Older versions were kept as plain text; remove them so nothing readable stays behind.
            History.forget(this, plainKey)
            Widgets.refreshNotes(this)
            updateMetaLine()
        }
    }

    private fun removeLock() {
        VaultUi.ensureOpen(this, store) {
            locked = false
            revealed = false
            savedText = ""
            save()
            val id = noteId ?: return@ensureOpen
            val renamed = try {
                store.rename(id, titleView.text.toString().ifBlank { "Note" })
            } catch (e: Exception) {
                id
            }
            followNewId(id, renamed)
            noteId = renamed
            if (renamed == id && meta.remind > System.currentTimeMillis()) {
                Reminders.schedule(this, id, reminderTitle(), meta.remind)
            }
            Widgets.refreshNotes(this)
            updateMetaLine()
        }
    }

    // ---------- Reminders and notifications ----------

    private fun askNotificationPermission() {
        if (!Reminders.canNotify(this) && Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            Toast.makeText(this, R.string.notify_permission, Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseReminder() {
        askNotificationPermission()
        val now = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val at = Calendar.getInstance().apply {
                    set(year, month, day, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                setReminder(at)
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), DateFormat.is24HourFormat(this)).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun setReminder(at: Long) {
        if (at <= System.currentTimeMillis()) {
            Toast.makeText(this, R.string.remind_past, Toast.LENGTH_SHORT).show()
            return
        }
        changeMeta { it.copy(remind = at) }
        if (noteId == null) save()
        val id = noteId
        if (id == null) {
            // An empty note is not saved, so it cannot ring yet.
            meta = meta.copy(remind = 0L)
            updateMetaLine()
            Toast.makeText(this, R.string.error_save, Toast.LENGTH_SHORT).show()
            return
        }
        Reminders.schedule(this, id, reminderTitle(), at)
        Toast.makeText(this, getString(R.string.remind_set, formatTime(at)), Toast.LENGTH_LONG).show()
    }

    private fun reminderTitle(): String =
        if (locked) getString(R.string.locked_note)
        else titleView.text.toString().ifBlank { getString(R.string.app_name) }

    private fun removeReminder() {
        changeMeta { it.copy(remind = 0L) }
        noteId?.let { Reminders.cancel(this, it) }
    }

    private fun toggleNotificationPin() {
        save()
        val id = noteId ?: return
        if (Reminders.isPinned(this, id)) {
            Reminders.unpinNotification(this, id)
        } else if (!locked) {
            askNotificationPermission()
            Reminders.pinNotification(this, id, titleView.text.toString().ifBlank { getString(R.string.app_name) }, previewText())
        }
    }

    /** Keeps reminders, notifications and widgets working after a note gets a new id. */
    private fun followNewId(oldId: String, newId: String) {
        if (oldId == newId) return
        Widgets.followNewId(this, oldId, newId)
        if (meta.remind > System.currentTimeMillis()) {
            Reminders.cancel(this, oldId)
            Reminders.schedule(this, newId, reminderTitle(), meta.remind)
        }
        if (Reminders.isPinned(this, oldId)) {
            Reminders.unpinNotification(this, oldId)
            if (!locked) {
                Reminders.pinNotification(this, newId, titleView.text.toString().ifBlank { getString(R.string.app_name) }, previewText())
            }
        }
    }

    private fun checkedToBottom() {
        val lines = bodyView.text.toString().split("\n")
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            if (TASK_BOX.containsMatchIn(lines[i])) {
                val block = ArrayList<String>()
                while (i < lines.size && TASK_BOX.containsMatchIn(lines[i])) {
                    block.add(lines[i])
                    i++
                }
                val (done, open) = block.partition { TASK_BOX.find(it)?.groupValues?.get(2) != " " }
                out.addAll(open)
                out.addAll(done)
            } else {
                out.add(lines[i])
                i++
            }
        }
        replaceBody(out.joinToString("\n"))
    }

    /** Replaces the whole note text as one undo step, keeping the cursor where it was. */
    private fun replaceBody(text: String) {
        if (text == bodyView.text.toString()) return
        val cursor = bodyView.selectionStart
        endTypingBurst()
        bodyView.setText(text)
        endTypingBurst()
        if (cursor >= 0) bodyView.setSelection(cursor.coerceAtMost(bodyView.text.length))
        if (reading) showRead()
    }

    // ---------- Read / edit modes ----------

    private fun showRead() {
        // Also called to refresh the read view; only hide the keyboard when switching to it.
        val switching = !reading
        reading = true
        readView.text = Markdown.render(
            bodyView.text.toString(),
            p,
            resources.displayMetrics.density,
            { line -> toggleTask(line) },
            { url -> openLink(url) },
            { title -> openNoteByTitle(title) },
            { path -> cachedImage(path) },
            getString(R.string.image_missing)
        )
        bodyView.visibility = View.GONE
        toolArea.visibility = View.GONE
        readScroll.visibility = View.VISIBLE
        modeButton.text = getString(R.string.mode_edit).uppercase()
        if (switching) hideKeyboard()
    }

    private fun showEdit() {
        reading = false
        readScroll.visibility = View.GONE
        bodyView.visibility = View.VISIBLE
        // No toolbar while the note is loading or still locked.
        toolArea.visibility = if (bodyView.isEnabled) View.VISIBLE else View.GONE
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
        replaceBody(lines.joinToString("\n"))
    }

    private fun openLink(url: String) {
        // A bare web address at the end of a sentence takes the full stop or comma with it.
        val trimmed = url.trim().trimEnd('.', ',', ';', ':', '!', '?', '\'', '"')
        if (trimmed.isEmpty()) return
        val full = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        if (full.startsWith("file:", ignoreCase = true) || full.startsWith("content:", ignoreCase = true)) {
            Toast.makeText(this, R.string.error_link, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_link, Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens the note with this title, or starts a new one when it does not exist yet. */
    private fun openNoteByTitle(link: String) {
        // Obsidian-style [[Title|shown text]] and [[Title#Heading]] point to the note "Title".
        val title = link.substringBefore('|').substringBefore('#').trim()
        if (title.isEmpty()) return
        save()
        inBackground({
            try {
                store.findByTitle(title)
            } catch (e: Exception) {
                null
            }
        }) { found ->
            // A link to this same note: a second editor on one file would overwrite the other's changes.
            if (found != null && found.id == noteId) return@inBackground
            val next = Intent(this, EditorActivity::class.java)
            if (found != null) {
                next.putExtra(EXTRA_ID, found.id)
            } else {
                next.putExtra(EXTRA_TITLE, title)
                next.putExtra(EXTRA_FOLDER, folder)
            }
            startActivity(next)
        }
    }

    private fun loadImage(path: String): Drawable? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            store.openAttachment(path)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val maxWidth = resources.displayMetrics.widthPixels - Ui.dp(this, 40f)
            val maxHeight = resources.displayMetrics.heightPixels * 2
            var sample = 1
            while (bounds.outWidth / sample > maxWidth * 2 || bounds.outHeight / sample > maxHeight * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = store.openAttachment(path)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            val width = minOf(maxWidth, bitmap.width)
            val height = (bitmap.height.toFloat() * width / bitmap.width).toInt()
            BitmapDrawable(resources, bitmap).apply { setBounds(0, 0, width, height) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
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
        if (resultCode != RESULT_OK) return
        val act: () -> Unit = when (requestCode) {
            REQ_DRAW -> {
                val drawing = data?.getStringExtra(DrawActivity.EXTRA_PATH) ?: return
                {
                    imageCache.remove(drawing)
                    if (editingDrawing) {
                        refreshInlineImages()
                        if (reading) showRead()
                    } else {
                        insertBlock("![]($drawing)")
                    }
                }
            }
            else -> {
                val uri = data?.data ?: return
                {
                    when (requestCode) {
                        REQ_IMAGE -> addImage(uri)
                        REQ_EXPORT_MD -> {
                            val text = buildText()
                            writeExport(uri) { text }
                        }
                        REQ_EXPORT_HTML -> {
                            val text = buildText()
                            writeExport(uri) { HtmlExport.page(store, text) }
                        }
                    }
                }
            }
        }
        // The screen may have been rebuilt (rotation) or the vault closed meanwhile: wait for the note.
        if (noteReady()) act() else pendingResult = act
    }

    private fun noteReady(): Boolean = !loadingNote && (!locked || (revealed && Vault.isOpen()))

    private fun runPendingResult() {
        val act = pendingResult ?: return
        if (!noteReady()) return
        pendingResult = null
        act()
    }

    private fun addImage(uri: Uri) {
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
        inBackground({
            try {
                store.list()
                    .filter { !it.meta.vault && !it.isConflict }
                    .map { it.title }
                    .filter { !it.equals(current, ignoreCase = true) }
            } catch (e: Exception) {
                emptyList()
            }
        }) { titles ->
            if (titles.isEmpty()) {
                Toast.makeText(this, R.string.no_other_notes, Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.pick_note)
                    .setItems(titles.toTypedArray()) { _, which -> insertAtCursor("[[${titles[which]}]]") }
                    .show()
            }
        }
    }

    // ---------- More menu ----------

    private fun showMoreMenu(anchor: View) {
        if (loadingNote) return
        val id = noteId
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        menu.add(0, M_PIN, 0, if (meta.pinned) R.string.unpin else R.string.pin)
        menu.add(0, M_REMIND, 1, R.string.remind)
        if (meta.remind > 0) menu.add(0, M_REMIND_OFF, 2, R.string.remind_remove)
        if (!locked) {
            menu.add(0, M_NOTIFY, 3,
                if (id != null && Reminders.isPinned(this, id)) R.string.notify_unpin else R.string.notify_pin)
        }
        menu.add(0, M_COLOR, 4, R.string.color)
        menu.add(0, M_TAGS, 5, R.string.tags)
        menu.add(0, M_MOVE, 6, R.string.move)
        menu.add(0, M_ARCHIVE, 7, if (meta.archived) R.string.unarchive else R.string.archive)
        menu.add(0, M_LOCK, 8, if (locked) R.string.unlock_note else R.string.lock_note)
        menu.add(0, M_HISTORY, 9, R.string.history)
        if (drawingsInNote().isNotEmpty()) menu.add(0, M_DRAW, 10, R.string.draw_edit)
        menu.add(0, M_CHECKED, 11, R.string.checked_to_bottom)
        menu.add(0, M_SHARE, 12, R.string.share)
        menu.add(0, M_EXPORT, 13, R.string.export_note)
        menu.add(0, M_TOC, 14, R.string.toc)
        menu.add(0, M_DELETE, 15, R.string.delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_PIN -> changeMeta { it.copy(pinned = !it.pinned) }
                M_REMIND -> chooseReminder()
                M_REMIND_OFF -> removeReminder()
                M_NOTIFY -> toggleNotificationPin()
                M_COLOR -> chooseColor()
                M_TAGS -> editTags()
                M_MOVE -> chooseNotebook()
                M_ARCHIVE -> changeMeta { it.copy(archived = !it.archived) }
                M_LOCK -> if (locked) removeLock() else lockNote()
                M_HISTORY -> showHistory()
                M_DRAW -> editDrawing()
                M_CHECKED -> checkedToBottom()
                M_SHARE -> share()
                M_EXPORT -> exportNote()
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

    private fun dialogBox(input: View): LinearLayout =
        LinearLayout(this).apply {
            val pad = Ui.dp(this@EditorActivity, 20f)
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    private fun editTags() {
        val input = EditText(this).apply {
            hint = getString(R.string.tags_hint)
            setText(meta.tags.joinToString(", "))
            setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tags)
            .setView(dialogBox(input))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                changeMeta { it.copy(tags = NoteMeta.splitList(input.text.toString()).distinct()) }
            }
            .show()
    }

    private fun chooseNotebook() {
        save()
        if (noteId == null) {
            Toast.makeText(this, R.string.error_move, Toast.LENGTH_SHORT).show()
            return
        }
        inBackground({
            try {
                store.notebooks()
            } catch (e: Exception) {
                emptyList()
            }
        }) { books ->
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
    }

    private fun askNewNotebook() {
        val input = EditText(this).apply {
            hint = getString(R.string.notebook_hint)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_new_notebook)
            .setView(dialogBox(input))
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
            val moved = store.move(current, target)
            noteId = moved
            folder = NoteStore.cleanPath(target)
            followNewId(current, moved)
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
                    Reminders.cancel(this, existing)
                    Reminders.unpinNotification(this, existing)
                    try {
                        if (folder == NoteStore.TRASH) {
                            store.deleteForever(existing)
                        } else {
                            store.delete(existing)
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
                    }
                    Widgets.refreshNotes(this)
                }
                finish()
            }
            .show()
    }

    // ---------- Toolbar actions ----------

    private fun endTypingBurst() {
        uiHandler.removeCallbacks(endBurst)
        typingBurst = false
    }

    /**
     * Gets the note text ready for a command: a cursor that was never placed goes to the end
     * (not to a hidden spot at the top), and the text gets the focus when the title had it.
     */
    private fun focusBody() {
        if (!bodyEverFocused) {
            bodyView.setSelection(bodyView.text.length)
            bodyEverFocused = true
        }
        if (!reading && !bodyView.isFocused) bodyView.requestFocus()
    }

    private fun insertAtCursor(text: String) {
        focusBody()
        endTypingBurst()
        val e = bodyView.text
        val pos = bodyView.selectionStart.coerceIn(0, e.length)
        e.insert(pos, text)
        bodyView.setSelection((pos + text.length).coerceAtMost(e.length))
        endTypingBurst()
        if (reading) showRead()
    }

    /** Inserts text on its own line. */
    private fun insertBlock(text: String) {
        focusBody()
        val e = bodyView.text
        val pos = bodyView.selectionStart.coerceIn(0, e.length)
        val lead = if (pos > 0 && e[pos - 1] != '\n') "\n" else ""
        insertAtCursor("$lead$text\n")
    }

    /** Number of '*' next to a position, looking back (step -1) or forward (step 1). */
    private fun starRun(e: CharSequence, from: Int, step: Int): Int {
        var n = 0
        var i = if (step < 0) from - 1 else from
        while (i in 0 until e.length && e[i] == '*') {
            n++
            i += step
        }
        return n
    }

    /** "*" around "**bold**" belongs to the bold marks, so italic must not take it away. */
    private fun ownMarks(prefix: String, before: Int, after: Int): Boolean =
        prefix != "*" || minOf(before, after) != 2

    private fun wrap(prefix: String, suffix: String = prefix) {
        val e = bodyView.text
        var a = minOf(bodyView.selectionStart, bodyView.selectionEnd).coerceIn(0, e.length)
        var b = maxOf(bodyView.selectionStart, bodyView.selectionEnd).coerceIn(0, e.length)
        // A double-tapped word often comes with a space; marks only work when they touch the words.
        while (a < b && e[a].isWhitespace()) a++
        while (b > a && e[b - 1].isWhitespace()) b--
        // Pressing the same button again removes the marks: first those right around the selection...
        if (a >= prefix.length && b + suffix.length <= e.length &&
            e.substring(a - prefix.length, a) == prefix && e.substring(b, b + suffix.length) == suffix &&
            ownMarks(prefix, starRun(e, a, -1), starRun(e, b, 1))
        ) {
            e.delete(b, b + suffix.length)
            e.delete(a - prefix.length, a)
            bodyView.setSelection(a - prefix.length, b - prefix.length)
            return
        }
        // ...then those inside it, when the marks were selected too.
        if (b - a >= prefix.length + suffix.length &&
            e.substring(a, a + prefix.length) == prefix && e.substring(b - suffix.length, b) == suffix &&
            ownMarks(prefix, starRun(e, a, 1), starRun(e, b, -1))
        ) {
            e.delete(b - suffix.length, b)
            e.delete(a, a + prefix.length)
            bodyView.setSelection(a, b - prefix.length - suffix.length)
            return
        }
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

    /** Starts of every line the selection touches (just the cursor's line without a selection). */
    private fun selectedLineStarts(): List<Int> {
        val e = bodyView.text
        val a = minOf(bodyView.selectionStart, bodyView.selectionEnd).coerceIn(0, e.length)
        var b = maxOf(bodyView.selectionStart, bodyView.selectionEnd).coerceIn(0, e.length)
        // A selection that ends at the very start of a line leaves that line alone.
        if (b > a && e[b - 1] == '\n') b--
        var start = a
        while (start > 0 && e[start - 1] != '\n') start--
        val out = arrayListOf(start)
        var i = start
        while (true) {
            val nl = TextUtils.indexOf(e, '\n', i)
            if (nl < 0 || nl >= b) break
            out.add(nl + 1)
            i = nl + 1
        }
        return out
    }

    private fun lineAt(e: CharSequence, start: Int): String {
        val end = TextUtils.indexOf(e, '\n', start).let { if (it < 0) e.length else it }
        return e.subSequence(start, end).toString()
    }

    /**
     * Turns a line format on or off for every selected line: off when all of them have it.
     * Empty lines inside a longer selection are left alone. Lines are changed from the last one up,
     * so the positions of the lines above stay right.
     */
    private fun toggleLines(has: (String) -> Boolean, change: (Editable, Int, String, Boolean) -> Unit) {
        val e = bodyView.text
        val starts = selectedLineStarts()
        val lines = starts.map { lineAt(e, it) }
        val used = if (starts.size > 1) starts.indices.filter { lines[it].isNotBlank() } else starts.indices.toList()
        if (used.isEmpty()) return
        val removing = used.all { has(lines[it]) }
        for (i in used.reversed()) change(e, starts[i], lines[i], removing)
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart(' ', '\t').length

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

    private fun bullet() = toggleLines(
        { line -> BULLET_LINE.containsMatchIn(line) && !TASK_LINE.containsMatchIn(line) }
    ) { e, start, line, removing ->
        val indent = indentOf(line)
        val task = TASK_LINE.find(line)
        val mark = BULLET_LINE.find(line)
        val quoteMark = QUOTE_LINE.find(line)
        when {
            removing -> if (mark != null) e.delete(start + indent, start + mark.value.length)
            task != null -> e.replace(start + indent, start + task.value.length, "- ")
            mark != null -> Unit
            quoteMark != null -> e.replace(start, start + quoteMark.value.length, "- ")
            else -> e.insert(start + indent, "- ")
        }
    }

    private fun task() = toggleLines({ line -> TASK_LINE.containsMatchIn(line) }) { e, start, line, removing ->
        val indent = indentOf(line)
        val task = TASK_LINE.find(line)
        val mark = BULLET_LINE.find(line)
        when {
            removing -> if (task != null) e.delete(start + indent, start + task.value.length)
            task != null -> Unit
            mark != null -> e.replace(start + indent, start + mark.value.length, "- [ ] ")
            else -> e.insert(start + indent, "- [ ] ")
        }
    }

    private fun quote() = toggleLines({ line -> QUOTE_LINE.containsMatchIn(line) }) { e, start, line, removing ->
        val quoteMark = QUOTE_LINE.find(line)
        when {
            removing -> if (quoteMark != null) e.delete(start, start + quoteMark.value.length)
            quoteMark != null -> Unit
            else -> e.insert(start, "> ")
        }
    }

    /** After Enter on a list line: continue the list, or end it when the item was left empty. */
    private fun continueList(e: Editable, newline: Int): Boolean {
        if (newline !in 0 until e.length || e[newline] != '\n') return false
        var start = newline
        while (start > 0 && e[start - 1] != '\n') start--
        val line = e.subSequence(start, newline).toString()
        if (RULE_LINE.matches(line)) return false
        val m = LIST_ITEM.find(line) ?: return false
        var restEnd = newline + 1
        while (restEnd < e.length && e[restEnd] != '\n') restEnd++
        if (line.substring(m.value.length).isBlank() && restEnd == newline + 1) {
            // Enter on an empty item: drop its mark and stay on that line.
            e.delete(start, newline + 1)
            return true
        }
        val g = m.groupValues
        val next = g[1] + when {
            g[2].isNotEmpty() -> g[2] + " [ ] "
            g[3].isNotEmpty() -> g[3] + " "
            else -> ((g[4].toLongOrNull() ?: 0L) + 1).toString() + g[5] + " "
        }
        e.insert(newline + 1, next)
        return true
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
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\\d)")
        private const val STYLE_NOW_LIMIT = 6000
        private const val STYLE_DELAY_MS = 250L
        const val EXTRA_ID = "note_id"
        const val EXTRA_TITLE = "note_title"
        const val EXTRA_FOLDER = "note_folder"
        private const val KEY_ID = "note_id"
        private const val KEY_EDITING_DRAWING = "editing_drawing"
        private const val KEY_READING = "reading"
        private const val KEY_CURSOR = "cursor"
        private const val MAX_SHARED_BYTES = 2 * 1024 * 1024
        private const val ID_TITLE = 101
        private const val ID_BODY = 102
        private const val REQ_IMAGE = 7
        private const val REQ_NOTIFY = 8
        private const val REQ_EXPORT_MD = 9
        private const val REQ_EXPORT_HTML = 10
        private const val REQ_DRAW = 11
        private const val M_PIN = 1
        private const val M_REMIND = 2
        private const val M_REMIND_OFF = 3
        private const val M_NOTIFY = 4
        private const val M_COLOR = 5
        private const val M_TAGS = 6
        private const val M_MOVE = 7
        private const val M_ARCHIVE = 8
        private const val M_LOCK = 9
        private const val M_HISTORY = 10
        private const val M_CHECKED = 11
        private const val M_SHARE = 12
        private const val M_TOC = 13
        private const val M_DELETE = 14
        private const val M_EXPORT = 15
        private const val M_DRAW = 16
        private const val MAX_UNDO = 100
        private const val BURST_MS = 700L
        private val WORD = Regex("\\S+")
        private val TOC_HEADING = Regex("^(#{1,6})\\s+(.*)")
        private val TASK_BOX = Regex("^(\\s*[-*+]\\s\\[)([ xX])(]\\s)")
        private val TASK_LINE = Regex("^\\s*[-*+] \\[[ xX]] ")
        private val BULLET_LINE = Regex("^\\s*[-*+] ")
        private val QUOTE_LINE = Regex("^> ?")
        private val LIST_ITEM = Regex("^(\\s*)(?:([-*+]) \\[[ xX]] |([-*+]) |(\\d{1,9})([.)]) )")
        private val RULE_LINE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
        private val IMAGE_LINK = Regex("!\\[[^\\]]*\\]\\(([^)\\s]+)\\)")
    }
}
