package io.github.andrasulthan.omninote

import android.content.Context
import java.io.File

/**
 * Version history (an idea from Notesnook). Older versions of a note are kept on this
 * phone only, so they never fill the synced folder. Locked notes stay encrypted here too.
 */
object History {

    private const val MAX_VERSIONS = 30
    private const val MIN_GAP = 2 * 60_000L

    class Version(val time: Long, val file: File)

    private fun dir(ctx: Context, key: String): File =
        File(File(ctx.filesDir, "history"), key).apply { mkdirs() }

    /** A key that stays the same when a note is renamed or moved, when possible. */
    fun keyFor(id: String, meta: NoteMeta): String =
        if (meta.created > 0) "c" + meta.created else "i" + Integer.toHexString(id.hashCode())

    /** Keeps the previous text of a note before it is overwritten (at most every 2 minutes). */
    fun record(ctx: Context, key: String, previous: String) {
        if (previous.isBlank()) return
        try {
            val folder = dir(ctx, key)
            val files = (folder.listFiles() ?: emptyArray()).sortedByDescending { it.name }
            val now = System.currentTimeMillis()
            val newest = files.firstOrNull()
            if (newest != null) {
                val time = newest.name.removeSuffix(".md").toLongOrNull() ?: 0L
                if (now - time < MIN_GAP) return
                if (newest.readText() == previous) return
            }
            File(folder, "$now.md").writeText(previous)
            files.drop(MAX_VERSIONS - 1).forEach { it.delete() }
        } catch (e: Exception) {
            // History is a safety net; saving the note itself matters more.
        }
    }

    fun versions(ctx: Context, key: String): List<Version> =
        (dir(ctx, key).listFiles() ?: emptyArray())
            .mapNotNull { f -> f.name.removeSuffix(".md").toLongOrNull()?.let { Version(it, f) } }
            .sortedByDescending { it.time }

    fun read(version: Version): String = version.file.readText()
}
