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
    val modified: Long
)

/**
 * Notes are plain Markdown files, either in app storage or in a folder the user
 * picks (so tools like Syncthing or Obsidian can read them too).
 * Images live in an "attachments" folder next to the notes.
 */
class NoteStore(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("omninote", Context.MODE_PRIVATE)

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

    fun list(): List<Note> {
        val tree = treeUri
        val notes = ArrayList<Note>()
        if (tree == null) {
            val files = localDir.listFiles() ?: emptyArray()
            for (f in files) {
                if (f.isFile && f.name.endsWith(MD)) {
                    notes.add(toNote(f.absolutePath, f.name, f.readText(), f.lastModified()))
                }
            }
        } else {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            val cursor = ctx.contentResolver.query(children, projection, null, null, null)
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0)
                        val name = cursor.getString(1) ?: ""
                        val isDir = cursor.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR
                        if (!isDir && name.endsWith(MD)) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString()
                            notes.add(toNote(uri, name, read(uri), cursor.getLong(2)))
                        }
                    }
                } finally {
                    cursor.close()
                }
            }
        }
        return notes.sortedByDescending { it.modified }
    }

    /** Finds a note by its title (used by [[note links]]). */
    fun findByTitle(title: String): Note? =
        list().firstOrNull { it.title.equals(title.trim(), ignoreCase = true) }

    fun read(id: String): String {
        return if (id.startsWith(CONTENT)) {
            ctx.contentResolver.openInputStream(Uri.parse(id))?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: ""
        } else {
            val f = File(id)
            if (f.exists()) f.readText() else ""
        }
    }

    /** Writes the note and returns its id (a new file is created when id is null). */
    fun save(id: String?, title: String, text: String): String {
        val target = id ?: create(fileNameFor(title))
        if (target.startsWith(CONTENT)) {
            ctx.contentResolver.openOutputStream(Uri.parse(target), "wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
        } else {
            File(target).writeText(text)
        }
        return target
    }

    fun delete(id: String) {
        if (id.startsWith(CONTENT)) {
            DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(id))
        } else {
            File(id).delete()
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
        val name = "img-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "." + ext
        val input = ctx.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("Cannot read image")
        input.use { inp ->
            val tree = treeUri
            if (tree == null) {
                val dir = File(localDir, ATTACH).apply { mkdirs() }
                File(dir, name).outputStream().use { inp.copyTo(it) }
            } else {
                val dirUri = attachmentDir(tree, create = true)
                    ?: throw IllegalStateException("No attachments folder")
                val file = DocumentsContract.createDocument(
                    ctx.contentResolver, dirUri, "application/octet-stream", name
                ) ?: throw IllegalStateException("Cannot create $name")
                ctx.contentResolver.openOutputStream(file, "w")?.use { inp.copyTo(it) }
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
        val dir = attachmentDir(tree, create = false) ?: return null
        val doc = findChild(tree, DocumentsContract.getDocumentId(dir), name) ?: return null
        return ctx.contentResolver.openInputStream(doc)
    }

    private fun attachmentDir(tree: Uri, create: Boolean): Uri? {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        findChild(tree, rootId, ATTACH)?.let { return it }
        if (!create) return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        return DocumentsContract.createDocument(
            ctx.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, ATTACH
        )
    }

    private fun findChild(tree: Uri, parentId: String, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        val cursor = ctx.contentResolver.query(children, projection, null, null, null) ?: return null
        try {
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        } finally {
            cursor.close()
        }
        return null
    }

    // ---------- Helpers ----------

    private fun create(name: String): String {
        val tree = treeUri
        if (tree == null) {
            var file = File(localDir, name)
            var n = 2
            while (file.exists()) {
                file = File(localDir, name.removeSuffix(MD) + " ($n)" + MD)
                n++
            }
            file.createNewFile()
            return file.absolutePath
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val uri = DocumentsContract.createDocument(
            ctx.contentResolver, parent, "application/octet-stream", name
        ) ?: throw IllegalStateException("Cannot create $name")
        return uri.toString()
    }

    private fun toNote(id: String, name: String, text: String, modified: Long): Note {
        val (title, body) = split(text)
        val preview = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString("\n")
        return Note(id, title.ifBlank { name.removeSuffix(MD) }, preview, modified)
    }

    companion object {
        private const val KEY_TREE = "tree_uri"
        private const val KEY_PREVIEW = "preview_lines"
        private const val KEY_FONT = "font_level"
        private const val MD = ".md"
        private const val CONTENT = "content://"
        private const val ATTACH = "attachments"

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
