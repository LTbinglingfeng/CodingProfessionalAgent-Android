package dev.cpa.android.feature.remote

import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class RemoteSearchState(val query: String = "", val loading: Boolean = false,
    val results: List<RemoteSearchResult> = emptyList(), val fallbackConnections: Set<String> = emptySet())

/** Each query owns its results; a cancelled/late desktop response cannot replace newer input. */
class RemoteSearchController(private val remote: RemoteDesktopRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(RemoteSearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var active = false
    init {
        scope.launch {
            var previousIds = remote.connections.value.filter { it.enabled }.map { it.id }.toSet()
            combine(remote.connections.map { cs -> cs.filter { it.enabled }.map { it.id }.toSet() }.distinctUntilChanged(), remote.sessions) { ids, _ -> ids }
                .collect { ids ->
                    val membershipChanged = ids != previousIds
                    previousIds = ids
                    if (active && (membershipChanged || state.value.query.isBlank())) search(state.value.query)
                }
        }
    }
    fun search(input: String) {
        active = true
        val token = ++generation
        job?.cancel()
        val query = input.trim()
        mutable.value = RemoteSearchState(input, loading = query.isNotEmpty())
        job = scope.launch {
            if (query.isNotEmpty()) delay(150)
            val connections = remote.connections.value.filter { it.enabled }
            val results = coroutineScope {
                connections.map { connection -> async {
                    val local = remote.sessions.value[connection.id].orEmpty()
                        .filter { (it.archivedAt ?: 0) <= 0 && (query.isEmpty() || it.title.contains(query, ignoreCase = true)) }
                        .map { RemoteSearchResult(connection.id, it.id, it.title, it.updatedAt) }
                    if (query.isEmpty()) return@async local to false
                    try { remote.searchSessions(connection.id, query) to false }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { local to true }
                } }.awaitAll()
            }
            if (token != generation) return@launch
            val currentIds = remote.connections.value.filter { it.enabled }.map { it.id }.toSet()
            val ordered = results.flatMap { it.first }.filter { it.connectionId in currentIds }
                .distinctBy { it.connectionId to it.sessionId }
                .sortedWith(compareByDescending<RemoteSearchResult> { it.titleMatch }.thenByDescending { it.updatedAt }).take(30)
            mutable.value = RemoteSearchState(input, results = ordered,
                fallbackConnections = connections.zip(results).filter { it.second.second && it.first.id in currentIds }.map { it.first.id }.toSet())
        }
    }
    fun close() { active = false; generation++; job?.cancel() }
}
