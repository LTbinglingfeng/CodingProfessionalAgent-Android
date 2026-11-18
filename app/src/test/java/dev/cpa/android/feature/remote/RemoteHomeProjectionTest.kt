package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class RemoteHomeProjectionTest {
    @Test fun pinnedAcrossDevicesUsesTimeAndKeepsIdentityAndEnabledFilter() {
        val connections = listOf(ConnectionSummary("a", "A", "https://a", false),
            ConnectionSummary("b", "B", "https://b", false), ConnectionSummary("disabled", "D", "https://d", false, enabled = false))
        val sessions = mapOf("a" to listOf(RemoteSession("same", "A pin", pinned = true, updatedAt = 2), RemoteSession("archived", "hidden", pinned = true, archivedAt = 1)),
            "b" to listOf(RemoteSession("same", "B pin", pinned = true, updatedAt = 3)),
            "disabled" to listOf(RemoteSession("same", "hidden", pinned = true, updatedAt = 5)))
        assertEquals(listOf("b", "a"), homePinnedSessions(connections, sessions, null).map { it.connection.id })
        assertEquals(listOf("a"), homePinnedSessions(connections, sessions, "a").map { it.connection.id })
        assertTrue(homeProjectSessions(sessions["a"]!!, emptySet()).isEmpty())
    }
    @Test fun projectOrderUsesRunningUnreadUpdatedCreatedAndStableId() {
        val sessions = listOf(RemoteSession("z", "Z", updatedAt = 9), RemoteSession("a", "A", updatedAt = 9),
            RemoteSession("newer-created", "created", updatedAt = 9, createdAt = 1),
            RemoteSession("unread", "unread", unread = "unread", updatedAt = 1),
            RemoteSession("running", "running", updatedAt = 0), RemoteSession("pin", "pin", pinned = true), RemoteSession("archived", "archived", archivedAt = 1))
        assertEquals(listOf("running", "unread", "newer-created", "a", "z"), homeProjectSessions(sessions, setOf("running")).map { it.id })
    }
    @Test fun visibleLimitIncludesEveryAttentionSessionAndGroupKeysCannotCollide() {
        val sessions = (1..10).map { RemoteSession("$it", "$it", unread = if (it <= 6) "unread" else "read") }
        assertEquals(7, homeGroupLimit(sessions, setOf("6", "7")))
        assertEquals(5, homeGroupLimit(sessions.map { it.copy(unread = "read") }, emptySet()))
        assertNotEquals(homeGroupKey("a", null), homeGroupKey("a", ""))
        assertNotEquals(homeGroupKey("a", null), homeGroupKey("a", "-"))
        assertNotEquals(homeGroupKey("a/b", "c"), homeGroupKey("a", "b/c"))
        assertNotEquals(homeGroupKey("a", "same"), homeGroupKey("b", "same"))
    }
}
