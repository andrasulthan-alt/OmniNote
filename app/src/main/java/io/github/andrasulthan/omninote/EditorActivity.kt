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
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.DateUtils
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
import java.util.Calendar

/** Writes, reads and edits one note. Saves automatically when the screen is left. */
class EditorActivity : Activity() {

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
    private lateinit var countView: TextView
    private var noteId: String? = null
    private var meta = NoteMeta()
    private var folder = ""
    private var savedText = ""
    private var deleted = false
    private var reading = false
    private var locked = false

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
            updateCount()
            updateMetaLine()
        }
    }

    override fun onPause() {
        super.onPause()
        if (!deleted) save()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_ID, noteId)
    }

    // ---------- Loading ----------

    private fun loadExisting(id: String) {
        val raw = try {
            store.read(id)
        } catch (e: Exception) {
            null
        }
        if (raw == null) {
            Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
            return
        }
        folder = store.folderOf(id)
        if (NoteStore.isConflictName(store.fileNameOf(id))) showConflictBar()
        if (!Vault.isLocked(raw)) {
            showPlain(raw)
            return
        }
        locked = true
        setEditable(false)
        val reveal = {
            try {
                showPlain(Vault.unseal(raw))
                setEditable(true)
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
        titleView.setText(title)
        bodyView.setText(body)
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
        val title = titleView.text.toString()
        val body = bodyView.text.toString()
        val plain = buildText()
        if (plain == savedText) return
        if (noteId == null && title.isBlank() && body.isBlank()) return
        val isNew = noteId == null
        if (isNew && !locked && title.isNotBlank()) {
            val clash = try {
                store.findByTitle(title) != null
            } catch (e: Exception) {
                false
            }
            if (clash) Toast.makeText(this, R.string.duplicate_title, Toast.LENGTH_SHORT).show()
        }
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
        } catch (e: Exception) {
            if (!locked) keepDraft(plain)
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

    private fun previewText(): String =
        Markdown.plain(bodyView.text.toString().trim().take(300))

    private fun updateCount() {
        val body = bodyView.text.toString()
        val words = WORD.findAll(body).count()
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
                showPlain(plain)
                savedText = ""
                save()
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

    private fun originalNote(): Note? {
        val id = noteId ?: return null
        val found = try {
            store.findOriginal(id)
        } catch (e: Exception) {
            null
        }
        if (found == null) Toast.makeText(this, R.string.conflict_no_original, Toast.LENGTH_LONG).show()
        return found
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
        Toast.makeText(this, R.string.conflict_resolved, Toast.LENGTH_SHORT).show()
        if (openId != null) {
            startActivity(Intent(this, EditorActivity::class.java).putExtra(EXTRA_ID, openId))
        }
        finish()
    }

    private fun keepThisVersion() {
        val original = originalNote() ?: return
        try {
            History.record(this, History.keyFor(original.id, original.meta), store.read(original.id))
            store.save(original.id, "", fileTextFor(buildText()))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_save, Toast.LENGTH_LONG).show()
            return
        }
        finishConflict(original.id)
    }

    private fun keepOriginal() {
        val original = originalNote() ?: return
        finishConflict(original.id)
    }

    private fun mergeWithOriginal() {
        val original = originalNote() ?: return
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
            return
        }
        finishConflict(original.id)
    }

    private fun openOriginal() {
        val original = originalNote() ?: return
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
            locked = true
            savedText = ""
            save()
            val renamed = store.rename(id, Vault.lockedTitle())
            followNewId(id, renamed)
            noteId = renamed
            Reminders.unpinNotification(this, renamed)
            updateMetaLine()
        }
    }

    private fun removeLock() {
        locked = false
        savedText = ""
        save()
        val id = noteId ?: return
        val renamed = store.rename(id, titleView.text.toString().ifBlank { "Note" })
        followNewId(id, renamed)
        noteId = renamed
        updateMetaLine()
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
        val id = noteId ?: return
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

    /** Keeps reminders and notifications working after a note gets a new id. */
    private fun followNewId(oldId: String, newId: String) {
        if (oldId == newId) return
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
        bodyView.setText(out.joinToString("\n"))
        if (reading) showRead()
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
            store.list()
                .filter { !it.meta.vault && !it.isConflict }
                .map { it.title }
                .filter { !it.equals(current, ignoreCase = true) }
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
        menu.add(0, M_CHECKED, 10, R.string.checked_to_bottom)
        menu.add(0, M_SHARE, 11, R.string.share)
        menu.add(0, M_TOC, 12, R.string.toc)
        menu.add(0, M_DELETE, 13, R.string.delete)
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
                M_CHECKED -> checkedToBottom()
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
        private const val REQ_NOTIFY = 8
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
        private val WORD = Regex("\\S+")
        private val TOC_HEADING = Regex("^(#{1,6})\\s+(.*)")
        private val TASK_BOX = Regex("^(\\s*[-*+]\\s\\[)([ xX])(]\\s)")
        private val TASK_PREFIXES = listOf("- [ ] ", "- [x] ", "- [X] ")
    }
}
