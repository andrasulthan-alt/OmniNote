package io.github.andrasulthan.omninote

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.View

/**
 * Small Markdown engine for OmniNote, written without libraries.
 * styleEditable() colours text while typing; render() builds the read view.
 * Supports images ![](attachments/x.jpg) and note links [[Title]].
 */
object Markdown {

    /** Marks the spans OmniNote adds, so only those are removed when restyling. */
    interface MdSpan

    class MdStyle(style: Int) : StyleSpan(style), MdSpan
    class MdColor(color: Int) : ForegroundColorSpan(color), MdSpan
    class MdBack(color: Int) : BackgroundColorSpan(color), MdSpan
    class MdSize(size: Float) : RelativeSizeSpan(size), MdSpan
    class MdStrike : StrikethroughSpan(), MdSpan
    class MdUnderline : UnderlineSpan(), MdSpan
    class MdMono : TypefaceSpan("monospace"), MdSpan

    class MdQuote(
        private val color: Int,
        private val stripe: Int,
        private val gap: Int
    ) : LeadingMarginSpan, MdSpan {
        override fun getLeadingMargin(first: Boolean): Int = stripe + gap

        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?
        ) {
            val oldStyle = p.style
            val oldColor = p.color
            p.style = Paint.Style.FILL
            p.color = color
            c.drawRect(x.toFloat(), top.toFloat(), (x + dir * stripe).toFloat(), bottom.toFloat(), p)
            p.style = oldStyle
            p.color = oldColor
        }
    }

    private class MdLink(
        private val url: String,
        private val color: Int,
        private val open: (String) -> Unit
    ) : ClickableSpan() {
        override fun onClick(widget: View) = open(url)

        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = true
        }
    }

    private class MdTap(
        private val color: Int,
        private val action: () -> Unit
    ) : ClickableSpan() {
        override fun onClick(widget: View) = action()

        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = false
        }
    }

    private val HEADING = Regex("^(#{1,6})\\s")
    private val QUOTE = Regex("^>\\s?")
    private val TASK = Regex("^(\\s*)[-*+]\\s\\[([ xX])]\\s")
    private val BULLET = Regex("^(\\s*)([-*+]|\\d+[.)])\\s")
    private val FENCE = Regex("^\\s*```")
    private val RULE = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val TABLE_SEP = Regex("^:?-{3,}:?$")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC = Regex("(?<![*\\w])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![*\\w])")
    private val STRIKE = Regex("~~(.+?)~~")
    private val CODE = Regex("`([^`]+)`")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)\\)")
    private val WIKI = Regex("\\[\\[([^\\]]+)]]")
    private val LINK = Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)")
    private val URL = Regex("https?://[^\\s)]+")
    private val TOKEN = Regex(
        "!\\[([^\\]]*)]\\(([^)\\s]+)\\)" +
            "|\\[\\[([^\\]]+)]]" +
            "|`([^`]+)`" +
            "|\\*\\*(.+?)\\*\\*" +
            "|~~(.+?)~~" +
            "|\\[([^\\]]+)]\\(([^)\\s]+)\\)" +
            "|(?<![*\\w])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![*\\w])" +
            "|(https?://[^\\s)]+)"
    )
    private val HEAD_SIZES = floatArrayOf(1.6f, 1.4f, 1.25f, 1.15f, 1.05f, 1.0f)

    private fun span(s: Spannable, what: Any, start: Int, end: Int) {
        if (start in 0 until end && end <= s.length) {
            s.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // ---------- Live styling while typing ----------

    fun styleEditable(e: Spannable, p: Ui.Palette) {
        for (old in e.getSpans(0, e.length, MdSpan::class.java)) e.removeSpan(old)
        val text = e.toString()
        var lineStart = 0
        var inFence = false
        while (lineStart <= text.length) {
            var lineEnd = text.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = text.length
            inFence = styleLine(e, text.substring(lineStart, lineEnd), lineStart, p, inFence)
            lineStart = lineEnd + 1
        }
    }

    private fun styleLine(e: Spannable, line: String, at: Int, p: Ui.Palette, inFence: Boolean): Boolean {
        val end = at + line.length
        if (FENCE.containsMatchIn(line)) {
            span(e, MdColor(p.muted), at, end)
            return !inFence
        }
        if (inFence) {
            span(e, MdBack(p.border), at, end)
            return true
        }
        val heading = HEADING.find(line)
        if (heading != null) {
            val level = heading.groupValues[1].length
            span(e, MdSize(HEAD_SIZES[level - 1]), at, end)
            span(e, MdStyle(Typeface.BOLD), at, end)
            span(e, MdColor(p.muted), at, at + heading.value.length)
        }
        val quote = QUOTE.find(line)
        if (quote != null) {
            span(e, MdColor(p.muted), at, end)
            span(e, MdStyle(Typeface.ITALIC), at + quote.value.length, end)
        }
        val task = TASK.find(line)
        if (task != null) {
            val markStart = at + task.groupValues[1].length
            val textStart = at + task.value.length
            span(e, MdColor(Ui.RED), markStart, textStart)
            if (task.groupValues[2] != " ") {
                span(e, MdStrike(), textStart, end)
                span(e, MdColor(p.muted), textStart, end)
            }
        } else {
            val bullet = BULLET.find(line)
            if (bullet != null) {
                span(e, MdColor(Ui.RED), at + bullet.groupValues[1].length, at + bullet.value.length)
            }
        }
        if (RULE.matches(line)) span(e, MdColor(p.muted), at, end)
        if (line.trimStart().startsWith("|")) {
            for (i in line.indices) {
                if (line[i] == '|') span(e, MdColor(p.muted), at + i, at + i + 1)
            }
        }
        inline(e, line, at, p)
        return false
    }

    private fun inline(e: Spannable, line: String, at: Int, p: Ui.Palette) {
        for (m in CODE.findAll(line)) {
            span(e, MdBack(p.border), at + m.range.first, at + m.range.last + 1)
        }
        for (m in BOLD.findAll(line)) {
            val s = at + m.range.first
            val t = at + m.range.last + 1
            span(e, MdStyle(Typeface.BOLD), s, t)
            span(e, MdColor(p.muted), s, s + 2)
            span(e, MdColor(p.muted), t - 2, t)
        }
        for (m in ITALIC.findAll(line)) {
            val s = at + m.range.first
            val t = at + m.range.last + 1
            span(e, MdStyle(Typeface.ITALIC), s, t)
            span(e, MdColor(p.muted), s, s + 1)
            span(e, MdColor(p.muted), t - 1, t)
        }
        for (m in STRIKE.findAll(line)) {
            val s = at + m.range.first
            val t = at + m.range.last + 1
            span(e, MdStrike(), s + 2, t - 2)
            span(e, MdColor(p.muted), s, s + 2)
            span(e, MdColor(p.muted), t - 2, t)
        }
        for (m in LINK.findAll(line)) {
            val label = m.groups[1]!!.range
            val url = m.groups[2]!!.range
            span(e, MdUnderline(), at + label.first, at + label.last + 1)
            span(e, MdColor(p.muted), at + url.first - 2, at + url.last + 2)
        }
        for (m in URL.findAll(line)) {
            span(e, MdUnderline(), at + m.range.first, at + m.range.last + 1)
        }
        for (m in IMAGE.findAll(line)) {
            span(e, MdColor(p.muted), at + m.range.first, at + m.range.last + 1)
        }
        for (m in WIKI.findAll(line)) {
            val s = at + m.range.first
            val t = at + m.range.last + 1
            span(e, MdUnderline(), s + 2, t - 2)
            span(e, MdColor(p.muted), s, s + 2)
            span(e, MdColor(p.muted), t - 2, t)
        }
    }

    // ---------- Read view ----------

    /**
     * Builds the read view.
     * onToggle gets the line number of a tapped checkbox, onLink a tapped web link,
     * onNote the title of a tapped [[note link]], loadImage turns an image path into a picture.
     */
    fun render(
        source: String,
        p: Ui.Palette,
        density: Float,
        onToggle: (Int) -> Unit,
        onLink: (String) -> Unit,
        onNote: (String) -> Unit = {},
        loadImage: (String) -> Drawable? = { null },
        missingImage: String = ""
    ): CharSequence {
        val ctx = RenderContext(p, onLink, onNote, loadImage, missingImage)
        val out = SpannableStringBuilder()
        val lines = source.replace("\r\n", "\n").split("\n")
        val stripe = (3 * density).toInt()
        val gap = (12 * density).toInt()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (FENCE.containsMatchIn(line)) {
                val start = out.length
                i++
                while (i < lines.size && !FENCE.containsMatchIn(lines[i])) {
                    out.append(lines[i]).append('\n')
                    i++
                }
                span(out, MdMono(), start, out.length)
                span(out, MdBack(p.border), start, out.length)
                i++
                continue
            }
            if (line.trimStart().startsWith("|")) {
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    val cells = splitRow(lines[i])
                    if (!cells.all { TABLE_SEP.matches(it) }) rows.add(cells)
                    i++
                }
                appendTable(out, rows, p)
                continue
            }
            val heading = HEADING.find(line)
            val task = TASK.find(line)
            val bullet = BULLET.find(line)
            val quote = QUOTE.find(line)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    val start = out.length
                    appendInline(out, line.substring(heading.value.length), ctx)
                    span(out, MdSize(HEAD_SIZES[level - 1]), start, out.length)
                    span(out, MdStyle(Typeface.BOLD), start, out.length)
                    out.append('\n')
                }
                task != null -> {
                    val checked = task.groupValues[2] != " "
                    val lineIndex = i
                    out.append(task.groupValues[1])
                    val boxStart = out.length
                    out.append(if (checked) "☑ " else "☐ ")
                    span(out, MdTap(Ui.RED) { onToggle(lineIndex) }, boxStart, out.length)
                    val textStart = out.length
                    appendInline(out, line.substring(task.value.length), ctx)
                    if (checked) {
                        span(out, MdStrike(), textStart, out.length)
                        span(out, MdColor(p.muted), textStart, out.length)
                    }
                    out.append('\n')
                }
                RULE.matches(line) -> {
                    val start = out.length
                    out.append("────────────────")
                    span(out, MdColor(p.muted), start, out.length)
                    out.append('\n')
                }
                bullet != null -> {
                    val marker = bullet.groupValues[2]
                    out.append(bullet.groupValues[1])
                    val start = out.length
                    out.append(if (marker[0].isDigit()) "$marker " else "• ")
                    span(out, MdColor(Ui.RED), start, out.length)
                    appendInline(out, line.substring(bullet.value.length), ctx)
                    out.append('\n')
                }
                quote != null -> {
                    val start = out.length
                    appendInline(out, line.substring(quote.value.length), ctx)
                    out.append('\n')
                    span(out, MdColor(p.muted), start, out.length)
                    span(out, MdQuote(p.muted, stripe, gap), start, out.length)
                }
                else -> {
                    appendInline(out, line, ctx)
                    out.append('\n')
                }
            }
            i++
        }
        return out
    }

    private class RenderContext(
        val p: Ui.Palette,
        val onLink: (String) -> Unit,
        val onNote: (String) -> Unit,
        val loadImage: (String) -> Drawable?,
        val missingImage: String
    )

    private fun appendInline(out: SpannableStringBuilder, text: String, ctx: RenderContext) {
        val p = ctx.p
        var last = 0
        for (m in TOKEN.findAll(text)) {
            out.append(text, last, m.range.first)
            val g = m.groupValues
            val s = out.length
            when {
                g[2].isNotEmpty() -> {
                    val picture = ctx.loadImage(g[2])
                    if (picture != null) {
                        out.append("\uFFFC")
                        span(out, ImageSpan(picture), s, out.length)
                    } else {
                        out.append(ctx.missingImage)
                        span(out, MdColor(p.muted), s, out.length)
                    }
                }
                g[3].isNotEmpty() -> {
                    out.append(g[3])
                    span(out, MdLink(g[3], p.text, ctx.onNote), s, out.length)
                }
                g[4].isNotEmpty() -> {
                    out.append(g[4])
                    span(out, MdBack(p.border), s, out.length)
                }
                g[5].isNotEmpty() -> {
                    out.append(g[5])
                    span(out, MdStyle(Typeface.BOLD), s, out.length)
                }
                g[6].isNotEmpty() -> {
                    out.append(g[6])
                    span(out, MdStrike(), s, out.length)
                }
                g[8].isNotEmpty() -> {
                    out.append(g[7])
                    span(out, MdLink(g[8], p.text, ctx.onLink), s, out.length)
                }
                g[9].isNotEmpty() -> {
                    out.append(g[9])
                    span(out, MdStyle(Typeface.ITALIC), s, out.length)
                }
                else -> {
                    out.append(g[10])
                    span(out, MdLink(g[10], p.text, ctx.onLink), s, out.length)
                }
            }
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun appendTable(out: SpannableStringBuilder, rows: List<List<String>>, p: Ui.Palette) {
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.size }
        val widths = IntArray(cols) { c -> rows.maxOf { r -> r.getOrNull(c)?.length ?: 0 } }
        val start = out.length
        rows.forEachIndexed { r, cells ->
            val rowStart = out.length
            for (c in 0 until cols) {
                if (c > 0) out.append(" │ ")
                out.append((cells.getOrNull(c) ?: "").padEnd(widths[c]))
            }
            if (r == 0) span(out, MdStyle(Typeface.BOLD), rowStart, out.length)
            out.append('\n')
            if (r == 0 && rows.size > 1) {
                val sepStart = out.length
                for (c in 0 until cols) {
                    if (c > 0) out.append("─┼─")
                    out.append("─".repeat(widths[c]))
                }
                span(out, MdColor(p.muted), sepStart, out.length)
                out.append('\n')
            }
        }
        span(out, MdMono(), start, out.length)
    }

    // ---------- Plain preview for the note list ----------

    fun plain(text: String): String = text
        .replace(IMAGE, "🖼")
        .replace(WIKI) { it.groupValues[1] }
        .replace(Regex("[-*+] \\[ ] "), "☐ ")
        .replace(Regex("[-*+] \\[[xX]] "), "☑ ")
        .replace(Regex("(^|\\s)#{1,6} "), "$1")
        .replace(Regex("(^|\\s)> "), "$1")
        .replace(LINK) { it.groupValues[1] }
        .replace("**", "")
        .replace("~~", "")
        .replace("`", "")
}
