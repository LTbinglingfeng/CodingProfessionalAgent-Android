package dev.cpa.android.feature.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteSkillComposerTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun cleanup() { scope.cancel() }
    @Test fun typingTriggerSelectingSkillAndEditingKeepUtf16Reference() {
        val repo = object : RemoteDesktopRepository by RemoteWorkspaceScreenTest.Fake() {
            override suspend fun loadSkills(connectionId: String, projectPath: String?) = RemoteSkillCatalog(
                listOf(RemoteSkill("review", "Review source", "/review/SKILL.md"), RemoteSkill("disabled", "", "/disabled/SKILL.md")), emptyMap())
        }
        val controller = RemoteSkillsController(SavedStateHandle(), "skill", repo, scope, "")
        compose.runOnIdle { controller.context("c", null, true) }
        compose.setContent { CpaTheme { Column {
            var text by remember { mutableStateOf("") }
            RemoteSkillComposer(controller, text, { text = it }, RemoteDefaults(skillTrigger = '#', skillModes = mapOf("disabled" to "disabled")), true, "消息")
        } } }
        compose.waitUntil(5_000) { !controller.state.value.loading }
        compose.onNodeWithContentDescription("消息").performTextInput("😀 #re")
        compose.onNodeWithText("Review").assertExists()
        compose.onNodeWithText("Review source").assertExists()
        compose.onNodeWithText("技能").assertExists()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("消息").performTextInput("\n")
        compose.runOnIdle {
            assertEquals("😀 \$review ", controller.state.value.draft.text)
            assertEquals(listOf(SkillReference(3, "review")), controller.state.value.draft.validReferences)
        }
        compose.onNodeWithContentDescription("消息").performTextInput("#dis")
        compose.onNodeWithText("Disabled").assertDoesNotExist()
        compose.onNodeWithContentDescription("消息").performTextReplacement("plain text")
        compose.runOnIdle { assertTrue(controller.state.value.draft.validReferences.isEmpty()) }
    }
    @Test fun slashAliasExecutesActionAndClearsOnlyCommandDraft() {
        val controller = RemoteSkillsController(SavedStateHandle(), "skill", RemoteWorkspaceScreenTest.Fake(), scope, "")
        var actions = 0
        compose.setContent { CpaTheme { Column {
            var text by remember { mutableStateOf("") }
            RemoteSkillComposer(controller, text, { text = it }, RemoteDefaults(), true, "消息",
                composerCommands(fast = null, compact = { actions++ }, model = {}))
        } } }
        compose.onNodeWithContentDescription("消息").performTextInput("/compress")
        compose.onNodeWithText("压缩").performClick()
        compose.runOnIdle { assertEquals(1, actions); assertEquals("", controller.state.value.draft.text) }
        compose.onNodeWithContentDescription("消息").performTextInput("/fast")
        compose.onNodeWithText("快速").assertDoesNotExist()
    }
    @Test fun compactReturnSendsButCommandReturnSelectsWithoutSending() {
        val controller = RemoteSkillsController(SavedStateHandle(), "return", RemoteWorkspaceScreenTest.Fake(), scope, "")
        var sends = 0
        var commands = 0
        compose.setContent { CpaTheme { Column {
            var text by remember { mutableStateOf("") }
            RemoteSkillComposer(controller, text, { text = it }, RemoteDefaults(), true, "消息",
                composerCommands(null, { commands++ }, {}), onSubmit = { sends++ })
        } } }
        compose.onNodeWithContentDescription("消息").performTextInput("hello ")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("消息").performImeAction()
        compose.runOnIdle { assertEquals(1, sends); assertEquals("hello ", controller.state.value.draft.text) }
        compose.onNodeWithContentDescription("消息").performTextReplacement("/compress")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("消息").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        compose.runOnIdle { assertEquals(1, sends); assertEquals(1, commands); assertEquals("", controller.state.value.draft.text) }
    }

    @Test fun multilineReturnInsertsNewlineAndFocusLossHidesCommands() {
        val controller = RemoteSkillsController(SavedStateHandle(), "return", RemoteWorkspaceScreenTest.Fake(), scope, "")
        compose.setContent { CpaTheme { Column {
            var text by remember { mutableStateOf("") }
            var other by remember { mutableStateOf("") }
            RemoteSkillComposer(controller, text, { text = it }, RemoteDefaults(), true, "消息",
                composerCommands(null, {}, {}))
            androidx.compose.material3.OutlinedTextField(other, { other = it }, label = { androidx.compose.material3.Text("另一输入") })
        } } }
        compose.onNodeWithContentDescription("消息").performTextInput("hello")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("消息").performTextInput("\n")
        compose.runOnIdle { assertEquals("hello\n", controller.state.value.draft.text) }
        compose.onNodeWithContentDescription("消息").performTextReplacement("/compress")
        compose.onNodeWithText("压缩").assertExists()
        compose.onNodeWithText("另一输入").performClick()
        compose.onNodeWithText("压缩").assertDoesNotExist()
    }

    @Test fun atomicSelectionCopiesConfiguredTriggerAndCutRemovesWholeSkill() {
        val controller = RemoteSkillsController(SavedStateHandle(), "atomic", RemoteWorkspaceScreenTest.Fake(), scope, "")
        val initial = "😀 \$review tail"
        compose.runOnIdle { controller.replace(initial, listOf(SkillReference(3, "review"))) }
        lateinit var clipboard: androidx.compose.ui.platform.ClipboardManager
        compose.setContent { CpaTheme { Column {
            clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            var text by remember { mutableStateOf(initial) }
            RemoteSkillComposer(controller, text, { text = it }, RemoteDefaults(skillTrigger = '/'), true, "消息")
        } } }
        compose.onNodeWithContentDescription("消息").performClick()
        compose.onNodeWithContentDescription("消息").performTextInputSelection(androidx.compose.ui.text.TextRange(4, 8))
        compose.onNodeWithContentDescription("消息").performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.CtrlLeft)
            pressKey(androidx.compose.ui.input.key.Key.C)
            keyUp(androidx.compose.ui.input.key.Key.CtrlLeft)
        }
        compose.runOnIdle { assertEquals("/skill:review", clipboard.getText()?.text) }
        compose.onNodeWithContentDescription("消息").performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.CtrlLeft)
            pressKey(androidx.compose.ui.input.key.Key.X)
            keyUp(androidx.compose.ui.input.key.Key.CtrlLeft)
        }
        compose.runOnIdle {
            assertEquals("😀  tail", controller.state.value.draft.text)
            assertTrue(controller.state.value.draft.validReferences.isEmpty())
            assertEquals("/skill:review", clipboard.getText()?.text)
        }
        compose.onNodeWithContentDescription("消息").performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.CtrlLeft)
            pressKey(androidx.compose.ui.input.key.Key.Z)
            keyUp(androidx.compose.ui.input.key.Key.CtrlLeft)
        }
        compose.runOnIdle {
            assertEquals(initial, controller.state.value.draft.text)
            assertEquals(listOf(SkillReference(3, "review")), controller.state.value.draft.validReferences)
        }
    }

    @Test fun backspaceDeletesReferencedSkillAsOneObject() {
        val controller = RemoteSkillsController(SavedStateHandle(), "atomic", RemoteWorkspaceScreenTest.Fake(), scope, "")
        val initial = "\$review tail"
        compose.runOnIdle { controller.replace(initial, listOf(SkillReference(0, "review"))) }
        compose.setContent { CpaTheme { Column {
            var text by remember { mutableStateOf(initial) }
            RemoteSkillComposer(controller, text, { text = it }, RemoteDefaults(), true, "消息")
        } } }
        compose.onNodeWithContentDescription("消息").performClick()
        compose.onNodeWithContentDescription("消息").performTextInputSelection(androidx.compose.ui.text.TextRange(7))
        compose.onNodeWithContentDescription("消息").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Backspace) }
        compose.runOnIdle {
            assertEquals(" tail", controller.state.value.draft.text)
            assertTrue(controller.state.value.draft.validReferences.isEmpty())
        }
        compose.onNodeWithContentDescription("消息").performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.CtrlLeft)
            pressKey(androidx.compose.ui.input.key.Key.Z)
            keyUp(androidx.compose.ui.input.key.Key.CtrlLeft)
        }
        compose.runOnIdle {
            assertEquals(initial, controller.state.value.draft.text)
            assertEquals(listOf(SkillReference(0, "review")), controller.state.value.draft.validReferences)
        }
        compose.onNodeWithContentDescription("消息").performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.CtrlLeft)
            keyDown(androidx.compose.ui.input.key.Key.ShiftLeft)
            pressKey(androidx.compose.ui.input.key.Key.Z)
            keyUp(androidx.compose.ui.input.key.Key.ShiftLeft)
            keyUp(androidx.compose.ui.input.key.Key.CtrlLeft)
        }
        compose.runOnIdle {
            assertEquals(" tail", controller.state.value.draft.text)
            assertTrue(controller.state.value.draft.validReferences.isEmpty())
        }
    }

}
