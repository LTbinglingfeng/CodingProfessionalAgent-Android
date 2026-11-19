package dev.cpa.android.feature.remote

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.cpa.android.LocaleLifecycleTestActivity
import dev.cpa.android.core.attachments.LocalAttachment
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.localization.AppLanguageResources
import dev.cpa.android.core.localization.AppLocaleRule
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

class RemoteLocaleLifecycleTest {
    private val base = RemoteWorkspaceScreenTest.Fake()
    private val response = CompletableDeferred<Unit>()
    private var sends = 0
    private var uploads = 0
    private var submitted: String? = null
    private lateinit var vm: RemoteThreadViewModel
    private val repo = object : RemoteDesktopRepository by base {
        override suspend fun uploadAttachments(connectionId: String, sessionId: String, files: List<LocalAttachment>): List<String> {
            assertEquals("c", connectionId); assertEquals("s", sessionId)
            assertEquals("附件.txt", files.single().name)
            uploads++
            return listOf("/remote/附件.txt")
        }
        override suspend fun send(connectionId: String, sessionId: String, text: String, modelId: String?) {
            sends++; submitted = text
            response.await()
            throw java.io.IOException("Lost acknowledgement")
        }
    }
    @get:Rule(order = 0) val language = AppLocaleRule("zh-Hans")
    @get:Rule(order = 1) val host = TestRule { statement, _ -> object : Statement() {
        override fun evaluate() {
            base.connections.value = listOf(ConnectionSummary("c", "Desktop", "https://desktop", false, ConnectionPhase.Connected))
            base.sessions.value = mapOf("c" to listOf(RemoteSession("s", "Session")))
            LocaleLifecycleTestActivity.content = {
                val factory = remember { viewModelFactory { initializer {
                    val saved = createSavedStateHandle()
                    saved["connectionId"] = "c"; saved["sessionId"] = "s"
                    RemoteThreadViewModel(saved, repo)
                } } }
                vm = viewModel(factory = factory)
                AppLanguageResources { CpaTheme { RemoteThreadRoute(vm, onBack = {}) } }
            }
            try { statement.evaluate() } finally {
                LocaleLifecycleTestActivity.content = {}
                response.cancel()
            }
        }
    } }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<LocaleLifecycleTestActivity>()

    private fun switchTo(tag: String, inputLabel: String) {
        val previous = compose.activity
        val manager = previous.getSystemService(LocaleManager::class.java)
        compose.runOnUiThread { manager.applicationLocales = LocaleList.forLanguageTags(tag) }
        compose.waitUntil(10000) { compose.activity !== previous && compose.onAllNodesWithContentDescription(inputLabel).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(previous.isDestroyed)
    }

    @Test fun imageValidationSurvivesLanguageChangeWithoutSending() {
        compose.runOnIdle {
            vm.edit("图片问题")
            vm.attachments.enqueue(listOf({ LocalAttachment("image", "图片.jpg", "image/jpeg", true, "/unused/image", 1) }))
        }
        compose.waitUntil(5000) { vm.attachments.state.value.items.size == 1 }
        compose.onNodeWithTag("composer-send").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("所选模型不支持图片，请更换模型或移除图片。").assertExists()
        switchTo("en", "Work on Desktop")
        compose.onNodeWithText("The selected model does not support images. Change the model or remove the images.").assertExists()
        compose.runOnIdle {
            assertEquals(0, sends); assertEquals(0, uploads)
            assertEquals("图片问题", vm.composer.value.draft)
            assertEquals(1, vm.attachments.state.value.items.size)
            assertFalse(vm.composer.value.unknown)
        }
    }

    @Test fun localeRecreationPreservesDraftAttachmentsPendingAndUnknownWithoutReplay() {
        val draft = "中文 draft 👨‍👩‍👧‍👦"
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "locale-lifecycle-attachment.txt")
        file.writeText("附件原文")
        try {
            compose.onNodeWithContentDescription("在 Desktop 上工作").performTextInput(draft)
            compose.runOnIdle { vm.attachments.enqueue(listOf({ LocalAttachment("locale-test", "附件.txt", "text/plain", false, file.path, file.length()) })) }
            compose.waitUntil(5000) { vm.attachments.state.value.items.size == 1 }
            val originalVm = vm
            switchTo("en", "Work on Desktop")
            compose.onNodeWithContentDescription("Work on Desktop").assertTextContains(draft)
            compose.runOnIdle {
                assertSame(originalVm, vm)
                assertEquals("附件.txt", vm.attachments.state.value.items.single().name)
                assertEquals(0, sends); assertEquals(0, uploads)
            }
            compose.onNodeWithTag("composer-send").performClick()
            compose.waitUntil(5000) { sends == 1 && vm.composer.value.pending }
            switchTo("zh-Hans", "在 Desktop 上工作")
            compose.onNodeWithTag("composer-send").assertDoesNotExist()
            compose.runOnIdle {
                assertSame(originalVm, vm)
                assertEquals(draft, vm.composer.value.draft)
                assertTrue(vm.attachments.state.value.locked)
                assertEquals(1, sends); assertEquals(1, uploads)
                response.complete(Unit)
            }
            compose.waitUntil(5000) { vm.composer.value.unknown && !vm.composer.value.pending }
            switchTo("en", "Work on Desktop")
            compose.onNodeWithContentDescription("Work on Desktop").assertTextContains(draft)
            compose.onNodeWithTag("composer-send").assertIsNotEnabled()
            compose.onNodeWithText("Sending failed or the outcome is unknown. Your draft and attachments are retained. Verify before acting manually.").assertExists()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("composer-send").assertIsNotEnabled()
            compose.runOnIdle {
                assertTrue(vm.composer.value.unknown)
                assertEquals(1, vm.attachments.state.value.items.size)
                assertEquals(1, sends); assertEquals(1, uploads)
                assertEquals("$draft\n\n@/remote/附件.txt", submitted)
            }
        } finally { file.delete() }
    }
}
