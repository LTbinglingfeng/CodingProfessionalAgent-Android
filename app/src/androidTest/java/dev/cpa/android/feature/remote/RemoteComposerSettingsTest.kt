package dev.cpa.android.feature.remote

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.storage.MemoryComposerPreferences
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteComposerSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun preferencesControlRealRouteKeyboardAndContextDisplay() {
        val preferences = MemoryComposerPreferences()
        var hidden = 0; var sent = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() { hidden++ }
        }
        val repo = object : RemoteDesktopRepository by RemoteWorkspaceScreenTest.Fake() {
            override val connections = MutableStateFlow(listOf(ConnectionSummary("c", "PC", "https://pc", false, ConnectionPhase.Connected)))
            override val capabilities = MutableStateFlow(mapOf("c" to RemoteCapabilities(listOf(RemoteModel("model", contextWindow = 1000.0)), RemoteDefaults(modelId = "model"))))
            override val collapseKeyboardAfterSend = preferences.collapseKeyboardAfterSend
            override suspend fun setCollapseKeyboardAfterSend(value: Boolean) = preferences.setCollapseKeyboardAfterSend(value)
            override val showContextUsage = preferences.showContextUsage
            override suspend fun setShowContextUsage(value: Boolean) = preferences.setShowContextUsage(value)
            override fun observeThread(connectionId: String, sessionId: String) = MutableStateFlow(RemoteThread(connectionId, sessionId,
                entries = listOf(RemoteEntry("a", "assistant", "reply", details = RemoteEntryDetails(usage = buildJsonObject { put("totalTokens", 505) })))))
            override suspend fun loadSkills(connectionId: String, projectPath: String?) = RemoteSkillCatalog(emptyList(), emptyMap())
            override suspend fun send(connectionId: String, sessionId: String, text: String, modelId: String?) { sent++ }
        }
        lateinit var vm: RemoteThreadViewModel
        compose.runOnIdle { vm = RemoteThreadViewModel(SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s")), repo) }
        compose.setContent { CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) { CpaTheme { RemoteThreadRoute(vm, onBack = {}) } } }
        compose.onNodeWithText("上下文已使用 51%").assertDoesNotExist()
        compose.runOnIdle { kotlinx.coroutines.runBlocking { preferences.setShowContextUsage(true) } }
        compose.onNodeWithText("上下文已使用 51%").assertExists()
        compose.runOnIdle { vm.edit("first") }
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { sent == 1 }
        compose.runOnIdle { assertEquals(1, sent); assertEquals(1, hidden) }
        compose.runOnIdle { kotlinx.coroutines.runBlocking { preferences.setCollapseKeyboardAfterSend(false) } }
        compose.runOnIdle { vm.edit("second") }
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { sent == 2 }
        compose.runOnIdle { assertEquals(2, sent); assertEquals(1, hidden) }
    }
}
