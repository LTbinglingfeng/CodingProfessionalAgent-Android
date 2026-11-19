package dev.cpa.android.feature.remote

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.data.repository.RemoteEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Deterministic UI projections of snapshot + full-entry replacement events, no model claims. */
class RemoteTimelineTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val entries = mutableStateOf(history())
    private val height = mutableStateOf(360)

    private fun history() = (0 until 40).map {
        RemoteEntry("e$it", if (it % 2 == 0) "user" else "assistant", "消息 $it\n" + "历史内容\n".repeat(5))
    }
    private fun show() {
        compose.setContent { CpaTheme { RemoteTimeline(entries.value, false, Modifier.height(height.value.dp)) } }
        compose.waitForIdle()
    }
    private fun position() = compose.onNodeWithTag("remote-timeline").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun readHistory() {
        compose.onNodeWithTag("remote-timeline").performTouchInput {
            swipe(start = Offset(center.x, height * .2f), end = Offset(center.x, height * .85f), durationMillis = 800)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("jump-latest").assertIsDisplayed()
    }

    @Test fun delayedSnapshotOpensAtBottomAndAppendFollows() {
        entries.value = emptyList()
        show()
        compose.runOnIdle { entries.value = history() }
        compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        compose.runOnIdle { entries.value += RemoteEntry("new", "assistant", "实际追加投影") }
        compose.onNodeWithText("实际追加投影").assertIsDisplayed()
        compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        compose.onNodeWithTag("jump-latest").assertDoesNotExist()
    }

    @Test fun sameIdReplacementTallerThanViewportFollowsActualEnd() {
        show()
        repeat(3) { update ->
            compose.runOnIdle {
                entries.value = entries.value.dropLast(1) + entries.value.last().copy(
                    text = "增长输出\n".repeat(70 + update * 20) + "末尾 $update")
            }
            compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
            compose.onNodeWithTag("jump-latest").assertDoesNotExist()
        }
    }

    @Test fun historyIsNotStolenByAppendReplacementOrResizeAndButtonResumes() {
        show()
        readHistory()
        val before = position()
        compose.runOnIdle {
            entries.value = entries.value.dropLast(1) + entries.value.last().copy(text = "流式增长\n".repeat(100)) +
                RemoteEntry("tool", "toolResult", "tool output", "shell")
            height.value = 260 // Same viewport remeasure path as IME / window resize.
        }
        compose.waitForIdle()
        assertEquals(before, position(), .01f)
        compose.onNodeWithTag("jump-latest").performClick()
        compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        compose.onNodeWithText("tool output").assertIsDisplayed()
        compose.runOnIdle { entries.value += RemoteEntry("next", "assistant", "恢复跟随") }
        compose.onNodeWithText("恢复跟随").assertIsDisplayed()
    }

    @Test fun followingSurvivesViewportShrinkAndExpansion() {
        show()
        listOf(220, 500, 300).forEach { size ->
            compose.runOnIdle { height.value = size }
            compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        }
    }

    @Test fun readingPositionAndIntentRestoreWithoutJumpingToLatest() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CpaTheme { RemoteTimeline(entries.value, false, Modifier.height(360.dp)) } }
        readHistory()
        val before = position()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(before, position(), .01f)
        compose.onNodeWithTag("jump-latest").assertIsDisplayed()
        compose.runOnIdle { entries.value += RemoteEntry("later", "assistant", "恢复后追加") }
        compose.waitForIdle()
        assertEquals(before, position(), .01f)
    }

    @Test fun semanticsHistoryScrollIsNotPulledBackByFollowEffect() {
        show()
        compose.onNodeWithTag("remote-timeline").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.ScrollBy
        ) { scroll -> scroll(0f, -600f) }
        compose.waitForIdle()
        compose.onNodeWithTag("jump-latest").assertIsDisplayed()
        val before = position()
        compose.runOnIdle { entries.value += RemoteEntry("accessible", "assistant", "语义滚动时追加") }
        compose.waitForIdle()
        assertEquals(before, position(), .01f)
    }

    @Test fun restoredHistoryPositionWaitsForDelayedSnapshot() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CpaTheme { RemoteTimeline(entries.value, entries.value.isEmpty(), Modifier.height(360.dp)) } }
        readHistory()
        val before = position()
        compose.runOnIdle { entries.value = emptyList() }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.runOnIdle { entries.value = history() }
        compose.waitForIdle()
        assertEquals(before, position(), .01f)
        compose.onNodeWithTag("jump-latest").assertIsDisplayed()
    }

    @Test fun smallDragWithinBottomThresholdKeepsFollowing() {
        show()
        compose.onNodeWithTag("remote-timeline").performTouchInput {
            swipe(start = center, end = center + Offset(0f, 40f), durationMillis = 800)
        }
        compose.waitForIdle()
        compose.runOnIdle { entries.value += RemoteEntry("near", "assistant", "接近底部继续跟随") }
        compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        compose.onNodeWithText("接近底部继续跟随").assertIsDisplayed()
        compose.onNodeWithTag("jump-latest").assertDoesNotExist()
    }

    @Test fun realComposerImeOpenAndCloseKeepsLatestVisibleWithoutSending() {
        var imeVisible = false
        var sends = 0
        val repo = UiFakeRepository()
        val vm = threadViewModel(repo)
        // Match MainActivity: edge-to-edge with IME insets resizing the content instead of panning the window.
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent { CpaTheme {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val bottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density)
            androidx.compose.runtime.SideEffect { imeVisible = bottom > 0 }
            val current = entries.value
            androidx.compose.runtime.LaunchedEffect(current) { repo.thread("s").value = dev.cpa.android.data.repository.RemoteThread("c", "s", entries = current) }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { RemoteThreadRoute(vm) {} }
        } }
        // Finished conversations now open at the prompt; explicitly opt into following.
        compose.onNodeWithTag("jump-latest").performClick()
        compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        compose.onNodeWithContentDescription("在 Desktop 上工作").performClick()
        compose.waitUntil(5_000) { imeVisible }
        compose.waitForIdle()
        compose.runOnIdle { entries.value += RemoteEntry("ime", "assistant", "键盘展开时更新") }
        compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        compose.onNodeWithText("键盘展开时更新").assertIsDisplayed()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) { !imeVisible }
        compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        assertEquals(0, sends)
        compose.runOnIdle { assertTrue(repo.calls.none { it == "send" }) }
    }

    @Test fun scrollingBackToEndResumesFollowingWithoutButton() {
        show()
        readHistory()
        compose.onNodeWithTag("remote-timeline").performTouchInput { swipeUp(durationMillis = 800) }
        compose.waitForIdle()
        compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        compose.runOnIdle { entries.value += RemoteEntry("follow", "assistant", "手动滚底后跟随") }
        compose.onNodeWithText("手动滚底后跟随").assertIsDisplayed()
        compose.onNodeWithTag("jump-latest").assertDoesNotExist()
    }
    @Test fun editPauseSuppressesFollowingUntilSuccessfulEditRequestsIt() {
        val paused = mutableStateOf(false)
        val requests = mutableStateOf(0)
        compose.setContent { CpaTheme {
            RemoteTimeline(entries.value, false, Modifier.height(360.dp), isPaused = paused.value, followRequests = requests.value)
        } }
        readHistory()
        val before = position()
        compose.runOnIdle { paused.value = true }
        compose.runOnIdle { entries.value += RemoteEntry("new-user", "user", "Remote prompt during edit") }
        compose.waitForIdle()
        assertEquals(before, position(), .01f)
        compose.onNodeWithTag("jump-latest").assertDoesNotExist()
        compose.runOnIdle { paused.value = false }
        compose.waitForIdle()
        assertEquals(before, position(), .01f)
        compose.runOnIdle { requests.value++ }
        compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        compose.runOnIdle { entries.value += RemoteEntry("response", "assistant", "Edited response") }
        compose.onNodeWithText("Edited response").assertIsDisplayed()
    }

    @Test fun shortFinishedContentFollowsWhenItGrowsPastViewport() {
        entries.value = listOf(RemoteEntry("short", "assistant", "Short reply"))
        compose.setContent { CpaTheme {
            RemoteTimeline(entries.value, false, Modifier.height(360.dp), startsFollowing = false)
        } }
        compose.waitForIdle()
        compose.runOnIdle { entries.value = listOf(RemoteEntry("short", "assistant", "Growing reply\n".repeat(100))) }
        compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
    }

}
