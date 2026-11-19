package dev.cpa.android.feature.remote

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.localization.AppLocaleRule
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteHomeComposerRouteTest {
    @get:Rule(order = 0) val language = AppLocaleRule("en")
    @get:Rule(order = 1) val compose = createComposeRule()
    @Test fun homeInputPreparesFormAndCommandsDoNotSend() {
        val base = RemoteWorkspaceScreenTest.Fake()
        base.connections.value = listOf(ConnectionSummary("c", "PC", "https://pc", false, ConnectionPhase.Connected))
        val repo = object : RemoteDesktopRepository by base {
            override val capabilities = MutableStateFlow(mapOf("c" to RemoteCapabilities(listOf(RemoteModel("m", "Model", supportsFast = true)), defaultsLoaded = true)))
            override suspend fun loadSkills(connectionId: String, projectPath: String?) = RemoteSkillCatalog(emptyList(), emptyMap())
        }
        lateinit var vm: RemoteHomeViewModel
        compose.runOnIdle { vm = RemoteHomeViewModel(SavedStateHandle(), repo) }
        var newThreads = 0
        compose.setContent { CpaTheme { RemoteHomeRoute(vm, { _, _ -> }, { newThreads++ }) } }
        compose.onNodeWithContentDescription("Ask CPA").performTextInput("/fast")
        compose.onNodeWithText("Fast").performClick()
        compose.runOnIdle { assertEquals("fast", vm.homeComposer.state.value.speed) }
        compose.onNodeWithText("Fast mode enabled").assertExists()
        compose.onNodeWithContentDescription("Ask CPA").performTextInput("Draft from home")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { newThreads == 1 }
        compose.runOnIdle {
            assertEquals("Draft from home", vm.newThread.state.value.draft)
            assertEquals("fast", vm.newThread.state.value.speed)
            assertEquals("", vm.homeComposer.state.value.draft)
            assertTrue(base.creationOrder.isEmpty())
            vm.newThread.close()
        }
        compose.onNodeWithContentDescription("Ask CPA").performTextInput("/model")
        compose.onNodeWithText("Model").performClick()
        compose.waitUntil(5_000) { vm.newThread.state.value.modelSettingsRequested }
        compose.runOnIdle { assertTrue(base.creationOrder.isEmpty()) }
    }
}
