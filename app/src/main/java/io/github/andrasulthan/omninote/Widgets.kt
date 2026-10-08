package io.github.andrasulthan.omninote

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast

/** Home screen widgets: a quick-note bar and a widget that shows one note. */
object Widgets {

    const val EXTRA_CHECKLIST = "new_checklist"
    private const val PREFS = "omninote_widgets"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun noteFor(ctx: Context, widgetId: Int): String? = prefs(ctx).getString("w$widgetId", null)

    fun setNote(ctx: Context, widgetId: Int, noteId: String) {
        prefs(ctx).edit().putString("w$widgetId", noteId).apply()
    }

    fun remove(ctx: Context, widgetId: Int) {
        prefs(ctx).edit().remove("w$widgetId").apply()
    }

    /**
     * Opens a screen from a widget. CLEAR_TOP returns to that screen if it is already open
     * (an open note is saved first) instead of piling up copies; the notes list keeps its place.
     */
    private fun activity(ctx: Context, requestCode: Int, intent: Intent): PendingIntent {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (intent.component?.className == MainActivity::class.java.name) {
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            ctx, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val refreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Redraws every note widget off the main thread; several quick requests become one redraw. */
    fun refreshNotes(ctx: Context) {
        val app = ctx.applicationContext
        if (!refreshQueued.compareAndSet(false, true)) return
        worker.execute {
            refreshQueued.set(false)
            refreshNow(app)
        }
    }

    fun updateQuick(ctx: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_quick)
        views.setOnClickPendingIntent(
            R.id.widget_quick_root,
            activity(ctx, 1, Intent(ctx, MainActivity::class.java))
        )
        views.setOnClickPendingIntent(
            R.id.widget_new_note,
            activity(ctx, 2, Intent(ctx, EditorActivity::class.java))
        )
        views.setOnClickPendingIntent(
            R.id.widget_new_list,
            activity(ctx, 3, Intent(ctx, EditorActivity::class.java).putExtra(EXTRA_CHECKLIST, "yes"))
        )
        manager.updateAppWidget(widgetId, views)
    }

    fun updateNote(ctx: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_note)
        val noteId = noteFor(ctx, widgetId)
        val raw = if (noteId == null) "" else try {
            NoteStore(ctx).read(noteId)
        } catch (e: Exception) {
            ""
        }
        if (noteId == null || raw.isBlank()) {
            views.setTextViewText(R.id.widget_note_title, ctx.getString(R.string.app_name))
            views.setTextViewText(R.id.widget_note_body, ctx.getString(R.string.widget_note_missing))
            views.setOnClickPendingIntent(
                R.id.widget_note_root,
                activity(ctx, 1000 + widgetId, Intent(ctx, MainActivity::class.java))
            )
        } else {
            if (Vault.isLocked(raw)) {
                views.setTextViewText(R.id.widget_note_title, "🔒 " + ctx.getString(R.string.locked_note))
                views.setTextViewText(R.id.widget_note_body, "")
            } else {
                val (_, content) = NoteMeta.parse(raw)
                val (title, body) = NoteStore.split(content)
                views.setTextViewText(
                    R.id.widget_note_title,
                    title.ifBlank { ctx.getString(R.string.widget_empty_note) }
                )
                views.setTextViewText(R.id.widget_note_body, Markdown.plain(body.trim().take(1200)))
            }
            views.setOnClickPendingIntent(
                R.id.widget_note_root,
                activity(
                    ctx, 1000 + widgetId,
                    Intent(ctx, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, noteId)
                )
            )
        }
        manager.updateAppWidget(widgetId, views)
    }

    /** Redraws every note widget, for example after a note was saved. */
    private fun refreshNow(ctx: Context) {
        try {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(ctx, NoteWidget::class.java))
            for (id in ids) updateNote(ctx, manager, id)
        } catch (e: Exception) {
            // Widgets are a convenience; never let them break saving.
        }
    }

    /** Points widgets at a note's new id after it was renamed, moved or locked. */
    fun followNewId(ctx: Context, oldId: String, newId: String) {
        if (oldId == newId) return
        val p = prefs(ctx)
        val edit = p.edit()
        for ((key, value) in p.all) {
            if (value == oldId) edit.putString(key, newId)
        }
        edit.apply()
        refreshNotes(ctx)
    }
}

/** The 4×1 bar with OMNINOTE, + NOTE and ☐ LIST. */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) Widgets.updateQuick(context, appWidgetManager, id)
    }
}

/** Shows one chosen note on the home screen. */
class NoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Reading notes can be slow on a synced folder, so draw the widgets off the main thread.
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                for (id in appWidgetIds) Widgets.updateNote(app, appWidgetManager, id)
            } catch (e: Exception) {
                // Drawn again on the next update.
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) Widgets.remove(context, id)
    }
}

/** Asks which note a new note widget should show. */
class NoteWidgetConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        Thread {
            val notes = try {
                NoteStore(this).list().filter { !it.meta.vault && !it.isConflict }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (!isDestroyed) showPicker(notes)
            }
        }.start()
    }

    private fun showPicker(notes: List<Note>) {
        if (notes.isEmpty()) {
            Toast.makeText(this, R.string.empty_filtered, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val labels = notes.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.widget_pick)
            .setItems(labels) { _, which -> choose(notes[which]) }
            .setOnCancelListener { finish() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun choose(note: Note) {
        Widgets.setNote(this, widgetId, note.id)
        Widgets.refreshNotes(this)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
