package io.github.andrasulthan.omninote

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Home screen: filters, search, note cards, multi-select, new-note button and settings menu. */
class MainActivity : Activity() {

    private lateinit var store: NoteStore
    private lateinit var p: Ui.Palette
    private lateinit var prefs: SharedPreferences
    private lateinit var header: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCount: TextView
    private lateinit var selectionActions: LinearLayout
    private lateinit var folderLabel: TextView
    private lateinit var emptyView: TextView
    private lateinit var grid: GridView
    private lateinit var chipRow: LinearLayout
    private lateinit var searchArea: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var recentScroll: HorizontalScrollView
    private lateinit var recentRow: LinearLayout
    private val notesAdapter = NotesAdapter()
    private val io = Executors.newSingleThreadExecutor()

    private var allNotes: List<Note> = emptyList()
    private var trashNotes: List<Note> = emptyList()
    private var notebooks: List<String> = emptyList()
    private var filter = FILTER_ALL
    private var query = ""
    private val selected = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        p = Ui.palette(this)
        prefs = getSharedPreferences("omninote", Context.MODE_PRIVATE)
        filter = savedInstanceState?.getString(KEY_FILTER) ?: FILTER_ALL
        val pad = Ui.dp(this, 20f)
        val small = Ui.dp(this, 8f)
        val iconSize = Ui.dp(this, 44f)

        val frame = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(context, 12f), pad, 0)
        }

        // Header: OMNINOTE ● ... search  more
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = Ui.text(this, 26f, p.text, bold = true).apply {
            text = getString(R.string.app_name).uppercase()
            letterSpacing = 0.12f
        }
        val dotSize = Ui.dp(this, 8f)
        val searchIcon = SearchIcon(this, p.text).apply {
            contentDescription = getString(R.string.search)
            setOnClickListener { toggleSearch() }
        }
        val more = Ui.text(this, 24f, p.text).apply {
            text = "⋯"
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.more)
            setOnClickListener { showMenu(it) }
        }
        header.addView(title)
        header.addView(Ui.dot(this), LinearLayout.LayoutParams(dotSize, dotSize).apply {
            marginStart = dotSize
        })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(searchIcon, LinearLayout.LayoutParams(iconSize, iconSize))
        header.addView(more, LinearLayout.LayoutParams(iconSize, iconSize))

        // Selection bar: ✕  3 selected  [actions...]
        selectionCount = Ui.text(this, 15f, p.text, bold = true).apply {
            setPadding(small, 0, small, 0)
        }
        selectionActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val close = Ui.text(this, 20f, p.text).apply {
            text = "✕"
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.close_selection)
            setOnClickListener { exitSelection() }
        }
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(close, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(selectionCount)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(selectionActions)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        // Search box with recent searches
        searchBox = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setHintTextColor(p.muted)
            setTextColor(p.text)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            background = Ui.rounded(p.surface, p.border, Ui.dp(context, 14f).toFloat(), Ui.dp(context, 1f))
            setPadding(pad * 3 / 4, small + 4, pad * 3 / 4, small + 4)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    updateRecent()
                    applyFilter()
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    saveRecent(query)
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
        }
        recentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recentScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, small, 0, 0)
            addView(recentRow)
        }
        searchArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, small, 0, 0)
            addView(searchBox)
            addView(recentScroll)
        }

        folderLabel = Ui.text(this, 12f, p.muted).apply {
            setPadding(0, Ui.dp(context, 4f), 0, small)
        }

        // Filter chips: All, notebooks, tags, Archive, Trash
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, Ui.dp(context, 12f))
            addView(chipRow)
        }

        grid = GridView(this).apply {
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = Ui.dp(context, 10f)
            verticalSpacing = Ui.dp(context, 10f)
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(0, 0, 0, Ui.dp(context, 96f))
            adapter = notesAdapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                val note = notesAdapter.getItem(position)
                if (selected.isNotEmpty()) toggleSelected(note) else open(note.id)
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
                toggleSelected(notesAdapter.getItem(position))
                true
            }
        }

        emptyView = Ui.text(this, 14f, p.muted).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            visibility = View.GONE
        }

        val body = FrameLayout(this)
        body.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))

        column.addView(header)
        column.addView(selectionBar)
        column.addView(searchArea)
        column.addView(folderLabel)
        column.addView(chipScroll)
        column.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val fabSize = Ui.dp(this, 60f)
        val fab = Ui.text(this, 30f, p.bg).apply {
            text = "+"
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.new_note)
            background = Ui.circle(p.text)
            setOnClickListener { open(null) }
        }

        frame.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(
            fab,
            FrameLayout.LayoutParams(fabSize, fabSize, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, pad, pad)
            }
        )
        Ui.applyInsets(frame)
        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_FILTER, filter)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selected.isNotEmpty()) {
            exitSelection()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------- Loading and filtering ----------

    private fun reload() {
        val name = store.folderName()
        folderLabel.text = if (name == null) {
            getString(R.string.folder_internal)
        } else {
            getString(R.string.folder_named, name)
        }
        grid.numColumns = if (store.gridLayout) 2 else 1
        io.execute {
            var failed = false
            val notes = try {
                store.list()
            } catch (e: Exception) {
                failed = true
                emptyList()
            }
            val trash = try {
                store.listTrash()
            } catch (e: Exception) {
                emptyList()
            }
            val books = try {
                store.notebooks()
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (!isDestroyed) {
                    if (failed) Toast.makeText(this, R.string.error_folder, Toast.LENGTH_LONG).show()
                    allNotes = notes
                    trashNotes = trash
                    notebooks = books
                    buildChips()
                    applyFilter()
                }
            }
        }
    }

    private fun inNotebook(note: Note, book: String): Boolean =
        note.folder == book || note.folder.startsWith("$book/")

    private fun hasTag(note: Note, tag: String): Boolean =
        note.meta.tags.any { it.equals(tag, ignoreCase = true) }

    private fun visibleNotes(): List<Note> {
        val base = when {
            filter == FILTER_TRASH -> trashNotes
            filter == FILTER_ARCHIVE -> allNotes.filter { it.meta.archived }
            filter.startsWith(PREFIX_BOOK) -> {
                val book = filter.removePrefix(PREFIX_BOOK)
                allNotes.filter { !it.meta.archived && inNotebook(it, book) }
            }
            filter.startsWith(PREFIX_TAG) -> {
                val tag = filter.removePrefix(PREFIX_TAG)
                allNotes.filter { !it.meta.archived && hasTag(it, tag) }
            }
            else -> allNotes.filter { !it.meta.archived }
        }
        val q = query.trim()
        if (q.isEmpty()) return base
        return base.filter { note ->
            note.title.contains(q, ignoreCase = true) ||
                note.body.contains(q, ignoreCase = true) ||
                note.meta.tags.any { it.contains(q, ignoreCase = true) }
        }
    }

    private fun applyFilter() {
        notesAdapter.items = visibleNotes()
        val visibleIds = notesAdapter.items.map { it.id }.toSet()
        selected.retainAll(visibleIds)
        updateSelectionBar()
        notesAdapter.notifyDataSetChanged()
        val nothingAtAll = allNotes.isEmpty() && trashNotes.isEmpty()
        emptyView.text = getString(if (nothingAtAll) R.string.empty else R.string.empty_filtered)
        emptyView.visibility = if (notesAdapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildChips() {
        val tags = allNotes.flatMap { it.meta.tags }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        val keys = mutableListOf(FILTER_ALL, FILTER_ARCHIVE, FILTER_TRASH)
        keys.addAll(notebooks.map { PREFIX_BOOK + it })
        keys.addAll(tags.map { PREFIX_TAG + it })
        if (filter !in keys) filter = FILTER_ALL

        chipRow.removeAllViews()
        addChip(getString(R.string.filter_all), FILTER_ALL, allNotes.count { !it.meta.archived })
        for (book in notebooks) {
            addChip(book, PREFIX_BOOK + book, allNotes.count { !it.meta.archived && inNotebook(it, book) })
        }
        for (tag in tags) {
            addChip("#$tag", PREFIX_TAG + tag, allNotes.count { !it.meta.archived && hasTag(it, tag) })
        }
        addChip(getString(R.string.filter_archive), FILTER_ARCHIVE, allNotes.count { it.meta.archived })
        addChip(getString(R.string.filter_trash), FILTER_TRASH, trashNotes.size)
    }

    private fun addChip(label: String, key: String, count: Int) {
        val isOn = key == filter
        val chip = Ui.text(this, 13f, if (isOn) p.bg else p.text).apply {
            text = "$label  $count"
            val h = Ui.dp(context, 14f)
            val v = Ui.dp(context, 8f)
            setPadding(h, v, h, v)
            background = Ui.rounded(
                if (isOn) p.text else Color.TRANSPARENT,
                if (isOn) p.text else p.border,
                Ui.dp(context, 20f).toFloat(),
                Ui.dp(context, 1f)
            )
            setOnClickListener {
                selected.clear()
                filter = key
                buildChips()
                applyFilter()
            }
        }
        chipRow.addView(chip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = Ui.dp(this@MainActivity, 8f) })
    }

    // ---------- Multi-select ----------

    private fun toggleSelected(note: Note) {
        if (!selected.remove(note.id)) selected.add(note.id)
        updateSelectionBar()
        notesAdapter.notifyDataSetChanged()
    }

    private fun exitSelection() {
        selected.clear()
        updateSelectionBar()
        notesAdapter.notifyDataSetChanged()
    }

    private fun selectedNotes(): List<Note> =
        notesAdapter.items.filter { it.id in selected }

    private fun updateSelectionBar() {
        val selecting = selected.isNotEmpty()
        header.visibility = if (selecting) View.GONE else View.VISIBLE
        selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        if (!selecting) return
        selectionCount.text = getString(R.string.selected_count, selected.size)
        selectionActions.removeAllViews()
        if (filter == FILTER_TRASH) {
            addAction(R.string.select_all) { selectAll() }
            addAction(R.string.restore) { restoreSelected() }
            addAction(R.string.delete_forever) { deleteSelectedForever() }
        } else {
            val notes = selectedNotes()
            addAction(R.string.select_all) { selectAll() }
            addAction(if (notes.any { !it.meta.pinned }) R.string.pin else R.string.unpin) { pinSelected() }
            addAction(R.string.add_tags) { tagSelected() }
            addAction(R.string.move) { moveSelected() }
            addAction(if (notes.any { !it.meta.archived }) R.string.archive else R.string.unarchive) {
                archiveSelected()
            }
            addAction(R.string.merge) { mergeSelected() }
            addAction(R.string.delete) { deleteSelected() }
        }
    }

    private fun addAction(label: Int, action: () -> Unit) {
        val button = Ui.text(this, 12f, p.text, bold = true).apply {
            text = getString(label).removeSuffix("…").uppercase()
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            val h = Ui.dp(context, 10f)
            setPadding(h, h, h, h)
            setOnClickListener { action() }
        }
        selectionActions.addView(button)
    }

    private fun selectAll() {
        selected.addAll(notesAdapter.items.map { it.id })
        updateSelectionBar()
        notesAdapter.notifyDataSetChanged()
    }

    private fun finishBulk(work: (List<Note>) -> Unit) {
        val notes = selectedNotes()
        if (notes.isEmpty()) return
        selected.clear()
        updateSelectionBar()
        runThenReload { work(notes) }
    }

    private fun pinSelected() {
        val pin = selectedNotes().any { !it.meta.pinned }
        finishBulk { notes -> notes.forEach { n -> store.updateMeta(n.id) { it.copy(pinned = pin) } } }
    }

    private fun archiveSelected() {
        val archive = selectedNotes().any { !it.meta.archived }
        finishBulk { notes -> notes.forEach { n -> store.updateMeta(n.id) { it.copy(archived = archive) } } }
    }

    private fun tagSelected() {
        val input = EditText(this).apply {
            hint = getString(R.string.tags_hint)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_tags)
            .setView(dialogBox(input))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val added = NoteMeta.splitList(input.text.toString())
                if (added.isNotEmpty()) {
                    finishBulk { notes ->
                        notes.forEach { n ->
                            store.updateMeta(n.id) { m ->
                                m.copy(tags = (m.tags + added).distinctBy { it.lowercase() })
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun moveSelected() {
        val books = notebooks
        val labels = listOf(getString(R.string.top_level)) + books + getString(R.string.menu_new_notebook)
        AlertDialog.Builder(this)
            .setTitle(R.string.move)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> finishBulk { notes -> notes.forEach { store.move(it.id, "") } }
                    labels.size - 1 -> askNotebookName { path ->
                        finishBulk { notes -> notes.forEach { store.move(it.id, path) } }
                    }
                    else -> {
                        val target = books[which - 1]
                        finishBulk { notes -> notes.forEach { store.move(it.id, target) } }
                    }
                }
            }
            .show()
    }

    /** Joins the selected notes into one new note; the originals go to the trash. */
    private fun mergeSelected() {
        val notes = selectedNotes()
        if (notes.size < 2) {
            Toast.makeText(this, R.string.merge_need_two, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.merge_confirm, notes.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.merge) { _, _ ->
                finishBulk { list ->
                    val first = list.first()
                    val sections = list.joinToString("\n\n---\n\n") { n ->
                        "## ${n.title}\n\n${n.body.trim()}"
                    }
                    val meta = NoteMeta(
                        created = System.currentTimeMillis(),
                        color = first.meta.color,
                        tags = list.flatMap { it.meta.tags }.distinctBy { it.lowercase() }
                    )
                    val text = NoteMeta.build(meta, NoteStore.join(first.title, sections))
                    store.save(null, first.title, text, first.folder)
                    list.forEach { store.delete(it.id) }
                }
            }
            .show()
    }

    private fun deleteSelected() {
        val count = selected.size
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_selected_confirm, count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                finishBulk { notes -> notes.forEach { store.delete(it.id) } }
            }
            .show()
    }

    private fun restoreSelected() {
        finishBulk { notes -> notes.forEach { store.restore(it.id) } }
    }

    private fun deleteSelectedForever() {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_forever_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_forever) { _, _ ->
                finishBulk { notes -> notes.forEach { store.deleteForever(it.id) } }
            }
            .show()
    }

    // ---------- Search ----------

    private fun toggleSearch() {
        if (searchArea.visibility == View.VISIBLE) {
            saveRecent(query)
            searchBox.setText("")
            searchArea.visibility = View.GONE
            hideKeyboard()
        } else {
            searchArea.visibility = View.VISIBLE
            updateRecent()
            searchBox.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun recentSearches(): List<String> =
        (prefs.getString(KEY_RECENT, "") ?: "").split("\n").filter { it.isNotBlank() }

    private fun saveRecent(q: String) {
        val clean = q.trim()
        if (clean.isEmpty()) return
        val list = (listOf(clean) + recentSearches().filter { !it.equals(clean, ignoreCase = true) }).take(5)
        prefs.edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
    }

    private fun updateRecent() {
        recentRow.removeAllViews()
        val recent = recentSearches()
        recentScroll.visibility = if (query.isEmpty() && recent.isNotEmpty()) View.VISIBLE else View.GONE
        for (item in recent) {
            val chip = Ui.text(this, 12f, p.muted).apply {
                text = "↺ $item"
                val h = Ui.dp(context, 12f)
                val v = Ui.dp(context, 6f)
                setPadding(h, v, h, v)
                background = Ui.rounded(Color.TRANSPARENT, p.border, Ui.dp(context, 16f).toFloat(), Ui.dp(context, 1f))
                setOnClickListener {
                    searchBox.setText(item)
                    searchBox.setSelection(item.length)
                }
            }
            recentRow.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = Ui.dp(this@MainActivity, 6f) })
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    // ---------- Actions ----------

    private fun open(id: String?) {
        val intent = Intent(this, EditorActivity::class.java)
        if (id != null) {
            intent.putExtra(EditorActivity.EXTRA_ID, id)
        } else if (filter.startsWith(PREFIX_BOOK)) {
            intent.putExtra(EditorActivity.EXTRA_FOLDER, filter.removePrefix(PREFIX_BOOK))
        }
        startActivity(intent)
    }

    private fun runThenReload(work: () -> Unit) {
        io.execute {
            try {
                work()
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, R.string.error_save, Toast.LENGTH_SHORT).show()
                }
            }
            runOnUiThread { if (!isDestroyed) reload() }
        }
    }

    private fun dialogBox(input: View): LinearLayout =
        LinearLayout(this).apply {
            val pad = Ui.dp(this@MainActivity, 20f)
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

    private fun askNotebookName(onName: (String) -> Unit) {
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
                if (path.isNotBlank()) onName(path)
            }
            .show()
    }

    // ---------- Menu ----------

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        menu.add(0, MENU_NOTEBOOK, 0, R.string.menu_new_notebook)
        menu.add(0, MENU_SORT, 1, R.string.menu_sort)
        menu.add(0, MENU_LAYOUT, 2, if (store.gridLayout) R.string.menu_list else R.string.menu_grid)
        menu.add(0, MENU_COLOR, 3, R.string.menu_default_color)
        menu.add(0, MENU_PREVIEW, 4, R.string.menu_preview)
        menu.add(0, MENU_FONT, 5, R.string.menu_font)
        menu.add(0, MENU_FOLDER, 6, R.string.menu_choose_folder)
        if (store.treeUri != null) menu.add(0, MENU_INTERNAL, 7, R.string.menu_internal)
        if (filter == FILTER_TRASH && trashNotes.isNotEmpty()) {
            menu.add(0, MENU_EMPTY_TRASH, 8, R.string.empty_trash)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_NOTEBOOK -> askNotebookName { path ->
                    filter = PREFIX_BOOK + path
                    runThenReload { store.createNotebook(path) }
                }
                MENU_SORT -> chooseSort()
                MENU_LAYOUT -> {
                    store.gridLayout = !store.gridLayout
                    grid.numColumns = if (store.gridLayout) 2 else 1
                    notesAdapter.notifyDataSetChanged()
                }
                MENU_COLOR -> chooseDefaultColor()
                MENU_PREVIEW -> choosePreview()
                MENU_FONT -> chooseFont()
                MENU_FOLDER -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)
                MENU_INTERNAL -> {
                    store.treeUri = null
                    filter = FILTER_ALL
                    reload()
                }
                MENU_EMPTY_TRASH -> confirmEmptyTrash()
            }
            true
        }
        popup.show()
    }

    private fun chooseSort() {
        val options = arrayOf(
            getString(R.string.sort_modified),
            getString(R.string.sort_newest),
            getString(R.string.sort_oldest),
            getString(R.string.sort_title)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_sort)
            .setSingleChoiceItems(options, store.sortMode) { dialog, which ->
                store.sortMode = which
                dialog.dismiss()
                reload()
            }
            .show()
    }

    private fun chooseDefaultColor() {
        val names = listOf(
            R.string.color_none, R.string.color_red, R.string.color_orange, R.string.color_yellow,
            R.string.color_green, R.string.color_blue, R.string.color_purple, R.string.color_gray
        ).map { getString(it) }
        val values = listOf<String?>(null) + NoteMeta.COLORS
        val checked = values.indexOf(store.defaultColor).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_default_color)
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                store.defaultColor = values[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun choosePreview() {
        val options = arrayOf(
            getString(R.string.preview_none),
            getString(R.string.preview_1),
            getString(R.string.preview_2),
            getString(R.string.preview_3)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_preview)
            .setSingleChoiceItems(options, store.previewLines) { dialog, which ->
                store.previewLines = which
                notesAdapter.notifyDataSetChanged()
                dialog.dismiss()
            }
            .show()
    }

    private fun chooseFont() {
        val options = arrayOf(
            getString(R.string.font_small),
            getString(R.string.font_normal),
            getString(R.string.font_large),
            getString(R.string.font_huge)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_font)
            .setSingleChoiceItems(options, store.fontLevel) { dialog, which ->
                store.fontLevel = which
                notesAdapter.notifyDataSetChanged()
                dialog.dismiss()
            }
            .show()
    }

    private fun confirmEmptyTrash() {
        AlertDialog.Builder(this)
            .setTitle(R.string.empty_trash)
            .setMessage(R.string.delete_forever_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.empty_trash) { _, _ ->
                runThenReload { store.emptyTrash() }
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            store.treeUri = uri
            filter = FILTER_ALL
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.error_folder, Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Note cards ----------

    private inner class NotesAdapter : BaseAdapter() {
        var items: List<Note> = emptyList()

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Note = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val card = (convertView as? LinearLayout) ?: buildCard()
            val note = items[position]
            val scale = store.fontScale()
            val lines = store.previewLines
            val isSelected = note.id in selected

            card.background = Ui.rounded(
                p.surface,
                if (isSelected) Ui.RED else p.border,
                Ui.dp(this@MainActivity, 16f).toFloat(),
                Ui.dp(this@MainActivity, if (isSelected) 2f else 1f)
            )

            val titleRow = card.getChildAt(0) as LinearLayout
            val dot = titleRow.getChildAt(0)
            val colour = NoteMeta.colorValue(note.meta.color)
            if (colour != null) {
                dot.background = Ui.circle(colour)
                dot.visibility = View.VISIBLE
            } else {
                dot.visibility = View.GONE
            }
            val titleView = titleRow.getChildAt(1) as TextView
            titleView.text = note.title
            titleView.textSize = 16f * scale

            val preview = card.getChildAt(1) as TextView
            preview.text = Markdown.plain(note.preview)
            preview.textSize = 13f * scale
            preview.maxLines = if (lines > 0) lines else 1
            preview.visibility =
                if (lines == 0 || note.preview.isEmpty()) View.GONE else View.VISIBLE

            val parts = ArrayList<String>()
            if (note.meta.pinned) parts.add(getString(R.string.pinned).uppercase())
            parts.add(DateUtils.getRelativeTimeSpanString(note.modified).toString())
            val place = if (filter == FILTER_TRASH) note.meta.trashedFrom else note.folder
            if (!place.isNullOrBlank()) parts.add(place)
            if (note.meta.tags.isNotEmpty()) parts.add(note.meta.tags.joinToString(" ") { "#$it" })
            (card.getChildAt(2) as TextView).text = parts.joinToString("  ·  ")
            return card
        }

        private fun buildCard(): LinearLayout {
            val ctx = this@MainActivity
            val pad = Ui.dp(ctx, 16f)
            val dotSize = Ui.dp(ctx, 8f)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(View(ctx), LinearLayout.LayoutParams(dotSize, dotSize).apply {
                        marginEnd = dotSize
                    })
                    addView(Ui.text(ctx, 16f, p.text, bold = true).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                addView(row)
                addView(Ui.text(ctx, 13f, p.muted).apply {
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, Ui.dp(ctx, 6f), 0, 0)
                })
                addView(Ui.text(ctx, 11f, p.muted).apply {
                    letterSpacing = 0.04f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, Ui.dp(ctx, 10f), 0, 0)
                })
            }
        }
    }

    /** Thin line search icon in the Nothing style. */
    private class SearchIcon(ctx: Context, tint: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = tint
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = minOf(width, height).toFloat()
            paint.strokeWidth = s * 0.06f
            val cx = width / 2f - s * 0.06f
            val cy = height / 2f - s * 0.06f
            val r = s * 0.17f
            canvas.drawCircle(cx, cy, r, paint)
            val d = r * 0.7071f
            canvas.drawLine(cx + d, cy + d, cx + d + s * 0.15f, cy + d + s * 0.15f, paint)
        }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val REQ_FOLDER = 42
        private const val KEY_FILTER = "filter"
        private const val KEY_RECENT = "recent_searches"
        private const val FILTER_ALL = "all"
        private const val FILTER_ARCHIVE = "archive"
        private const val FILTER_TRASH = "trash"
        private const val PREFIX_BOOK = "book:"
        private const val PREFIX_TAG = "tag:"
        private const val MENU_NOTEBOOK = 1
        private const val MENU_SORT = 2
        private const val MENU_LAYOUT = 3
        private const val MENU_COLOR = 4
        private const val MENU_PREVIEW = 5
        private const val MENU_FONT = 6
        private const val MENU_FOLDER = 7
        private const val MENU_INTERNAL = 8
        private const val MENU_EMPTY_TRASH = 9
    }
}
