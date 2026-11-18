package dev.cpa.android.feature.remote

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.localization.AppLocaleRule
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteForkNavigationTest {
    @get:Rule(order = 0) val language = AppLocaleRule("en")
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun homeManagementMarksSessionWithoutBeginningViewing() {
        val base = RemoteWorkspaceScreenTest.Fake()
        base.connections.value = listOf(ConnectionSummary("c", "Desktop", "https://desktop", false, ConnectionPhase.Connected))
        val session = RemoteSession("s", "Managed session", pinned = true)
        base.sessions.value = mapOf("c" to listOf(session))
        var views = 0
        val marks = mutableListOf<Boolean>()
        val repo = object : RemoteDesktopRepository by base {
            override fun beginViewing(connectionId: String, sessionId: String) { views++ }
            override suspend fun setSessionUnread(connectionId: String, sessionId: String, unread: Boolean) {
                assertEquals("c", connectionId); assertEquals("s", sessionId)
                marks += unread
                base.sessions.value = mapOf("c" to listOf(session.copy(unread = if (unread) "unread" else "read")))
            }
        }
        compose.setContent { CpaTheme { RemoteNavigation(repo) } }
        compose.onNodeWithTag(homeKey("open", "c", "s")).performTouchInput { longClick() }
        compose.onNodeWithText("Mark as Unread").performClick()
        compose.waitUntil(5_000) { marks.size == 1 }
        compose.onNodeWithTag(homeKey("open", "c", "s")).performTouchInput { longClick() }
        compose.onNodeWithText("Mark as Read").performClick()
        compose.waitUntil(5_000) { marks.size == 2 }
        compose.runOnIdle { assertEquals(listOf(true, false), marks); assertEquals(0, views) }
    }

    @Test fun forkUsesTargetViewModelAndBackRestoresSourceWithoutDialog() {
        val base = RemoteWorkspaceScreenTest.Fake()
        base.connections.value = listOf(ConnectionSummary("c", "Desktop", "https://desktop", false, ConnectionPhase.Connected))
        base.sessions.value = mapOf("c" to listOf(RemoteSession("s", "Source session")))
        val threads = mutableMapOf<String, MutableStateFlow<RemoteThread>>()
        val opened = mutableListOf<String>()
        val sent = mutableListOf<String>()
        var target: String? = null
        val repo = object : RemoteDesktopRepository by base {
            override fun observeThread(connectionId: String, sessionId: String) =
                threads.getOrPut(sessionId) { MutableStateFlow(RemoteThread(connectionId, sessionId, entries = if (sessionId != "s") emptyList() else listOf(
                    RemoteEntry("u", "user", "question"), RemoteEntry("a", "assistant", "answer", details = RemoteEntryDetails(status = "done", createdAt = 1.0, completedAt = 2.0))))) }
            override suspend fun openThread(connectionId: String, sessionId: String) { opened += sessionId }
            override suspend fun forkSession(connectionId: String, sessionId: String, newSessionId: String, atEntryId: String?, worktree: RemoteWorktreeFork?) {
                assertEquals("s", sessionId)
                target = newSessionId
                base.sessions.value = mapOf("c" to (base.sessions.value.getValue("c") + RemoteSession(newSessionId, "Fork target")))
            }
            override suspend fun send(connectionId: String, sessionId: String, text: String, modelId: String?) {
                sent += sessionId
            }
        }
        compose.setContent { CpaTheme { RemoteNavigation(repo) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Source session", substring = true))
        compose.onNodeWithText("Source session", substring = true).performClick()
        compose.onNodeWithContentDescription("fork-here").performClick()
        compose.waitUntil(5_000) { target != null && target in opened }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Fork target").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Work on Desktop").performTextInput("target message")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { sent.size == 1 }
        compose.runOnIdle { assertEquals(listOf(target), sent) }
        compose.onNodeWithTag("thread-back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Source session").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Work on Desktop").performTextInput("source message")
        compose.onNodeWithTag("composer-send").performClick()
        compose.waitUntil(5_000) { sent.size == 2 }
        compose.runOnIdle { assertEquals(listOf(target, "s"), sent) }
    }
}
