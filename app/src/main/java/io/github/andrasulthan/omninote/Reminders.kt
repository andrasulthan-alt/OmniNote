package io.github.andrasulthan.omninote

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Reminders and notes shown in the notification panel (an idea from Scarlet Notes).
 * Uses the normal alarm, so no "exact alarm" permission is needed.
 * Locked notes never show their text in a notification.
 */
object Reminders {

    private const val CHANNEL_REMIND = "reminders"
    private const val CHANNEL_PINNED = "pinned_notes"
    private const val KEY_PINNED = "notify_pinned"
    const val EXTRA_ID = "reminder_note_id"
    const val EXTRA_TITLE = "reminder_note_title"

    private fun manager(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels(ctx: Context) {
        val nm = manager(ctx)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMIND,
                ctx.getString(R.string.channel_reminders),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PINNED,
                ctx.getString(R.string.channel_pinned),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    /** True when the app is allowed to show notifications. */
    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun code(id: String): Int = id.hashCode()

    private fun openNote(ctx: Context, id: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_ID, id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            ctx, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun alarmIntent(ctx: Context, id: String, title: String): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_TITLE, title)
        }
        return PendingIntent.getBroadcast(
            ctx, code(id), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---------- Reminders ----------

    fun schedule(ctx: Context, id: String, title: String, at: Long) {
        val alarms = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx, id, title))
    }

    fun cancel(ctx: Context, id: String) {
        val alarms = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(alarmIntent(ctx, id, ""))
    }

    fun showReminder(ctx: Context, id: String, title: String, text: String) {
        if (!canNotify(ctx)) return
        ensureChannels(ctx)
        val builder = Notification.Builder(ctx, CHANNEL_REMIND)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setColor(Ui.RED)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openNote(ctx, id, code(id)))
            .setAutoCancel(true)
        if (text.isNotBlank()) {
            builder.setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
        }
        manager(ctx).notify(code(id), builder.build())
    }

    // ---------- Notes shown in the notification panel ----------

    fun pinnedIds(ctx: Context): Set<String> =
        ctx.getSharedPreferences("omninote", Context.MODE_PRIVATE)
            .getStringSet(KEY_PINNED, emptySet())?.toSet() ?: emptySet()

    private fun savePinned(ctx: Context, ids: Set<String>) {
        ctx.getSharedPreferences("omninote", Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PINNED, HashSet(ids)).apply()
    }

    fun isPinned(ctx: Context, id: String): Boolean = id in pinnedIds(ctx)

    fun pinNotification(ctx: Context, id: String, title: String, text: String) {
        savePinned(ctx, pinnedIds(ctx) + id)
        if (!canNotify(ctx)) return
        ensureChannels(ctx)
        val notification = Notification.Builder(ctx, CHANNEL_PINNED)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(Ui.RED)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openNote(ctx, id, code(id) + 1))
            .build()
        manager(ctx).notify(code(id) + 1, notification)
    }

    fun unpinNotification(ctx: Context, id: String) {
        savePinned(ctx, pinnedIds(ctx) - id)
        manager(ctx).cancel(code(id) + 1)
    }

    /** Sets every future reminder and pinned note again (after a restart or update). */
    fun restoreAll(ctx: Context) {
        val notes = try {
            NoteStore(ctx).list()
        } catch (e: Exception) {
            return
        }
        val now = System.currentTimeMillis()
        val pinned = pinnedIds(ctx)
        for (note in notes) {
            val title = if (note.meta.vault) ctx.getString(R.string.locked_note) else note.title
            if (note.meta.remind > now) schedule(ctx, note.id, title, note.meta.remind)
            if (note.id in pinned) {
                if (note.meta.vault) {
                    unpinNotification(ctx, note.id)
                } else {
                    pinNotification(ctx, note.id, note.title, Markdown.plain(note.preview))
                }
            }
        }
    }
}

/** Shows a reminder when its time comes, then clears it from the note. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        val title = intent.getStringExtra(Reminders.EXTRA_TITLE) ?: ""
        val pending = goAsync()
        Thread {
            try {
                val store = NoteStore(context)
                val raw = try {
                    store.read(id)
                } catch (e: Exception) {
                    ""
                }
                val isLocked = Vault.isLocked(raw)
                val text = if (isLocked) {
                    ""
                } else {
                    val (_, content) = NoteMeta.parse(raw)
                    Markdown.plain(NoteStore.split(content).second.trim().take(300))
                }
                val shownTitle = when {
                    isLocked -> "🔒 " + context.getString(R.string.locked_note)
                    title.isNotBlank() -> title
                    else -> context.getString(R.string.app_name)
                }
                Reminders.showReminder(context, id, shownTitle, text)
                if (!isLocked) {
                    try {
                        store.updateMeta(id) { it.copy(remind = 0L) }
                    } catch (e: Exception) {
                        // The note may have moved; the reminder was still shown.
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Puts reminders and pinned notes back after the phone restarts or the app updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                Reminders.restoreAll(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
