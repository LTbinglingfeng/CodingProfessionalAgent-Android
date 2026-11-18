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
class RemoteAskPanelTest {
    @get:Rule val compose = createComposeRule()
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
        compose.onNodeWithText("1. 方案一").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, writes.size)
            assertEquals(JsonPrimitive("s"), writes.single()[0])
            assertEquals(JsonPrimitive("tool-1"), writes.single()[1])
            assertEquals(JsonPrimitive(0), writes.single()[2].jsonObject["index"])
            assertNull(controller.state.value.request)
        }
    }
    @Test fun rejectedCustomAnswerRetainsEditableDraft() {
        val controller = RemoteAskController("s", scope, { _, _, mutation -> if (mutation) JsonPrimitive(false) else response() })
        compose.setContent { CpaTheme { RemoteAskPanel(controller) } }
        compose.waitUntil(5_000) { controller.state.value.request != null }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("custom answer")
        compose.onNodeWithText("提交自由回答").performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("custom answer", controller.state.value.customText)
            assertNotNull(controller.state.value.request)
            assertFalse(controller.state.value.outcomeUnknown)
        }
        compose.onNodeWithText("桌面拒绝操作（false），表单已保留。请刷新核对。").assertExists()
    }
}
