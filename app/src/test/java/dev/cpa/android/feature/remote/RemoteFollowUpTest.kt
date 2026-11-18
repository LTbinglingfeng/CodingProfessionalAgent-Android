package dev.cpa.android.feature.remote

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.attachments.LocalAttachment
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFollowUpTest {
    @Test fun sendAcceptanceCallbackRunsOnlyAfterValidationAndBeforeAsyncSend() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val base = FakeRemoteRepository()
            val gate = CompletableDeferred<Unit>()
            var sends = 0; var accepted = 0
            val repo = object : RemoteDesktopRepository by base {
                override suspend fun send(connectionId: String, sessionId: String, text: String, modelId: String?) { sends++; gate.await() }
            }
            val vm = RemoteThreadViewModel(SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s")), repo)
            runCurrent()
            vm.sendWithAcceptance { accepted++ }; assertEquals(0, accepted)
            vm.edit("hello"); vm.sendWithAcceptance { accepted++ }
            assertEquals(1, accepted); assertEquals(0, sends)
            vm.sendWithAcceptance { accepted++ }; assertEquals(1, accepted)
            runCurrent(); assertEquals(1, sends)
            gate.complete(Unit); runCurrent()
            base.state.value = base.state.value.copy(outcomeUnknown = true)
            vm.edit("held"); vm.sendWithAcceptance { accepted++ }; assertEquals(1, accepted)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun runningSendSerializesAcknowledgementsWithoutReplay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val base = FakeRemoteRepository(); base.state.value = base.state.value.copy(running = true)
            val gate = CompletableDeferred<Unit>(); val prompts = mutableListOf<String>()
            val repo = object : RemoteDesktopRepository by base {
                override suspend fun sendFollowUp(connectionId: String, sessionId: String, text: String, references: List<SkillReference>, images: List<LocalAttachment>) {
                    prompts += text; gate.await()
                }
            }
            val vm = RemoteThreadViewModel(SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s")), repo)
            vm.edit("next"); vm.send(); vm.send(); runCurrent()
            assertEquals(listOf("next"), prompts)
            assertTrue(vm.composer.value.pending); assertEquals("next", vm.composer.value.draft)
            gate.complete(Unit); runCurrent()
            assertEquals("", vm.composer.value.draft)
            assertTrue(base.state.value.running)
            vm.edit("more"); vm.send(); runCurrent()
            assertEquals(listOf("next", "more"), prompts)
            vm.edit("uncertain"); base.state.value = base.state.value.copy(outcomeUnknown = true)
            vm.send(); runCurrent(); assertEquals(listOf("next", "more"), prompts)
        } finally { Dispatchers.resetMain() }
    }
}
