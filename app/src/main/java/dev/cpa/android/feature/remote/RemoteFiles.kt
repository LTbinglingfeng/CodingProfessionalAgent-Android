package dev.cpa.android.feature.remote

import androidx.annotation.StringRes
import dev.cpa.android.R

import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class RemoteFileNode(val path: String, val name: String, val directory: Boolean)
data class RemoteFileRow(val node: RemoteFileNode, val depth: Int)
data class RemoteFilePreview(val path: String, val lines: List<String> = emptyList(), val loading: Boolean = true, val truncated: Boolean = false, @param:StringRes val error: Int? = null)
data class RemoteFilesState(val root: String? = null, val children: Map<String, List<RemoteFileNode>> = emptyMap(), val expanded: Set<String> = emptySet(),
    val loading: Set<String> = emptySet(), val errors: Map<String, Int> = emptyMap(), val query: String = "", val preview: RemoteFilePreview? = null)
private val skippedFileNames = setOf(".git", "node_modules", "dist", ".next", "coverage")
internal fun filePreview(path: String, text: String): RemoteFilePreview {
    val count = text.codePointCount(0, text.length)
    val capped = if (count > 200_000) text.substring(0, text.offsetByCodePoints(0, 200_000)) else text
    return RemoteFilePreview(path, capped.split('\n').map { it.removeSuffix("\r") }, false, count > 200_000)
}
internal fun fileRows(state: RemoteFilesState): List<RemoteFileRow> {
    val root = state.root ?: return emptyList()
    val query = state.query.trim()
    fun matches(node: RemoteFileNode): Boolean = query.isEmpty() || node.name.contains(query, ignoreCase = true) ||
        node.directory && state.children[node.path].orEmpty().any(::matches)
    val rows = mutableListOf<RemoteFileRow>()
    fun visit(path: String, depth: Int) {
        for (node in state.children[path].orEmpty()) if (matches(node)) {
            rows += RemoteFileRow(node, depth)
            if (node.directory && node.path in state.expanded) visit(node.path, depth + 1)
        }
    }
    visit(root, 0)
    return rows
}
class RemoteFilesController(private val connection: String, private val remote: RemoteDesktopRepository, private val scope: CoroutineScope, private val previewDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val mutable = MutableStateFlow(RemoteFilesState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var previewGeneration = 0L
    private val jobs = mutableMapOf<String, Job>()
    private val directoryVersions = mutableMapOf<String, Long>()
    private var previewJob: Job? = null
    fun root(path: String?) {
        generation++; jobs.values.forEach(Job::cancel); jobs.clear(); closePreview()
        mutable.value = RemoteFilesState(root = path?.takeIf(String::isNotBlank))
        mutable.value.root?.let(::load)
    }
    fun query(value: String) { mutable.update { it.copy(query = value) } }
    fun toggle(node: RemoteFileNode) {
        if (!node.directory) { open(node.path); return }
        val expanding = node.path !in mutable.value.expanded
        mutable.update { it.copy(expanded = if (expanding) it.expanded + node.path else it.expanded - node.path) }
        if (expanding && node.path !in mutable.value.children) load(node.path)
    }
    fun load(path: String) {
        val token = generation
        val version = (directoryVersions[path] ?: 0L) + 1
        directoryVersions[path] = version
        jobs[path]?.cancel()
        mutable.update { it.copy(loading = it.loading + path, errors = it.errors - path) }
        jobs[path] = scope.launch {
            try {
                val entries = remote.directoryEntries(connection, path).filter { it.name !in skippedFileNames && it.name !in setOf(".", "..") }
                    .map { RemoteFileNode(RemotePath.join(path, it.name), it.name, it.isDirectory) }
                if (token == generation && directoryVersions[path] == version) mutable.update { it.copy(children = it.children + (path to entries), loading = it.loading - path) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == generation && directoryVersions[path] == version) mutable.update { it.copy(loading = it.loading - path, errors = it.errors + (path to R.string.directory_read_failed)) } }
        }
    }
    fun open(path: String) {
        val token = ++previewGeneration; previewJob?.cancel()
        mutable.update { it.copy(preview = RemoteFilePreview(path)) }
        previewJob = scope.launch {
            try {
                val content = remote.readRemoteFile(connection, path)
                val preview = withContext(previewDispatcher) { filePreview(path, content) }
                if (token == previewGeneration) mutable.update { it.copy(preview = preview) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == previewGeneration) mutable.update { it.copy(preview = RemoteFilePreview(path, loading = false, error = R.string.file_read_failed)) } }
        }
    }
    fun closePreview() { previewGeneration++; previewJob?.cancel(); mutable.update { it.copy(preview = null) } }
    fun close() { generation++; jobs.values.forEach(Job::cancel); jobs.clear(); closePreview(); mutable.update { it.copy(loading = emptySet()) } }
}
