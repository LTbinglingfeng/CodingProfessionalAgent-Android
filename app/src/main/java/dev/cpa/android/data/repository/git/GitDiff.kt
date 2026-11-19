package dev.cpa.android.data.repository.git

import java.io.ByteArrayOutputStream

data class GitLine(val kind: Char, val oldNumber: Int?, val newNumber: Int?, val text: String)
data class GitHunk(val heading: String, val lines: List<GitLine>)
data class GitFile(val oldPath: String, val path: String, val status: String, val binary: Boolean, val hunks: List<GitHunk>) {
    val additions get() = hunks.sumOf { h -> h.lines.count { it.kind == '+' } }
    val deletions get() = hunks.sumOf { h -> h.lines.count { it.kind == '-' } }
}
object GitDiffParser {
    private val header = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$")
    fun unquote(path: String): String {
        if (path.length < 2 || !path.startsWith('"') || !path.endsWith('"')) return path
        val out = ByteArrayOutputStream(); val text = path.substring(1, path.length - 1); var i = 0
        fun append(value: String) { out.write(value.toByteArray(Charsets.UTF_8)) }
        while (i < text.length) {
            if (text[i] != '\\') {
                val cp = text.codePointAt(i); append(String(Character.toChars(cp))); i += Character.charCount(cp); continue
            }
            i++; if (i == text.length) { append("\\"); break }
            val ch = text[i++]
            when (ch) {
                't' -> out.write(9); 'n' -> out.write(10)
                '\\', '"', '\'' -> append(ch.toString())
                in '0'..'7' -> {
                    val octal = ch + text.substring(i, minOf(i + 2, text.length))
                    val byte = octal.takeIf { it.length == 3 }?.toIntOrNull(8)
                    if (byte != null && byte <= 255) { out.write(byte); i += 2 } else append("\\$ch")
                }
                else -> append("\\$ch")
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
    private fun token(text: String, start: Int): Pair<String, Int>? {
        if (start >= text.length) return null
        var i = start
        if (text[i] == '"') {
            i++
            while (i < text.length) {
                if (text[i] == '\\') { i += 2; continue }
                if (text[i++] == '"') return unquote(text.substring(start, i)) to i
            }
            return null
        }
        while (i < text.length && text[i] != ' ' && text[i] != '\t') i++
        return text.substring(start, i) to i
    }
    private fun strip(path: String) = if (path.startsWith("a/") || path.startsWith("b/")) path.drop(2) else path
    private fun paths(line: String): Pair<String, String>? {
        val body = line.removePrefix("diff --git ")
        val first = token(body, 0) ?: return null
        if (body.startsWith('"')) {
            var i = first.second; while (i < body.length && body[i].isWhitespace()) i++
            return token(body, i)?.let { strip(first.first) to strip(it.first) }
        }
        var index = body.indexOf(" b/")
        while (index >= 0) {
            val old = strip(body.take(index)); val new = body.substring(index + 3)
            if (old == new) return old to new
            index = body.indexOf(" b/", index + 3)
        }
        return null
    }
    private fun marker(line: String): String {
        val tail = line.drop(4)
        return strip(if (tail.startsWith('"')) token(tail, 0)?.first ?: tail else tail.substringBefore('\t'))
    }
    fun parse(raw: String): List<GitFile> {
        val lines = raw.split('\n').map { it.removeSuffix("\r") }; val files = mutableListOf<GitFile>(); var i = 0
        while (i < lines.size) {
            if (!lines[i].startsWith("diff --git ")) { i++; continue }
            val pair = paths(lines[i++]); var old = pair?.first.orEmpty(); var path = pair?.second.orEmpty()
            var status = "modified"; var binary = false; val hunks = mutableListOf<GitHunk>()
            while (i < lines.size && !lines[i].startsWith("diff --git ")) {
                val line = lines[i]
                when {
                    line.startsWith("new file mode") -> status = "added"
                    line.startsWith("deleted file mode") -> status = "deleted"
                    line.startsWith("similarity index") || line.startsWith("rename from") -> status = "renamed"
                    line.startsWith("copy from") -> status = "copied"
                }
                when {
                    line.startsWith("rename from ") || line.startsWith("copy from ") -> old = unquote(line.substringAfter("from "))
                    line.startsWith("rename to ") || line.startsWith("copy to ") -> path = unquote(line.substringAfter("to "))
                    line.startsWith("Binary files ") || line.startsWith("GIT binary patch") -> binary = true
                    line.startsWith("--- ") -> old = marker(line)
                    line.startsWith("+++ ") -> path = marker(line)
                }
                val match = header.matchEntire(line)
                if (match != null) {
                    var oldNumber = match.groupValues[1].toIntOrNull() ?: 0
                    var newNumber = match.groupValues[3].toIntOrNull() ?: 0
                    var oldLeft = match.groupValues[2].toIntOrNull() ?: 1
                    var newLeft = match.groupValues[4].toIntOrNull() ?: 1
                    val body = mutableListOf<GitLine>(); i++
                    while (i < lines.size) {
                        val value = lines[i]
                        if (value.startsWith("diff --git ") || value.startsWith("@@ ")) break
                        if (value.startsWith("\\ No newline at end of file")) { i++; continue }
                        val kind = value.firstOrNull() ?: ' '
                        when (kind) {
                            '+' -> { if (newLeft <= 0) break; newLeft--; body += GitLine('+', null, newNumber++, value.drop(1)) }
                            '-' -> { if (oldLeft <= 0) break; oldLeft--; body += GitLine('-', oldNumber++, null, value.drop(1)) }
                            else -> { if (oldLeft <= 0 || newLeft <= 0) break; oldLeft--; newLeft--; body += GitLine(' ', oldNumber++, newNumber++, if (value.startsWith(' ')) value.drop(1) else value) }
                        }
                        i++
                    }
                    hunks += GitHunk(line, body); continue
                }
                i++
            }
            if (path.isEmpty() || path == "/dev/null") path = old.takeUnless { it.isEmpty() || it == "/dev/null" } ?: path
            files += GitFile(old, path, status, binary, hunks)
        }
        return files
    }
}
