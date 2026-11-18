package dev.cpa.android.feature.remote

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteHomeComposerTest {
    private class Repo(val base: FakeRemoteRepository = FakeRemoteRepository()) : RemoteDesktopRepository by base {
        override val capabilities = MutableStateFlow(mapOf("c" to RemoteCapabilities(listOf(RemoteModel("fast", supportsFast = true)), defaultsLoaded = true)))
        override suspend fun loadSkills(connectionId: String, projectPath: String?) = RemoteSkillCatalog(emptyList(), emptyMap())
        var creates = 0
        override suspend fun createConfiguredSession(connectionId: String, sessionId: String, title: String, options: RemoteNewSessionOptions) { creates++ }
    }
    @Test fun homeHandoffPreservesUtf16SkillsAndSpeedWithoutCreatingOrSending() = runTest {
        val repo = Repo()
        repo.base.projects.value = mapOf("c" to listOf(RemoteProject("p", "Pinned", "/src", pinned = true)))
        val saved = SavedStateHandle()
        val target = RemoteNewThreadController(saved, repo, backgroundScope)
        val home = RemoteHomeComposer(saved, repo, backgroundScope, target)
        runCurrent()
        home.edit("😀 \$review explain")
        home.skills.replace(home.state.value.draft, listOf(SkillReference(3, "review")))
        home.toggleSpeed(); home.submit(); runCurrent()
        assertEquals("c", target.state.value.connectionId)
        assertEquals("p", target.state.value.projectId)
        assertEquals("fast", target.state.value.speed)
        assertEquals("😀 \$review explain", target.state.value.draft)
        assertEquals(listOf(SkillReference(3, "review")), target.skills.state.value.draft.validReferences)
        assertEquals("", home.state.value.draft)
        assertEquals(0, repo.creates); assertEquals(0, repo.base.sends)
        val restored = RemoteNewThreadController(saved, repo, backgroundScope)
        assertEquals(target.state.value.draft, restored.state.value.draft)
        assertEquals(target.skills.state.value.draft.validReferences, restored.skills.state.value.draft.validReferences)
    }
    @Test fun deviceSelectionPrefersConnectedAndUnknownTargetKeepsBothDrafts() = runTest {
        val repo = Repo()
        repo.base.connections.value = listOf(ConnectionSummary("off", "Offline", "https://off", false), repo.base.connections.value.single(),
            ConnectionSummary("disabled", "Hidden", "https://disabled", false, ConnectionPhase.Connected, enabled = false))
        val saved = SavedStateHandle(mapOf("new.target" to "unresolved", "new.uncertain" to true, "new.connection" to "c", "new.draft" to "original"))
        val target = RemoteNewThreadController(saved, repo, backgroundScope)
        val home = RemoteHomeComposer(saved, repo, backgroundScope, target)
        runCurrent(); assertEquals("c", home.state.value.connectionId)
        home.selectConnection("off"); runCurrent(); assertEquals("off", home.state.value.connectionId)
        home.edit("keep home draft"); home.submit(); runCurrent()
        assertTrue(target.state.value.open); assertTrue(target.state.value.unknown)
        assertEquals("c", target.state.value.connectionId)
        assertEquals("original", target.state.value.draft)
        assertEquals("keep home draft", home.state.value.draft)
        assertEquals(0, repo.creates); assertEquals(0, repo.base.sends)
        home.selectConnection("disabled"); runCurrent(); assertNull(home.state.value.connectionId)
    }
    @Test fun handoffReopensExistingFormWithoutReplacingEitherDraft() = runTest {
        val repo = Repo(); val saved = SavedStateHandle()
        val target = RemoteNewThreadController(saved, repo, backgroundScope)
        val home = RemoteHomeComposer(saved, repo, backgroundScope, target)
        runCurrent(); target.open("c"); target.draft("existing prompt"); target.close()
        home.edit("new prompt"); home.submit(); runCurrent()
        assertTrue(target.state.value.open)
        assertEquals("existing prompt", target.state.value.draft)
        assertEquals("new prompt", home.state.value.draft)
        assertEquals(0, repo.creates); assertEquals(0, repo.base.sends)
    }
    @Test fun modelCommandOpensSettingsWithoutMovingOrClearingHomeDraft() = runTest {
        val repo = Repo(); val saved = SavedStateHandle()
        val target = RemoteNewThreadController(saved, repo, backgroundScope)
        val home = RemoteHomeComposer(saved, repo, backgroundScope, target)
        runCurrent(); home.edit("unsent"); home.modelSettings(); runCurrent()
        assertTrue(target.state.value.modelSettingsRequested)
        assertEquals("unsent", home.state.value.draft)
        assertEquals("", target.state.value.draft)
        assertEquals(0, repo.creates); assertEquals(0, repo.base.sends)
    }
}
