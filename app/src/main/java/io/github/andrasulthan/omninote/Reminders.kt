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
 * Reminders ring on time with an exact alarm when Android allows it, otherwise as close as it can.
 * Locked notes never show their text in a notification.
 */
object Reminders {

    private const val CHANNEL_REMIND = "reminders"
    private const val CHANNEL_PINNED = "pinned_notes"
    private const val KEY_PINNED = "notify_pinned"
    const val EXTRA_ID = "reminder_note_id"
    const val EXTRA_TITLE = "reminder_note_title"
    const val ACTION_UNPIN = "io.github.andrasulthan.omninote.UNPIN"

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
        val operation = alarmIntent(ctx, id, title)
        val exact = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
        try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    fun cancel(ctx: Context, id: String) {
        val alarms = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.cancel(alarmIntent(ctx, id, ""))
    }

    /** Shows the reminder; returns false when notifications are turned off. */
    fun showReminder(ctx: Context, id: String, title: String, text: String): Boolean {
        if (!canNotify(ctx) || !manager(ctx).areNotificationsEnabled()) return false
        ensureChannels(ctx)
        val builder = Notification.Builder(ctx, CHANNEL_REMIND)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setColor(Ui.RED)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openNote(ctx, id, code(id)))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
        if (text.isNotBlank()) {
            builder.setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
        }
        manager(ctx).notify(code(id), builder.build())
        return true
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
            .setDeleteIntent(unpinIntent(ctx, id))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        manager(ctx).notify(code(id) + 1, notification)
    }

    /** Android 14 lets people swipe away ongoing notifications; then the note is no longer pinned. */
    private fun unpinIntent(ctx: Context, id: String): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = ACTION_UNPIN
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getBroadcast(
            ctx, code(id) + 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun forgetPinned(ctx: Context, id: String) {
        savePinned(ctx, pinnedIds(ctx) - id)
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
            // Future reminders are set again; ones missed while the phone was off ring right away.
            if (note.meta.remind > now) {
                schedule(ctx, note.id, title, note.meta.remind)
            } else if (note.meta.remind > 0 && !note.meta.vault) {
                schedule(ctx, note.id, title, now + 5_000)
            }
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
        if (intent.action == Reminders.ACTION_UNPIN) {
            Reminders.forgetPinned(context, id)
            return
        }
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
                val shown = Reminders.showReminder(context, id, shownTitle, text)
                // Keep the reminder in the note when it could not be shown, so it is not lost.
                if (shown && !isLocked) {
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
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
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
