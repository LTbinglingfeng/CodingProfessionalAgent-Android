package dev.cpa.android.feature.remote

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.data.repository.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test

class RemoteActivityUiTest {
    @get:Rule val compose = createComposeRule()
    private val call = RemoteContent.ToolCall("call|suffix", "read_file", buildJsonObject { put("path", "sample.kt") })
    private fun assistant(calls: List<RemoteContent>) = RemoteEntry("assistant", "assistant", "", details = RemoteEntryDetails(content = calls))
    private fun live(entry: RemoteEntry, results: Map<String, Map<String, RemoteEntry>> = emptyMap(), live: Boolean = true) =
        RemoteTurn.Assistant(listOf(entry), results, 0.0, live = live)

    @Test fun commandSummarySupportsGraphemesOnAndroid() {
        val command = RemoteContent.ToolCall("cmd", "bash", buildJsonObject { put("command", "👨‍👩‍👧‍👦".repeat(65)) })
        compose.setContent { CpaTheme { RemoteAssistantTurn(live(assistant(listOf(command))), emptyMap(), true) } }
        compose.onNodeWithText("正在运行 " + "👨‍👩‍👧‍👦".repeat(61) + "…").assertExists()
    }

    @Test fun liveToolBecomesFailedResultWithoutLosingExpandedDetails() {
        val results = mutableStateOf<Map<String, Map<String, RemoteEntry>>>(emptyMap())
        val running = mutableStateOf(mapOf("call" to RemoteRunningTool("read_file")))
        compose.setContent { CpaTheme { RemoteAssistantTurn(live(assistant(listOf(call)), results.value), running.value, true) } }
        compose.onNodeWithText("正在使用 Read File").assertExists().performClick()
        compose.onNodeWithText("sample.kt", substring = true).assertExists()
        compose.runOnIdle {
            running.value = emptyMap()
            results.value = mapOf("assistant" to mapOf("call" to RemoteEntry("result", "toolResult", "File missing", details = RemoteEntryDetails(toolCallId = "call", isError = true))))
        }
        compose.onNodeWithText("已使用 Read File").assertExists()
        compose.onNodeWithText("错误").assertExists()
        compose.onNodeWithText("File missing", substring = true).assertExists()
    }

    @Test fun consecutiveToolHistoryExpandsAndThinkingIsNotRendered() {
        val second = call.copy(id = "second", name = "write_file")
        val entry = assistant(listOf(call, RemoteContent.Thinking("private thought"), second))
        compose.setContent { CpaTheme { RemoteAssistantTurn(live(entry), emptyMap(), true) } }
        compose.onNodeWithText("private thought").assertDoesNotExist()
        compose.onAllNodesWithText("正在使用 Write File").assertCountEquals(1)
        compose.onNodeWithText("正在使用 Read File").assertDoesNotExist()
        compose.onNodeWithText("正在使用 Write File").performClick()
        compose.onNodeWithText("正在使用 Read File").assertExists()
        compose.onAllNodesWithText("正在使用 Write File").assertCountEquals(2)
        compose.onAllNodesWithText("正在使用 Write File").onFirst().performClick()
        compose.onNodeWithText("正在使用 Read File").assertDoesNotExist()
    }

    @Test fun finishedTurnFoldsActivityBehindHeader() {
        val entry = RemoteEntry("assistant", "assistant", "final reply", details = RemoteEntryDetails(content = listOf(call, RemoteContent.Text("final reply")),
            status = "done", createdAt = 0.0, completedAt = 65_000.0))
        compose.setContent { CpaTheme { RemoteAssistantTurn(live(entry, live = false), emptyMap(), true) } }
        compose.onNodeWithText("已完成 1m 5s").assertExists()
        compose.onNodeWithText("final reply").assertExists()
        compose.onNodeWithText("已使用 Read File").assertDoesNotExist()
        compose.onNodeWithText("已完成 1m 5s").performClick()
        compose.onNodeWithText("已使用 Read File").assertExists()
    }
}
