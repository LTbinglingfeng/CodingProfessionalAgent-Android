package dev.cpa.android.feature.remote

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteManagementViewModelTest {
    @Test fun homeRefreshContinuesPastFailureAndStaysRefreshingUntilLastDesktopFinishes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val base = FakeRemoteRepository()
            val first = base.connections.value.single()
            base.connections.value = listOf(first, first.copy(id = "disabled", enabled = false), first.copy(id = "second"))
            val calls = mutableListOf<String>()
            val finish = CompletableDeferred<Unit>()
            val repo = object : RemoteDesktopRepository by base {
                override suspend fun refresh(id: String) {
                    calls += id
                    if (id == first.id) error("offline")
                    finish.await()
                }
            }
            val vm = RemoteHomeViewModel(SavedStateHandle(), repo)
            val visible = listOf(first.id, "second")
            vm.refreshVisible(visible); vm.refreshVisible(visible); runCurrent()
            assertEquals(listOf(first.id, "second"), calls)
            assertTrue(vm.operation.value.refreshing)
            finish.complete(Unit); runCurrent()
            assertFalse(vm.operation.value.refreshing)
            assertFalse(vm.operation.value.pending)
            assertNotNull(vm.operation.value.error)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun forkRetainsTargetOnCancellationAndDoesNotReplayAfterRestore() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val completion = CompletableDeferred<Unit>()
            var calls = 0
            var target: String? = null
            val repo = object : RemoteDesktopRepository by FakeRemoteRepository() {
                override suspend fun forkSession(connectionId: String, sessionId: String, newSessionId: String, atEntryId: String?, worktree: RemoteWorktreeFork?) {
                    calls++; target = newSessionId; completion.await()
                }
            }
            val state = saved()
            val first = RemoteThreadViewModel(state, repo)
            first.edit("unsent"); first.fork("entry"); first.fork(); runCurrent()
            assertEquals(1, calls)
            assertEquals(target, first.forkTarget.value)
            completion.cancel(); runCurrent()
            val restored = RemoteThreadViewModel(state, repo)
            restored.fork(); runCurrent()
            assertEquals(1, calls)
            assertTrue(restored.composer.value.unknown)
            assertEquals(target, restored.forkTarget.value)
            assertNull(restored.forkOpened.value)
            assertEquals("unsent", restored.composer.value.draft)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun confirmedForkNavigatesOnceAndPreservesComposerDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = object : RemoteDesktopRepository by FakeRemoteRepository() {
                override suspend fun forkSession(connectionId: String, sessionId: String, newSessionId: String, atEntryId: String?, worktree: RemoteWorktreeFork?) {
                    assertEquals("c", connectionId); assertEquals("s", sessionId)
                    assertEquals("entry", atEntryId); assertEquals(RemoteWorktreeFork("main", "env"), worktree)
                }
            }
            val vm = RemoteThreadViewModel(saved(), repo)
            vm.edit("keep"); vm.fork("entry", RemoteWorktreeFork("main", "env")); runCurrent()
            assertNotNull(vm.forkOpened.value)
            assertEquals("keep", vm.composer.value.draft)
            vm.consumedForkNavigation()
            assertNull(vm.forkOpened.value); assertNull(vm.forkTarget.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun runActionPreservesDraftAndSharesPendingAndRunningGuards() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val base = FakeRemoteRepository()
            val completion = CompletableDeferred<Unit>()
            val actions = mutableListOf<RemoteRunAction>()
            val repository = object : RemoteDesktopRepository by base {
                override suspend fun runAction(connectionId: String, sessionId: String, action: RemoteRunAction) {
                    assertEquals("c", connectionId); assertEquals("s", sessionId)
                    actions += action; completion.await()
                }
            }
            val vm = RemoteThreadViewModel(saved(), repository)
            runCurrent(); vm.edit("unsent draft")
            vm.runAction(RemoteRunAction.Resume); vm.runAction(RemoteRunAction.Compact); vm.send(); runCurrent()
            assertEquals(listOf(RemoteRunAction.Resume), actions)
            assertEquals(0, base.sends)
            completion.complete(Unit); runCurrent()
            assertEquals("unsent draft", vm.composer.value.draft)
            base.state.value = base.state.value.copy(running = true)
            vm.runAction(RemoteRunAction.Compact); runCurrent()
            assertEquals(1, actions.size)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun managementSharesSendGuardAndDoesNotDiscardDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val base = FakeRemoteRepository()
            val completion = CompletableDeferred<Unit>()
            var renames = 0
            val repository = object : RemoteDesktopRepository by base {
                override suspend fun renameSession(connectionId: String, sessionId: String, title: String) {
                    assertEquals("c", connectionId); assertEquals("s", sessionId); assertEquals("new", title)
                    renames++; completion.await()
                }
            }
            val vm = RemoteThreadViewModel(saved(), repository)
            runCurrent()
            vm.edit("keep draft")
            vm.rename("new"); vm.rename("duplicate"); vm.send()
            runCurrent()
            assertEquals(1, renames); assertEquals(0, base.sends)
            assertTrue(vm.composer.value.pending)
            completion.complete(Unit); advanceUntilIdle()
            assertEquals("keep draft", vm.composer.value.draft)
            assertFalse(vm.composer.value.pending)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun restoredUnknownCannotChangeSettings() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            val repository = object : RemoteDesktopRepository by FakeRemoteRepository() {
                override suspend fun setSessionRuntime(connectionId: String, sessionId: String, modelId: String?, reasoningEffort: String?, speed: String?) { calls++ }
            }
            val state = saved().apply { set("draft:1:c:s:pending", true) }
            val vm = RemoteThreadViewModel(state, repository)
            vm.runtime("model", "high", "fast"); advanceUntilIdle()
            assertEquals(0, calls)
            assertTrue(vm.composer.value.unknown)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun validationFailureIsSanitizedAndDoesNotClaimUnknown() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = object : RemoteDesktopRepository by FakeRemoteRepository() {
                override suspend fun renameSession(connectionId: String, sessionId: String, title: String) { throw IllegalArgumentException("private remote content") }
            }
            val state = saved()
            val vm = RemoteThreadViewModel(state, repository)
            vm.rename(""); advanceUntilIdle()
            assertFalse(vm.composer.value.unknown)
            assertEquals(dev.cpa.android.R.string.session_invalid_parameters, vm.composer.value.error)
            assertEquals(false, state.get<Boolean>("draft:1:c:s:pending"))
        } finally { Dispatchers.resetMain() }
    }
    @Test fun cancelledMutationPersistsUnknownWithoutReplay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val completion = CompletableDeferred<Unit>()
            val repository = object : RemoteDesktopRepository by FakeRemoteRepository() {
                override suspend fun archiveSession(connectionId: String, sessionId: String) { completion.await() }
            }
            val state = saved()
            val vm = RemoteThreadViewModel(state, repository)
            vm.archive(); runCurrent(); completion.cancel(); advanceUntilIdle()
            assertTrue(vm.composer.value.unknown)
            assertEquals(true, state.get<Boolean>("draft:1:c:s:pending"))
        } finally { Dispatchers.resetMain() }
    }
    @Test fun allManagementActionsUseTargetSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<String>()
            val repository = object : RemoteDesktopRepository by FakeRemoteRepository() {
                override suspend fun setSessionPinned(connectionId: String, sessionId: String, pinned: Boolean) { calls += "$connectionId/$sessionId/pin/$pinned" }
                override suspend fun moveSession(connectionId: String, sessionId: String, projectId: String?) { calls += "$connectionId/$sessionId/move/$projectId" }
                override suspend fun setSessionUnread(connectionId: String, sessionId: String, unread: Boolean) { calls += "$connectionId/$sessionId/unread/$unread" }
            }
            val vm = RemoteThreadViewModel(saved(), repository)
            vm.pin(true); advanceUntilIdle()
            vm.move(null); advanceUntilIdle()
            vm.unread(false); advanceUntilIdle()
            assertEquals(listOf("c/s/pin/true", "c/s/move/null", "c/s/unread/false"), calls)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun askRestoresToolIdentityAndClearsMarkerOnlyAfterHumanConfirmation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var query: kotlinx.serialization.json.JsonElement? = null
            val repository = object : RemoteDesktopRepository by FakeRemoteRepository() {
                override suspend fun callSessionRpc(connectionId: String, sessionId: String, method: String,
                    args: List<kotlinx.serialization.json.JsonElement>, mutation: Boolean): kotlinx.serialization.json.JsonElement {
                    assertFalse(mutation)
                    query = args.single()
                    return kotlinx.serialization.json.Json.parseToJsonElement("""{"request":null,"resolved":null}""")
                }
            }
            val state = saved().apply {
                set("draft:1:c:s:askPending", true)
                set("draft:1:c:s:askTool", "pending-tool")
            }
            val vm = RemoteThreadViewModel(state, repository)
            vm.ask.start(); runCurrent()
            assertTrue(query.toString().contains("pending-tool"))
            assertEquals(true, state.get<Boolean>("draft:1:c:s:askPending"))
            vm.ask.acknowledgeOutcome()
            assertEquals(false, state.get<Boolean>("draft:1:c:s:askPending"))
            assertNull(state.get<String>("draft:1:c:s:askTool"))
            vm.ask.close()
        } finally { Dispatchers.resetMain() }
    }
    private fun saved() = SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s"))
}
