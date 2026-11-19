package dev.cpa.android.feature.remote

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.data.repository.*
import dev.cpa.android.data.repository.git.GitDiffParser
import dev.cpa.android.data.repository.git.GitRepo
import dev.cpa.android.data.repository.git.GitReview
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PanelsAndAgentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun changesPanelOpensFileRelativeToRepoRootAndSwitchesComparison() {
        val repo = UiFakeRepository()
        repo.sessions.value = mapOf("c" to listOf(RemoteSession("s", "Task", "p", "feature")))
        repo.review = GitReview(GitRepo("/repo", "feature", "main", listOf("main", "feature"), null, false),
            GitDiffParser.parse("diff --git a/src/f.kt b/src/f.kt\n--- a/src/f.kt\n+++ b/src/f.kt\n@@ -1 +1 @@\n-old\n+new\n"), "HEAD", workingTree = true)
        val vm = threadViewModel(repo)
        compose.setContent { CpaTheme { SessionPanelSheet(vm, true, false) {} } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("f.kt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("src").assertExists()
        compose.onNodeWithText("第 1-1 行").assertExists()
        compose.onNodeWithText("new").assertExists()
        compose.onNodeWithText("Project").assertExists()
        compose.onNodeWithContentDescription("open:src/f.kt").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("file contents").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertTrue(repo.calls.contains("read:/repo/src/f.kt")) }
        compose.onNodeWithTag("preview-close").performClick()
        compose.onNodeWithTag("panel-expand-all").performClick()
        compose.onNodeWithText("new").assertDoesNotExist()
        compose.onNodeWithTag("panel-base").performClick()
        compose.onNodeWithText("origin/feature").performClick()
        compose.waitUntil(5_000) { repo.calls.any { it == "git:/src:feature:feature" } }
        compose.onNodeWithTag("panel-base").performClick()
        compose.onNodeWithText("工作区更改").performClick()
        compose.waitUntil(5_000) { repo.calls.count { it == "git:/src:null:feature" } >= 2 }
    }

    @Test fun nonRepositoryAndNoProjectStatesAreExplained() {
        val repo = UiFakeRepository()
        val vm = threadViewModel(repo)
        compose.setContent { CpaTheme { SessionPanelSheet(vm, true, false) {} } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("当前项目不是 Git 仓库").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/src").assertExists()
        compose.onNodeWithText("所有文件").performClick()
        compose.onNodeWithText("文件").assertExists()
    }

    @Test fun fileTreeExpandsFiltersAndPreviewsReadOnly() {
        val repo = UiFakeRepository().apply {
            files = mapOf("/project" to listOf(RemoteDirectoryEntry("src", true), RemoteDirectoryEntry(".git", true)),
                "/project/src" to listOf(RemoteDirectoryEntry("Main.kt", false)))
            fileText = "first\r\nsecond"
        }
        lateinit var controller: RemoteFilesController
        lateinit var clipboard: androidx.compose.ui.platform.ClipboardManager
        compose.setContent { CpaTheme {
            val scope = rememberCoroutineScope()
            controller = remember { RemoteFilesController("c", repo, scope) }
            clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            androidx.compose.foundation.layout.Box(Modifier.height(600.dp)) { FileTree(controller, true) }
            FilePreviewSheet(controller, true)
        } }
        compose.runOnIdle { controller.root("/project") }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("src").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(".git").assertDoesNotExist()
        compose.onNodeWithTag("file:/project/src").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Main.kt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("搜索文件").performTextInput("Main")
        compose.onNodeWithText("Main.kt").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("second").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("src/Main.kt").assertExists()
        compose.onNodeWithTag("preview-copy").performClick()
        compose.runOnIdle { assertEquals("/project/src/Main.kt", clipboard.getText()?.text); assertTrue(repo.calls.contains("read:/project/src/Main.kt")) }
        compose.onNodeWithTag("preview-close").performClick()
        compose.onNodeWithText("second").assertDoesNotExist()
    }

    @Test fun subAgentPillOpensRecordAndPendingLaunchIsNotClickable() {
        val repo = UiFakeRepository()
        val spawn = RemoteContent.ToolCall("spawn|call", "spawn_agent", kotlinx.serialization.json.buildJsonObject {
            put("name", kotlinx.serialization.json.JsonPrimitive("Worker")); put("prompt", kotlinx.serialization.json.JsonPrimitive("Inspect")) })
        repo.thread("s").value = RemoteThread("c", "s", running = true, entries = listOf(RemoteEntry("u", "user", "go"),
            RemoteEntry("a", "assistant", "", details = RemoteEntryDetails(content = listOf(spawn)))))
        var opened: RemoteSubAgent? = null
        val vm = threadViewModel(repo)
        compose.setContent { CpaTheme { RemoteThreadRoute(vm, onSubAgent = { opened = it }) {} } }
        compose.onNodeWithTag("agent-pill:part:spawn|call").assertExists().performClick()
        compose.runOnIdle { assertNull(opened) }
        val record = RemoteSubAgent("agent", "s", "child", "Worker", "queued", "m", 0.0, 1.0, parentToolCallId = "spawn|record")
        compose.runOnIdle { repo.thread("s").value = repo.thread("s").value.copy(subAgents = listOf(record)) }
        compose.onNodeWithText("（排队中）").assertExists()
        compose.onNodeWithTag("agent-pill:agent").performClick()
        compose.runOnIdle { assertEquals(record, opened) }
    }

    @Test fun childLoadRetryReloadsTheChildNotTheParent() {
        val repo = object : UiFakeRepository() {
            override suspend fun openThread(connectionId: String, sessionId: String) {
                super.openThread(connectionId, sessionId)
                if (sessionId == "child") {
                    if (calls.count { it == "open:child" } == 1) error("load failed")
                    thread("child").value = RemoteThread("c", "child", entries = listOf(RemoteEntry("u", "user", "reloaded")))
                }
            }
        }
        repo.thread("s").value = RemoteThread("c", "s", subAgents = listOf(RemoteSubAgent("a", "s", "child", "Worker", "completed", "m", 0.0, 1.0)))
        val vm = threadViewModel(repo)
        compose.setContent { CpaTheme { SubAgentRoute(vm, "a", emptyMap(), onSubAgent = {}) {} } }
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("reloaded").assertExists()
        compose.runOnIdle { assertEquals(1, repo.calls.count { it == "open:s" }) }
    }

    @Test fun subAgentScreenShowsReadOnlyChildAndNestedLaunches() {
        val repo = UiFakeRepository()
        val first = RemoteSubAgent("a", "s", "child", "Worker", "completed", "m", 0.0, 2000.0, completedAt = 2000.0)
        val nestedCall = RemoteContent.ToolCall("n1", "spawn_agent", kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive("Nested")) })
        val second = first.copy(id = "b", parentSessionId = "child", sessionId = "grandchild", name = "Nested", parentToolCallId = "n1")
        repo.thread("s").value = RemoteThread("c", "s", subAgents = listOf(first))
        repo.thread("child").value = RemoteThread("c", "child", entries = listOf(RemoteEntry("cu", "user", "child prompt"),
            RemoteEntry("a1", "assistant", "child answer", details = RemoteEntryDetails(content = listOf(RemoteContent.Text("child answer"), nestedCall), status = "done"))),
            subAgents = listOf(second))
        var nested: RemoteSubAgent? = null
        val vm = threadViewModel(repo)
        compose.setContent { CpaTheme { SubAgentRoute(vm, "a", emptyMap(), onSubAgent = { nested = it }) {} } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("child prompt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Worker").assertExists()
        compose.onNodeWithText("已完成 2s · m", substring = true).assertExists()
        compose.onNodeWithTag("thread-composer-card").assertDoesNotExist()
        compose.onNodeWithTag("turn-header").performClick()
        compose.onNodeWithTag("agent-pill:b").performClick()
        compose.runOnIdle { assertEquals(second, nested); assertTrue(repo.calls.contains("open:child")) }
    }

    @Test fun completedSubAgentOpensAtPrompt() = openingPosition(false)
    @Test fun runningSubAgentFollowsOutput() = openingPosition(true)

    private fun openingPosition(running: Boolean) {
        val repo = UiFakeRepository()
        val agent = RemoteSubAgent("a", "s", "child", "Worker", if (running) "running" else "completed", "m", 0.0, 2000.0)
        repo.thread("s").value = RemoteThread("c", "s", subAgents = listOf(agent))
        repo.thread("child").value = RemoteThread("c", "child", loading = true)
        val vm = threadViewModel(repo)
        compose.setContent { CpaTheme { SubAgentRoute(vm, "a", emptyMap(), onSubAgent = {}) {} } }
        compose.runOnIdle { repo.thread("child").value = RemoteThread("c", "child", entries = (0 until 40).map {
            RemoteEntry("e$it", if (it % 2 == 0) "user" else "assistant", "Child message $it\n" + "history\n".repeat(5)) }) }
        compose.waitForIdle()
        val position = compose.onNodeWithTag("remote-timeline").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        if (running) compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        else { assertEquals(0f, position, .01f); compose.onNodeWithTag("jump-latest").assertIsDisplayed() }
    }
}
