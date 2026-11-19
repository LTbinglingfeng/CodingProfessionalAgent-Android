package dev.cpa.android.feature.remote

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.localization.AppLocaleRule
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteAskRouteTest {
    @get:Rule(order = 0) val language = AppLocaleRule("en")
    @get:Rule(order = 1) val compose = createComposeRule()
    @Test fun newQuestionsOpenAutomaticallyAndTypingStopsCountdownWithoutLosingDraft() {
        val base = RemoteWorkspaceScreenTest.Fake()
        base.connections.value = listOf(ConnectionSummary("c", "PC", "https://pc", false, ConnectionPhase.Connected))
        var question: String? = "first"
        var countdown = true
        val cancelled = CompletableDeferred<JsonElement>()
        val methods = mutableListOf<String>()
        var answer: String? = null
        val repo = object : RemoteDesktopRepository by base {
            override suspend fun callSessionRpc(connectionId: String, sessionId: String, method: String, args: List<JsonElement>, mutation: Boolean): JsonElement {
                if (mutation) {
                    methods += method
                    if (method == "ask:cancelCountdown") return cancelled.await()
                    answer = args[2].jsonObject["text"]?.jsonPrimitive?.content
                    question = null
                    return JsonPrimitive(true)
                }
                return buildJsonObject {
                    put("request", question?.let { id -> buildJsonObject {
                        put("id", id); put("sessionId", "s"); put("toolCallId", "tool-$id")
                        put("question", "Question $id"); put("options", JsonArray(emptyList()))
                        put("allowCustom", true); put("allowSkip", true)
                        if (countdown) put("countdownDeadline", System.currentTimeMillis() + 60000)
                    } } ?: JsonNull)
                    put("resolved", JsonNull)
                }
            }
        }
        lateinit var vm: RemoteThreadViewModel
        compose.runOnIdle { vm = RemoteThreadViewModel(SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s")), repo) }
        compose.setContent { CpaTheme { RemoteThreadRoute(vm, onBack = {}) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Question first").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ask-custom").performScrollTo().performTextInput("first")
        compose.waitUntil(5000) { vm.ask.state.value.cancellingCountdown }
        compose.onNodeWithTag("ask-custom").assertIsEnabled().performTextReplacement("完整 custom answer")
        compose.onNodeWithTag("ask-send").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("ask:cancelCountdown"), methods)
            countdown = false
            cancelled.complete(JsonPrimitive(true))
        }
        compose.waitUntil(5000) { !vm.ask.state.value.busy }
        compose.onNodeWithTag("ask-send").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Question first").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            assertEquals("完整 custom answer", answer)
            assertEquals(listOf("ask:cancelCountdown", "ask:submitAnswer"), methods)
            question = "second"; vm.ask.refresh()
        }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Question second").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ask-later").performClick()
        compose.runOnIdle { vm.ask.refresh() }
        compose.waitForIdle()
        compose.onNodeWithText("Question second").assertDoesNotExist()
        compose.runOnIdle { question = "third"; vm.ask.refresh() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Question third").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(2, methods.size) }
    }
    @Test fun viewingOwnershipFollowsForegroundAndRouteDisposal() {
        val base = RemoteWorkspaceScreenTest.Fake()
        var starts = 0
        var ends = 0
        val repo = object : RemoteDesktopRepository by base {
            override fun beginViewing(connectionId: String, sessionId: String) {
                assertEquals("c", connectionId); assertEquals("s", sessionId); starts++
            }
            override fun endViewing(connectionId: String, sessionId: String) { ends++ }
        }
        val visible = androidx.compose.runtime.mutableStateOf(true)
        lateinit var registry: androidx.lifecycle.LifecycleRegistry
        val owner = object : androidx.lifecycle.LifecycleOwner {
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        lateinit var vm: RemoteThreadViewModel
        compose.runOnIdle {
            registry = androidx.lifecycle.LifecycleRegistry(owner)
            registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            vm = RemoteThreadViewModel(SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s")), repo)
        }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                if (visible.value) CpaTheme { RemoteThreadRoute(vm, onBack = {}) }
            }
        }
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, ends) }
        compose.runOnIdle { registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
        compose.runOnIdle { assertEquals(1, ends) }
        compose.runOnIdle { registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(2, starts); visible.value = false }
        compose.runOnIdle { assertEquals(2, ends) }
    }

}
