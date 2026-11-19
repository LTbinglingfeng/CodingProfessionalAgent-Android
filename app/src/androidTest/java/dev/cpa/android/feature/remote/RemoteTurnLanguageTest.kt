package dev.cpa.android.feature.remote

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.localization.AppLocaleRule
import dev.cpa.android.data.repository.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RemoteTurnLanguageTest {
    @get:Rule(order = 0) val language = AppLocaleRule("en")
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun historyKeepsResultOwnershipAndRawArgumentsInEnglish() {
        fun message(id: String, name: String) = RemoteEntry(id, "assistant", "", details = RemoteEntryDetails(
            content = listOf(RemoteContent.ToolCall("same", name, buildJsonObject { put("path", "/目录/文件.kt") }))))
        val first = message("a", "read")
        val last = message("b", "write")
        val turn = RemoteTurn.Assistant(listOf(first, last), mapOf(
            "a" to mapOf("same" to RemoteEntry("r1", "toolResult", "读取原文")),
            "b" to mapOf("same" to RemoteEntry("r2", "toolResult", "写入错误原文", details = RemoteEntryDetails(isError = true)))
        ), null, live = true)
        compose.setContent { CpaTheme { RemoteAssistantTurn(turn, emptyMap(), true) } }
        compose.onNodeWithText("Read 文件.kt").assertDoesNotExist()
        compose.onNodeWithText("Wrote 文件.kt").performClick()
        compose.onNodeWithText("Read 文件.kt").performClick()
        compose.onNodeWithText("读取原文", substring = true).assertExists()
        compose.onAllNodesWithText("Wrote 文件.kt").onLast().performClick()
        compose.onNodeWithText("写入错误原文", substring = true).assertExists()
        compose.onNodeWithText("Error").assertExists()
        compose.onAllNodesWithText("/目录/文件.kt", substring = true).assertCountEquals(1)
        compose.onNodeWithText("读取原文", substring = true).assertDoesNotExist()
    }

    @Test fun completedTurnFoldsCopiesAndForksOriginalReply() {
        val first = RemoteEntry("first", "assistant", "先前过程")
        val compaction = RemoteEntry("compact", "compaction", "摘要")
        val last = RemoteEntry("last", "assistant", "最终回复 👨‍👩‍👧‍👦", details = RemoteEntryDetails(completedAt = 6000.0))
        val turn = mutableStateOf(RemoteTurn.Assistant(listOf(first, compaction, last), emptyMap(), 1000.0, live = true))
        var boundary: RemoteEntry? = null
        lateinit var clipboard: androidx.compose.ui.platform.ClipboardManager
        compose.setContent { CpaTheme {
            clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            RemoteAssistantTurn(turn.value, emptyMap(), true, onForkMessage = { boundary = it })
        } }
        compose.onNodeWithContentDescription("copy-reply").assertDoesNotExist()
        compose.runOnIdle { turn.value = turn.value.copy(live = false) }
        compose.onNodeWithText("先前过程").assertDoesNotExist()
        compose.onNodeWithText("Context compacted").assertExists()
        compose.onNodeWithText("Completed 5s").performClick()
        compose.onNodeWithText("先前过程").assertExists()
        compose.onNodeWithText("Completed 5s").performClick()
        compose.onNodeWithContentDescription("copy-reply").performClick()
        compose.runOnIdle { assertEquals(last.text, clipboard.getText()?.text) }
        compose.onNodeWithContentDescription("fork-here").performClick()
        compose.runOnIdle { assertEquals(last, boundary) }
    }
}
