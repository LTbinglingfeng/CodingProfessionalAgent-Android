package dev.cpa.android.feature.remote.markdown

sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val text: String, val level: Int) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class ListBlock(val start: Int?, val items: List<List<MarkdownBlock>>) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

/** The same native block subset as iOS, including partially streamed fences and lazy list continuations. */
object MarkdownBlocks {
    fun parse(text: String): List<MarkdownBlock> = lines(text.replace("\r\n", "\n").split('\n'), 0)
    private fun lines(input: List<String>, depth: Int): List<MarkdownBlock> {
        if (depth >= 64) return listOf(MarkdownBlock.Paragraph(input.joinToString("\n")))
        val result = mutableListOf<MarkdownBlock>(); val paragraph = mutableListOf<String>(); var index = 0
        fun flush() { if (paragraph.isNotEmpty()) result += MarkdownBlock.Paragraph(paragraph.joinToString("\n")); paragraph.clear() }
        while (index < input.size) {
            val line = input[index]; val trimmed = line.trim()
            if (trimmed.isEmpty()) { flush(); index++; continue }
            val fence = fence(trimmed)
            if (fence != null) {
                flush(); val indent = indent(line); val code = mutableListOf<String>(); index++
                while (index < input.size) {
                    val current = input[index++]
                    if (closes(current, fence)) break
                    code += dropIndent(current, indent)
                }
                result += MarkdownBlock.Code(code.joinToString("\n")); continue
            }
            val heading = heading(trimmed)
            if (heading != null) { flush(); result += heading; index++; continue }
            if (rule(trimmed)) { flush(); result += MarkdownBlock.Rule; index++; continue }
            if (trimmed.startsWith('>')) {
                flush(); val quote = mutableListOf<String>()
                while (index < input.size && input[index].trim().startsWith('>')) quote += input[index++].trim().drop(1).removePrefix(" ")
                result += MarkdownBlock.Quote(lines(quote, depth + 1)); continue
            }
            val header = tableRow(trimmed)
            if (header != null && index + 1 < input.size && delimiter(input[index + 1], header.size)) {
                flush(); val rows = mutableListOf<List<String>>(); index += 2
                while (index < input.size) {
                    val row = input[index].trim()
                    if (startsBlock(row) || item(input[index]) != null) break
                    val cells = tableRow(row) ?: break
                    rows += header.indices.map { cells.getOrElse(it) { "" } }; index++
                }
                result += MarkdownBlock.Table(header, rows); continue
            }
            val first = item(line)
            if (first != null) {
                flush(); val items = mutableListOf<List<MarkdownBlock>>()
                while (index < input.size) {
                    val entry = item(input[index]) ?: break
                    if (rule(input[index].trim()) || entry.indent >= first.indent + 2 || (entry.number == null) != (first.number == null)) break
                    val body = mutableListOf(entry.text); var marker = fence(entry.text.trim()); var blank = false; index++
                    while (index < input.size) {
                        val current = input[index]; val value = current.trim()
                        if (marker != null) {
                            body += dropIndent(current, entry.contentIndent)
                            if (closes(current, marker)) marker = null
                        } else if (value.isEmpty()) { body += ""; blank = true }
                        else if (indent(current) >= entry.indent + 2) {
                            body += dropIndent(current, entry.contentIndent)
                            marker = fence(item(current)?.text?.trim() ?: value); blank = false
                        } else if (!blank && item(current) == null && !startsBlock(value)) body += value
                        else break
                        index++
                    }
                    while (body.lastOrNull() == "") body.removeAt(body.lastIndex)
                    items += lines(body, depth + 1)
                }
                result += MarkdownBlock.ListBlock(first.number, items); continue
            }
            paragraph += trimmed; index++
        }
        flush(); return result
    }
    private data class Item(val indent: Int, val number: Int?, val contentIndent: Int, val text: String)
    private val itemPattern = Regex("^([-*+•]|[0-9]{1,9}[.)])([ \\t]+)(.*)$")
    private fun item(line: String): Item? {
        val match = itemPattern.matchEntire(line.trimStart(' ', '\t')) ?: return null
        val marker = match.groupValues[1]; val spaces = match.groupValues[2].takeWhile { it == ' ' }.length.coerceAtMost(4).coerceAtLeast(1)
        return Item(indent(line), marker.dropLast(1).toIntOrNull(), indent(line) + marker.length + spaces, match.groupValues[3])
    }
    private fun heading(text: String): MarkdownBlock.Heading? {
        val count = text.takeWhile { it == '#' }.length
        return if (count in 1..6 && text.getOrNull(count) == ' ') MarkdownBlock.Heading(text.drop(count).trim(), count) else null
    }
    private fun fence(text: String): String? = if (text.startsWith("```") || text.startsWith("~~~")) text.takeWhile { it == text.first() } else null
    private fun closes(text: String, fence: String): Boolean = text.trim().let { it.startsWith(fence) && it.all { c -> c == fence.first() } }
    private fun rule(text: String): Boolean = text.replace(" ", "").let { it.length >= 3 && it.first() in "-*_" && it.all { c -> c == it.first() } }
    private fun startsBlock(text: String) = fence(text) != null || heading(text) != null || rule(text) || text.startsWith('>')
    internal fun tableRow(text: String): List<String>? {
        if ('|' !in text) return null
        val cells = mutableListOf<String>(); val cell = StringBuilder(); var escaped = false
        for (c in text) {
            if (escaped) { if (c != '|') cell.append('\\'); cell.append(c); escaped = false }
            else when(c) { '\\' -> escaped = true; '|' -> { cells += cell.toString(); cell.clear() }; else -> cell.append(c) }
        }
        if (escaped) cell.append('\\')
        cells += cell.toString()
        if (text.startsWith('|')) cells.removeAt(0)
        if (cells.size > 1 && cells.last().isBlank()) cells.removeAt(cells.lastIndex)
        return cells.map(String::trim)
    }
    private fun delimiter(text: String, count: Int): Boolean {
        val cells = tableRow(text.trim()) ?: return false
        return cells.size == count && cells.all { cell -> cell.removePrefix(":").removeSuffix(":").let { it.isNotEmpty() && it.all { c -> c == '-' } } }
    }
    private fun indent(text: String) = text.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 4 else 1 }
    private fun dropIndent(text: String, width: Int): String {
        var index = 0; var consumed = 0
        while (index < text.length && consumed < width && (text[index] == ' ' || text[index] == '\t')) { consumed += if (text[index] == '\t') 4 else 1; index++ }
        return text.drop(index)
    }
}
