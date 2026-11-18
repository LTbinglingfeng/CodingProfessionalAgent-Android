package dev.cpa.android.feature.remote

import android.content.res.Configuration
import android.os.LocaleList
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.network.tunnel.TunnelDiscoveryPhase
import dev.cpa.android.core.network.tunnel.TunnelDiscoveryState
import dev.cpa.android.core.storage.MemoryHomePreferences
import dev.cpa.android.core.storage.RecentTunnelSummary
import dev.cpa.android.data.repository.*
import dev.cpa.android.data.repository.git.GitReview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** Recording repository for UI tests: every mutation is logged, reads come from mutable flows. */
internal open class UiFakeRepository : RemoteDesktopRepository {
    override val skillUsage = RemoteSkillUsage()
    override val connections = MutableStateFlow(listOf(ConnectionSummary("c", "Desktop", "http://desktop:18080", true, ConnectionPhase.Connected)))
    override val projects = MutableStateFlow(mapOf("c" to listOf(RemoteProject("p", "Project", "/src"))))
    override val sessions = MutableStateFlow(mapOf("c" to listOf(RemoteSession("s", "Task", "p", "main"))))
    override val capabilities = MutableStateFlow<Map<String, RemoteCapabilities>>(emptyMap())
    override val runningSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    override val collapseKeyboardAfterSend = MutableStateFlow(true)
    override suspend fun setCollapseKeyboardAfterSend(value: Boolean) { collapseKeyboardAfterSend.value = value }
    override val showContextUsage = MutableStateFlow(false)
    override suspend fun setShowContextUsage(value: Boolean) { showContextUsage.value = value }
    override val wrapCode = MutableStateFlow(true)
    override suspend fun setWrapCode(wrap: Boolean) { wrapCode.value = wrap }
    val threads = mutableMapOf<String, MutableStateFlow<RemoteThread>>()
    val calls = mutableListOf<String>()
    var saved: List<Any?>? = null
    var tunnelDraft: List<Any?>? = null
    var sent: String? = null
    var discovery: Flow<TunnelDiscoveryState> = flowOf(TunnelDiscoveryState(TunnelDiscoveryPhase.Connected))
    var recent: RecentTunnelSummary? = null
    var review: GitReview? = null
    var files: Map<String, List<RemoteDirectoryEntry>> = emptyMap()
    var fileText: String = "file contents"
    var searchResults: List<RemoteSearchResult> = emptyList()
    fun thread(session: String) = threads.getOrPut(session) { MutableStateFlow(RemoteThread("c", session)) }
    override fun observeThread(connectionId: String, sessionId: String) = thread(sessionId)
    override suspend fun saveConnection(id: String?, name: String, address: String, password: String?, allowCleartext: Boolean): String {
        calls += "save"; saved = listOf(id, name, address, password, allowCleartext); return id ?: "new"
    }
    override suspend fun saveTunnelConnection(id: String?, name: String, baseUrl: String, apiKey: String?, serverId: String, serverName: String,
        password: String?, allowCleartext: Boolean): String {
        calls += "saveTunnel"; tunnelDraft = listOf(id, name, baseUrl, apiKey, serverId, serverName, password, allowCleartext); return id ?: "tunnel"
    }
    override fun watchTunnel(id: String?, baseUrl: String, apiKey: String?, allowCleartext: Boolean): Flow<TunnelDiscoveryState> {
        calls += "watch:$baseUrl:${apiKey.orEmpty()}"; return discovery
    }
    override suspend fun recentTunnelSummary() = recent
    override suspend fun removeConnection(id: String) { calls += "remove:$id" }
    override suspend fun reorderConnections(ids: List<String>) { calls += "reorder:${ids.joinToString(",")}" }
    override suspend fun disconnectAll() { calls += "disconnectAll" }
    override suspend fun connect(id: String) { calls += "connect:$id" }
    override suspend fun disconnect(id: String) { calls += "disconnect:$id" }
    override suspend fun refresh(id: String) { calls += "refresh:$id" }
    override suspend fun openThread(connectionId: String, sessionId: String) { calls += "open:$sessionId" }
    override suspend fun createSession(connectionId: String, projectId: String?, title: String) = error("Not used")
    override suspend fun createConfiguredSession(connectionId: String, sessionId: String, title: String, options: RemoteNewSessionOptions) {
        calls += "create:${options.projectId}:${options.workLocation}:${options.branch}"
    }
    override suspend fun send(connectionId: String, sessionId: String, text: String, modelId: String?) { calls += "send"; sent = text }
    override suspend fun sendFollowUp(connectionId: String, sessionId: String, text: String,
        references: List<SkillReference>, images: List<dev.cpa.android.core.attachments.LocalAttachment>) { calls += "followUp"; sent = text }
    override suspend fun stop(connectionId: String, sessionId: String) { calls += "stop" }
    override suspend fun runAction(connectionId: String, sessionId: String, action: RemoteRunAction) { calls += "action:${action.wireValue}" }
    override suspend fun setSessionRuntime(connectionId: String, sessionId: String, modelId: String?, reasoningEffort: String?, speed: String?) {
        calls += "runtime:$modelId:$reasoningEffort:$speed"
    }
    override suspend fun renameSession(connectionId: String, sessionId: String, title: String) { calls += "rename:$sessionId:$title" }
    override suspend fun setSessionPinned(connectionId: String, sessionId: String, pinned: Boolean) { calls += "pin:$sessionId:$pinned" }
    override suspend fun archiveSession(connectionId: String, sessionId: String) { calls += "archive:$sessionId" }
    override suspend fun moveSession(connectionId: String, sessionId: String, projectId: String?) { calls += "move:$sessionId:$projectId" }
    override suspend fun setSessionUnread(connectionId: String, sessionId: String, unread: Boolean) { calls += "unread:$sessionId:$unread" }
    override suspend fun setProjectPinned(connectionId: String, projectId: String, pinned: Boolean) { calls += "pinProject:$projectId:$pinned" }
    override suspend fun removeProject(connectionId: String, projectId: String) { calls += "removeProject:$projectId" }
    override suspend fun updateProject(connectionId: String, projectId: String, name: String, paths: List<String>) { calls += "updateProject:$name:${paths.joinToString()}" }
    override suspend fun forkSession(connectionId: String, sessionId: String, newSessionId: String, atEntryId: String?, worktree: RemoteWorktreeFork?) {
        calls += "fork:$sessionId:$atEntryId:${worktree?.branch}:${worktree?.environmentId}"
    }
    override suspend fun editMessage(connectionId: String, sessionId: String, entryId: String, text: String, references: List<SkillReference>) {
        calls += "edit:$entryId:$text"
    }
    override suspend fun gitBranches(connectionId: String, path: String) = RemoteBranches("main", listOf("main", "dev"))
    override suspend fun environmentProjects(connectionId: String) = listOf(RemoteProject("env", "Environment", "/env"))
    override suspend fun gitReview(connectionId: String, path: String, base: String?, target: String?): GitReview {
        calls += "git:$path:$base:$target"; return review ?: error("no repo")
    }
    override suspend fun homeDirectory(connectionId: String) = "/home"
    override suspend fun directoryEntries(connectionId: String, path: String) = files[path] ?: listOf(RemoteDirectoryEntry("src", true), RemoteDirectoryEntry("README.md", false))
    override suspend fun readRemoteFile(connectionId: String, path: String): String { calls += "read:$path"; return fileText }
    override suspend fun loadSkills(connectionId: String, projectPath: String?) = RemoteSkillCatalog(emptyList(), emptyMap())
    override suspend fun searchSessions(connectionId: String, query: String) = searchResults.filter { it.connectionId == connectionId }
    var askRequest: JsonElement = JsonNull
    val rpcCalls = mutableListOf<String>()
    override suspend fun callSessionRpc(connectionId: String, sessionId: String, method: String, args: List<JsonElement>, mutation: Boolean): JsonElement {
        rpcCalls += method
        return if (method == "ask:getRequests") kotlinx.serialization.json.buildJsonObject { put("request", askRequest) } else kotlinx.serialization.json.JsonPrimitive(true)
    }
    override suspend fun acknowledgeOutcome(connectionId: String, sessionId: String) { calls += "acknowledge" }
    override fun onForeground() = Unit
    override fun onBackground() = Unit
}

internal fun homeViewModel(repo: RemoteDesktopRepository) = RemoteHomeViewModel(SavedStateHandle(), repo, MemoryHomePreferences())
internal fun threadViewModel(repo: RemoteDesktopRepository, session: String = "s", connection: String = "c") =
    RemoteThreadViewModel(SavedStateHandle(mapOf("connectionId" to connection, "sessionId" to session)), repo)

/** Renders content with an explicit app language, independent of the device locale. */
@Composable
internal fun Localized(tag: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val config = LocalConfiguration.current
    val localized = remember(tag) { ContextThemeWrapper(base, 0).apply {
        applyOverrideConfiguration(Configuration(config).apply { setLocales(LocaleList.forLanguageTags(tag)) })
    } }
    CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) { CpaTheme(dark = false) { content() } }
}
