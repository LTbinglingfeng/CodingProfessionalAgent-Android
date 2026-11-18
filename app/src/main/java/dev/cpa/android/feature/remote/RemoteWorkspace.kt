package dev.cpa.android.feature.remote

import androidx.annotation.StringRes
import dev.cpa.android.R

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Paths belong to the desktop, never to the Android filesystem. */
object RemotePath {
    fun isWindows(path: String) = Regex("^[A-Za-z]:").containsMatchIn(path) || path.startsWith("\\\\")
    fun root(path: String): String {
        if (path.startsWith("\\\\")) {
            val parts = path.drop(2).split('\\').filter(String::isNotEmpty)
            return if (parts.size >= 2) "\\\\${parts[0]}\\${parts[1]}\\" else path
        }
        return if (isWindows(path)) path.take(2) + "\\" else "/"
    }
    private fun isSeparator(char: Char?, windows: Boolean) = char == '/' || (windows && char == '\\')
    fun join(base: String, name: String): String {
        val windows = isWindows(base)
        val separator = if (isSeparator(base.lastOrNull(), windows)) "" else if (windows) "\\" else "/"
        return base + separator + name
    }
    fun parent(path: String): String? {
        if (path.isEmpty()) return null
        val root = root(path)
        val windows = isWindows(path)
        var trimmed = path
        while (trimmed.length > root.length && isSeparator(trimmed.last(), windows)) trimmed = trimmed.dropLast(1)
        if (trimmed.length <= root.length) return null
        val index = trimmed.indexOfLast { isSeparator(it, windows) }
        if (index < 0) return null
        return trimmed.take(index).takeIf { it.length >= root.length } ?: root
    }
}

data class DirectoryState(val path: String = "", val home: String = "", val entries: List<RemoteDirectoryEntry> = emptyList(),
    val loading: Boolean = false, @param:StringRes val error: Int? = null)
class RemoteDirectoryController(private val connectionId: String, private val remote: RemoteDesktopRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(DirectoryState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    fun home() = load(null)
    fun load(path: String?) {
        if (path != null && path.isBlank()) return
        val token = ++generation
        job?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            try {
                val target = path ?: mutable.value.home.ifEmpty {
                    try { remote.homeDirectory(connectionId) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { "/" }
                }
                if (token != generation) return@launch
                if (path == null) mutable.update { it.copy(home = target) }
                val entries = remote.directoryEntries(connectionId, target)
                if (token == generation) mutable.update { it.copy(path = target, entries = entries, loading = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == generation) mutable.update { it.copy(loading = false, error = R.string.directory_refresh_failed) } }
        }
    }
    fun close() { generation++; job?.cancel() }
}

data class ProjectEditorState(val connectionId: String? = null, val projectId: String? = null, val name: String = "",
    val paths: List<String> = emptyList(), val pending: Boolean = false, val unknown: Boolean = false,
    val refreshed: Boolean = false, @param:StringRes val error: Int? = null)
class RemoteProjectController(private val saved: SavedStateHandle, private val remote: RemoteDesktopRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(ProjectEditorState(
        saved["project.connection"], saved["project.id"], saved["project.name"] ?: "",
        saved.get<ArrayList<String>>("project.paths").orEmpty(),
        unknown = saved.get<String>("project.connection") in saved.get<ArrayList<String>>("project.unknown").orEmpty()))
    val state = mutable.asStateFlow()
    private fun unknownIds() = saved.get<ArrayList<String>>("project.unknown").orEmpty().toMutableSet()
    private fun markUnknown(connectionId: String, unknown: Boolean) {
        val ids = unknownIds()
        if (unknown) ids.add(connectionId) else ids.remove(connectionId)
        saved["project.unknown"] = ArrayList(ids)
    }
    fun open(connectionId: String, project: RemoteProject) {
        if (mutable.value.pending) return
        saved["project.connection"] = connectionId; saved["project.id"] = project.id
        saved["project.name"] = project.name; saved["project.paths"] = ArrayList(project.allPaths)
        mutable.value = ProjectEditorState(connectionId, project.id, project.name, project.allPaths, unknown = connectionId in unknownIds())
    }
    fun close() {
        if (mutable.value.pending) return
        saved.remove<String>("project.connection"); saved.remove<String>("project.id")
        mutable.value = ProjectEditorState()
    }
    fun name(value: String) {
        if (mutable.value.pending) return
        saved["project.name"] = value; mutable.update { it.copy(name = value) }
    }
    fun paths(value: List<String>) {
        if (mutable.value.pending) return
        val normalized = value.map(String::trim).filter(String::isNotEmpty).distinct()
        saved["project.paths"] = ArrayList(normalized); mutable.update { it.copy(paths = normalized) }
    }
    private fun write(action: suspend (String, String) -> Unit) {
        val state = mutable.value
        val connection = state.connectionId ?: return
        val project = state.projectId ?: return
        if (state.pending || state.unknown) return
        markUnknown(connection, true)
        mutable.update { it.copy(pending = true, error = null, refreshed = false) }
        scope.launch {
            try {
                action(connection, project)
                markUnknown(connection, false)
                mutable.update { it.copy(pending = false) }
                close()
            } catch (e: CancellationException) {
                mutable.update { it.copy(pending = false, unknown = true) }; throw e
            } catch (_: Exception) {
                mutable.update { it.copy(pending = false, unknown = true,
                    error = R.string.project_mutation_unknown) }
            }
        }
    }
    fun save() {
        val state = mutable.value
        if (state.name.isBlank()) return
        write { connection, id -> remote.updateProject(connection, id, state.name, state.paths) }
    }
    fun pin(value: Boolean) = write { connection, id -> remote.setProjectPinned(connection, id, value) }
    fun remove() = write { connection, id -> remote.removeProject(connection, id) }
    fun refresh() {
        val connection = mutable.value.connectionId ?: return
        if (mutable.value.pending) return
        mutable.update { it.copy(pending = true, refreshed = false) }
        scope.launch {
            try { remote.refreshProjects(connection); mutable.update { it.copy(refreshed = true, error = null) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.project_refresh_failed) } }
            finally { mutable.update { it.copy(pending = false) } }
        }
    }
    fun acknowledge() {
        val state = mutable.value
        val connection = state.connectionId ?: return
        if (state.pending || !state.refreshed) return
        mutable.update { it.copy(pending = true) }
        scope.launch {
            try {
                remote.acknowledgeProjectOutcome(connection); markUnknown(connection, false)
                mutable.update { it.copy(unknown = false, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.project_verify_required) } }
            finally { mutable.update { it.copy(pending = false) } }
        }
    }
    fun browser(connectionId: String, scope: CoroutineScope) = RemoteDirectoryController(connectionId, remote, scope)
}
