package dev.kurobanekotlin.core.chat

/** A block of Markdown the chat can draw without a parser library. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val items: List<String>) : MdBlock
    data class Numbered(val items: List<String>) : MdBlock
    data class Code(val lang: String?, val text: String) : MdBlock
}

/** One inline span. [href] is set for a link. */
data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val href: String? = null)

/** Headings, emphasis, lists, inline code, fenced code, and links. Pure and unit-tested. */
object MarkdownParser {
    fun parse(src: String): List<MdBlock> {
        if (src.isEmpty()) return emptyList()
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                val lang = line.trim().removePrefix("```").trim().ifBlank { null }
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                if (i < lines.size) i++
                blocks += MdBlock.Code(lang, body.toString())
                continue
            }
            if (line.isBlank()) {
                i++
                continue
            }
            val heading = heading(line)
            if (heading != null) {
                blocks += heading
                i++
                continue
            }
            if (isBullet(line)) {
                val items = mutableListOf<String>()
                while (i < lines.size && isBullet(lines[i])) {
                    items += lines[i].trim().drop(1).trim()
                    i++
                }
                blocks += MdBlock.Bullet(items)
                continue
            }
            if (isNumbered(line)) {
                val items = mutableListOf<String>()
                while (i < lines.size && isNumbered(lines[i])) {
                    items += lines[i].trim().substringAfter('.').trim()
                    i++
                }
                blocks += MdBlock.Numbered(items)
                continue
            }
            val para = StringBuilder(line.trim())
            i++
            while (i < lines.size && lines[i].isNotBlank() && heading(lines[i]) == null &&
                !lines[i].trimStart().startsWith("```") && !isBullet(lines[i]) && !isNumbered(lines[i])
            ) {
                para.append(' ').append(lines[i].trim())
                i++
            }
            blocks += MdBlock.Paragraph(para.toString())
        }
        return blocks
    }

    fun inlines(text: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        var i = 0
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                spans += MdSpan(buf.toString())
                buf.clear()
            }
        }
        while (i < text.length) {
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                val closer = text.indexOf(text.substring(i, i + 2), i + 2)
                if (closer > i + 2) {
                    flush()
                    spans += MdSpan(text.substring(i + 2, closer), bold = true)
                    i = closer + 2
                    continue
                }
            }
            if (text[i] == '`') {
                val closer = text.indexOf('`', i + 1)
                if (closer > i) {
                    flush()
                    spans += MdSpan(text.substring(i + 1, closer), code = true)
                    i = closer + 1
                    continue
                }
            }
            if (text[i] == '[') {
                val labelEnd = text.indexOf(']', i + 1)
                if (labelEnd > i && labelEnd + 1 < text.length && text[labelEnd + 1] == '(') {
                    val hrefEnd = text.indexOf(')', labelEnd + 2)
                    if (hrefEnd > labelEnd) {
                        flush()
                        spans += MdSpan(text.substring(i + 1, labelEnd), href = text.substring(labelEnd + 2, hrefEnd))
                        i = hrefEnd + 1
                        continue
                    }
                }
            }
            if ((text[i] == '*' || text[i] == '_') && (i == 0 || text[i - 1] != text[i])) {
                val mark = text[i]
                val closer = text.indexOf(mark, i + 1)
                if (closer > i + 1) {
                    flush()
                    spans += MdSpan(text.substring(i + 1, closer), italic = true)
                    i = closer + 1
                    continue
                }
            }
            buf.append(text[i])
            i++
        }
        flush()
        return spans
    }

    private fun heading(line: String): MdBlock.Heading? {
        val t = line.trimStart()
        val level = t.takeWhile { it == '#' }.length
        if (level !in 1..3 || t.getOrNull(level) != ' ') return null
        return MdBlock.Heading(level, t.drop(level + 1).trim())
    }

    private fun isBullet(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("- ") || t.startsWith("* ")
    }

    private fun isNumbered(line: String): Boolean {
        val t = line.trim()
        val dot = t.indexOf('.')
        if (dot <= 0) return false
        return t.substring(0, dot).all { it.isDigit() } && t.getOrNull(dot + 1) == ' '
    }
}
