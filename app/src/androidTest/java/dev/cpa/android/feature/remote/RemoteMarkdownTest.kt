package dev.cpa.android.feature.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.feature.remote.markdown.RemoteCodeBlock
import dev.cpa.android.feature.remote.markdown.RemoteMarkdown
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteMarkdownTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nativeMarkdownRendersLinksAndIncompleteStreamingCode() {
        val source = mutableStateOf("# 标题\n\n**粗体**\n\n[资料](https://example.com/docs)\n\n```kotlin\nval x = \"**literal**\"")
        var opened: String? = null
        compose.setContent { CpaTheme { RemoteMarkdown(source.value, onLink = { opened = it }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("标题").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("粗体").fetchSemanticsNodes().isNotEmpty() }
        // The Text fills the row; tap the link glyphs, not the empty center of that row.
        compose.onNodeWithText("资料", substring = true).performTouchInput { click(Offset(10f, centerY)) }
        compose.runOnIdle { assertEquals("https://example.com/docs", opened) }
        compose.onNodeWithText("val x = \"**literal**\"").assertExists()
        compose.runOnIdle { source.value += "\nprintln(x)\n```\n\n完成" }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("完成").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("val x = \"**literal**\"\nprintln(x)").assertExists()
    }
    @Test fun codeWrapSwitchesBetweenHorizontalScrollingAndWrappedText() {
        val wrap = mutableStateOf(false)
        val code = "val longLine = \"" + "abcdef".repeat(100) + "\""
        compose.setContent { CpaTheme { Box(Modifier.width(240.dp)) { SelectionContainer { RemoteCodeBlock(code, wrap.value) } } } }
        val horizontal = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        compose.onNode(horizontal).assertExists().performTouchInput { swipeLeft() }
        val range = compose.onNode(horizontal).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        compose.runOnIdle { assertTrue(range.value() > 0) }
        compose.runOnIdle { wrap.value = true }
        compose.onNode(horizontal).assertDoesNotExist()
        compose.onNodeWithText(code).assertExists()
    }
}
