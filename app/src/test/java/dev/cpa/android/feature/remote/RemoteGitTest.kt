package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import dev.cpa.android.data.repository.git.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteGitTest {
    @Test fun changingComparisonRejectsLateResultAndFailureHidesOldDiff() = runTest {
        val old = CompletableDeferred<GitReview>()
        val expected = GitReview(GitRepo("/repo", "main", "main", emptyList(), null, true), emptyList(), "new")
        val repo = object : RemoteDesktopRepository by FakeRemoteRepository() {
            override suspend fun gitReview(connectionId: String, path: String, base: String?, target: String?): GitReview = when(base) {
                "old" -> withContext(NonCancellable) { old.await() }
                "fail" -> error("missing remote")
                else -> expected
            }
        }
        val controller = RemoteGitController("c", repo, backgroundScope)
        controller.load("/repo", "old", null); runCurrent()
        controller.load("/repo", null, null); runCurrent()
        old.complete(expected.copy(comparison = "stale")); runCurrent()
        assertEquals("new", controller.state.value.review?.comparison)
        controller.load("/repo", "fail", null); runCurrent()
        assertNull(controller.state.value.review); assertNotNull(controller.state.value.error)
    }
}
