package dev.cpa.android.feature.remote

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNewThreadTest {
    private class Repo(val base: FakeRemoteRepository = FakeRemoteRepository()) : RemoteDesktopRepository by base {
        override val capabilities = MutableStateFlow(mapOf("c" to RemoteCapabilities(
            listOf(RemoteModel("a", supportsFast = true, reasoningLevels = listOf(RemoteReasoningOption("medium"), RemoteReasoningOption("high"))), RemoteModel("b")),
            RemoteDefaults(modelId = "a", speed = "fast"), defaultsLoaded = true)))
        val creates = mutableListOf<Pair<String, RemoteNewSessionOptions>>()
        val sent = mutableListOf<Pair<String, String>>()
        var sendFailure: Exception? = null
        var createWait: CompletableDeferred<Unit>? = null
        var branchWait: CompletableDeferred<Unit>? = null
        var inspected = 0
        var acknowledged = 0
        override suspend fun createConfiguredSession(connectionId: String, sessionId: String, title: String, options: RemoteNewSessionOptions) {
            creates += sessionId to options; createWait?.await()
        }
        override suspend fun send(connectionId: String, sessionId: String, text: String, modelId: String?) {
            sent += sessionId to text; sendFailure?.let { throw it }
        }
        override suspend fun gitBranches(connectionId: String, path: String): RemoteBranches {
            if (path == "/slow") withContext(NonCancellable) { branchWait?.await() }
            return RemoteBranches(if (path == "/slow") "old" else "main", listOf(if (path == "/slow") "old" else "main", "dev"))
        }
        override suspend fun openThread(connectionId: String, sessionId: String) { inspected++ }
        override suspend fun acknowledgeOutcome(connectionId: String, sessionId: String) { acknowledged++ }
    }
    @Test fun projectDefaultsAndExplicitNoProjectStayDistinctAndSpeedNormalizes() = runTest {
        val repo = Repo()
        repo.base.projects.value = mapOf("c" to listOf(RemoteProject("p", "Pinned", "/src", pinned = true)))
        repo.base.sessions.value = mapOf("c" to listOf(RemoteSession("last", "Last", "p", modelId = "a", reasoningEffort = "high", updatedAt = 99)))
        val controller = RemoteNewThreadController(SavedStateHandle(), repo, backgroundScope)
        controller.open("c", autoProject = true); runCurrent()
        assertEquals("p", controller.state.value.projectId)
        assertEquals("high", controller.state.value.reasoning)
        assertEquals("fast", controller.state.value.speed)
        controller.model("b")
        assertEquals("standard", controller.state.value.speed)
        assertEquals("off", controller.state.value.reasoning)
        controller.project(null); runCurrent()
        assertNull(controller.state.value.projectId)
        assertNull(controller.state.value.branch)
        assertEquals("a", controller.state.value.modelId)
        assertEquals("medium", controller.state.value.reasoning)
    }
    @Test fun lateBranchResponseCannotLeakAcrossProjects() = runTest {
        val repo = Repo(); repo.branchWait = CompletableDeferred()
        repo.base.projects.value = mapOf("c" to listOf(RemoteProject("p", "Slow", "/slow"), RemoteProject("q", "Fast", "/fast")))
        val controller = RemoteNewThreadController(SavedStateHandle(), repo, backgroundScope)
        controller.open("c", "p"); runCurrent()
        controller.project("q"); runCurrent()
        assertEquals("main", controller.state.value.branch)
        repo.branchWait!!.complete(Unit); runCurrent()
        assertEquals("main", controller.state.value.branch)
        assertFalse(controller.state.value.branches.contains("old"))
    }
    @Test fun firstSendFailureKeepsCreatedTargetAndRequiresInspectionThenHumanConfirmation() = runTest {
        val repo = Repo(); repo.sendFailure = IllegalStateException("private error")
        val controller = RemoteNewThreadController(SavedStateHandle(), repo, backgroundScope)
        controller.open("c"); controller.draft("hello"); controller.submit(); controller.submit(); runCurrent()
        assertEquals(1, repo.creates.size); assertEquals(1, repo.sent.size)
        assertTrue(controller.state.value.created); assertTrue(controller.state.value.unknown)
        assertEquals("hello", controller.state.value.draft)
        controller.acknowledge(); controller.submit(); runCurrent()
        assertEquals(0, repo.acknowledged); assertEquals(1, repo.sent.size)
        controller.inspectTarget(); runCurrent()
        assertTrue(controller.state.value.inspected); assertTrue(controller.state.value.unknown)
        controller.acknowledge(); runCurrent()
        repo.sendFailure = null
        controller.submit(); runCurrent()
        assertEquals(1, repo.creates.size)
        assertEquals(2, repo.sent.size)
        assertEquals(repo.sent[0].first, repo.sent[1].first)
        assertEquals(repo.creates.single().first, controller.state.value.openedSession)
        controller.consumedNavigation()
        assertFalse(controller.state.value.open); assertEquals("", controller.state.value.draft)
    }
    @Test fun restoredInFlightCreationKeepsTargetAndNeverAutomaticallyReplays() = runTest {
        val repo = Repo(); repo.createWait = CompletableDeferred()
        val saved = SavedStateHandle()
        val controller = RemoteNewThreadController(saved, repo, backgroundScope)
        controller.open("c"); controller.draft("original"); controller.submit(); runCurrent()
        val target = controller.state.value.target
        val restored = RemoteNewThreadController(saved, repo, backgroundScope)
        restored.open("other"); restored.submit(); runCurrent()
        assertEquals("c", restored.state.value.connectionId)
        assertEquals(target, restored.state.value.target)
        assertEquals("original", restored.state.value.draft)
        assertTrue(restored.state.value.unknown)
        assertEquals(1, repo.creates.size); assertTrue(repo.sent.isEmpty())
        repo.createWait!!.cancel(); runCurrent()
    }
    @Test fun defaultsWaitForDesktopSettingsAndRestoreKeepsUserSelections() = runTest {
        val repo = Repo()
        val full = repo.capabilities.value.getValue("c")
        repo.capabilities.value = mapOf("c" to full.copy(defaults = RemoteDefaults(), defaultsLoaded = false))
        val saved = SavedStateHandle()
        val controller = RemoteNewThreadController(saved, repo, backgroundScope)
        controller.open("c"); runCurrent()
        assertEquals("standard", controller.state.value.speed)
        repo.capabilities.value = mapOf("c" to full); runCurrent()
        assertEquals("fast", controller.state.value.speed)
        controller.reasoning("high"); controller.speed("standard")
        val restored = RemoteNewThreadController(saved, repo, backgroundScope); runCurrent()
        assertEquals("high", restored.state.value.reasoning)
        assertEquals("standard", restored.state.value.speed)
    }
    @Test fun restorationDoesNotEraseSelectionsWhileVaultAndSnapshotsLoad() = runTest {
        val repo = Repo()
        val connections = repo.base.connections.value
        val caps = repo.capabilities.value
        repo.base.connections.value = emptyList(); repo.capabilities.value = emptyMap()
        val saved = SavedStateHandle(mapOf("new.open" to true, "new.connection" to "c", "new.project" to "p",
            "new.model" to "a", "new.reasoning" to "high", "new.speed" to "fast", "new.speedInitialized" to true,
            "new.selection.connection" to "c", "new.selection.project" to "p", "new.branch" to "dev",
            "new.branch.connection" to "c", "new.branch.path" to "/src"))
        val controller = RemoteNewThreadController(saved, repo, backgroundScope)
        runCurrent()
        assertEquals("c", controller.state.value.connectionId)
        assertEquals("high", controller.state.value.reasoning)
        repo.base.connections.value = connections; runCurrent()
        assertEquals("fast", controller.state.value.speed)
        assertEquals("dev", controller.state.value.branch)
        repo.base.projects.value = mapOf("c" to listOf(RemoteProject("p", "Project", "/src")))
        repo.capabilities.value = caps; runCurrent()
        assertEquals("high", controller.state.value.reasoning)
        assertEquals("dev", controller.state.value.branch)
    }
    @Test fun fileOnlyNewThreadUploadsBeforeCreatingAndFailureKeepsAttachment() = runTest {
        val order = mutableListOf<String>()
        val base = FakeRemoteRepository()
        var failUpload = true
        val repo = object : RemoteDesktopRepository by base {
            override suspend fun uploadAttachments(connectionId: String, sessionId: String, files: List<dev.cpa.android.core.attachments.LocalAttachment>): List<String> {
                order += "upload"; if (failUpload) error("write result unknown"); return listOf("/remote/report.txt")
            }
            override suspend fun createConfiguredSession(connectionId: String, sessionId: String, title: String, options: RemoteNewSessionOptions) {
                order += "create"; assertEquals("report.txt", title)
            }
            override suspend fun send(connectionId: String, sessionId: String, text: String, modelId: String?) {
                order += "send"; assertEquals("@/remote/report.txt", text)
            }
        }
        val controller = RemoteNewThreadController(SavedStateHandle(), repo, backgroundScope)
        controller.open("c")
        controller.attachments.enqueue(listOf(suspend { dev.cpa.android.core.attachments.LocalAttachment("file", "report.txt", "text/plain", false, "/not-read-by-fixture", 1) }))
        runCurrent(); controller.submit(); runCurrent()
        assertEquals(listOf("upload"), order)
        assertEquals(1, controller.attachments.state.value.items.size)
        assertTrue(controller.state.value.unknown)
        controller.abandonAttempt(); failUpload = false
        controller.submit(); runCurrent()
        assertEquals(listOf("upload", "upload", "create", "send"), order)
        assertTrue(controller.attachments.state.value.items.isEmpty())
        assertNotNull(controller.state.value.openedSession)
    }
    @Test fun titleUsesFirstNonEmptyLineAndDoesNotSplitEmojiOrCombiningMarks() {
        assertEquals("你好", newThreadTitle("\n你好\nsecond"))
        assertEquals("😀".repeat(50) + "…", newThreadTitle("😀".repeat(51)))
        assertEquals("á".repeat(50) + "…", newThreadTitle("á".repeat(51)))
    }
}
