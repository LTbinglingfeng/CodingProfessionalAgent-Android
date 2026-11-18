package dev.cpa.android.feature.remote.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.Cpa
import dev.cpa.android.core.designsystem.cpaText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** iOS MarkdownStyle: 17pt body with ~5.5pt extra leading, 22/20/18 headings, 13pt mono code. */
private val Body = cpaText(17, lineHeight = 26.sp)

@Composable
internal fun RemoteMarkdown(text: String, wrapCode: Boolean = true, onLink: ((String) -> Unit)? = null) {
    val blocks by produceState<List<MarkdownBlock>?>(null, text) {
        value = withContext(Dispatchers.Default) { MarkdownBlocks.parse(text) }
    }
    val handler = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    val open: (String) -> Unit = onLink ?: { url ->
        linkError = runCatching {
            val scheme = android.net.Uri.parse(url).scheme?.lowercase()
            require(scheme in setOf("https", "http", "mailto", "tel"))
            handler.openUri(url)
        }.isFailure
    }
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer {
            if (blocks == null) Text(text, style = Body, color = Cpa.colors.label) else Blocks(blocks.orEmpty(), wrapCode, open)
        }
        if (linkError) Text(stringResource(R.string.link_open_failed), style = cpaText(13), color = Cpa.colors.red)
    }
}

private fun spacing(previous: MarkdownBlock?, next: MarkdownBlock): Int = when {
    previous == null -> 0
    previous is MarkdownBlock.Heading -> 10
    previous is MarkdownBlock.Paragraph && (next is MarkdownBlock.ListBlock || next is MarkdownBlock.Code || next is MarkdownBlock.Table) -> 12
    else -> 18
}

@Composable
private fun Blocks(blocks: List<MarkdownBlock>, wrap: Boolean, open: (String) -> Unit, compact: Boolean = false) {
    val c = Cpa.colors
    Column(Modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            val gap = if (compact) (if (index == 0) 0 else 8) else spacing(blocks.getOrNull(index - 1), block)
            if (gap > 0) Spacer(Modifier.height(gap.dp))
            when (block) {
                is MarkdownBlock.Paragraph -> Inline(block.text, open)
                is MarkdownBlock.Heading -> Inline(block.text, open, when (block.level) {
                    1 -> cpaText(22, FontWeight.SemiBold, lineHeight = 29.sp)
                    2 -> cpaText(20, FontWeight.SemiBold, lineHeight = 27.sp)
                    3 -> cpaText(18, FontWeight.SemiBold, lineHeight = 25.sp)
                    else -> cpaText(17, FontWeight.SemiBold, lineHeight = 24.sp)
                })
                is MarkdownBlock.Code -> RemoteCodeBlock(block.text, wrap)
                is MarkdownBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.gray4, RoundedCornerShape(2.dp)))
                    Box(Modifier.weight(1f).padding(start = 14.dp)) {
                        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.secondaryLabel) { Blocks(block.blocks, wrap, open) }
                    }
                }
                is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val digits = ((block.start ?: 1) + block.items.size - 1).toString().length
                    block.items.forEachIndexed { index, items -> Row(Modifier.fillMaxWidth()) {
                        if (block.start != null) Text("${block.start.toLong() + index}.", Modifier.width(maxOf(25, (digits + 1) * 10).dp), style = Body, color = c.label)
                        else Box(Modifier.width(25.dp).height(26.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(6.dp).background(c.label, CircleShape)) }
                        Spacer(Modifier.width(4.dp))
                        Box(Modifier.weight(1f)) { Blocks(items, wrap, open, compact = true) }
                    } }
                }
                is MarkdownBlock.Table -> TableBlock(block, open)
                MarkdownBlock.Rule -> Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            }
        }
    }
}

@Composable
private fun TableBlock(block: MarkdownBlock.Table, open: (String) -> Unit) {
    val c = Cpa.colors
    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    val wide = (0 until columns).map { col -> (listOf(block.header) + block.rows).any { (it.getOrNull(col)?.length ?: 0) > 24 } }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.padding(0.5.dp).border(0.5.dp, c.separator, shape).clip(shape)) {
            (listOf(block.header) + block.rows).forEachIndexed { rowIndex, row ->
                Row(Modifier.height(IntrinsicSize.Min).background(if (rowIndex == 0) c.secondaryBackground else c.background)) {
                    (0 until columns).forEach { col ->
                        if (col > 0) Box(Modifier.width(0.5.dp).fillMaxHeight().background(c.separator))
                        Box(Modifier.then(if (wide[col]) Modifier.width(240.dp) else Modifier.widthIn(min = 60.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                            Inline(row.getOrNull(col).orEmpty(), open, cpaText(15, if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 21.sp))
                        }
                    }
                }
                if (rowIndex < block.rows.size) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            }
        }
    }
}

@Composable
internal fun RemoteCodeBlock(text: String, wrap: Boolean) {
    val c = Cpa.colors
    val style = cpaText(13, mono = true, lineHeight = 19.sp)
    Box(Modifier.fillMaxWidth().background(c.codeBackground, RoundedCornerShape(12.dp))) {
        if (wrap) Text(text, Modifier.padding(12.dp), style = style, color = c.label)
        else Box(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
            Text(text, softWrap = false, style = style, color = c.label)
        }
    }
}

@Composable
private fun Inline(source: String, open: (String) -> Unit, style: TextStyle = Body) {
    val parsed by produceState<InlineText?>(null, source) { value = withContext(Dispatchers.Default) { MarkdownInline.parse(source) } }
    val c = Cpa.colors
    val color = androidx.compose.material3.LocalContentColor.current
    val value = parsed
    val rendered = remember(value, source, c, open) {
        if (value == null) AnnotatedString(source) else buildAnnotatedString {
            // Links read as primary text with a trailing ↗ instead of an underline (iOS MarkdownView).
            val links = value.spans.filter { it.kind == "link" && it.url != null }.sortedBy { it.end }
            var cursor = 0
            val shifts = mutableListOf<Int>()
            for (link in links) {
                append(value.text.substring(cursor, link.end)); append("\u2009↗"); cursor = link.end; shifts += link.end
            }
            append(value.text.substring(cursor))
            fun map(offset: Int) = offset + shifts.count { it < offset } * 2
            value.spans.forEach { span ->
                val start = map(span.start); val end = map(span.end)
                when (span.kind) {
                    "bold" -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, end)
                    "italic" -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                    "strike" -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                    "code" -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = c.secondaryLabel, fontSize = 15.sp), start, end)
                    "link" -> span.url?.let { url -> addLink(LinkAnnotation.Url(url, TextLinkStyles(style = SpanStyle(color = c.label)),
                        linkInteractionListener = { open(url) }), start, end + 2) }
                }
            }
        }
    }
    Text(rendered, Modifier.fillMaxWidth(), style = style, color = if (color == androidx.compose.ui.graphics.Color.Unspecified) c.label else color)
}
