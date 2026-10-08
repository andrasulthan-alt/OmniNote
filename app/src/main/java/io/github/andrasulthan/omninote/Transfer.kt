package io.github.andrasulthan.omninote

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One place that holds files: OmniNote's own storage (tree == null) or a folder the user picked. */
class NotesRoot(private val ctx: Context, private val tree: Uri?) {

    private val localDir: File = File(ctx.filesDir, "notes").apply { mkdirs() }

    private class Entry(val id: String, val name: String, val isDir: Boolean)

    /** Relative paths of every file, like "Work/Plan.md". */
    fun files(recursive: Boolean = true): List<String> {
        val out = ArrayList<String>()
        val t = tree
        if (t == null) {
            walkLocal(localDir, "", out, recursive)
        } else {
            walkTree(DocumentsContract.getTreeDocumentId(t), "", out, recursive, 0)
        }
        return out
    }

    private fun walkLocal(dir: File, path: String, out: MutableList<String>, recursive: Boolean) {
        val list = dir.listFiles() ?: return
        for (f in list) {
            val rel = join(path, f.name)
            if (f.isDirectory) {
                if (recursive) walkLocal(f, rel, out, true)
            } else {
                out.add(rel)
            }
        }
    }

    private fun walkTree(docId: String, path: String, out: MutableList<String>, recursive: Boolean, depth: Int) {
        if (depth > 10) return
        for (c in children(docId)) {
            val rel = join(path, c.name)
            if (c.isDir) {
                if (recursive) walkTree(c.id, rel, out, true, depth + 1)
            } else {
                out.add(rel)
            }
        }
    }

    private fun children(parentId: String): List<Entry> {
        val t = tree ?: return emptyList()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(t, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val out = ArrayList<Entry>()
        val cursor = ctx.contentResolver.query(uri, projection, null, null, null) ?: return out
        cursor.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Entry(
                        c.getString(0),
                        c.getString(1) ?: "",
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                )
            }
        }
        return out
    }

    /** Document id of a folder path, created when asked. */
    private fun dirId(parts: List<String>, create: Boolean): String? {
        val t = tree ?: return null
        var current = DocumentsContract.getTreeDocumentId(t)
        for (part in parts) {
            val next = children(current).firstOrNull { it.isDir && it.name == part }
            current = when {
                next != null -> next.id
                create -> {
                    val made = DocumentsContract.createDocument(
                        ctx.contentResolver,
                        DocumentsContract.buildDocumentUriUsingTree(t, current),
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        part
                    ) ?: return null
                    DocumentsContract.getDocumentId(made)
                }
                else -> return null
            }
        }
        return current
    }

    private fun fileUri(rel: String): Uri? {
        val t = tree ?: return null
        val parts = rel.split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val dir = dirId(parts.dropLast(1), false) ?: return null
        val doc = children(dir).firstOrNull { !it.isDir && it.name == parts.last() } ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(t, doc.id)
    }

    fun exists(rel: String): Boolean =
        if (tree == null) File(localDir, rel).exists() else fileUri(rel) != null

    fun open(rel: String): InputStream? =
        if (tree == null) {
            File(localDir, rel).takeIf { it.isFile }?.inputStream()
        } else {
            fileUri(rel)?.let { ctx.contentResolver.openInputStream(it) }
        }

    /** Opens a file for writing, creating folders when needed and replacing an existing file. */
    fun openWrite(rel: String): OutputStream {
        val t = tree
        if (t == null) {
            val f = File(localDir, rel)
            f.parentFile?.mkdirs()
            return f.outputStream()
        }
        val parts = rel.split("/").filter { it.isNotEmpty() }
        val dir = dirId(parts.dropLast(1), true) ?: throw IllegalStateException("Cannot open folder for $rel")
        val existing = children(dir).firstOrNull { !it.isDir && it.name == parts.last() }
        val uri = if (existing != null) {
            DocumentsContract.buildDocumentUriUsingTree(t, existing.id)
        } else {
            DocumentsContract.createDocument(
                ctx.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(t, dir),
                "application/octet-stream",
                parts.last()
            ) ?: throw IllegalStateException("Cannot create $rel")
        }
        return ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Cannot write $rel")
    }

    fun write(rel: String, input: InputStream) {
        openWrite(rel).use { input.copyTo(it) }
    }

    fun writeText(rel: String, text: String) {
        openWrite(rel).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    fun delete(rel: String) {
        if (tree == null) {
            File(localDir, rel).delete()
        } else {
            fileUri(rel)?.let { DocumentsContract.deleteDocument(ctx.contentResolver, it) }
        }
    }

    /** "a/Note.md", or "a/Note (2).md" when that name is taken. */
    fun freePath(rel: String, separator: String = " (", closing: String = ")"): String {
        if (!exists(rel)) return rel
        val slash = rel.lastIndexOf('/')
        val dot = rel.lastIndexOf('.')
        val hasExt = dot > slash + 1
        val base = if (hasExt) rel.substring(0, dot) else rel
        val ext = if (hasExt) rel.substring(dot) else ""
        var n = 2
        while (exists(base + separator + n + closing + ext)) n++
        return base + separator + n + closing + ext
    }

    private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
}

/** Import, export, copying between folders and automatic backups. */
object Transfer {

    class Result(val notes: Int, val failed: Int)

    private const val KEY_BACKUP_TREE = "backup_tree"
    private const val KEY_LAST_BACKUP = "last_backup"
    private const val DAY = 24 * 60 * 60_000L
    private const val KEEP_BACKUPS = 7
    private const val BACKUP_PREFIX = "OmniNote-backup-"
    private const val SCARLET_SEPARATOR = "----------------------------------------"
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp")
    private val TEXT_EXT = setOf("md", "markdown", "txt")
    private val LIST_TYPES = setOf(
        "NUMBERED_LIST", "BULLET_1", "BULLET_2", "BULLET_3", "CHECKLIST_UNCHECKED", "CHECKLIST_CHECKED"
    )
    private val IMAGE_LINK = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
    private val WIKI_IMAGE = Regex("!\\[\\[([^\\]|]+)(\\|[^\\]]*)?\\]\\]")
    private val SCARLET_TASK = Regex("(?m)^\\[([ xX])\\] ")
    private val SCARLET_IMAGE_TAG = Regex("<image>[^<]*</image>")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("omninote", Context.MODE_PRIVATE)

    fun currentRoot(ctx: Context): NotesRoot = NotesRoot(ctx, NoteStore(ctx).treeUri)

    /** Number of notes (outside the trash) in a location. */
    fun countNotes(root: NotesRoot): Int =
        root.files().count { it.endsWith(".md") && !it.startsWith(".") }

    // ---------- Export ----------

    /** Writes every file of the current notes location into one ZIP. Returns the number of notes. */
    fun exportZip(ctx: Context, out: OutputStream): Int {
        val root = currentRoot(ctx)
        var count = 0
        ZipOutputStream(out).use { zip ->
            for (rel in root.files()) {
                if (rel.lowercase().endsWith(".zip")) continue
                val input = root.open(rel) ?: continue
                zip.putNextEntry(ZipEntry(rel))
                input.use { it.copyTo(zip) }
                zip.closeEntry()
                if (rel.endsWith(".md")) count++
            }
        }
        return count
    }

    // ---------- Copy between locations ----------

    /** Copies every file that the target does not have yet. Returns the number of files copied. */
    fun copyAll(from: NotesRoot, to: NotesRoot): Int {
        var count = 0
        for (rel in from.files()) {
            if (to.exists(rel)) continue
            val input = from.open(rel) ?: continue
            input.use { to.write(rel, it) }
            count++
        }
        return count
    }

    // ---------- Import ----------

    /** Imports ZIP archives, Markdown or text files and Scarlet Notes backups. */
    fun importUris(ctx: Context, uris: List<Uri>): Result {
        var notes = 0
        var failed = 0
        for (uri in uris) {
            try {
                val name = displayName(ctx, uri) ?: "Imported.md"
                val ext = name.substringAfterLast('.', "").lowercase()
                val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("No input")
                notes += input.use { stream ->
                    if (ext == "zip") {
                        importZip(ctx, stream)
                    } else {
                        importText(ctx, name, stream.readBytes().toString(Charsets.UTF_8))
                    }
                }
            } catch (e: Exception) {
                failed++
            }
        }
        return Result(notes, failed)
    }

    private fun isScarletJson(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("{") && t.contains("\"notes\"")
    }

    private fun importText(ctx: Context, fileName: String, text: String): Int {
        val trimmed = text.trimStart()
        if (isScarletJson(trimmed)) return importScarlet(ctx, trimmed)
        if (trimmed.startsWith(SCARLET_SEPARATOR)) return importScarletMarkdown(ctx, trimmed)
        if (text.isBlank()) return 0
        val root = currentRoot(ctx)
        val target = root.freePath(NoteStore.fileNameFor(fileName.substringBeforeLast('.')))
        root.writeText(target, text)
        return 1
    }

    private fun importZip(ctx: Context, input: InputStream): Int {
        val root = currentRoot(ctx)
        val texts = ArrayList<Pair<String, String>>()
        val images = HashMap<String, String>()
        val drawingData = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val rel = NoteStore.cleanPath(entry.name.replace('\\', '/'))
                    val name = rel.substringAfterLast('/')
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val junk = rel.isBlank() || rel.startsWith("__MACOSX") || name.startsWith("._")
                    when {
                        junk -> Unit
                        rel == ".omninote/vault.txt" -> if (!root.exists(rel)) root.write(rel, zip)
                        ext == "json" && name.startsWith("drawing-") -> drawingData[name] = zip.readBytes()
                        ext in IMAGE_EXT -> {
                            val safe = name.replace(Regex("[\\s()\\[\\]]+"), "-")
                            val target = root.freePath("attachments/$safe", "-", "")
                            root.write(target, zip)
                            images[name] = target
                            images[safe] = target
                        }
                        ext in TEXT_EXT -> texts.add(rel to zip.readBytes().toString(Charsets.UTF_8))
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        // Stroke data of drawings goes next to the image it belongs to, even if that image was renamed.
        for ((name, bytes) in drawingData) {
            val png = images[name.removeSuffix(".json") + ".png"] ?: continue
            root.write(DrawActivity.jsonFor(png), bytes.inputStream())
        }
        var count = 0
        for ((rel, text) in texts) {
            if (isScarletJson(text)) {
                count += importScarlet(ctx, text.trimStart())
                continue
            }
            if (text.isBlank()) continue
            val target = if (rel.lowercase().endsWith(".md")) rel else rel.substringBeforeLast('.') + ".md"
            root.writeText(root.freePath(target), relinkImages(text, images))
            count++
        }
        return count
    }

    /** Points image links at the copies placed in the attachments folder. */
    private fun relinkImages(text: String, images: Map<String, String>): String {
        if (images.isEmpty()) return text
        val step = IMAGE_LINK.replace(text) { m ->
            val target = m.groupValues[2]
            val raw = target.substringAfterLast('/')
            val name = try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (e: Exception) {
                raw
            }
            val found = images[name] ?: images[raw]
            if (found != null) "![${m.groupValues[1]}]($found)" else m.value
        }
        return WIKI_IMAGE.replace(step) { m ->
            val name = m.groupValues[1].trim().substringAfterLast('/')
            val found = images[name]
            if (found != null) "![]($found)" else m.value
        }
    }

    // ---------- Scarlet Notes ----------

    private fun importScarlet(ctx: Context, json: String): Int {
        val data = JSONObject(json)
        val folders = HashMap<String, String>()
        data.optJSONArray("folders")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                folders[f.optString("uuid")] = f.optString("title")
            }
        }
        val tags = HashMap<String, String>()
        data.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                tags[t.optString("uuid")] = t.optString("title")
            }
        }
        val notes = data.optJSONArray("notes") ?: return 0
        val store = NoteStore(ctx)
        val imageNote = ctx.getString(R.string.import_scarlet_image)
        var count = 0
        for (i in 0 until notes.length()) {
            val n = notes.optJSONObject(i) ?: continue
            val state = n.optString("state", "DEFAULT")
            if (state == "TRASH") continue
            val (title, body) = scarletContent(n.optString("description", ""), imageNote)
            if (title.isBlank() && body.isBlank()) continue
            val meta = NoteMeta(
                created = n.optLong("timestamp", 0L),
                color = scarletColor(n.optInt("color", 0)),
                tags = n.optString("tags", "").split(",")
                    .mapNotNull { tags[it.trim()] }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() },
                pinned = n.optBoolean("pinned", false),
                archived = state == "ARCHIVED"
            )
            val folder = NoteStore.cleanPath(folders[n.optString("folder", "")] ?: "")
            val hint = title.ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: "" }
            store.save(null, hint, NoteMeta.build(meta, NoteStore.join(title, body)), folder)
            count++
        }
        return count
    }

    /** Turns Scarlet's list of blocks into a title and Markdown text. Images become a marker. */
    private fun scarletContent(description: String, imageNote: String): Pair<String, String> {
        val array = try {
            JSONObject(description).getJSONArray("note")
        } catch (e: Exception) {
            return "" to description
        }
        var title = ""
        val out = StringBuilder()
        var previousList = false
        for (i in 0 until array.length()) {
            val f = array.optJSONObject(i) ?: continue
            val type = f.optString("format")
            val text = f.optString("text")
            if (i == 0 && type == "HEADING") {
                title = text.trim()
                continue
            }
            val line = when (type) {
                "HEADING" -> "# $text"
                "SUB_HEADING" -> "## $text"
                "HEADING_3" -> "### $text"
                "NUMBERED_LIST", "BULLET_1" -> "- $text"
                "BULLET_2" -> "  - $text"
                "BULLET_3" -> "    - $text"
                "CHECKLIST_UNCHECKED" -> "- [ ] $text"
                "CHECKLIST_CHECKED" -> "- [x] $text"
                "CODE" -> "```\n$text\n```"
                "QUOTE" -> text.lines().joinToString("\n") { "> $it" }
                "SEPARATOR" -> "---"
                "IMAGE" -> "> $imageNote"
                "TAG" -> null
                else -> text
            } ?: continue
            val isList = type in LIST_TYPES
            if (out.isNotEmpty()) out.append(if (isList && previousList) "\n" else "\n\n")
            out.append(line)
            previousList = isList
        }
        return title to out.toString()
    }

    /** Scarlet's "export as Markdown" backup: notes separated by a line of dashes. */
    private fun importScarletMarkdown(ctx: Context, text: String): Int {
        val store = NoteStore(ctx)
        val imageNote = ctx.getString(R.string.import_scarlet_image)
        var count = 0
        for (chunk in text.split(SCARLET_SEPARATOR)) {
            val withTasks = SCARLET_TASK.replace(chunk.trim()) { m -> "- [${m.groupValues[1]}] " }
            val note = SCARLET_IMAGE_TAG.replace(withTasks) { "> $imageNote" }
            if (note.isBlank()) continue
            val (title, body) = NoteStore.split(note)
            val hint = title.ifBlank { body.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: "" }
            store.save(null, hint, NoteMeta.build(NoteMeta(created = System.currentTimeMillis()), note), "")
            count++
        }
        return count
    }

    /** Picks the closest OmniNote colour for a Scarlet colour; greys become no colour. */
    private fun scarletColor(argb: Int): String? {
        if (argb == 0 || Color.alpha(argb) == 0) return null
        val hsv = FloatArray(3)
        Color.colorToHSV(argb, hsv)
        if (hsv[1] < 0.2f || hsv[2] < 0.2f) return null
        val hue = hsv[0]
        val index = when {
            hue < 15f || hue >= 345f -> 0
            hue < 45f -> 1
            hue < 70f -> 2
            hue < 170f -> 3
            hue < 260f -> 4
            else -> 5
        }
        return NoteMeta.COLORS.getOrNull(index)
    }

    // ---------- Automatic backup ----------

    fun backupTree(ctx: Context): Uri? = prefs(ctx).getString(KEY_BACKUP_TREE, null)?.let { Uri.parse(it) }

    fun setBackupTree(ctx: Context, uri: Uri?) {
        prefs(ctx).edit().putString(KEY_BACKUP_TREE, uri?.toString()).apply()
    }

    fun lastBackup(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_BACKUP, 0L)

    /** Writes a dated ZIP into the backup folder and keeps only the newest ones. */
    fun backupNow(ctx: Context): Boolean {
        val tree = backupTree(ctx) ?: return false
        return try {
            val dest = NotesRoot(ctx, tree)
            val name = BACKUP_PREFIX + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"
            exportZip(ctx, dest.openWrite(dest.freePath(name, "-", "")))
            dest.files(recursive = false)
                .filter { it.startsWith(BACKUP_PREFIX) && it.endsWith(".zip") }
                .sorted()
                .dropLast(KEEP_BACKUPS)
                .forEach { dest.delete(it) }
            prefs(ctx).edit().putLong(KEY_LAST_BACKUP, System.currentTimeMillis()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Makes a backup in the background when the last one is more than a day old. */
    fun maybeAutoBackup(ctx: Context) {
        if (backupTree(ctx) == null) return
        if (System.currentTimeMillis() - lastBackup(ctx) < DAY) return
        if (!backupRunning.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            try {
                backupNow(app)
            } catch (e: Exception) {
                // Tried again next time the app opens.
            } finally {
                backupRunning.set(false)
            }
        }.start()
    }

    private val backupRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun displayName(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
