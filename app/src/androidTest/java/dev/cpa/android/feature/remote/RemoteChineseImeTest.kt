package dev.cpa.android.feature.remote

import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.data.repository.RemoteDefaults
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Opt-in real Fcitx5 Pinyin integration; no direct semantic text injection. */
class RemoteChineseImeTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("CPA_CHINESE_IME") == "fcitx5")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ==
                "org.fcitx.fcitx5.android/.input.FcitxInputMethodService")
            base.evaluate()
        }
    } }
    @get:Rule val rules: TestRule = RuleChain.outerRule(optIn).around(compose)

    @Test fun pinyinCandidateCommitsWithoutSendingAndReturnSubmitsOnce() = verifyPinyin(compact = true)
    @Test fun pinyinCandidateInExpandedComposerReturnsNewline() = verifyPinyin(compact = false)

    private fun verifyPinyin(compact: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val controller = RemoteSkillsController(SavedStateHandle(), "real-ime", RemoteWorkspaceScreenTest.Fake(), scope, "")
            val refs = if (compact) emptyList() else listOf(dev.cpa.android.data.repository.SkillReference(0, "review"))
            val prefix = if (compact) "" else "\$review "
            controller.replace(prefix, refs)
            var sends = 0
            lateinit var view: android.view.View
            compose.setContent { CpaTheme { Column {
                view = androidx.compose.ui.platform.LocalView.current
                var text by remember { mutableStateOf(prefix) }
                RemoteSkillComposer(controller, text, { text = it }, RemoteDefaults(), true, "IME test", onSubmit = if (compact) ({ sends++ }) else null)
            } } }
            val editor = compose.onNode(hasSetTextAction())
            editor.performClick().assertIsFocused()
            editor.performTextInputSelection(androidx.compose.ui.text.TextRange(prefix.length))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            compose.waitForIdle()
            compose.runOnIdle {
                val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            for (key in listOf(KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_O)) {
                instrumentation.sendKeyDownUpSync(key)
                compose.waitForIdle()
            }
            compose.runOnIdle { assertEquals(0, sends); assertNotEquals(prefix + "你好", controller.state.value.draft.text) }
            // Space selects the first actual Pinyin candidate through the IME, not Compose semantics.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
            try {
                compose.waitUntil(5000) { controller.state.value.draft.text == prefix + "你好" }
            } catch (e: ComposeTimeoutException) {
                assertEquals(prefix + "你好", controller.state.value.draft.text)
                throw e
            }
            compose.runOnIdle { assertEquals(0, sends); assertEquals(refs, controller.state.value.draft.validReferences) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            if (compact) {
                compose.waitUntil(5000) { sends == 1 }
                compose.runOnIdle { assertEquals("你好", controller.state.value.draft.text); assertEquals(1, sends) }
            } else {
                compose.waitUntil(5000) { controller.state.value.draft.text == prefix + "你好\n" }
                compose.runOnIdle { assertEquals(0, sends) }
            }
        } finally { scope.cancel() }
    }

}
