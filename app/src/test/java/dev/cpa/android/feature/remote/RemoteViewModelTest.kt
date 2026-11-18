package dev.cpa.android.feature.remote

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {
    @Test fun pendingSendCannotDuplicateAndFailureKeepsDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val remote = FakeRemoteRepository()
            val vm = RemoteThreadViewModel(SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s")), remote)
            vm.edit("你好 😀")
            vm.send(); vm.send()
            runCurrent()
            assertEquals(1, remote.sends)
            assertTrue(vm.composer.value.pending)
            remote.sendResult.completeExceptionally(IllegalStateException("timeout"))
            advanceUntilIdle()
            assertEquals("你好 😀", vm.composer.value.draft)
            assertTrue(vm.composer.value.unknown)
            vm.send(); advanceUntilIdle()
            assertEquals(1, remote.sends)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun restoredPendingNeverReplaysRpc() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val remote = FakeRemoteRepository()
            val saved = SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s", "draft:1:c:s" to "draft", "draft:1:c:s:pending" to true))
            val vm = RemoteThreadViewModel(saved, remote)
            vm.send(); advanceUntilIdle()
            assertEquals(0, remote.sends)
            assertTrue(vm.composer.value.unknown)
            assertEquals("draft", vm.composer.value.draft)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun oversizedDraftIsMemoryOnlyWithoutTruncation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val saved = SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s"))
            val vm = RemoteThreadViewModel(saved, FakeRemoteRepository())
            vm.edit("a".repeat(20_000))
            assertEquals(20_000, saved.get<String>("draft:1:c:s")!!.length)
            val ime = "a".repeat(20_000) + "你好😀"
            vm.edit(ime)
            assertEquals(ime, vm.composer.value.draft)
            assertNull(saved.get<String>("draft:1:c:s"))
            vm.edit("short")
            assertEquals("short", saved.get<String>("draft:1:c:s"))
            advanceUntilIdle()
        } finally { Dispatchers.resetMain() }
    }
    @Test fun cancelledSendRetainsUnknownMarkerAndDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val remote = FakeRemoteRepository()
            val saved = SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s"))
            val vm = RemoteThreadViewModel(saved, remote)
            vm.edit("original")
            vm.send(); runCurrent()
            remote.sendResult.cancel()
            advanceUntilIdle()
            assertEquals(true, saved.get<Boolean>("draft:1:c:s:pending"))
            assertTrue(vm.composer.value.unknown)
            assertFalse(vm.composer.value.pending)
            assertEquals("original", vm.composer.value.draft)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun saveClosesOnlyOnSuccessAndNeverLeaksValidationDetails() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val remote = FakeRemoteRepository()
            remote.saveResult = CompletableDeferred()
            val vm = RemoteHomeViewModel(SavedStateHandle(), remote)
            var closed = 0
            vm.save(null, "name", "bad", "secret", false) { closed++ }
            vm.save(null, "name", "bad", "secret", false) { closed++ }
            runCurrent()
            assertEquals(1, remote.saves)
            assertEquals(0, closed)
            remote.saveResult!!.completeExceptionally(IllegalArgumentException("secret"))
            advanceUntilIdle()
            assertEquals(0, closed)
            assertEquals(dev.cpa.android.R.string.home_invalid_input, vm.operation.value.error)
            remote.saveResult = null
            vm.save(null, "name", "https://host", null, false) { closed++ }
            advanceUntilIdle()
            assertEquals(1, closed)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun sessionCreationUsesReturnedIdOnly() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val remote = FakeRemoteRepository()
            val vm = RemoteHomeViewModel(SavedStateHandle(), remote)
            vm.create("c", "project", "新会话")
            advanceUntilIdle()
            assertEquals("c" to "server/id", vm.operation.value.opened)
            vm.consumedNavigation()
            assertNull(vm.operation.value.opened)
        } finally { Dispatchers.resetMain() }
    }
}

class FakeRemoteRepository : RemoteDesktopRepository {
    override val skillUsage = RemoteSkillUsage()
    override val connections = MutableStateFlow(listOf(ConnectionSummary("c", "Desktop", "https://localhost", false, ConnectionPhase.Connected)))
    override val projects = MutableStateFlow<Map<String, List<RemoteProject>>>(emptyMap())
    override val sessions = MutableStateFlow<Map<String, List<RemoteSession>>>(emptyMap())
    val state = MutableStateFlow(RemoteThread("c", "s"))
    var sends = 0
    val sendResult = CompletableDeferred<Unit>()
    override fun observeThread(connectionId: String, sessionId: String) = state
    var saves = 0
    var saveResult: CompletableDeferred<Unit>? = null
    override suspend fun saveConnection(id: String?, name: String, address: String, password: String?, allowCleartext: Boolean): String {
        saves++
        saveResult?.await()
        return id ?: "c"
    }
    override suspend fun removeConnection(id: String) {}
    override suspend fun connect(id: String) {}
    override suspend fun disconnect(id: String) {}
    override suspend fun refresh(id: String) {}
    override suspend fun openThread(connectionId: String, sessionId: String) {}
    override suspend fun createSession(connectionId: String, projectId: String?, title: String) = "server/id"
    override suspend fun send(connectionId: String, sessionId: String, text: String, modelId: String?) { sends++; sendResult.await() }
    override suspend fun stop(connectionId: String, sessionId: String) {}
    override fun onForeground() {}
    override fun onBackground() {}
}
