package io.github.andrasulthan.omninote

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintDocumentAdapter
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream

/** Turns a note into a self-contained web page, and prints it to PDF. */
object HtmlExport {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    private val TABLE_SEP = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
    private val LIST = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
    private val TASK = Regex("^\\[([ xX])\\]\\s+(.*)$")
    private val IMAGE = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
    private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")
    private val WIKI = Regex("\\[\\[([^\\]]+)\\]\\]")
    private val EMBED = Regex("!\\[\\[([^\\]|]+\\.(?:png|jpe?g|gif|webp|bmp))(?:\\|[^\\]]*)?\\]\\]", RegexOption.IGNORE_CASE)
    private val CODE = Regex("`([^`]+)`")
    private val CODE_MARK = Regex("\u0000(\\d+)\u0000")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val STRIKE = Regex("~~(.+?)~~")
    private val ITALIC = Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")

    /** Kept alive until Android has finished preparing the print job. */
    private var printing: WebView? = null
    private val SAFE_LINK = Regex("^(https?|mailto|tel):", RegexOption.IGNORE_CASE)

    private val CSS = """
        body{margin:0;padding:24px;font-family:Roboto,"Segoe UI",Arial,sans-serif;color:#111;background:#fff;line-height:1.55;font-size:15px}
        article{max-width:720px;margin:0 auto}
        h1,h2,h3,h4,h5,h6{font-family:"Roboto Mono",monospace;line-height:1.25;margin:1.1em 0 .5em}
        h1{font-size:1.7em;margin-top:0;border-bottom:2px solid #D71921;padding-bottom:.25em}
        h2{font-size:1.35em}
        h3{font-size:1.15em}
        p{margin:0 0 .8em}
        pre{background:#f2f2f2;padding:12px;border-radius:8px;white-space:pre-wrap;word-wrap:break-word}
        code{font-family:monospace;background:#f2f2f2;padding:1px 4px;border-radius:4px}
        pre code{padding:0;background:none}
        blockquote{margin:0 0 .8em;padding:.2em 1em;border-left:3px solid #D71921;color:#444}
        ul,ol{margin:0 0 .8em;padding-left:1.4em}
        li{margin:.15em 0}
        li.task{list-style:none}
        li.done{color:#777;text-decoration:line-through}
        .box{font-family:monospace;margin-right:.3em}
        table{border-collapse:collapse;margin:0 0 .8em;width:100%}
        th,td{border:1px solid #ccc;padding:6px 8px;text-align:left;vertical-align:top}
        th{background:#f2f2f2}
        hr{border:none;border-top:1px solid #ccc;margin:1.2em 0}
        img{max-width:100%;border-radius:8px}
        a{color:#D71921}
        .wiki{border-bottom:1px dotted #D71921}
        @media print{body{padding:0}}
    """.trimIndent()

    /** A complete web page for one note, with its images embedded. */
    fun page(store: NoteStore, noteText: String): String {
        val (_, content) = NoteMeta.parse(noteText)
        val (title, body) = NoteStore.split(content)
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>").append(esc(title.ifBlank { "OmniNote" })).append("</title>")
        sb.append("<style>").append(CSS).append("</style></head><body><article>")
        if (title.isNotBlank()) sb.append("<h1>").append(inline(title, store)).append("</h1>")
        sb.append(bodyHtml(body, store))
        sb.append("</article></body></html>")
        return sb.toString()
    }

    /** Opens Android's print screen for the page; choose "Save as PDF" there. */
    fun print(activity: Activity, html: String, jobName: String) {
        printing?.destroy()
        val web = WebView(activity)
        printing = web
        web.settings.javaScriptEnabled = false
        web.webViewClient = object : WebViewClient() {
            private var started = false

            override fun onPageFinished(view: WebView, url: String?) {
                if (started) return
                started = true
                val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val attributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .build()
                try {
                    manager.print(jobName, Releasing(view.createPrintDocumentAdapter(jobName), view), attributes)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(activity, R.string.export_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    /**
     * Passes everything to the WebView's own adapter and lets go of the WebView when printing ends,
     * so the closed note screen is not kept in memory until the next print.
     */
    private class Releasing(private val inner: PrintDocumentAdapter, private val web: WebView) : PrintDocumentAdapter() {
        override fun onStart() = inner.onStart()

        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?, callback: LayoutResultCallback?, extras: Bundle?
        ) = inner.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)

        override fun onWrite(
            pages: Array<out PageRange>?, destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?, callback: WriteResultCallback?
        ) = inner.onWrite(pages, destination, cancellationSignal, callback)

        override fun onFinish() {
            inner.onFinish()
            if (printing === web) {
                printing = null
                web.post { web.destroy() }
            }
        }
    }

    private fun bodyHtml(body: String, store: NoteStore): String {
        val lines = body.replace("\r\n", "\n").split("\n")
        val out = StringBuilder()
        val paragraph = ArrayList<String>()

        fun flush() {
            if (paragraph.isEmpty()) return
            out.append("<p>").append(paragraph.joinToString("<br>") { inline(it, store) }).append("</p>")
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> {
                    flush()
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        code.add(lines[i])
                        i++
                    }
                    out.append("<pre><code>").append(esc(code.joinToString("\n"))).append("</code></pre>")
                }
                t.isEmpty() -> flush()
                HEADING.matches(t) -> {
                    flush()
                    val m = HEADING.find(t)!!
                    val level = m.groupValues[1].length
                    out.append("<h$level>").append(inline(m.groupValues[2].trim(), store)).append("</h$level>")
                }
                RULE.matches(t) -> {
                    flush()
                    out.append("<hr>")
                }
                t.startsWith(">") -> {
                    flush()
                    val quote = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quote.add(lines[i].trim().removePrefix(">").trimStart())
                        i++
                    }
                    out.append("<blockquote>").append(quote.joinToString("<br>") { inline(it, store) })
                        .append("</blockquote>")
                    continue
                }
                t.startsWith("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1].trim()) -> {
                    flush()
                    out.append("<table><thead><tr>")
                    for (cell in cells(t)) out.append("<th>").append(inline(cell, store)).append("</th>")
                    out.append("</tr></thead><tbody>")
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        out.append("<tr>")
                        for (cell in cells(lines[i].trim())) out.append("<td>").append(inline(cell, store)).append("</td>")
                        out.append("</tr>")
                        i++
                    }
                    out.append("</tbody></table>")
                    continue
                }
                LIST.matches(line) -> {
                    flush()
                    val ordered = LIST.find(line)!!.groupValues[2].first().isDigit()
                    out.append(if (ordered) "<ol>" else "<ul>")
                    while (i < lines.size) {
                        val m = LIST.find(lines[i]) ?: break
                        val level = m.groupValues[1].replace("\t", "  ").length / 2
                        val margin = if (level > 0) " style=\"margin-left:${level * 1.5}em\"" else ""
                        val task = TASK.find(m.groupValues[3])
                        if (task != null) {
                            val done = task.groupValues[1] != " "
                            out.append("<li class=\"task").append(if (done) " done" else "").append("\"").append(margin)
                                .append("><span class=\"box\">").append(if (done) "☑" else "☐").append("</span>")
                                .append(inline(task.groupValues[2], store)).append("</li>")
                        } else {
                            out.append("<li").append(margin).append(">").append(inline(m.groupValues[3], store))
                                .append("</li>")
                        }
                        i++
                    }
                    out.append(if (ordered) "</ol>" else "</ul>")
                    continue
                }
                else -> paragraph.add(t)
            }
            i++
        }
        flush()
        return out.toString()
    }

    private fun cells(line: String): List<String> =
        line.trim().trim('|').split("|").map { it.trim() }

    private fun inline(text: String, store: NoteStore): String {
        val codes = ArrayList<String>()
        var s = CODE.replace(text) { m ->
            codes.add(m.groupValues[1])
            "\u0000" + (codes.size - 1) + "\u0000"
        }
        s = esc(s)
        s = IMAGE.replace(s) { m -> image(unesc(m.groupValues[2]), m.groupValues[1], store) }
        s = EMBED.replace(s) { m -> image(unesc(m.groupValues[1].trim()), "", store) }
        s = WIKI.replace(s) { m ->
            // [[Note|shown text]] shows only the text, like in the read view.
            val inner = m.groupValues[1]
            val label = inner.substringAfter('|', "").trim().ifEmpty { inner.substringBefore('|').trim() }
            "<span class=\"wiki\">" + label.ifEmpty { inner } + "</span>"
        }
        s = LINK.replace(s) { m ->
            val target = m.groupValues[2].trim()
            val safe = SAFE_LINK.containsMatchIn(target) || !target.contains(":")
            if (safe) "<a href=\"" + target + "\">" + m.groupValues[1] + "</a>" else m.groupValues[1]
        }
        s = BOLD.replace(s) { m -> "<strong>" + m.groupValues[1] + "</strong>" }
        s = STRIKE.replace(s) { m -> "<del>" + m.groupValues[1] + "</del>" }
        s = ITALIC.replace(s) { m -> "<em>" + m.groupValues[1] + "</em>" }
        s = CODE_MARK.replace(s) { m -> "<code>" + esc(codes[m.groupValues[1].toInt()]) + "</code>" }
        return s
    }

    private fun image(path: String, alt: String, store: NoteStore): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return "<img src=\"" + esc(path) + "\" alt=\"$alt\">"
        }
        var bytes = try {
            store.openAttachment(path)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return alt
        var mime = when (path.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        // Camera photos are several megabytes each; embedded as they are, a few of them made the
        // page too big for the memory of older phones. Large pictures are made smaller first.
        if (bytes.size > MAX_EMBED_BYTES && mime != "image/gif") {
            shrink(bytes)?.let {
                bytes = it
                mime = "image/jpeg"
            }
        }
        return "<img src=\"data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP) +
            "\" alt=\"" + alt + "\">"
    }

    private const val MAX_EMBED_BYTES = 1_500_000
    private const val MAX_EMBED_SIDE = 1600

    /** A smaller JPEG copy of a large picture, or null when it cannot be made. */
    private fun shrink(bytes: ByteArray): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EMBED_SIDE || bounds.outHeight / (sample * 2) >= MAX_EMBED_SIDE) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bitmap == null) {
            null
        } else {
            val out = ByteArrayOutputStream()
            // JPEG has no transparency: paint transparent parts white, like the page.
            val flat = if (bitmap.hasAlpha()) {
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { copy ->
                    Canvas(copy).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(bitmap, 0f, 0f, null)
                    }
                    bitmap.recycle()
                }
            } else {
                bitmap
            }
            flat.compress(Bitmap.CompressFormat.JPEG, 85, out)
            flat.recycle()
            out.toByteArray().takeIf { it.isNotEmpty() && it.size < bytes.size }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun unesc(s: String): String =
        s.replace("&quot;", "\"").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
