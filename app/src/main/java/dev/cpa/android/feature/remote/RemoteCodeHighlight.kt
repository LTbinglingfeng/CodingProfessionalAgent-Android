package dev.cpa.android.feature.remote

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

private val slashExtensions = "ts tsx js jsx mjs cjs go rs java c h cpp hpp cc cxx cs swift kt kts scala php json jsonc css scss sass less".split(' ').toSet()
private val hashExtensions = "py pyw rb sh bash zsh fish ps1 psm1 yaml yml toml pl pm r make mk dockerfile conf ini env".split(' ').toSet()
private val keywords = """
    func function return if else for while switch case default break continue struct class enum interface type
    import package from export let var const static final public private protected internal open override async await
    try catch throw throws raise except finally defer go chan select map range nil null None true false True False
    this self super new delete in is not and or def elif lambda with as pass yield impl trait pub use mod fn mut
    match where guard protocol extension some any do then end begin local readonly declare
""".trim().split(Regex("\\s+")).toSet()
private val identifiers = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")
private val numbers = Regex("""\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""")
private val hashComment = Regex("""(?<![\w$])#""")

private data class SyntaxColors(val keyword: Color, val string: Color, val comment: Color, val number: Color, val type: Color)
private val lightSyntax = SyntaxColors(Color(0xff9c29b3), Color(0xffc41917), Color(0xff29823d), Color(0xff1c4ad9), Color(0xff29738c))
private val darkSyntax = SyntaxColors(Color(0xffd978eb), Color(0xfffa8c85), Color(0xff78bf78), Color(0xff8cabfa), Color(0xff73c7c7))

@Composable
internal fun highlightedLine(line: String, path: String): AnnotatedString {
    val dark = isSystemInDarkTheme()
    return remember(line, path, dark) { highlightSourceLine(line, path, dark) }
}

internal fun highlightSourceLine(line: String, path: String, dark: Boolean): AnnotatedString = buildAnnotatedString {
    append(line)
    val palette = if (dark) darkSyntax else lightSyntax
    val name = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
    val ext = when {
        name == "dockerfile" || name.startsWith("dockerfile.") -> "dockerfile"
        name == "makefile" -> "make"
        else -> name.substringAfterLast('.', "")
    }
    val comment = when (ext) {
        in slashExtensions -> line.indexOf("//")
        in hashExtensions -> hashComment.find(line)?.range?.first ?: -1
        else -> -1
    }
    val end = if (comment < 0) line.length else comment
    if (comment >= 0) addStyle(SpanStyle(color = palette.comment), comment, line.length)
    val code = line.subSequence(0, end)
    // Pair possible opening quotes in linear time, including escaped characters.
    // This also lets scanning continue past an unmatched quote of another kind.
    val quotes = charArrayOf('"', '\'', '`')
    if (code.any { it in quotes }) {
        val closing = IntArray(end) { -1 }
        for (quote in quotes) {
            var next = -1
            var afterNext = -1
            for (i in end - 1 downTo 0) {
                val match = when (line[i]) {
                    quote -> { closing[i] = next; i }
                    '\\' -> afterNext
                    else -> next
                }
                afterNext = next
                next = match
            }
        }
        var i = 0
        while (i < end) {
            val close = closing[i]
            if (close > i) {
                addStyle(SpanStyle(color = palette.string), i, close + 1)
                i = close + 1
            } else i++
        }
    }
    for (word in identifiers.findAll(code)) {
        val color = when {
            word.value.first() in 'A'..'Z' -> palette.type
            word.value in keywords -> palette.keyword
            else -> continue
        }
        addStyle(SpanStyle(color = color), word.range.first, word.range.last + 1)
    }
    for (number in numbers.findAll(code))
        addStyle(SpanStyle(color = palette.number), number.range.first, number.range.last + 1)
}
