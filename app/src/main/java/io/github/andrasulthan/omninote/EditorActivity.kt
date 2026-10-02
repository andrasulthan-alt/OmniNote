package io.github.andrasulthan.omninote

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/** Writes and edits one note. Saves automatically when the screen is left. */
class EditorActivity : Activity() {

    private lateinit var store: NoteStore
    private lateinit var titleView: EditText
    private lateinit var bodyView: EditText
    private var noteId: String? = null
    private var savedText = ""
    private var deleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        noteId = savedInstanceState?.getString(KEY_ID) ?: intent.getStringExtra(EXTRA_ID)

        val p = Ui.palette(this)
        val pad = Ui.dp(this, 20f)
        val match = LinearLayout.LayoutParams.MATCH_PARENT

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
        val delete = Ui.text(this, 13f, Ui.RED).apply {
            text = getString(R.string.delete).uppercase()
            letterSpacing = 0.1f
            setPadding(pad, pad / 2, 0, pad / 2)
            setOnClickListener { confirmDelete() }
        }
        bar.addView(back)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(delete)

        titleView = EditText(this).apply {
            id = ID_TITLE
            hint = getString(R.string.title_hint)
            setHintTextColor(p.muted)
            setTextColor(p.text)
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = null
            setPadding(0, pad / 2, 0, pad / 2)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setSingleLine(true)
        }

        val line = View(this).apply { setBackgroundColor(p.border) }

        bodyView = EditText(this).apply {
            id = ID_BODY
            hint = getString(R.string.body_hint)
            setHintTextColor(p.muted)
            setTextColor(p.text)
            textSize = 16f
            typeface = Typeface.MONOSPACE
            background = null
            gravity = Gravity.TOP or Gravity.START
            setPadding(0, pad, 0, pad)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setLineSpacing(0f, 1.25f)
        }

        root.addView(bar)
        root.addView(titleView)
        root.addView(line, LinearLayout.LayoutParams(match, Ui.dp(this, 1f)))
        root.addView(bodyView, LinearLayout.LayoutParams(match, 0, 1f))
        Ui.applyInsets(root)
        setContentView(root)

        val existing = noteId
        if (existing != null) {
            try {
                val (title, body) = NoteStore.split(store.read(existing))
                titleView.setText(title)
                bodyView.setText(body)
                savedText = NoteStore.join(title, body)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.error_open, Toast.LENGTH_LONG).show()
            }
        } else {
            titleView.requestFocus()
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

    companion object {
        const val EXTRA_ID = "note_id"
        private const val KEY_ID = "note_id"
        private const val ID_TITLE = 101
        private const val ID_BODY = 102
    }
}
