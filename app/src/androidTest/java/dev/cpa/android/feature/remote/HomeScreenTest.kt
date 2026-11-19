package dev.cpa.android.feature.remote

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.data.repository.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun repo() = UiFakeRepository().apply {
        connections.value = listOf(ConnectionSummary("a", "A", "http://a:1", true, ConnectionPhase.Connected),
            ConnectionSummary("b", "B", "http://b:1", true, ConnectionPhase.Connected))
        projects.value = emptyMap()
        sessions.value = mapOf("a" to listOf(RemoteSession("same", "A pin", pinned = true)), "b" to listOf(RemoteSession("same", "B pin", pinned = true)))
    }

    @Test fun pinnedIdentityDeviceFilterAndDisabledSelectionReset() {
        val repo = repo()
        var opened: Pair<String, String>? = null
        compose.setContent { CpaTheme { RemoteHomeRoute(homeViewModel(repo), { c, s -> opened = c to s }, {}) } }
        compose.onNodeWithText("置顶").assertExists()
        compose.onNodeWithTag(homeKey("open", "a", "same")).performClick()
        compose.runOnIdle { assertEquals("a" to "same", opened) }
        compose.onNodeWithTag(homeKey("filter", "b")).performClick()
        compose.onNodeWithTag(homeKey("open", "a", "same")).assertDoesNotExist()
        compose.onNodeWithTag(homeKey("open", "b", "same")).performClick()
        compose.runOnIdle { assertEquals("b" to "same", opened); repo.connections.value = repo.connections.value.map { if (it.id == "b") it.copy(enabled = false) else it } }
        compose.onNodeWithTag(homeKey("open", "a", "same")).assertExists()
        compose.onNodeWithTag(homeKey("open", "b", "same")).assertDoesNotExist()
        compose.onNodeWithTag(homeKey("filter", "b")).assertDoesNotExist()
    }

    @Test fun groupFoldingResetsShowAllAndAttentionSessionsStayVisible() {
        val repo = UiFakeRepository().apply {
            connections.value = listOf(ConnectionSummary("a", "A", "http://a:1", true, ConnectionPhase.Connected))
            projects.value = emptyMap()
            sessions.value = mapOf("a" to (1..10).map { RemoteSession("$it", "thread-$it", unread = when (it) { 6 -> "error"; in 1..5 -> "unread"; else -> "read" }, updatedAt = (11 - it).toLong()) })
            runningSessions.value = mapOf("a" to setOf("7"))
        }
        compose.setContent { CpaTheme { RemoteHomeRoute(homeViewModel(repo), { _, _ -> }, {}) } }
        val list = compose.onNodeWithTag("home-list")
        list.performScrollToNode(hasText("显示全部（10）"))
        compose.onNodeWithContentDescription("thread-6，未读（异常）").assertExists()
        compose.onNodeWithContentDescription("thread-7，正在运行").assertExists()
        compose.onNodeWithText("thread-8").assertDoesNotExist()
        compose.onNodeWithText("显示全部（10）").performClick()
        list.performScrollToNode(hasText("thread-10"))
        compose.onNodeWithText("收起").assertExists()
        list.performScrollToNode(hasTestTag(homeKey("group", "a", "")))
        compose.onNodeWithTag(homeKey("group", "a", "")).performClick()
        compose.onNodeWithText("thread-7").assertDoesNotExist()
        compose.onNodeWithTag(homeKey("group", "a", "")).performClick()
        list.performScrollToNode(hasText("显示全部（10）"))
        compose.onNodeWithText("thread-8").assertDoesNotExist()
    }

    @Test fun longPressMenusDispatchSessionAndProjectActions() {
        val repo = UiFakeRepository().apply {
            connections.value = listOf(ConnectionSummary("c", "Desktop", "http://desktop:1", true, ConnectionPhase.Connected))
            projects.value = mapOf("c" to listOf(RemoteProject("p", "Project", "/src"), RemoteProject("q", "Other", "/other")))
            sessions.value = mapOf("c" to listOf(RemoteSession("s", "Task", "p")))
        }
        lateinit var clipboard: androidx.compose.ui.platform.ClipboardManager
        compose.setContent {
            clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            CpaTheme { RemoteHomeRoute(homeViewModel(repo), { _, _ -> }, {}) }
        }
        val row = compose.onNodeWithTag(homeKey("open", "c", "s"))
        row.performTouchInput { longClick() }
        compose.onNodeWithText("置顶聊天").performClick()
        compose.waitUntil(5_000) { "pin:s:true" in repo.calls }
        row.performTouchInput { longClick() }
        compose.onNodeWithText("移至项目").performClick()
        compose.onAllNodesWithText("Other").onLast().performClick()
        compose.waitUntil(5_000) { "move:s:q" in repo.calls }
        row.performTouchInput { longClick() }
        compose.onNodeWithText("复制会话 ID").performClick()
        compose.runOnIdle { assertEquals("s", clipboard.getText()?.text) }
        compose.onNodeWithText("已复制到剪贴板").assertExists()
        row.performTouchInput { longClick() }
        compose.onNodeWithText("复制深度链接").performClick()
        compose.runOnIdle { assertEquals("http://desktop:1/#/chat/s", clipboard.getText()?.text) }
        row.performTouchInput { longClick() }
        compose.onNodeWithText("重命名聊天").performClick()
        compose.onNodeWithContentDescription("标题").performTextReplacement("Renamed 中文")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5_000) { "rename:s:Renamed 中文" in repo.calls }
        row.performTouchInput { longClick() }
        compose.onNodeWithText("标记为未读").performClick()
        compose.waitUntil(5_000) { "unread:s:true" in repo.calls }
        row.performTouchInput { longClick() }
        compose.onNodeWithText("归档聊天").performClick()
        compose.waitUntil(5_000) { "archive:s" in repo.calls }
        compose.onNodeWithTag(homeKey("group", "c", "p")).performTouchInput { longClick() }
        compose.onNodeWithText("移除项目").performClick()
        compose.onNodeWithText("移除 Project？").assertExists()
        compose.runOnIdle { assertFalse(repo.calls.any { it.startsWith("removeProject") }) }
        compose.onAllNodesWithText("移除项目").onLast().performClick()
        compose.waitUntil(5_000) { "removeProject:p" in repo.calls }
    }

    @Test fun continueInNewChatForksAndOpensTheCopy() {
        val repo = UiFakeRepository()
        var opened: Pair<String, String>? = null
        compose.setContent { CpaTheme { RemoteHomeRoute(homeViewModel(repo), { c, s -> opened = c to s }, {}) } }
        compose.onNodeWithTag(homeKey("open", "c", "s")).performTouchInput { longClick() }
        compose.onNodeWithText("在新聊天中继续").performClick()
        compose.waitUntil(5_000) { opened != null }
        compose.runOnIdle { assertTrue(repo.calls.any { it == "fork:s:null:null:null" }); assertEquals("c", opened!!.first); assertNotEquals("s", opened!!.second) }
    }

    @Test fun worktreeSheetSendsBranchAndEnvironment() {
        val repo = UiFakeRepository()
        compose.setContent { CpaTheme { RemoteHomeRoute(homeViewModel(repo), { _, _ -> }, {}) } }
        compose.onNodeWithTag(homeKey("open", "c", "s")).performTouchInput { longClick() }
        compose.onNodeWithText("在新工作树中继续").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Environment").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("分支").performClick()
        compose.onNodeWithText("dev").performClick()
        compose.onNodeWithText("Environment").performClick()
        compose.onNodeWithTag("worktree-continue").performClick()
        compose.waitUntil(5_000) { repo.calls.any { it.startsWith("fork:") } }
        compose.runOnIdle { assertTrue(repo.calls.contains("fork:s:null:dev:env")) }
    }

    @Test fun emptyStatesOfferAddConnectionAndSettings() {
        val repo = UiFakeRepository().apply { connections.value = emptyList() }
        compose.setContent { CpaTheme { RemoteHomeRoute(homeViewModel(repo), { _, _ -> }, {}) } }
        compose.onNodeWithText("尚未添加桌面端").assertExists()
        compose.onNodeWithText("添加连接").performClick()
        compose.onNodeWithText("直连桌面端").assertExists()
        compose.onNodeWithText("通过 CLIProxyAPI Tunnel 连接").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { repo.connections.value = listOf(ConnectionSummary("c", "Desktop", "http://desktop:1", true, enabled = false)) }
        compose.onNodeWithText("所有连接均已断开").assertExists()
        compose.onNodeWithText("打开设置").performClick()
        compose.onNodeWithText("设置").assertExists()
    }

    @Test fun homeComposerPreparesNewChatWithoutCreating() {
        val repo = UiFakeRepository()
        var newThread = 0
        lateinit var vm: RemoteHomeViewModel
        compose.runOnIdle { vm = homeViewModel(repo) }
        compose.setContent { CpaTheme { RemoteHomeRoute(vm, { _, _ -> }, { newThread++ }) } }
        compose.onNodeWithContentDescription("向 CPA 提问").performTextInput("draft prompt")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { newThread == 1 }
        compose.runOnIdle {
            assertEquals("draft prompt", vm.newThread.state.value.draft)
            assertTrue(repo.calls.none { it.startsWith("create") || it == "send" })
        }
    }
}
