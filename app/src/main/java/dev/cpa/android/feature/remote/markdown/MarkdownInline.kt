package dev.cpa.android.feature.remote.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

/** Platform-independent inline spans; no HTML renderer, WebView, scripts or remote image loading. */
data class InlineSpan(val start: Int, val end: Int, val kind: String, val url: String? = null)
data class InlineText(val text: String, val spans: List<InlineSpan>)
object MarkdownInline {
    private val parser = Parser.builder().enabledBlockTypes(emptySet()).extensions(listOf(StrikethroughExtension.create())).build()
    fun parse(value: String): InlineText {
        val root = parser.parse(value)
        val text = StringBuilder(); val spans = mutableListOf<InlineSpan>()
        data class Visit(val node: Node, val closing: Boolean = false, val start: Int = 0)
        val stack = ArrayDeque<Visit>(); stack.addLast(Visit(root))
        while (stack.isNotEmpty()) {
            val visit = stack.removeLast(); val node = visit.node
            if (visit.closing) {
                val kind = when(node) { is StrongEmphasis -> "bold"; is Emphasis -> "italic"; is Strikethrough -> "strike"; is Link -> "link"; else -> null }
                if (kind != null && text.length > visit.start) spans += InlineSpan(visit.start, text.length, kind, (node as? Link)?.destination)
                continue
            }
            when (node) {
                is Text -> text.append(node.literal)
                is Code -> { val start = text.length; text.append(node.literal); spans += InlineSpan(start, text.length, "code") }
                is SoftLineBreak, is HardLineBreak -> text.append('\n')
                is HtmlInline -> text.append(node.literal)
                else -> {
                    // Treat image alt text as text, as the iOS inline Text view does; attachments render separately.
                    if (node is Paragraph && text.isNotEmpty()) text.append("\n\n")
                    stack.addLast(Visit(node, true, text.length))
                    var child = node.lastChild
                    while (child != null) { stack.addLast(Visit(child)); child = child.previous }
                }
            }
        }
        return InlineText(text.toString(), spans)
    }
}
