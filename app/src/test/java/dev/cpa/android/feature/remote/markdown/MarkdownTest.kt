package dev.cpa.android.feature.remote.markdown

import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    @Test fun headingsParagraphsQuotesAndRulesKeepNativeSubset() {
        val blocks = MarkdownBlocks.parse("# 标题\r\n\r\n第一行\r\n第二行\r\n\r\n> 引用\r\n>\r\n> ## 小标题\r\n\r\n---")
        assertEquals(MarkdownBlock.Heading("标题", 1), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("第一行\n第二行"), blocks[1])
        assertEquals(MarkdownBlock.Quote(listOf(MarkdownBlock.Paragraph("引用"), MarkdownBlock.Heading("小标题", 2))), blocks[2])
        assertEquals(MarkdownBlock.Rule, blocks[3])
        assertEquals(listOf(MarkdownBlock.Paragraph("####### literal\n#no-space")), MarkdownBlocks.parse("####### literal\n#no-space"))
    }
    @Test fun streamingFenceKeepsLiteralWhitespaceAndRequiresMatchingClosingLength() {
        val text = "  ````kotlin\n  val x = \"**raw**\"\n  ```\n    indented\n  `````\nend"
        assertEquals(listOf(MarkdownBlock.Code("val x = \"**raw**\"\n```\n  indented"), MarkdownBlock.Paragraph("end")), MarkdownBlocks.parse(text))
        assertEquals(listOf(MarkdownBlock.Code("line one\nline two")), MarkdownBlocks.parse("~~~python\nline one\nline two"))
    }
    @Test fun nestedListsPreserveStartNumbersAndLazyContinuation() {
        val blocks = MarkdownBlocks.parse("3. parent\n   continuation\n   - nested\n   - second\n4. sibling\n\nafter")
        val list = blocks.first() as MarkdownBlock.ListBlock
        assertEquals(3, list.start); assertEquals(2, list.items.size)
        assertEquals(MarkdownBlock.Paragraph("parent\ncontinuation"), list.items[0][0])
        assertEquals(listOf(listOf(MarkdownBlock.Paragraph("nested")), listOf(MarkdownBlock.Paragraph("second"))), (list.items[0][1] as MarkdownBlock.ListBlock).items)
        assertEquals(MarkdownBlock.Paragraph("after"), blocks.last())
    }
    @Test fun fencedCodeInsideListDoesNotBecomeSiblingItems() {
        val block = MarkdownBlocks.parse("- ```text\n  - literal\n  # not a heading\n  ```\n- next").single() as MarkdownBlock.ListBlock
        assertEquals(listOf(MarkdownBlock.Code("- literal\n# not a heading")), block.items[0])
        assertEquals(listOf(MarkdownBlock.Paragraph("next")), block.items[1])
    }
    @Test fun tablesHandleEscapedPipesMissingCellsAndFollowingBlocks() {
        val blocks = MarkdownBlocks.parse("| A | B |\n| :- | -: |\n| x\\|y | z | ignored |\n| short |\n\n## After")
        assertEquals(MarkdownBlock.Table(listOf("A", "B"), listOf(listOf("x|y", "z"), listOf("short", ""))), blocks.first())
        assertEquals(MarkdownBlock.Heading("After", 2), blocks.last())
        assertEquals(listOf(MarkdownBlock.Paragraph("a | b\n--- | nope")), MarkdownBlocks.parse("a | b\n--- | nope"))
    }
    @Test fun pathologicalQuoteNestingRemainsReadableWithoutStackOverflow() {
        var block = MarkdownBlocks.parse(">".repeat(2000) + " content").single()
        var depth = 0
        while (block is MarkdownBlock.Quote) { depth++; block = block.blocks.single() }
        assertEquals(64, depth)
        assertTrue((block as MarkdownBlock.Paragraph).text.endsWith("content"))
    }
    @Test fun inlineStylesPreserveChineseEmojiAndNestedEmphasis() {
        val inline = MarkdownInline.parse("😀 **粗体 *斜体*** 和 `a*b` ~~删除~~")
        assertEquals("😀 粗体 斜体 和 a*b 删除", inline.text)
        fun text(kind: String) = inline.spans.filter { it.kind == kind }.map { inline.text.substring(it.start, it.end) }
        assertEquals(listOf("粗体 斜体"), text("bold"))
        assertEquals(listOf("斜体"), text("italic"))
        assertEquals(listOf("a*b"), text("code"))
        assertEquals(listOf("删除"), text("strike"))
    }
    @Test fun linksEscapesAndLiteralHtmlNeverProduceExecutableContent() {
        val inline = MarkdownInline.parse("[**资料**](https://example.com/a?q=1) \\*literal\\* <script>alert(1)</script>")
        assertEquals("资料 *literal* <script>alert(1)</script>", inline.text)
        assertEquals("https://example.com/a?q=1", inline.spans.single { it.kind == "link" }.url)
        assertTrue(inline.spans.any { it.kind == "bold" && it.start == 0 && it.end == 2 })
    }
    @Test fun incompleteInlineSyntaxAndNewlinesRemainVisibleWhileStreaming() {
        assertEquals("unfinished **bold", MarkdownInline.parse("unfinished **bold").text)
        assertEquals("line one\nline two", MarkdownInline.parse("line one\nline two").text)
        assertEquals("# literal", MarkdownInline.parse("# literal").text)
    }
}
