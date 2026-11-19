package dev.cpa.android.feature.remote

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.data.repository.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ThreadScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun route(repo: UiFakeRepository, onBack: () -> Unit = {}, onForked: (String) -> Unit = {}, locale: String? = null): RemoteThreadViewModel {
        lateinit var vm: RemoteThreadViewModel
        compose.runOnIdle { vm = threadViewModel(repo) }
        compose.setContent {
            if (locale != null) Localized(locale) { RemoteThreadRoute(vm, onForked = onForked, onBack = onBack) }
            else CpaTheme { RemoteThreadRoute(vm, onForked = onForked, onBack = onBack) }
        }
        return vm
    }

    @Test fun activeRunSendsFollowUpAndPendingLabelsUpdate() {
        val repo = UiFakeRepository()
        repo.thread("s").value = RemoteThread("c", "s", running = true, entries = listOf(RemoteEntry("e", "user", "next", pendingStatus = "pending")))
        route(repo)
        compose.onNodeWithText("等待桌面端确认").assertExists()
        for ((status, label) in listOf("queue" to "排队中", "steer" to "待调整方向")) {
            compose.runOnIdle {
                val thread = repo.thread("s").value
                repo.thread("s").value = thread.copy(entries = listOf(thread.entries.single().copy(pendingStatus = status)))
            }
            compose.onNodeWithText(label).assertExists()
            compose.onNodeWithText("等待桌面端确认").assertDoesNotExist()
        }
        compose.onNodeWithTag("composer-stop").assertExists()
        compose.onNodeWithContentDescription("在 Desktop 上工作").performTextInput("continue")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { repo.calls.contains("followUp") }
        compose.runOnIdle { assertEquals("continue", repo.sent); repo.thread("s").value = repo.thread("s").value.copy(entries = listOf(RemoteEntry("e", "user", "next")), outcomeUnknown = true) }
        compose.onNodeWithText("待调整方向").assertDoesNotExist()
        compose.onNodeWithTag("send-unknown").assertExists()
        compose.onNodeWithContentDescription("在 Desktop 上工作").performTextInput("more")
        compose.onNodeWithTag("composer-send").assertIsNotEnabled()
    }

    @Test fun unrecognizedFollowUpStatusUsesNeutralEnglishLabel() {
        val repo = UiFakeRepository()
        repo.thread("s").value = RemoteThread("c", "s", running = true,
            entries = listOf(RemoteEntry("e", "user", "next", pendingStatus = "unrecognized")))
        route(repo, locale = "en")
        compose.onNodeWithText("Awaiting desktop confirmation").assertExists()
        compose.onNodeWithText("Queued").assertDoesNotExist()
    }

    @Test fun promptBuildStatusInLiveTurnClearsWhileRunContinues() {
        val repo = UiFakeRepository()
        val thread = repo.thread("s")
        thread.value = thread.value.copy(running = true, isBuildingSystemPrompt = true, entries = listOf(RemoteEntry("u", "user", "Hello")))
        route(repo, locale = "zh-Hans")
        val status = compose.onNodeWithTag("system-prompt-build-status")
        status.assertIsDisplayed().assertTextEquals("正在构建增强性系统提示词").assertHasNoClickAction()
        compose.runOnIdle { thread.value = thread.value.copy(isBuildingSystemPrompt = false) }
        status.assertDoesNotExist()
        compose.onNodeWithTag("turn-header").assertExists()
    }

    @Test fun promptBuildStatusShownWithoutLiveTurn() {
        val repo = UiFakeRepository()
        repo.thread("s").value = RemoteThread("c", "s", running = true, isBuildingSystemPrompt = true,
            entries = listOf(RemoteEntry("u", "user", "Hello", pendingStatus = "pending")))
        route(repo, locale = "en")
        compose.onNodeWithTag("turn-header").assertDoesNotExist()
        compose.onNodeWithTag("system-prompt-build-status").assertIsDisplayed()
            .assertTextEquals("Building enhanced system prompt").assertHasNoClickAction()
    }

    @Test fun orphanToolOutputIsVisibleAndStopDispatches() {
        val repo = UiFakeRepository()
        repo.thread("s").value = RemoteThread("c", "s", running = true, entries = listOf(RemoteEntry("e", "tool_result", "actual output", "shell")))
        route(repo)
        compose.onNodeWithText("actual output").assertIsDisplayed()
        compose.onNodeWithText("tool_result · shell").assertIsDisplayed()
        compose.onNodeWithTag("composer-stop").performClick()
        compose.waitUntil(5_000) { "stop" in repo.calls }
    }

    @Test fun completedSnapshotStartsAtTopAndNewPromptResumesFollowing() {
        val repo = UiFakeRepository()
        repo.thread("s").value = RemoteThread("c", "s", loading = true, running = true)
        route(repo)
        compose.runOnIdle { repo.thread("s").value = RemoteThread("c", "s",
            entries = (0 until 30).map { RemoteEntry("e$it", if (it % 2 == 0) "user" else "assistant", "History $it\n".repeat(6)) }) }
        compose.waitForIdle()
        fun position() = compose.onNodeWithTag("remote-timeline").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(0f, position(), .01f)
        compose.onNodeWithTag("jump-latest").assertExists()
        compose.runOnIdle { repo.thread("s").value = repo.thread("s").value.let { it.copy(running = true, entries = it.entries + RemoteEntry("next", "user", "New prompt")) } }
        compose.onNodeWithTag("remote-timeline-end").assertIsDisplayed()
        compose.runOnIdle { repo.thread("s").value = repo.thread("s").value.let { it.copy(entries = it.entries + RemoteEntry("answer", "assistant", "New answer")) } }
        compose.onNodeWithText("New answer").assertIsDisplayed()
    }

    @Test fun moreMenuRenamesCopiesArchivesAndOpensPanels() {
        val repo = UiFakeRepository()
        var back = 0
        lateinit var clipboard: androidx.compose.ui.platform.ClipboardManager
        lateinit var vm: RemoteThreadViewModel
        compose.runOnIdle { vm = threadViewModel(repo) }
        compose.setContent {
            clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            CpaTheme { RemoteThreadRoute(vm) { back++ } }
        }
        compose.onNodeWithTag("thread-title").assertTextEquals("Task")
        compose.onNodeWithText("Desktop • Project").assertExists()
        compose.onNodeWithTag("thread-more").performClick()
        compose.onNodeWithText("重命名").performClick()
        compose.onNodeWithContentDescription("标题").performTextReplacement("中文 title 😀")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { "rename:s:中文 title 😀" in repo.calls }
        compose.onNodeWithTag("thread-more").performClick()
        compose.onNodeWithText("复制对话串 ID").performClick()
        compose.runOnIdle { assertEquals("s", clipboard.getText()?.text) }
        compose.onNodeWithTag("thread-more").performClick()
        compose.onNodeWithText("置顶").performClick()
        compose.waitUntil(5_000) { "pin:s:true" in repo.calls }
        compose.onNodeWithTag("thread-more").performClick()
        compose.onNodeWithText("归档").performClick()
        compose.waitUntil(5_000) { "archive:s" in repo.calls }
        compose.runOnIdle { repo.sessions.value = mapOf("c" to listOf(RemoteSession("s", "Task", "p", "main", archivedAt = 5))) }
        compose.waitUntil(5_000) { back == 1 }
    }

    @Test fun resumeOnlyShowsForInterruptedSessionAndRunsOnce() {
        val repo = UiFakeRepository()
        val entry = RemoteEntry("a", "assistant", "Done", details = RemoteEntryDetails(status = "done"))
        repo.thread("s").value = RemoteThread("c", "s", listOf(entry))
        route(repo)
        compose.onNodeWithTag("composer-resume").assertDoesNotExist()
        compose.runOnIdle { repo.thread("s").value = RemoteThread("c", "s", listOf(entry.copy(details = entry.details.copy(status = "aborted")))) }
        compose.onNodeWithTag("composer-resume").assertExists()
        compose.onNodeWithText("执行中断").assertExists()
        compose.runOnIdle { repo.thread("s").value = repo.thread("s").value.copy(outcomeUnknown = true) }
        compose.onNodeWithTag("composer-resume").assertDoesNotExist()
        compose.runOnIdle { repo.thread("s").value = repo.thread("s").value.copy(outcomeUnknown = false); assertTrue(repo.calls.none { it.startsWith("action:") }) }
        compose.onNodeWithTag("composer-resume").performClick()
        compose.waitUntil(5_000) { "action:resume" in repo.calls }
    }

    @Test fun reasoningSliderAndModelSheetKeepWireValues() {
        val repo = UiFakeRepository()
        repo.capabilities.value = mapOf("c" to RemoteCapabilities(listOf(RemoteModel("m", "远端模型名", supportsFast = true, reasoningLevels = listOf(
            RemoteReasoningOption("LoW", "远端默认标签"), RemoteReasoningOption("vendor-custom", "远端 custom")))), defaultsLoaded = true))
        repo.sessions.value = mapOf("c" to listOf(RemoteSession("s", "Task", "p", modelId = "m", reasoningEffort = "LoW")))
        route(repo)
        compose.onNodeWithText("远端模型名").assertExists()
        compose.onNodeWithTag("composer-reasoning").performClick()
        compose.onNodeWithTag("reasoning-slider").performTouchInput { click(centerRight - androidx.compose.ui.geometry.Offset(60f, 0f)) }
        compose.onNodeWithText("远端 custom").assertExists()
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, 300f)) }
        compose.waitUntil(5_000) { "runtime:null:vendor-custom:null" in repo.calls }
        compose.runOnIdle { repo.sessions.value = mapOf("c" to listOf(repo.sessions.value.getValue("c").single().copy(reasoningEffort = "vendor-custom"))) }
        compose.onNodeWithContentDescription("在 Desktop 上工作").performTextInput("/fast")
        compose.onNodeWithText("快速").performClick()
        compose.waitUntil(5_000) { "runtime:null:null:fast" in repo.calls }
        compose.onNodeWithText("已开启快速模式").assertExists()
        compose.onNodeWithContentDescription("在 Desktop 上工作").performTextInput("/model")
        compose.onNodeWithText("模型").performClick()
        compose.onNodeWithTag("advanced-reasoning").performClick()
        compose.onNodeWithText("低").performClick()
        compose.onNodeWithText("高级").performTouchInput { swipeDown(startY = top, endY = top + 1200f, durationMillis = 300) }
        compose.waitUntil(5_000) { repo.calls.any { it.startsWith("runtime:m:LoW") } }
    }

    @Test fun rejectedReasoningPickReopensAtConfirmedValue() {
        val repo = object : UiFakeRepository() {
            override suspend fun setSessionRuntime(connectionId: String, sessionId: String, modelId: String?, reasoningEffort: String?, speed: String?) {
                super.setSessionRuntime(connectionId, sessionId, modelId, reasoningEffort, speed)
                throw IllegalArgumentException("rejected")
            }
        }
        repo.capabilities.value = mapOf("c" to RemoteCapabilities(listOf(RemoteModel("m", reasoningLevels = listOf(
            RemoteReasoningOption("original", "Original"), RemoteReasoningOption("other", "Other")))), defaultsLoaded = true))
        repo.sessions.value = mapOf("c" to listOf(RemoteSession("s", "Task", "p", modelId = "m", reasoningEffort = "original")))
        route(repo)
        compose.onNodeWithTag("composer-reasoning").performClick()
        compose.onNodeWithTag("reasoning-slider").performTouchInput { click(centerRight - androidx.compose.ui.geometry.Offset(60f, 0f)) }
        compose.onNodeWithText("Other").assertExists()
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(centerX, 300f)) }
        compose.waitUntil(5_000) { "runtime:null:other:null" in repo.calls }
        compose.onNodeWithText("好").performClick()
        compose.onNodeWithTag("composer-reasoning").performClick()
        compose.onNodeWithText("Original").assertExists()
        compose.onNodeWithText("Other").assertDoesNotExist()
    }

    @Test fun inlineEditReplacesBubbleAndSubmitsRevisedText() {
        val repo = UiFakeRepository()
        repo.thread("s").value = RemoteThread("c", "s", entries = listOf(RemoteEntry("u", "user", "original"),
            RemoteEntry("a", "assistant", "answer", details = RemoteEntryDetails(status = "done", completedAt = 2.0, createdAt = 1.0))))
        route(repo)
        compose.onNodeWithText("original").performTouchInput { longClick() }
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("message-editor").assertExists()
        compose.onNodeWithTag("thread-composer-card").assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑历史消息").performTextReplacement("revised")
        compose.onNodeWithTag("edit-send").performClick()
        compose.waitUntil(5_000) { "edit:u:revised" in repo.calls }
    }

    @Test fun bubbleMenuCopiesSkillsWithConfiguredTrigger() {
        val repo = UiFakeRepository()
        repo.capabilities.value = mapOf("c" to RemoteCapabilities(defaults = RemoteDefaults(skillTrigger = '/')))
        repo.thread("s").value = RemoteThread("c", "s", entries = listOf(RemoteEntry("u", "user", "原文 \$review", skillReferences = listOf(SkillReference(3, "review")))))
        lateinit var clipboard: androidx.compose.ui.platform.ClipboardManager
        lateinit var vm: RemoteThreadViewModel
        compose.runOnIdle { vm = threadViewModel(repo) }
        compose.setContent {
            clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            CpaTheme { RemoteThreadRoute(vm) {} }
        }
        compose.onNodeWithTag("bubble:u").performTouchInput { longClick() }
        compose.onNodeWithText("拷贝").performClick()
        compose.runOnIdle { assertEquals("原文 /skill:review", clipboard.getText()?.text) }
    }

    @Test fun messageForkCopiesUpToReplyAndOpensTarget() {
        val repo = UiFakeRepository()
        repo.thread("s").value = RemoteThread("c", "s", entries = listOf(RemoteEntry("u", "user", "q"),
            RemoteEntry("a", "assistant", "reply", details = RemoteEntryDetails(status = "done", createdAt = 1.0, completedAt = 3000.0))))
        var opened: String? = null
        route(repo, onForked = { opened = it })
        compose.onNodeWithContentDescription("fork-here").performClick()
        compose.waitUntil(5_000) { opened != null }
        compose.runOnIdle { assertTrue(repo.calls.contains("fork:s:a:null:null")) }
    }

    @Test fun pendingAskShowsBannerAndSheetSubmitsOption() {
        val repo = UiFakeRepository()
        repo.askRequest = buildJsonObject {
            put("id", "ask1"); put("sessionId", "s"); put("toolCallId", "tool"); put("question", "Pick one")
            put("options", buildJsonArray { add(buildJsonObject { put("title", "Alpha"); put("recommended", true) }); add(buildJsonObject { put("title", "Beta"); put("description", "Second") }) })
            put("allowCustom", true); put("allowSkip", true)
        }
        route(repo)
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("ask-sheet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pick one").assertExists()
        compose.onNodeWithText("推荐").assertExists()
        compose.onNodeWithTag("ask-later").performClick()
        compose.onNodeWithTag("ask-banner").performClick()
        compose.onNodeWithTag("ask-option:1").performClick()
        compose.waitUntil(5_000) { "ask:submitAnswer" in repo.rpcCalls }
    }
}
