package io.github.andrasulthan.omninote

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Note properties kept as YAML front matter at the top of the Markdown file,
 * the same way Obsidian stores them, so other apps keep working with the files.
 * Ideas from Scarlet Notes (pin, color, tags, reminders, locked notes)
 * and Notesnook (archive, trash, vault).
 */
data class NoteMeta(
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val color: String? = null,
    val tags: List<String> = emptyList(),
    val created: Long = 0L,
    val trashedFrom: String? = null,
    val remind: Long = 0L,
    val vault: Boolean = false,
    val extra: List<String> = emptyList()
) {
    companion object {
        private val CLOSING_FENCE = Regex("\n---[ \t]*(?=\n|$)")
        private fun isTrue(value: String): Boolean =
            unquote(value).lowercase() in setOf("true", "yes", "on", "1")

        val COLORS = listOf("red", "orange", "yellow", "green", "blue", "purple", "gray")

        fun colorValue(name: String?): Int? = when (name) {
            "red" -> 0xFFD71921.toInt()
            "orange" -> 0xFFF2994A.toInt()
            "yellow" -> 0xFFF2C94C.toInt()
            "green" -> 0xFF27AE60.toInt()
            "blue" -> 0xFF2F80ED.toInt()
            "purple" -> 0xFF9B51E0.toInt()
            "gray" -> 0xFF8A8A8A.toInt()
            else -> null
        }

        /** Splits a file into its properties and the Markdown content after them. */
        fun parse(text: String): Pair<NoteMeta, String> {
            val t = text.replace("\r\n", "\n")
            if (!t.startsWith("---\n")) return NoteMeta() to t
            // The closing fence must be a whole "---" line; an empty block ("---\n---") is allowed.
            val fence = CLOSING_FENCE.find(t, 3) ?: return NoteMeta() to t
            val end = fence.range.first
            val afterFence = (fence.range.last + 1).let { if (it < t.length && t[it] == '\n') it + 1 else it }
            val block = if (end > 4) t.substring(4, end) else ""

            var pinned = false
            var archived = false
            var color: String? = null
            val tags = ArrayList<String>()
            var created = 0L
            var trashedFrom: String? = null
            var remind = 0L
            var vault = false
            val extra = ArrayList<String>()
            var inTags = false

            for (line in block.split("\n")) {
                if (inTags) {
                    val item = line.trim()
                    if (item.startsWith("- ")) {
                        tags.add(cleanTag(item.removePrefix("- ")))
                        continue
                    }
                    inTags = false
                }
                val colon = line.indexOf(':')
                val key = if (colon > 0) line.substring(0, colon).trim().lowercase() else ""
                val value = if (colon > 0) line.substring(colon + 1).trim() else ""
                when (key) {
                    "pinned" -> pinned = isTrue(value)
                    "archived" -> archived = isTrue(value)
                    "color" -> color = unquote(value).lowercase().ifBlank { null }
                    "tags" -> if (value.isEmpty()) inTags = true else tags.addAll(splitList(value))
                    "created" -> created = parseTime(unquote(value))
                    "trashed_from" -> trashedFrom = unquote(value).ifBlank { null }
                    "remind" -> remind = parseTime(unquote(value))
                    "vault" -> vault = value.equals("true", ignoreCase = true)
                    else -> if (line.isNotBlank()) extra.add(line)
                }
            }
            val meta = NoteMeta(
                pinned = pinned,
                archived = archived,
                color = color,
                tags = tags.filter { it.isNotBlank() }.distinct(),
                created = created,
                trashedFrom = trashedFrom,
                remind = remind,
                vault = vault,
                extra = extra
            )
            return meta to t.substring(afterFence)
        }

        /** Puts the properties back on top of the Markdown content. */
        fun build(meta: NoteMeta, content: String): String {
            val lines = ArrayList<String>()
            if (meta.vault) lines.add("vault: true")
            if (meta.created > 0) lines.add("created: " + formatTime(meta.created))
            if (meta.pinned) lines.add("pinned: true")
            if (meta.archived) lines.add("archived: true")
            meta.color?.let { lines.add("color: $it") }
            if (meta.tags.isNotEmpty()) lines.add("tags: [" + meta.tags.joinToString(", ") + "]")
            if (meta.remind > 0) lines.add("remind: " + formatTime(meta.remind))
            meta.trashedFrom?.let { lines.add("trashed_from: \"$it\"") }
            lines.addAll(meta.extra)
            if (lines.isEmpty()) return content
            return "---\n" + lines.joinToString("\n") + "\n---\n" + content
        }

        fun splitList(value: String): List<String> =
            value.trim().removePrefix("[").removeSuffix("]")
                .split(",")
                .map { cleanTag(it) }
                .filter { it.isNotBlank() }

        private fun cleanTag(value: String): String =
            unquote(value).removePrefix("#").trim()

        private fun unquote(value: String): String =
            value.trim().removeSurrounding("\"").removeSurrounding("'").trim()

        private fun formatter(pattern: String): SimpleDateFormat =
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun formatTime(millis: Long): String =
            formatter("yyyy-MM-dd'T'HH:mm:ss'Z'").format(java.util.Date(millis))

        fun parseTime(value: String): Long {
            val v = value.trim()
            if (v.isEmpty()) return 0L
            v.toLongOrNull()?.let { n -> return if (n in 1..99_999_999_999L) n * 1000 else n }
            val position = java.text.ParsePosition(0)
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
                position.index = 0
                position.errorIndex = -1
                val date = try { formatter(pattern).parse(v, position) } catch (e: Exception) { null }
                if (date != null && position.index == v.length) return date.time
            }
            // Times without a zone are local, like in Obsidian and most editors.
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
                position.index = 0
                position.errorIndex = -1
                val local = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val date = try { local.parse(v, position) } catch (e: Exception) { null }
                if (date != null && position.index == v.length) return date.time
            }
            return 0L
        }
    }
}
