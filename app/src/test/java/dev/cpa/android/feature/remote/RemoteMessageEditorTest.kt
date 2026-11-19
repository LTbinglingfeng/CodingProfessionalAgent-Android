package dev.cpa.android.feature.remote

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteMessageEditorTest {
    private val entry = RemoteEntry("u", "user", "😀 \$review old", images = listOf(RemoteSentImage("YWJj", "image/png")), skillReferences = listOf(SkillReference(3, "review")))
    private class Repo(val base: FakeRemoteRepository = FakeRemoteRepository()) : RemoteDesktopRepository by base {
        var calls = 0; var inspected = 0; var acknowledged = 0
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var prompt = ""; var refs = emptyList<SkillReference>()
        override suspend fun editMessage(connectionId: String, sessionId: String, entryId: String, text: String, references: List<SkillReference>) {
            calls++; prompt = text; refs = references; gate?.await(); failure?.let { throw it }
        }
        override suspend fun openThread(connectionId: String, sessionId: String) { inspected++ }
        override suspend fun acknowledgeOutcome(connectionId: String, sessionId: String) { acknowledged++; base.state.value = base.state.value.copy(outcomeUnknown = false) }
    }
    @Test fun successfulEditKeepsOrdinaryDraftAndPreservesUtf16References() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repo(); repo.base.state.value = repo.base.state.value.copy(entries = listOf(entry))
            val vm = RemoteThreadViewModel(SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s")), repo)
            vm.edit("ordinary unsent draft")
            vm.editor.open(entry); vm.editor.edit("😀 \$review revised")
            repo.gate = CompletableDeferred(); vm.editor.submit(); vm.editor.submit(); runCurrent()
            assertEquals(1, repo.calls); assertTrue(vm.editor.state.value.pending)
            assertEquals(listOf(SkillReference(3, "review")), repo.refs)
            vm.send(); runCurrent(); assertEquals(0, repo.base.sends)
            repo.gate!!.complete(Unit); runCurrent()
            assertFalse(vm.editor.state.value.open); assertNull(vm.editor.state.value.entryId)
            assertEquals(1, vm.editor.completedEdits.value)
            assertEquals("ordinary unsent draft", vm.composer.value.draft)
            assertEquals(entry.images, repo.base.state.value.entries.single().images)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun legacyEditConvertsPresentationToExplicitUtf16References() = runTest {
        val old = RemoteEntry("old", "user", "😀 /skill:review args", hasSkillReferences = false)
        val repo = Repo(); repo.base.state.value = repo.base.state.value.copy(entries = listOf(old))
        val editor = RemoteMessageEditor(SavedStateHandle(), "edit", "c", "s", repo, backgroundScope,
            knownSkills = { listOf(RemoteSkill("Review", "", "")) })
        editor.open(old)
        assertEquals("😀 ${'$'}Review args", editor.state.value.draft)
        assertEquals(listOf(SkillReference(3, "Review")), editor.skills.state.value.draft.references)
        assertEquals(old, repo.base.state.value.entries.single())
        editor.submit(); runCurrent()
        assertEquals("😀 ${'$'}Review args", repo.prompt)
        assertEquals(listOf(SkillReference(3, "Review")), repo.refs)
    }
    @Test fun rejectionKeepsDraftAndAllowsManualRetry() = runTest {
        val repo = Repo(); repo.base.state.value = repo.base.state.value.copy(entries = listOf(entry))
        val editor = RemoteMessageEditor(SavedStateHandle(), "edit", "c", "s", repo, backgroundScope)
        editor.open(entry); editor.edit("changed"); repo.failure = IllegalArgumentException("rejected")
        editor.submit(); runCurrent()
        assertEquals("changed", editor.state.value.draft); assertFalse(editor.state.value.unknown)
        assertEquals(0, editor.completedEdits.value)
        repo.failure = null; editor.submit(); runCurrent(); assertEquals(2, repo.calls)
        assertFalse(editor.state.value.open)
    }
    @Test fun processRestoreDoesNotResubmitAndRequiresReadThenConfirmation() = runTest {
        val repo = Repo(); repo.base.state.value = repo.base.state.value.copy(entries = listOf(entry)); repo.gate = CompletableDeferred()
        val saved = SavedStateHandle()
        val editor = RemoteMessageEditor(saved, "edit", "c", "s", repo, backgroundScope)
        editor.open(entry); editor.edit("changed"); editor.submit(); runCurrent()
        val restored = RemoteMessageEditor(saved, "edit", "c", "s", repo, backgroundScope)
        assertTrue(restored.state.value.unknown); assertEquals("changed", restored.state.value.draft)
        restored.submit(); restored.acknowledge(); runCurrent(); assertEquals(1, repo.calls); assertEquals(0, repo.acknowledged)
        restored.inspect(); runCurrent(); restored.acknowledge(); runCurrent()
        assertEquals(1, repo.acknowledged); assertFalse(restored.state.value.unknown)
        restored.close(); restored.resume(); assertEquals("changed", restored.state.value.draft)
        assertEquals(0, restored.completedEdits.value)
    }
    @Test fun cancelledEditKeepsUnknownMarkerAndCannotBeDiscarded() = runTest {
        val repo = Repo(); repo.base.state.value = repo.base.state.value.copy(entries = listOf(entry)); repo.gate = CompletableDeferred()
        val saved = SavedStateHandle(); val job = SupervisorJob(); val scope = CoroutineScope(backgroundScope.coroutineContext + job)
        val editor = RemoteMessageEditor(saved, "edit", "c", "s", repo, scope)
        editor.open(entry); editor.submit(); runCurrent(); job.cancel(); runCurrent()
        assertTrue(editor.state.value.unknown); assertEquals(true, saved.get<Boolean>("edit:uncertain"))
        editor.discard(); assertEquals("u", editor.state.value.entryId)
    }
    @Test fun pendingRunningAndMissingMessagesCannotBeEdited() = runTest {
        val repo = Repo(); repo.base.state.value = repo.base.state.value.copy(entries = listOf(entry), running = true)
        val editor = RemoteMessageEditor(SavedStateHandle(), "edit", "c", "s", repo, backgroundScope)
        editor.open(entry); assertFalse(editor.state.value.open)
        repo.base.state.value = repo.base.state.value.copy(running = false)
        editor.open(entry); editor.edit("changed")
        repo.base.state.value = repo.base.state.value.copy(entries = emptyList())
        editor.submit(); runCurrent(); assertEquals(0, repo.calls)
        assertNotNull(editor.state.value.error)
    }
}
