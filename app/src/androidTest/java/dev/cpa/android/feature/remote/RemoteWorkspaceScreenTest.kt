package dev.cpa.android.feature.remote

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteWorkspaceScreenTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cleanup() { scope.cancel() }
    internal class Fake : RemoteDesktopRepository {
        override val skillUsage = RemoteSkillUsage()
        override val connections = MutableStateFlow(emptyList<ConnectionSummary>())
        override val projects = MutableStateFlow(mapOf("c" to listOf(RemoteProject("p", "Project", "/src"))))
        override val sessions = MutableStateFlow(emptyMap<String, List<RemoteSession>>())
        override fun observeThread(connectionId: String, sessionId: String) = MutableStateFlow(RemoteThread(connectionId, sessionId))
        override suspend fun saveConnection(id: String?, name: String, address: String, password: String?, allowCleartext: Boolean) = error("Not used")
        override suspend fun removeConnection(id: String) = Unit
        override suspend fun connect(id: String) = Unit
        override suspend fun disconnect(id: String) = Unit
        override suspend fun refresh(id: String) = Unit
        override suspend fun openThread(connectionId: String, sessionId: String) = Unit
        override suspend fun createSession(connectionId: String, projectId: String?, title: String) = error("Not used")
        val creationOrder = mutableListOf<String>()
        var configured: RemoteNewSessionOptions? = null
        var createdTarget: String? = null
        var firstPrompt: String? = null
        override suspend fun createConfiguredSession(connectionId: String, sessionId: String, title: String, options: RemoteNewSessionOptions) {
            creationOrder += "create"; configured = options; createdTarget = sessionId
        }
        override suspend fun send(connectionId: String, sessionId: String, text: String, modelId: String?) {
            creationOrder += "send"; firstPrompt = text
            assertEquals(createdTarget, sessionId)
        }
        override suspend fun stop(connectionId: String, sessionId: String) = Unit
        override fun onForeground() = Unit
        override fun onBackground() = Unit
        override suspend fun homeDirectory(connectionId: String) = "/home"
        override suspend fun directoryEntries(connectionId: String, path: String) = listOf(RemoteDirectoryEntry("src", true), RemoteDirectoryEntry(".hidden", true))
        var forkWorktree: RemoteWorktreeFork? = null
        var forkTarget: String? = null
        override suspend fun gitBranches(connectionId: String, path: String) = RemoteBranches("main", listOf("main", "dev"))
        override suspend fun environmentProjects(connectionId: String) = listOf(RemoteProject("env", "Environment", "/env"))
        override suspend fun forkSession(connectionId: String, sessionId: String, newSessionId: String, atEntryId: String?, worktree: RemoteWorktreeFork?) {
            assertEquals("c", connectionId); assertEquals("s", sessionId)
            forkTarget = newSessionId; forkWorktree = worktree
        }
        var savedPaths: List<String>? = null
        var savedName: String? = null
        override suspend fun updateProject(connectionId: String, projectId: String, name: String, paths: List<String>) {
            assertEquals("c", connectionId); assertEquals("p", projectId)
            savedName = name; savedPaths = paths
        }
    }
    @Test fun selectingDirectoryUsesDesktopPathAndHiddenToggle() {
        val controller = RemoteDirectoryController("c", Fake(), scope)
        var selected: String? = null
        compose.setContent { CpaTheme { RemoteDirectoryBrowser(controller, {}) { selected = it } } }
        compose.waitUntil(5_000) { controller.state.value.path == "/home" }
        val directoryList = compose.onNodeWithTag("desktop-directory-list")
        directoryList.performScrollToNode(hasText("src"))
        compose.onNodeWithText(".hidden").assertDoesNotExist()
        compose.onNodeWithTag("directory-menu").performClick()
        compose.onNodeWithText("显示隐藏文件夹").performClick()
        directoryList.performScrollToNode(hasText(".hidden"))
        compose.onNodeWithText(".hidden").assertIsDisplayed()
        directoryList.performScrollToNode(hasText("src"))
        compose.onNodeWithText("src").performClick()
        compose.waitUntil(5_000) { controller.state.value.path == "/home/src" }
        compose.onNodeWithText("选择此目录").performClick()
        compose.runOnIdle { assertEquals("/home/src", selected) }
    }
    @Test fun directorySelectionAndCancelStayReachableInShortWindow() {
        val controller = RemoteDirectoryController("c", Fake(), scope)
        var selected: String? = null
        var cancelled = false
        compose.setContent { CpaTheme { RemoteDirectoryBrowser(controller, { cancelled = true }) { selected = it } } }
        compose.waitUntil(5_000) { controller.state.value.path == "/home" }
        compose.onNodeWithText("选择此目录").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("/home", selected) }
        compose.onNodeWithTag("desktop-directory-list").performScrollToNode(hasText("src"))
        compose.onNodeWithText("src").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { controller.state.value.path == "/home/src" }
        compose.onNodeWithText("选择此目录").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("/home/src", selected) }
        compose.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
    @Test fun sourceFolderRemovalAndNameEditReachRepository() {
        val repo = Fake()
        val controller = RemoteProjectController(SavedStateHandle(), repo, scope)
        controller.open("c", repo.projects.value["c"]!!.single())
        val projects = repo.projects.value
        compose.setContent { CpaTheme { RemoteProjectEditor(controller, projects) } }
        compose.onNode(hasSetTextAction()).performTextReplacement("Renamed")
        compose.onNodeWithContentDescription("remove-folder:/src").performScrollTo().performClick()
        compose.onNodeWithTag("project-save").performClick()
        compose.waitUntil(5_000) { repo.savedName != null }
        compose.runOnIdle {
            assertEquals("Renamed", repo.savedName)
            assertEquals(emptyList<String>(), repo.savedPaths)
            assertNull(controller.state.value.projectId)
        }
    }
}
