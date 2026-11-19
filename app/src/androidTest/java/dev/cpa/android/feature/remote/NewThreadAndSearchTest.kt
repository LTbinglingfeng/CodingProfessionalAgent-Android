package dev.cpa.android.feature.remote

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.localization.AppLocaleRule
import dev.cpa.android.core.network.tunnel.TunnelDiscoveryNotice
import dev.cpa.android.core.network.tunnel.TunnelDiscoveryPhase
import dev.cpa.android.core.network.tunnel.TunnelDiscoveryState
import dev.cpa.android.core.network.tunnel.TunnelProtocol
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NewThreadAndSearchTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cleanup() { scope.cancel() }

    @Test fun selectingWorktreeBranchCreatesThenSendsAndOpensChat() {
        val repo = UiFakeRepository()
        val controller = RemoteNewThreadController(SavedStateHandle(), repo, scope)
        compose.runOnIdle { controller.open("c", "p") }
        var opened: Pair<String, String>? = null
        compose.setContent { CpaTheme { NewThreadRoute(controller, emptyMap(), onBack = {}, onOpened = { c, s -> opened = c to s }) } }
        compose.waitUntil(5_000) { controller.state.value.branches.isNotEmpty() }
        compose.onNodeWithTag("new-thread-device").assertTextContains("Desktop")
        compose.onNodeWithTag("new-thread-project").assertTextContains("Project")
        compose.onNodeWithTag("new-thread-location").performClick()
        compose.onNodeWithText("在工作树中工作").performClick()
        compose.onNodeWithTag("new-thread-branch").performClick()
        compose.onNodeWithText("dev").performClick()
        compose.onNodeWithContentDescription("向 CPA 提问").performTextInput("新的任务")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { opened != null }
        compose.runOnIdle {
            assertEquals(listOf("create:p:worktree:dev", "send"), repo.calls.filter { it.startsWith("create") || it == "send" })
            assertEquals("新的任务", repo.sent)
            assertEquals("c", opened!!.first)
        }
    }

    @Test fun backClosesFormAndAdvancedSheetEditsSelections() {
        val repo = UiFakeRepository()
        repo.capabilities.value = mapOf("c" to RemoteCapabilities(listOf(RemoteModel("m", "Model M", supportsFast = true,
            reasoningLevels = listOf(RemoteReasoningOption("low"), RemoteReasoningOption("high")))), defaultsLoaded = true))
        val controller = RemoteNewThreadController(SavedStateHandle(), repo, scope)
        compose.runOnIdle { controller.open("c", "p") }
        var back = 0
        compose.setContent { CpaTheme { NewThreadRoute(controller, emptyMap(), onBack = { back++ }, onOpened = { _, _ -> }) } }
        compose.waitUntil(5_000) { controller.state.value.modelId == "m" }
        compose.onNodeWithText("Model M").assertExists()
        compose.onNodeWithTag("composer-reasoning").performClick()
        compose.onNodeWithTag("reasoning-slider").performTouchInput { click(centerLeft + androidx.compose.ui.geometry.Offset(40f, 0f)) }
        compose.runOnIdle { assertEquals("low", controller.state.value.reasoning) }
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, 300f)) }
        compose.onNodeWithContentDescription("向 CPA 提问").performTextInput("/model")
        compose.onNodeWithText("模型").performClick()
        compose.onNodeWithText("高级").assertExists()
        compose.onNodeWithTag("advanced-speed").performClick()
        compose.onNodeWithText("快速").performClick()
        compose.runOnIdle { assertEquals("fast", controller.state.value.speed) }
        compose.onNodeWithText("高级").performTouchInput { swipeDown(startY = top, endY = top + 1200f, durationMillis = 300) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("高级").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("new-thread-back").performClick()
        compose.runOnIdle { assertEquals(1, back); assertFalse(controller.state.value.open); assertTrue(repo.calls.none { it.startsWith("create") }) }
    }

    @Test fun searchRecentExcludesDisabledDevicesAndNewChatPrefersConnected() {
        val repo = UiFakeRepository().apply {
            connections.value = listOf(ConnectionSummary("off", "Offline", "http://offline:1", true),
                ConnectionSummary("c", "PC", "http://pc:1", true, ConnectionPhase.Connected))
            sessions.value = mapOf("c" to listOf(RemoteSession("s", "Visible chat", "p")))
            searchResults = listOf(RemoteSearchResult("c", "s", "Visible chat", 1, snippet = "a visible snippet"))
        }
        var target: String? = null
        var opened: Pair<String, String>? = null
        compose.setContent { CpaTheme {
            val connections by repo.connections.collectAsState()
            val projects by repo.projects.collectAsState()
            val sessions by repo.sessions.collectAsState()
            val controller = androidx.compose.runtime.remember { RemoteSearchController(repo, scope) }
            SearchSheet(controller, connections, projects, sessions, onDismiss = {}, onOpen = { c, s -> opened = c to s },
                onNewChat = { target = it }, onNotice = {})
        } }
        compose.onNodeWithText("Visible chat").assertIsDisplayed()
        compose.onNodeWithText("PC · Project").assertExists()
        compose.onNodeWithTag("search-new-chat").performClick()
        compose.runOnIdle { assertEquals("c", target) }
        compose.onNodeWithContentDescription("搜索聊天").performTextInput("visible")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(homeKey("search", "c", "s")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("search-new-chat").assertDoesNotExist()
        compose.onNodeWithTag(homeKey("search", "c", "s")).performClick()
        compose.runOnIdle { assertEquals("c" to "s", opened); assertTrue(repo.calls.none { it.startsWith("create") }) }
    }
}

class LanguageRegressionTest {
    @get:Rule(order = 0) val language = AppLocaleRule("en")
    @get:Rule(order = 1) val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cleanup() { scope.cancel() }

    @Test fun englishNewThreadSelectorsAndCreateFlow() {
        val repo = UiFakeRepository()
        val controller = RemoteNewThreadController(SavedStateHandle(), repo, scope)
        compose.runOnIdle { controller.open("c", "p") }
        var opened = false
        compose.setContent { CpaTheme { NewThreadRoute(controller, emptyMap(), onBack = {}, onOpened = { _, _ -> opened = true }) } }
        compose.waitUntil(5_000) { controller.state.value.branches.isNotEmpty() }
        compose.onNodeWithText("Work locally").performClick()
        compose.onNodeWithText("Work in worktree").performClick()
        compose.onNodeWithContentDescription("Ask CPA").performTextInput("新的任务")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { opened }
        compose.runOnIdle { assertTrue(repo.calls.contains("create:p:worktree:main")) }
    }

    @Test fun tunnelNoticesUpdateAndClearWithoutSaving() {
        val updates = MutableStateFlow(TunnelDiscoveryState(TunnelDiscoveryPhase.Reconnecting, notice = TunnelDiscoveryNotice.Reconnecting, reconnectAttempt = 7))
        val repo = UiFakeRepository().apply { connections.value = emptyList(); discovery = updates }
        val vm = homeViewModel(repo)
        compose.setContent { CpaTheme { val op by vm.operation.collectAsState()
            ConnectionEditorSheet(vm, ConnectionEditorTarget(null, tunnel = true), emptyList(), emptyMap(), op, onDismiss = {}) } }
        compose.onNodeWithContentDescription("URL").performTextInput("https://gateway.test")
        compose.onNodeWithContentDescription("API Key").performTextInput("key")
        compose.onNodeWithTag("tunnel-search").performClick()
        compose.onNodeWithText("Connection lost, reconnecting (attempt 7)…").assertExists()
        compose.runOnIdle { updates.value = TunnelDiscoveryState(TunnelDiscoveryPhase.Connected,
            listOf(TunnelProtocol.Server("pc", "原始桌面", TunnelProtocol.SERVER_TYPE)), notice = TunnelDiscoveryNotice.RecentGatewaySaveFailed) }
        compose.onNodeWithText("Connected, but the recent gateway could not be saved. Enter it again next time.").assertExists()
        compose.onNodeWithText("原始桌面").assertExists()
        compose.runOnIdle { updates.value = TunnelDiscoveryState(TunnelDiscoveryPhase.Failed, notice = TunnelDiscoveryNotice.AuthenticationFailed) }
        compose.onNodeWithText("Gateway authentication failed. Check the API key.").assertExists()
        compose.onNodeWithText("原始桌面").assertDoesNotExist()
        compose.runOnIdle { assertTrue(repo.calls.none { it.startsWith("save") }) }
    }

    @Test fun repositoryProblemTranslatesWhileRemoteFailureRemainsVerbatim() {
        val remoteError = "远端原始错误：模型 unavailable"
        val repo = UiFakeRepository()
        repo.thread("s").value = RemoteThread("c", "s", entries = listOf(RemoteEntry("u", "user", "q"), RemoteEntry("a", "assistant", "",
            details = RemoteEntryDetails(status = "error", errorMessage = remoteError))), error = RemoteProblem.OutcomeUnknown, outcomeUnknown = true)
        val vm = threadViewModel(repo)
        compose.setContent { CpaTheme { RemoteThreadRoute(vm) {} } }
        compose.onNodeWithText("The operation outcome is unknown. Refresh and verify the desktop before confirming.").assertExists()
        compose.onNodeWithText(remoteError).assertExists()
        compose.onNodeWithTag("send-unknown").assertExists()
        compose.onNodeWithContentDescription("Work on Desktop").performTextInput("draft")
        compose.onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    @Test fun englishComposerCommandsAndPlaceholders() {
        val repo = UiFakeRepository()
        repo.thread("s").value = RemoteThread("c", "s", entries = listOf(RemoteEntry("u", "user", "hi")))
        val vm = threadViewModel(repo)
        compose.setContent { CpaTheme { RemoteThreadRoute(vm) {} } }
        compose.onNodeWithContentDescription("Work on Desktop").performTextInput("/")
        compose.onNodeWithText("Compact").assertExists()
        compose.onNodeWithText("Compact conversation context").performClick()
        compose.waitUntil(5_000) { "action:compact" in repo.calls }
        compose.onNodeWithText("Context compaction requested").assertExists()
        compose.onNodeWithTag("composer-attach").performClick()
        compose.onNodeWithText("Camera").assertExists()
        compose.onNodeWithText("Photos").assertExists()
        compose.onNodeWithText("Files").assertExists()
    }

}
