package io.github.andrasulthan.omninote

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Note(
    val id: String,
    val title: String,
    val preview: String,
    val modified: Long,
    val folder: String = "",
    val meta: NoteMeta = NoteMeta(),
    val body: String = "",
    val fileName: String = ""
) {
    /** Creation time when known, otherwise the last change. */
    val created: Long get() = if (meta.created > 0) meta.created else modified

    /** True for the extra copy Syncthing makes when a note changed on two devices. */
    val isConflict: Boolean get() = NoteStore.isConflictName(fileName)
}

/**
 * Notes are plain Markdown files, either in app storage or in a folder the user
 * picks (so tools like Syncthing or Obsidian can read them too).
 * Notebooks are sub-folders, deleted notes go to ".trash", images to "attachments",
 * and shared settings (like the vault check) to ".omninote".
 */
class NoteStore(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("omninote", Context.MODE_PRIVATE)

    // ---------- Settings ----------

    var treeUri: Uri?
        get() = prefs.getString(KEY_TREE, null)?.let { Uri.parse(it) }
        set(value) {
            prefs.edit().putString(KEY_TREE, value?.toString()).apply()
        }

    /** Lines of preview on the home screen: 0 (title only) to 3. */
    var previewLines: Int
        get() = prefs.getInt(KEY_PREVIEW, 2)
        set(value) {
            prefs.edit().putInt(KEY_PREVIEW, value.coerceIn(0, 3)).apply()
        }

    /** Note text size: 0 small, 1 normal, 2 large, 3 extra large. */
    var fontLevel: Int
        get() = prefs.getInt(KEY_FONT, 1)
        set(value) {
            prefs.edit().putInt(KEY_FONT, value.coerceIn(0, 3)).apply()
        }

    /** 0 last modified, 1 newest created, 2 oldest created, 3 title A to Z. */
    var sortMode: Int
        get() = prefs.getInt(KEY_SORT, 0)
        set(value) {
            prefs.edit().putInt(KEY_SORT, value.coerceIn(0, 3)).apply()
        }

    var gridLayout: Boolean
        get() = prefs.getBoolean(KEY_GRID, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GRID, value).apply()
        }

    /** Colour given to new notes, or null for none. */
    var defaultColor: String?
        get() = prefs.getString(KEY_COLOR, null)
        set(value) {
            prefs.edit().putString(KEY_COLOR, value).apply()
        }

    fun fontScale(): Float = when (fontLevel) {
        0 -> 0.875f
        2 -> 1.15f
        3 -> 1.3f
        else -> 1f
    }

    private val localDir: File
        get() = File(ctx.filesDir, "notes").apply { mkdirs() }

    /** Readable name of the chosen folder, or null when notes live on this phone only. */
    fun folderName(): String? {
        val tree = treeUri ?: return null
        val docId = DocumentsContract.getTreeDocumentId(tree)
        return docId.substringAfterLast(':').ifBlank { docId }
    }

    // ---------- Listing ----------

    /** Every note outside the trash (archived ones included). */
    fun list(): List<Note> = collect(trash = false)

    fun listTrash(): List<Note> = collect(trash = true)

    fun findByTitle(title: String): Note? =
        list().firstOrNull {
            !it.meta.vault && !it.isConflict && it.title.equals(title.trim(), ignoreCase = true)
        }

    fun allTags(): List<String> =
        list().flatMap { it.meta.tags }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** All notebook paths, like "Work" and "Work/Ideas". */
    fun notebooks(): List<String> {
        val out = ArrayList<String>()
        val tree = treeUri
        if (tree == null) {
            walkLocalDirs(localDir, "", out)
        } else {
            walkTreeDirs(tree, DocumentsContract.getTreeDocumentId(tree), "", out, 0)
        }
        return out.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun createNotebook(path: String): Boolean {
        val clean = cleanPath(path)
        if (clean.isBlank()) return false
        val tree = treeUri
        return if (tree == null) {
            File(localDir, clean).mkdirs() || File(localDir, clean).isDirectory
        } else {
            folderDocId(tree, clean, create = true) != null
        }
    }

    private fun collect(trash: Boolean): List<Note> {
        val notes = ArrayList<Note>()
        val tree = treeUri
        if (tree == null) {
            if (trash) {
                val files = File(localDir, TRASH).listFiles() ?: emptyArray()
                for (f in files) {
                    if (f.isFile && isMd(f.name)) {
                        load(f.absolutePath, f.name, f.lastModified(), TRASH, notes) { f.readText() }
                    }
                }
            } else {
                walkLocal(localDir, "", notes)
            }
        } else {
            if (trash) {
                val trashId = folderDocId(tree, TRASH, create = false)
                if (trashId != null) {
                    for (d in children(tree, trashId)) {
                        if (!d.isDir && isMd(d.name)) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.id).toString()
                            load(uri, d.name, d.modified, TRASH, notes) { read(uri) }
                        }
                    }
                }
            } else {
                walkTree(tree, DocumentsContract.getTreeDocumentId(tree), "", notes, 0)
            }
        }
        return sort(notes)
    }

    private fun sort(notes: List<Note>): List<Note> {
        val byMode: Comparator<Note> = when (sortMode) {
            1 -> compareByDescending<Note> { it.created }
            2 -> compareBy<Note> { it.created }
            3 -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            else -> compareByDescending<Note> { it.modified }
        }
        return notes.sortedWith(compareByDescending<Note> { it.meta.pinned }.then(byMode))
    }

    private fun walkLocal(dir: File, path: String, out: MutableList<Note>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                if (!isSpecial(f.name)) walkLocal(f, joinPath(path, f.name), out)
            } else if (isMd(f.name)) {
                load(f.absolutePath, f.name, f.lastModified(), path, out) { f.readText() }
            }
        }
    }

    private fun walkLocalDirs(dir: File, path: String, out: MutableList<String>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory && !isSpecial(f.name)) {
                val p = joinPath(path, f.name)
                out.add(p)
                walkLocalDirs(f, p, out)
            }
        }
    }

    private fun walkTree(tree: Uri, docId: String, path: String, out: MutableList<Note>, depth: Int) {
        if (depth > MAX_DEPTH) return
        for (d in children(tree, docId)) {
            if (d.isDir) {
                if (!isSpecial(d.name)) walkTree(tree, d.id, joinPath(path, d.name), out, depth + 1)
            } else if (isMd(d.name)) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, d.id).toString()
                load(uri, d.name, d.modified, path, out) { read(uri) }
            }
        }
    }

    private fun walkTreeDirs(tree: Uri, docId: String, path: String, out: MutableList<String>, depth: Int) {
        if (depth > MAX_DEPTH) return
        for (d in children(tree, docId)) {
            if (d.isDir && !isSpecial(d.name)) {
                val p = joinPath(path, d.name)
                out.add(p)
                walkTreeDirs(tree, d.id, p, out, depth + 1)
            }
        }
    }

    // ---------- Sync conflicts ----------

    /** File name of a note, like "Shopping.md". */
    fun fileNameOf(id: String): String =
        if (id.startsWith(CONTENT)) displayName(Uri.parse(id)) ?: "" else File(id).name

    /** The note a Syncthing conflict copy belongs to, or null when it is not found. */
    fun findOriginal(conflictId: String): Note? {
        val name = fileNameOf(conflictId)
        if (!isConflictName(name)) return null
        val base = conflictBase(name)
        val folder = folderOf(conflictId)
        return list().firstOrNull { it.folder == folder && it.fileName == base && it.id != conflictId }
    }

    // ---------- Reading and writing ----------

    fun read(id: String): String {
        return if (id.startsWith(CONTENT)) {
            ctx.contentResolver.openInputStream(Uri.parse(id))?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: ""
        } else {
            val f = File(id)
            if (!f.exists()) throw java.io.FileNotFoundException(id)
            f.readText()
        }
    }

    /**
     * Writes the note and returns its id.
     * A new file is created (inside the given notebook) when id is null.
     */
    fun save(id: String?, title: String, text: String, folder: String = ""): String {
        val target = id ?: create(fileNameFor(title), cleanPath(folder))
        write(target, text)
        return target
    }

    /** Changes the properties of a note and keeps its text. */
    fun updateMeta(id: String, change: (NoteMeta) -> NoteMeta) {
        val (meta, content) = NoteMeta.parse(read(id))
        write(id, NoteMeta.build(change(meta), content))
    }

    private fun write(id: String, text: String) {
        if (id.startsWith(CONTENT)) {
            ctx.contentResolver.openOutputStream(Uri.parse(id), "wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
        } else {
            // Write to a temporary file first, so a crash never leaves a half-written note.
            val target = File(id)
            val temp = File(target.parentFile, "." + target.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.writeText(text)
                temp.delete()
            }
        }
        synchronized(cache) { cache.remove(id) }
    }

    /** Renames a note file to match a title and returns its new id. */
    fun rename(id: String, title: String): String {
        val name = fileNameFor(title)
        if (!id.startsWith(CONTENT)) {
            val src = File(id)
            val parent = src.parentFile ?: return id
            if (src.name == name) return id
            val dest = uniqueFile(parent, name)
            return if (src.renameTo(dest)) dest.absolutePath else id
        }
        return try {
            DocumentsContract.renameDocument(ctx.contentResolver, Uri.parse(id), name)?.toString() ?: id
        } catch (e: Exception) {
            id
        }
    }

    // ---------- Hidden settings files (shared through sync) ----------

    fun readHidden(name: String): String? {
        val tree = treeUri
        if (tree == null) {
            val f = File(File(localDir, HIDDEN), name)
            return if (f.exists()) f.readText() else null
        }
        val dirId = folderDocId(tree, HIDDEN, create = false) ?: return null
        val doc = children(tree, dirId).firstOrNull { !it.isDir && it.name == name } ?: return null
        return read(DocumentsContract.buildDocumentUriUsingTree(tree, doc.id).toString())
    }

    fun writeHidden(name: String, text: String) {
        val tree = treeUri
        if (tree == null) {
            val dir = File(localDir, HIDDEN).apply { mkdirs() }
            File(dir, name).writeText(text)
            return
        }
        val dirId = folderDocId(tree, HIDDEN, create = true)
            ?: throw IllegalStateException("No settings folder")
        val existing = children(tree, dirId).firstOrNull { !it.isDir && it.name == name }
        val uri = if (existing != null) {
            DocumentsContract.buildDocumentUriUsingTree(tree, existing.id)
        } else {
            DocumentsContract.createDocument(
                ctx.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, dirId),
                "application/octet-stream",
                name
            ) ?: throw IllegalStateException("Cannot create $name")
        }
        write(uri.toString(), text)
    }

    // ---------- Notebooks, trash and moving ----------

    /** Moves a note to another notebook ("" is the top level) and returns its new id. */
    fun move(id: String, folder: String): String {
        val target = cleanPath(folder)
        val tree = treeUri
        if (tree == null) {
            val src = File(id)
            val dir = File(localDir, target).apply { mkdirs() }
            if (src.parentFile?.canonicalPath == dir.canonicalPath) return id
            val dest = uniqueFile(dir, src.name)
            if (!src.renameTo(dest)) {
                dest.writeText(src.readText())
                src.delete()
            }
            return dest.absolutePath
        }
        val srcUri = Uri.parse(id)
        val targetId = folderDocId(tree, target, create = true)
            ?: throw IllegalStateException("Cannot open $target")
        val targetUri = DocumentsContract.buildDocumentUriUsingTree(tree, targetId)
        try {
            val path = DocumentsContract.findDocumentPath(ctx.contentResolver, srcUri)?.path
            if (path != null && path.size >= 2) {
                val parentId = path[path.size - 2]
                if (parentId == targetId) return id
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
                val moved = DocumentsContract.moveDocument(ctx.contentResolver, srcUri, parentUri, targetUri)
                if (moved != null) return moved.toString()
            }
        } catch (e: Exception) {
            // Some folders cannot move files; copy and delete below instead.
        }
        val name = displayName(srcUri) ?: fileNameFor("")
        val copy = DocumentsContract.createDocument(
            ctx.contentResolver, targetUri, "application/octet-stream", name
        ) ?: throw IllegalStateException("Cannot move $name")
        write(copy.toString(), read(id))
        DocumentsContract.deleteDocument(ctx.contentResolver, srcUri)
        return copy.toString()
    }

    /** Moves a note to the trash and remembers where it came from. Returns the new id. */
    fun delete(id: String): String {
        val from = folderOf(id)
        updateMeta(id) { it.copy(trashedFrom = from.ifBlank { null }) }
        return move(id, TRASH)
    }

    /** Puts a note from the trash back in its notebook. Returns the new id. */
    fun restore(id: String): String {
        val (meta, _) = NoteMeta.parse(read(id))
        val folder = meta.trashedFrom ?: ""
        updateMeta(id) { it.copy(trashedFrom = null) }
        return move(id, folder)
    }

    fun deleteForever(id: String) {
        if (id.startsWith(CONTENT)) {
            DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(id))
        } else {
            File(id).delete()
        }
    }

    fun emptyTrash() {
        for (note in listTrash()) deleteForever(note.id)
    }

    /** Notebook path of a note ("" for the top level). */
    fun folderOf(id: String): String {
        val tree = treeUri
        if (tree == null || !id.startsWith(CONTENT)) {
            val parent = File(id).parentFile ?: return ""
            return parent.canonicalPath
                .removePrefix(localDir.canonicalPath)
                .trim('/')
        }
        return try {
            val root = DocumentsContract.getTreeDocumentId(tree)
            val doc = DocumentsContract.getDocumentId(Uri.parse(id))
            if (doc.startsWith("$root/")) doc.removePrefix("$root/").substringBeforeLast('/', "") else ""
        } catch (e: Exception) {
            ""
        }
    }

    // ---------- Attachments ----------

    /** Copies an image into the attachments folder and returns its relative path. */
    fun saveAttachment(source: Uri): String {
        val mime = ctx.contentResolver.getType(source) ?: "image/jpeg"
        val ext = when {
            mime.endsWith("png") -> "png"
            mime.endsWith("webp") -> "webp"
            mime.endsWith("gif") -> "gif"
            else -> "jpg"
        }
        val name = "img-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + "." + ext
        val input = ctx.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("Cannot read image")
        input.use { inp ->
            val tree = treeUri
            if (tree == null) {
                val dir = File(localDir, ATTACH).apply { mkdirs() }
                File(dir, name).outputStream().use { inp.copyTo(it) }
            } else {
                val dirId = folderDocId(tree, ATTACH, create = true)
                    ?: throw IllegalStateException("No attachments folder")
                val dirUri = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
                val file = DocumentsContract.createDocument(
                    ctx.contentResolver, dirUri, "application/octet-stream", name
                ) ?: throw IllegalStateException("Cannot create $name")
                ctx.contentResolver.openOutputStream(file, "w")?.use { inp.copyTo(it) }
                // The folder may have renamed the file (for example "img (1).jpg"); link the real name.
                return "$ATTACH/" + (displayName(file) ?: name)
            }
        }
        return "$ATTACH/$name"
    }

    fun openAttachment(path: String): InputStream? {
        val name = path.substringAfterLast('/')
        if (name.isBlank() || name.contains("..")) return null
        val tree = treeUri
        if (tree == null) {
            val f = File(File(localDir, ATTACH), name)
            return if (f.exists()) f.inputStream() else null
        }
        val dirId = folderDocId(tree, ATTACH, create = false) ?: return null
        val doc = children(tree, dirId).firstOrNull { !it.isDir && it.name == name } ?: return null
        return ctx.contentResolver.openInputStream(
            DocumentsContract.buildDocumentUriUsingTree(tree, doc.id)
        )
    }

    // ---------- Folder helpers ----------

    private class Entry(val id: String, val name: String, val isDir: Boolean, val modified: Long)

    private fun children(tree: Uri, parentId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<Entry>()
        val cursor = ctx.contentResolver.query(uri, projection, null, null, null) ?: return out
        try {
            while (cursor.moveToNext()) {
                out.add(
                    Entry(
                        cursor.getString(0),
                        cursor.getString(1) ?: "",
                        cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        cursor.getLong(3)
                    )
                )
            }
        } finally {
            cursor.close()
        }
        return out
    }

    /** Document id of the folder at a relative path, created when asked. */
    private fun folderDocId(tree: Uri, path: String, create: Boolean): String? {
        var current = DocumentsContract.getTreeDocumentId(tree)
        for (part in path.split("/").filter { it.isNotBlank() }) {
            val next = children(tree, current).firstOrNull { it.isDir && it.name == part }
            current = when {
                next != null -> next.id
                create -> {
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, current)
                    val made = DocumentsContract.createDocument(
                        ctx.contentResolver, parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR, part
                    ) ?: return null
                    DocumentsContract.getDocumentId(made)
                }
                else -> return null
            }
        }
        return current
    }

    private fun displayName(uri: Uri): String? {
        val cursor = ctx.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        ) ?: return null
        try {
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor.close()
        }
    }

    private fun create(name: String, folder: String): String {
        val tree = treeUri
        if (tree == null) {
            val dir = File(localDir, folder).apply { mkdirs() }
            val file = uniqueFile(dir, name)
            file.createNewFile()
            return file.absolutePath
        }
        val parentId = folderDocId(tree, folder, create = true)
            ?: throw IllegalStateException("Cannot open $folder")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val uri = DocumentsContract.createDocument(
            ctx.contentResolver, parent, "application/octet-stream", name
        ) ?: throw IllegalStateException("Cannot create $name")
        return uri.toString()
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        var n = 2
        while (file.exists()) {
            file = File(dir, name.removeSuffix(MD) + " ($n)" + MD)
            n++
        }
        return file
    }

    /** Reads a note only when it changed since the last listing; one broken file never hides the others. */
    private fun load(id: String, name: String, modified: Long, folder: String, out: MutableList<Note>, text: () -> String) {
        val cached = synchronized(cache) { cache[id] }
        if (cached != null && modified > 0 && cached.first == modified) {
            out.add(cached.second)
            return
        }
        try {
            val note = toNote(id, name, text(), modified, folder)
            synchronized(cache) { cache[id] = modified to note }
            out.add(note)
        } catch (e: Exception) {
            // Skip a file that cannot be read right now; it shows up again on the next refresh.
        }
    }

    private fun toNote(id: String, name: String, text: String, modified: Long, folder: String): Note {
        val (meta, content) = NoteMeta.parse(text)
        if (meta.vault) return Note(id, "", "", modified, folder, meta, "", name)
        val (title, body) = split(content)
        val preview = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString("\n")
        return Note(id, title.ifBlank { name.substringBeforeLast('.') }, preview, modified, folder, meta, body, name)
    }

    companion object {
        private const val KEY_TREE = "tree_uri"
        private const val KEY_PREVIEW = "preview_lines"
        private const val KEY_FONT = "font_level"
        private const val KEY_SORT = "sort_mode"
        private const val KEY_GRID = "grid_layout"
        private const val KEY_COLOR = "default_color"
        private const val MD = ".md"
        private const val CONTENT = "content://"
        private const val ATTACH = "attachments"
        private const val HIDDEN = ".omninote"
        const val TRASH = ".trash"
        private const val MAX_DEPTH = 8
        private val cache = HashMap<String, Pair<Long, Note>>()

        fun isMd(name: String): Boolean = name.lowercase().endsWith(MD) && !name.startsWith(".")

        private val CONFLICT = Regex("\\.sync-conflict-\\d{8}-\\d{6}-[A-Z0-9]{7}")

        private fun isSpecial(name: String): Boolean = name.startsWith(".") || name == ATTACH

        private fun joinPath(parent: String, name: String): String =
            if (parent.isEmpty()) name else "$parent/$name"

        fun isConflictName(name: String): Boolean = CONFLICT.containsMatchIn(name)

        /** "Note.sync-conflict-20261002-173012-ABCDEF7.md" becomes "Note.md". */
        fun conflictBase(name: String): String = name.replace(CONFLICT, "")

        fun cleanPath(path: String): String =
            path.split("/")
                .map { it.replace(Regex("[\\\\:*?\"<>|\\p{Cntrl}]"), " ").trim() }
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/")

        /** Splits "# Title" on the first line from the rest of the note. */
        fun split(text: String): Pair<String, String> {
            val normalized = text.replace("\r\n", "\n")
            val firstLine = normalized.substringBefore('\n').trim()
            return if (firstLine.startsWith("# ")) {
                val body = if (normalized.contains('\n')) {
                    normalized.substringAfter('\n').trimStart('\n')
                } else {
                    ""
                }
                firstLine.removePrefix("# ").trim() to body
            } else {
                "" to normalized
            }
        }

        fun join(title: String, body: String): String =
            if (title.isBlank()) body else "# ${title.trim()}\n\n$body"

        fun fileNameFor(title: String): String {
            val clean = title
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .trim()
                .take(60)
                .trim()
            val base = clean.ifBlank {
                "Note " + SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
            }
            return base + MD
        }
    }
}
