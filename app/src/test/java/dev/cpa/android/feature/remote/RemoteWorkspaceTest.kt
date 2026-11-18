package dev.cpa.android.feature.remote

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteWorkspaceTest {
    @Test fun remotePathsHandlePosixDrivesAndUncRoots() {
        assertEquals("/", RemotePath.root("/Users/dev"))
        assertNull(RemotePath.parent("/"))
        assertEquals("/", RemotePath.parent("/src/"))
        assertEquals("C:\\", RemotePath.root("C:\\Users\\dev"))
        assertNull(RemotePath.parent("C:\\"))
        assertEquals("C:\\", RemotePath.parent("C:/src/"))
        assertEquals("C:\\src", RemotePath.join("C:\\", "src"))
        assertEquals("\\\\server\\share\\", RemotePath.root("\\\\server\\share\\dir"))
        assertNull(RemotePath.parent("\\\\server\\share\\"))
        assertEquals("\\\\server\\share\\", RemotePath.parent("\\\\server\\share\\dir"))
        assertEquals("/src/folder", RemotePath.join("/src", "folder"))
    }
    @Test fun posixBackslashesArePartOfTheDirectoryName() {
        assertEquals("/src/dir\\/child", RemotePath.join("/src/dir\\", "child"))
        assertEquals("/src", RemotePath.parent("/src/dir\\name/"))
        assertEquals("/src/dir\\", RemotePath.parent("/src/dir\\/child"))
    }
    @Test fun directoryNavigationRejectsLateResultsAndPreservesPreviousOnFailure() = runTest {
        val slow = CompletableDeferred<Unit>()
        val repo = object : RemoteDesktopRepository by FakeRemoteRepository() {
            override suspend fun homeDirectory(connectionId: String): String = error("No runtime info")
            override suspend fun directoryEntries(connectionId: String, path: String): List<RemoteDirectoryEntry> {
                if (path == "/slow") withContext(NonCancellable) { slow.await() }
                if (path == "/missing") error("private path error")
                return listOf(RemoteDirectoryEntry(path, true))
            }
        }
        val controller = RemoteDirectoryController("c", repo, backgroundScope)
        controller.home(); runCurrent()
        assertEquals("/", controller.state.value.path)
        controller.load("/slow"); runCurrent()
        controller.load("/new"); runCurrent()
        slow.complete(Unit); runCurrent()
        assertEquals("/new", controller.state.value.path)
        controller.load("/missing"); runCurrent()
        assertEquals("/new", controller.state.value.path)
        assertEquals("/new", controller.state.value.entries.single().name)
        assertFalse(controller.state.value.loading)
        assertEquals(dev.cpa.android.R.string.directory_refresh_failed, controller.state.value.error)
    }
    @Test fun projectDraftRestoresAndPendingWriteRequiresRefreshAndConfirmation() = runTest {
        val pending = CompletableDeferred<Unit>()
        var writes = 0
        var refreshes = 0
        var acknowledgements = 0
        val repo = object : RemoteDesktopRepository by FakeRemoteRepository() {
            override suspend fun updateProject(connectionId: String, projectId: String, name: String, paths: List<String>) {
                assertEquals("c", connectionId); assertEquals("p", projectId)
                writes++; pending.await()
            }
            override suspend fun refreshProjects(connectionId: String) { refreshes++ }
            override suspend fun acknowledgeProjectOutcome(connectionId: String) { acknowledgements++ }
        }
        val saved = SavedStateHandle()
        val first = RemoteProjectController(saved, repo, backgroundScope)
        first.open("c", RemoteProject("p", "Project", "/src"))
        first.name("Draft"); first.paths(listOf("/new", "/new"))
        first.save(); first.save(); first.close(); runCurrent()
        assertEquals(1, writes)
        assertEquals("p", first.state.value.projectId)
        val restored = RemoteProjectController(saved, repo, backgroundScope)
        assertEquals("Draft", restored.state.value.name)
        assertEquals(listOf("/new"), restored.state.value.paths)
        assertTrue(restored.state.value.unknown)
        restored.save(); restored.acknowledge(); runCurrent()
        assertEquals(0, acknowledgements)
        pending.cancel(); runCurrent()
        restored.refresh(); runCurrent(); restored.acknowledge(); runCurrent()
        assertEquals(1, refreshes); assertEquals(1, acknowledgements)
        assertFalse(restored.state.value.unknown)
        assertEquals("Draft", restored.state.value.name)
    }
}
