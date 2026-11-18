package dev.cpa.android.feature.remote

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.cpa.android.core.designsystem.CpaTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Test UI with an explicit RPC fake: does not claim a live agent asked the question. */
class RemoteAskPanelLanguageTest {
    @get:Rule(order = 0) val language = dev.cpa.android.core.localization.AppLocaleRule("en")
    @get:Rule(order = 1) val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cleanup() { scope.cancel() }
    private fun response() = Json.parseToJsonElement("""{"request":{"id":"ask-1","sessionId":"s","toolCallId":"tool-1","question":"选择测试方案","options":[{"title":"方案一","recommended":true}],"allowCustom":true,"allowSkip":true},"resolved":null}""")

    @Test fun selectedOptionCallsRealControllerWireShape() {
        val writes = mutableListOf<List<JsonElement>>()
        val controller = RemoteAskController("s", scope, { _, args, mutation ->
            if (mutation) { writes += args; JsonPrimitive(true) } else response()
        })
        compose.setContent { CpaTheme { RemoteAskPanel(controller) } }
        compose.waitUntil(5_000) { controller.state.value.request != null }
        compose.onNodeWithText("Recommended").assertExists()
        compose.onNodeWithText("Refresh / Verify").assertExists()
        compose.onNodeWithText("1. 方案一").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("The desktop accepted the answer; this does not mean the agent has consumed it.").assertExists()
        compose.runOnIdle {
            assertEquals(1, writes.size)
            assertEquals(JsonPrimitive("s"), writes.single()[0])
            assertEquals(JsonPrimitive("tool-1"), writes.single()[1])
            assertEquals(JsonPrimitive(0), writes.single()[2].jsonObject["index"])
            assertNull(controller.state.value.request)
        }
    }
    @Test fun unknownAnswerNeedsSuccessfulLookupAndExplicitAcknowledgement() {
        var writes = 0
        var lookupFails = false
        val controller = RemoteAskController("s", scope, { _, _, mutation ->
            if (mutation) { writes++; if (writes == 1) JsonNull else JsonPrimitive(false) }
            else if (lookupFails) throw java.io.IOException("private diagnostic") else response()
        })
        compose.setContent { CpaTheme { RemoteAskPanel(controller) } }
        compose.waitUntil(5000) { controller.state.value.snapshotFresh }
        compose.onNodeWithText("1. 方案一").performScrollTo().performClick()
        compose.waitUntil(5000) { controller.state.value.outcomeUnknown }
        compose.onNodeWithText("Outcome unknown: the response was not a boolean.", substring = true).assertExists()
        compose.onNodeWithText("1. 方案一").assertIsNotEnabled()
        compose.runOnIdle { lookupFails = true }
        compose.onNodeWithText("Refresh / Verify").performScrollTo().performClick()
        compose.waitUntil(5000) { controller.state.value.lookupError != null }
        compose.onNodeWithText("Lookup failed; this does not mean there is no question. Check the connection and refresh.").assertExists()
        compose.onNodeWithText("I verified the result; unlock resending").assertIsNotEnabled()
        compose.onNodeWithText("private diagnostic", substring = true).assertDoesNotExist()
        compose.runOnIdle { lookupFails = false }
        compose.onNodeWithText("Refresh / Verify").performScrollTo().performClick()
        compose.waitUntil(5000) { controller.state.value.checkedAfterFailure }
        compose.onNodeWithText("I verified the result; unlock resending").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, writes) }
        compose.onNodeWithText("Manually verified. Submitting again is a new explicit action.").assertExists()
        compose.onNodeWithText("1. 方案一").performScrollTo().performClick()
        compose.waitUntil(5000) { writes == 2 && !controller.state.value.busy }
        compose.onNodeWithText("The desktop rejected the operation (false). The form is retained. Refresh and verify.").assertExists()
        compose.onNodeWithText("选择测试方案").assertExists()
        compose.runOnIdle { assertFalse(controller.state.value.outcomeUnknown); assertEquals(2, writes) }
    }

}
