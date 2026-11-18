package dev.cpa.android.feature.remote

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.attachments.LocalAttachment
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSkillsControllerTest {
    private val skill = RemoteSkill("review", "Review code", "/skills/review/SKILL.md")
    @Test fun lateScansCannotLeakAcrossProjectsAndReconnectReloads() = runTest {
        val gate = CompletableDeferred<Unit>(); val calls = mutableListOf<String?>()
        val repo = object : RemoteDesktopRepository by FakeRemoteRepository() {
            override suspend fun loadSkills(connectionId: String, projectPath: String?): RemoteSkillCatalog {
                calls += projectPath
                if (projectPath == "/slow") withContext(NonCancellable) { gate.await() }
                return RemoteSkillCatalog(listOf(skill.copy(name = projectPath.orEmpty())), emptyMap())
            }
        }
        val c = RemoteSkillsController(SavedStateHandle(), "skills", repo, backgroundScope, "")
        c.context("c", "/slow", true); runCurrent()
        c.context("c", "/fast", true); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("/fast", c.state.value.catalog.skills.single().name)
        c.context("c", "/fast", false); runCurrent(); assertTrue(c.state.value.catalog.skills.isEmpty())
        c.context("c", "/fast", true); runCurrent(); assertEquals(3, calls.size)
    }
    @Test fun selectedReferencesRestoreShiftAndUsageNeverRegresses() = runTest {
        var count = 5L
        val repo = object : RemoteDesktopRepository by FakeRemoteRepository() {
            override suspend fun loadSkills(connectionId: String, projectPath: String?) = RemoteSkillCatalog(listOf(skill), mapOf("review" to count))
        }
        val saved = SavedStateHandle()
        val c = RemoteSkillsController(saved, "skills", repo, backgroundScope, "😀 #re")
        c.context("c", null, true); runCurrent()
        val text = c.select(skill, '#')
        assertEquals("😀 \$review ", text)
        val restored = RemoteSkillsController(saved, "skills", repo, backgroundScope, text)
        assertEquals(listOf(SkillReference(3, "review")), restored.state.value.draft.validReferences)
        c.sent(c.payload(RemoteDefaults(skillTrigger = '#')).references)
        count = 2; c.refresh(); runCurrent()
        assertEquals(6L, c.state.value.catalog.usage["review"])
        restored.context("c", null, true); runCurrent()
        assertEquals(6L, restored.state.value.catalog.usage["review"])
        repo.skillUsage.merge("c", mapOf("other" to 9L)); runCurrent()
        assertEquals(9L, c.state.value.catalog.usage["other"])
        assertEquals(9L, restored.state.value.catalog.usage["other"])
        c.edit("prefix $text")
        assertEquals(10, c.state.value.draft.validReferences.single().start)
        c.edit(""); assertTrue(c.state.value.draft.references.isEmpty())
    }
    @Test fun newThreadSendsCanonicalSkillsAndKeepsReferenceOnRejection() = runTest {
        val base = FakeRemoteRepository(); val calls = mutableListOf<Pair<String, List<SkillReference>>>(); var reject = true
        val repo = object : RemoteDesktopRepository by base {
            override val capabilities = MutableStateFlow(mapOf("c" to RemoteCapabilities(defaults = RemoteDefaults(skillTrigger = '#'), defaultsLoaded = true)))
            override suspend fun loadSkills(connectionId: String, projectPath: String?) = RemoteSkillCatalog(listOf(skill), emptyMap())
            override suspend fun createConfiguredSession(connectionId: String, sessionId: String, title: String, options: RemoteNewSessionOptions) = Unit
            override suspend fun sendWithSkills(connectionId: String, sessionId: String, text: String, references: List<SkillReference>, images: List<LocalAttachment>) {
                calls += text to references
                if (reject) throw IllegalArgumentException("reject")
            }
        }
        val saved = SavedStateHandle(); val c = RemoteNewThreadController(saved, repo, backgroundScope)
        c.open("c"); runCurrent(); c.draft("😀 #re")
        c.draft(c.skills.select(skill, '#')); c.submit(); runCurrent()
        assertEquals("😀 \$review", calls.single().first)
        assertEquals(listOf(SkillReference(3, "review")), calls.single().second)
        assertEquals(listOf(SkillReference(3, "review")), c.skills.state.value.draft.validReferences)
        assertNotNull(c.state.value.target); assertFalse(c.state.value.unknown)
        reject = false; c.submit(); runCurrent(); assertNotNull(c.state.value.openedSession)
        c.consumedNavigation(); assertTrue(c.skills.state.value.draft.references.isEmpty())
    }
    @Test fun existingThreadNormalizesTypedSkillsBeforeSendAndClearsOnlyOnSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val calls = mutableListOf<Pair<String, List<SkillReference>>>()
            val repo = object : RemoteDesktopRepository by FakeRemoteRepository() {
                override val capabilities = MutableStateFlow(mapOf("c" to RemoteCapabilities(defaults = RemoteDefaults(skillTrigger = '#'))))
                override suspend fun loadSkills(connectionId: String, projectPath: String?) = RemoteSkillCatalog(listOf(skill), emptyMap())
                override suspend fun sendWithSkills(connectionId: String, sessionId: String, text: String, references: List<SkillReference>, images: List<LocalAttachment>) { calls += text to references }
            }
            val vm = RemoteThreadViewModel(SavedStateHandle(mapOf("connectionId" to "c", "sessionId" to "s")), repo)
            runCurrent(); vm.edit("  😀 #REVIEW  "); vm.send(); runCurrent()
            assertEquals("😀 \$review", calls.single().first)
            assertEquals(listOf(SkillReference(3, "review")), calls.single().second)
            assertEquals("", vm.composer.value.draft)
        } finally { Dispatchers.resetMain() }
    }
}
