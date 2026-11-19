package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSearchTest {
    @Test fun mergesDesktopsWithFallbackTitlePriorityAndGlobalLimit() = runTest {
        val base = FakeRemoteRepository()
        base.connections.value += ConnectionSummary("d", "Offline", "https://offline", false)
        base.sessions.value = mapOf("d" to listOf(RemoteSession("same", "MATCH local", updatedAt = 2),
            RemoteSession("hidden", "match archived", archivedAt = 1)))
        val repo = object : RemoteDesktopRepository by base {
            override suspend fun searchSessions(connectionId: String, query: String): List<RemoteSearchResult> {
                assertEquals("match", query)
                if (connectionId == "d") error("Offline")
                return (0..35).map { RemoteSearchResult("c", if (it == 0) "same" else "$it", "Content", 100L + it, false, "matching content") }
            }
        }
        val controller = RemoteSearchController(repo, backgroundScope)
        controller.search(" match "); advanceTimeBy(151); runCurrent()
        assertEquals(30, controller.state.value.results.size)
        assertEquals("d", controller.state.value.results.first().connectionId)
        assertEquals(setOf("d"), controller.state.value.fallbackConnections)
        assertFalse(controller.state.value.results.any { it.sessionId == "hidden" })
        assertFalse(controller.state.value.loading)
    }
    @Test fun debounceAndGenerationRejectLateNonCancellableResult() = runTest {
        val calls = mutableListOf<String>()
        val old = CompletableDeferred<Unit>()
        val repo = object : RemoteDesktopRepository by FakeRemoteRepository() {
            override suspend fun searchSessions(connectionId: String, query: String): List<RemoteSearchResult> {
                calls += query
                if (query == "old") withContext(NonCancellable) { old.await() }
                return listOf(RemoteSearchResult(connectionId, query, query, 1))
            }
        }
        val controller = RemoteSearchController(repo, backgroundScope)
        controller.search("discarded"); advanceTimeBy(100)
        controller.search("old"); advanceTimeBy(151); runCurrent()
        controller.search("new"); advanceTimeBy(151); runCurrent()
        assertEquals("new", controller.state.value.results.single().title)
        old.complete(Unit); runCurrent()
        assertEquals(listOf("old", "new"), calls)
        assertEquals("new", controller.state.value.results.single().title)
        controller.close()
    }
    @Test fun emptyQueryShowsRecentWithoutRemoteRequests() = runTest {
        val base = FakeRemoteRepository()
        base.sessions.value = mapOf("c" to (1..40).map { RemoteSession("$it", "Title", updatedAt = it.toLong()) })
        val controller = RemoteSearchController(base, backgroundScope)
        controller.search(""); runCurrent()
        assertEquals(30, controller.state.value.results.size)
        assertEquals("40", controller.state.value.results.first().sessionId)
        assertTrue(controller.state.value.fallbackConnections.isEmpty())
    }
    @Test fun disabledDevicesAreNeverQueriedAndRecentResultsFollowEnabledSnapshots() = runTest {
        val base = FakeRemoteRepository()
        base.connections.value += ConnectionSummary("d", "Disabled", "https://disabled", false, enabled = false)
        base.sessions.value = mapOf("c" to listOf(RemoteSession("old", "Old")), "d" to listOf(RemoteSession("hidden", "Hidden", updatedAt = 99)))
        val calls = mutableListOf<String>()
        val repo = object : RemoteDesktopRepository by base {
            override suspend fun searchSessions(connectionId: String, query: String): List<RemoteSearchResult> {
                calls += connectionId
                return listOf(RemoteSearchResult(connectionId, "match", "Match", 1))
            }
        }
        val controller = RemoteSearchController(repo, backgroundScope)
        controller.search(""); runCurrent()
        assertEquals(listOf("old"), controller.state.value.results.map { it.sessionId })
        base.sessions.value += "c" to listOf(RemoteSession("new", "New", updatedAt = 10)); runCurrent()
        assertEquals(listOf("new"), controller.state.value.results.map { it.sessionId })
        controller.search("match"); advanceTimeBy(151); runCurrent()
        assertEquals(listOf("c"), calls)
        base.connections.value = base.connections.value.map { it.copy(enabled = false) }; runCurrent(); advanceTimeBy(151); runCurrent()
        assertTrue(controller.state.value.results.isEmpty())
        assertTrue(controller.state.value.fallbackConnections.isEmpty())
        controller.close()
        base.connections.value = base.connections.value.map { it.copy(enabled = true) }; runCurrent(); advanceTimeBy(151); runCurrent()
        assertEquals(listOf("c"), calls)
    }
}
