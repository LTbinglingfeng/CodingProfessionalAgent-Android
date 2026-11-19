package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFilesTest {
    @Test fun treeFiltersLoadedDescendantsWithoutFetchingOrDroppingCollapsedParent() {
        val dir = RemoteFileNode("/root/src", "src", true)
        val file = RemoteFileNode("/root/src/Main.kt", "Main.kt", false)
        val state = RemoteFilesState("/root", mapOf("/root" to listOf(dir), dir.path to listOf(file)), query = "main")
        assertEquals(listOf(dir), fileRows(state).map { it.node })
        assertEquals(listOf(dir, file), fileRows(state.copy(expanded = setOf(dir.path))).map { it.node })
        assertTrue(fileRows(state.copy(query = "unknown")).isEmpty())
    }
    @Test fun previewCapsUnicodeSafelyAndNormalizesCrLf() {
        assertEquals(listOf("one", "two", ""), filePreview("a", "one\r\ntwo\n").lines)
        val preview = filePreview("a", "😀".repeat(200001))
        assertTrue(preview.truncated); assertEquals(400000, preview.lines.single().length)
    }
    @Test fun rootChangeRejectsLateDirectoryAndSkipsBuildFolders() = runTest {
        val old = CompletableDeferred<List<RemoteDirectoryEntry>>()
        val repo = object : RemoteDesktopRepository by FakeRemoteRepository() {
            override suspend fun directoryEntries(connectionId: String, path: String) = if (path == "/old") withContext(NonCancellable) { old.await() }
                else listOf(RemoteDirectoryEntry(".git", true), RemoteDirectoryEntry("node_modules", true), RemoteDirectoryEntry(".env", false))
        }
        val controller = RemoteFilesController("c", repo, backgroundScope, StandardTestDispatcher(testScheduler))
        controller.root("/old"); runCurrent(); controller.root("/new"); runCurrent()
        old.complete(listOf(RemoteDirectoryEntry("stale", false))); runCurrent()
        assertEquals("/new", controller.state.value.root)
        assertEquals(listOf(".env"), fileRows(controller.state.value).map { it.node.name })
    }
    @Test fun latestPreviewWinsAndReadFailureCanRetry() = runTest {
        val old = CompletableDeferred<String>(); var fail = true
        val repo = object : RemoteDesktopRepository by FakeRemoteRepository() {
            override suspend fun readRemoteFile(connectionId: String, path: String): String {
                if (path == "old") return withContext(NonCancellable) { old.await() }
                if (fail) error("missing")
                return "new"
            }
        }
        val controller = RemoteFilesController("c", repo, backgroundScope, StandardTestDispatcher(testScheduler))
        controller.open("old"); runCurrent(); controller.open("new"); runCurrent()
        assertNotNull(controller.state.value.preview?.error)
        old.complete("old contents"); runCurrent()
        assertEquals("new", controller.state.value.preview?.path)
        fail = false; controller.open("new")
        runCurrent()
        assertEquals(listOf("new"), controller.state.value.preview?.lines)
        controller.close(); assertNull(controller.state.value.preview)
    }
}
