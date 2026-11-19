package dev.cpa.android.core.designsystem

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DialogActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longAlertKeepsLastActionReachable() = verifyLastAction(sheet = false)
    @Test fun longActionSheetKeepsLastActionReachable() = verifyLastAction(sheet = true)

    @Test fun switchingTabsUpdatesSelectedSemantics() {
        val right = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { CpaTheme { SegmentedTabs("Files", "Review", right.value, { right.value = it }) } }
        compose.onNodeWithText("Files").assertIsSelected()
        compose.onNodeWithText("Review").assertIsNotSelected().performClick()
        compose.onNodeWithText("Files").assertIsNotSelected()
        compose.onNodeWithText("Review").assertIsSelected()
    }

    private fun verifyLastAction(sheet: Boolean) {
        var clicked = 0
        val actions = (1..30).map { DialogAction("Action $it") { clicked = it } }
        compose.setContent { CpaTheme {
            if (sheet) CpaActionSheet("Actions", actions) {} else CpaAlert("Actions", actions = actions, onDismiss = {})
        } }
        compose.onNodeWithText("Action 30").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(30, clicked) }
    }
}
