package io.github.andrasulthan.omninote

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Home screen: list of notes, new-note button and settings menu. */
class MainActivity : Activity() {

    private lateinit var store: NoteStore
    private lateinit var p: Ui.Palette
    private lateinit var folderLabel: TextView
    private lateinit var emptyView: TextView
    private val notesAdapter = NotesAdapter()
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        p = Ui.palette(this)
        val pad = Ui.dp(this, 20f)

        val frame = FrameLayout(this).apply { setBackgroundColor(p.bg) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, Ui.dp(context, 12f), pad, 0)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = Ui.text(this, 26f, p.text, bold = true).apply {
            text = getString(R.string.app_name).uppercase()
            letterSpacing = 0.12f
        }
        val more = Ui.text(this, 24f, p.text).apply {
            text = "⋯"
            contentDescription = getString(R.string.more)
            setPadding(pad, pad / 2, 0, pad / 2)
            setOnClickListener { showMenu(it) }
        }
        val dotSize = Ui.dp(this, 8f)
        header.addView(title)
        header.addView(Ui.dot(this), LinearLayout.LayoutParams(dotSize, dotSize).apply {
            marginStart = dotSize
        })
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(more)

        folderLabel = Ui.text(this, 12f, p.muted).apply {
            setPadding(0, Ui.dp(context, 2f), 0, Ui.dp(context, 14f))
        }

        val list = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = Ui.dp(context, 10f)
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(0, 0, 0, Ui.dp(context, 96f))
            adapter = notesAdapter
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
                open(notesAdapter.getItem(position).id)
            }
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
                confirmDelete(notesAdapter.getItem(position))
                true
            }
        }

        emptyView = Ui.text(this, 14f, p.muted).apply {
            text = getString(R.string.empty)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            visibility = View.GONE
        }

        val body = FrameLayout(this)
        body.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))

        column.addView(header)
        column.addView(folderLabel)
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

    private fun reload() {
        val name = store.folderName()
        folderLabel.text = if (name == null) {
            getString(R.string.folder_internal)
        } else {
            getString(R.string.folder_named, name)
        }
        io.execute {
            val result: List<Note>? = try {
                store.list()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (!isDestroyed) {
                    if (result == null) {
                        Toast.makeText(this, R.string.error_folder, Toast.LENGTH_LONG).show()
                    }
                    notesAdapter.items = result ?: emptyList()
                    notesAdapter.notifyDataSetChanged()
                    emptyView.visibility =
                        if (notesAdapter.items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun open(id: String?) {
        val intent = Intent(this, EditorActivity::class.java)
        if (id != null) intent.putExtra(EditorActivity.EXTRA_ID, id)
        startActivity(intent)
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_FOLDER, 0, R.string.menu_choose_folder)
        if (store.treeUri != null) {
            popup.menu.add(0, MENU_INTERNAL, 1, R.string.menu_internal)
        }
        popup.menu.add(0, MENU_PREVIEW, 2, R.string.menu_preview)
        popup.menu.add(0, MENU_FONT, 3, R.string.menu_font)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_FOLDER -> startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER
                )
                MENU_INTERNAL -> {
                    store.treeUri = null
                    reload()
                }
                MENU_PREVIEW -> choosePreview()
                MENU_FONT -> chooseFont()
            }
            true
        }
        popup.show()
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
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.error_folder, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(note: Note) {
        AlertDialog.Builder(this)
            .setTitle(note.title)
            .setMessage(R.string.delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                io.execute {
                    try {
                        store.delete(note.id)
                    } catch (e: Exception) {
                        // The list reload below shows the real state.
                    }
                    runOnUiThread { if (!isDestroyed) reload() }
                }
            }
            .show()
    }

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

            val titleView = card.getChildAt(0) as TextView
            titleView.text = note.title
            titleView.textSize = 16f * scale

            val preview = card.getChildAt(1) as TextView
            preview.text = Markdown.plain(note.preview)
            preview.textSize = 13f * scale
            preview.maxLines = if (lines > 0) lines else 1
            preview.visibility =
                if (lines == 0 || note.preview.isEmpty()) View.GONE else View.VISIBLE

            (card.getChildAt(2) as TextView).text =
                DateUtils.getRelativeTimeSpanString(note.modified)
            return card
        }

        private fun buildCard(): LinearLayout {
            val ctx = this@MainActivity
            val pad = Ui.dp(ctx, 16f)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                background = Ui.rounded(
                    p.surface, p.border, Ui.dp(ctx, 16f).toFloat(), Ui.dp(ctx, 1f)
                )
                addView(Ui.text(ctx, 16f, p.text, bold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(Ui.text(ctx, 13f, p.muted).apply {
                    ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, Ui.dp(ctx, 6f), 0, 0)
                })
                addView(Ui.text(ctx, 11f, p.muted).apply {
                    letterSpacing = 0.05f
                    setPadding(0, Ui.dp(ctx, 10f), 0, 0)
                })
            }
        }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val REQ_FOLDER = 42
        private const val MENU_FOLDER = 1
        private const val MENU_INTERNAL = 2
        private const val MENU_PREVIEW = 3
        private const val MENU_FONT = 4
    }
}
