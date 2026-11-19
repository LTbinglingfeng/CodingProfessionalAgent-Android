package dev.cpa.android.feature.remote

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.data.repository.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test

class RemoteTurnUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nestedLaunchAppearsBetweenToolGroupsAndDisappearsWhenLaunchFails() {
        val exec = RemoteContent.ToolCall("exec", "exec", JsonNull)
        val read = RemoteContent.ToolCall("read", "read", JsonNull)
        val entry = RemoteEntry("a", "assistant", "", details = RemoteEntryDetails(content = listOf(exec, read)))
        val turn = RemoteTurn.Assistant(listOf(entry), emptyMap(), null, live = true)
        fun details(status: String) = mapOf("exec" to RemoteRunningTool(details = buildJsonObject {
            put("nestedTools", buildJsonArray { add(buildJsonObject {
                put("id", "child"); put("name", "spawn_agent"); put("status", status)
                put("args", buildJsonObject { put("name", "Worker"); put("prompt", "Inspect") })
            }) })
        }))
        val running = mutableStateOf(details("running"))
        compose.setContent { CpaTheme { RemoteAssistantTurn(turn, running.value, true) } }
        compose.onNodeWithTag("agent-pill:part:child").assertIsNotEnabled()
        compose.onNodeWithText("正在执行代码模式").assertExists()
        compose.onNodeWithText("正在读取 read").assertExists()
        compose.runOnIdle { running.value = details("error") }
        compose.onNodeWithTag("agent-pill:part:child").assertDoesNotExist()
    }
    @Test fun consecutiveStagesShareHistoryAndPreserveResults() {
        fun message(id: String, name: String) = RemoteEntry(id, "assistant", "", details = RemoteEntryDetails(content = listOf(RemoteContent.ToolCall("same", name, JsonNull))))
        val first = message("a", "read"); val last = message("b", "write")
        val turn = RemoteTurn.Assistant(listOf(first, last), mapOf("a" to mapOf("same" to RemoteEntry("r1", "toolResult", "first result")), "b" to mapOf("same" to RemoteEntry("r2", "toolResult", "second result"))), null, live = true)
        compose.setContent { CpaTheme { RemoteAssistantTurn(turn, emptyMap(), true) } }
        compose.onNodeWithText("已读取 read").assertDoesNotExist()
        compose.onNodeWithText("已写入 write").performClick()
        compose.onNodeWithText("已读取 read").performClick()
        compose.onNodeWithText("first result").assertExists()
        compose.onAllNodesWithText("已写入 write").onLast().performClick()
        compose.onNodeWithText("second result").assertExists()
    }
    @Test fun skillOnlyRunHidesActivityUntilReplyAppears() {
        val skill = RemoteContent.ToolCall("skill", "read", buildJsonObject { put("path", "/review/SKILL.md") })
        val entry = RemoteEntry("a", "assistant", "", details = RemoteEntryDetails(content = listOf(skill)))
        val turn = mutableStateOf(RemoteTurn.Assistant(listOf(entry), emptyMap(), null, live = true))
        compose.setContent { CpaTheme { RemoteAssistantTurn(turn.value, emptyMap(), true) } }
        compose.onNodeWithText("已读取Review 技能").assertDoesNotExist()
        compose.runOnIdle { turn.value = turn.value.copy(items = listOf(entry.copy(details = entry.details.copy(content = listOf(skill, RemoteContent.Text("reply")))))) }
        compose.onNodeWithText("已读取Review 技能").assertExists()
        compose.runOnIdle { turn.value = turn.value.copy(live = false) }
        compose.onNodeWithText("已读取Review 技能").assertDoesNotExist()
        compose.onNodeWithText("已完成").performClick()
        compose.onNodeWithText("已读取Review 技能").assertExists()
    }
    @Test fun completedReplyCopiesOnlyFinalTextAndForksLastAssistantBoundary() {
        val first = RemoteEntry("first", "assistant", "early")
        val last = RemoteEntry("last", "assistant", "final")
        val turn = mutableStateOf(RemoteTurn.Assistant(listOf(first, last), emptyMap(), null, live = true))
        var boundary: RemoteEntry? = null
        lateinit var clipboard: androidx.compose.ui.platform.ClipboardManager
        compose.setContent { CpaTheme {
            clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            RemoteAssistantTurn(turn.value, emptyMap(), true, onForkMessage = { boundary = it })
        } }
        compose.onNodeWithContentDescription("fork-here").assertDoesNotExist()
        compose.onNodeWithContentDescription("copy-reply").assertDoesNotExist()
        compose.runOnIdle { turn.value = turn.value.copy(live = false) }
        compose.onNodeWithContentDescription("copy-reply").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("final", clipboard.getText()?.text) }
        compose.onNodeWithContentDescription("fork-here").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(last, boundary) }
        compose.runOnIdle { turn.value = turn.value.copy(items = listOf(first, last.copy(details = RemoteEntryDetails(status = "error")))) }
        compose.onNodeWithContentDescription("fork-here").assertDoesNotExist()
        compose.onNodeWithText("生成失败").assertExists()
    }
    @Test fun completionFoldsStagesAndExpansionRestoresCompactionAndTools() {
        val first = RemoteEntry("a", "assistant", "earlier stage")
        val compaction = RemoteEntry("c", "compaction", "summary")
        val last = RemoteEntry("b", "assistant", "final reply")
        val turn = mutableStateOf(RemoteTurn.Assistant(listOf(first, compaction, last), emptyMap(), null, live = true))
        compose.setContent { CpaTheme { RemoteAssistantTurn(turn.value, emptyMap(), true) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("earlier stage").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { turn.value = turn.value.copy(live = false) }
        compose.onNodeWithText("earlier stage").assertDoesNotExist()
        compose.waitUntil(5000) { compose.onAllNodesWithText("final reply").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("上下文已压缩").assertExists()
        compose.onNodeWithContentDescription("copy-reply").assertExists()
        compose.onNodeWithTag("turn-header").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("earlier stage").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("turn-header").performClick()
        compose.onNodeWithText("earlier stage").assertDoesNotExist()
    }
}
