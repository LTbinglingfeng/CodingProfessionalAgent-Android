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

class RemoteWorkspaceLanguageTest {
    @get:Rule(order = 0) val language = dev.cpa.android.core.localization.AppLocaleRule("en")
    @get:Rule(order = 1) val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cleanup() { scope.cancel() }
    @Test fun selectingDirectoryUsesDesktopPathAndHiddenToggle() {
        val controller = RemoteDirectoryController("c", RemoteWorkspaceScreenTest.Fake(), scope)
        var selected: String? = null
        compose.setContent { CpaTheme { RemoteDirectoryBrowser(controller, {}) { selected = it } } }
        compose.waitUntil(5_000) { controller.state.value.path == "/home" }
        val directoryList = compose.onNodeWithTag("desktop-directory-list")
        directoryList.performScrollToNode(hasText("src"))
        compose.onNodeWithText(".hidden").assertDoesNotExist()
        compose.onNodeWithTag("directory-menu").performClick()
        compose.onNodeWithText("Show Hidden Folders").performClick()
        directoryList.performScrollToNode(hasText(".hidden"))
        compose.onNodeWithText(".hidden").assertIsDisplayed()
        directoryList.performScrollToNode(hasText("src"))
        compose.onNodeWithText("src").performClick()
        compose.waitUntil(5_000) { controller.state.value.path == "/home/src" }
        compose.onNodeWithText("Select This Directory").performClick()
        compose.runOnIdle { assertEquals("/home/src", selected) }
    }
    @Test fun sourceFolderRemovalAndNameEditReachRepository() {
        val repo = RemoteWorkspaceScreenTest.Fake()
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
