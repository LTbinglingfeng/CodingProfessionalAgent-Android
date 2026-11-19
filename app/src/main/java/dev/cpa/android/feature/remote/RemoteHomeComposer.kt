package dev.cpa.android.feature.remote

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeComposerState(val connectionId: String? = null, val connected: Boolean = false,
    val defaults: RemoteDefaults = RemoteDefaults(), val model: RemoteModel? = null,
    val speed: String = "standard", val draft: String = "")

/** Home submission prepares a new-thread form; it never creates or sends a session. */
class RemoteHomeComposer(private val saved: SavedStateHandle, private val remote: RemoteDesktopRepository,
    scope: CoroutineScope, private val newThread: RemoteNewThreadController) {
    val skills = RemoteSkillsController(saved, "home.skills", remote, scope, saved["home.draft"] ?: "")
    private val selection = MutableStateFlow<String?>(saved["home.device"])
    private var speedOverride: String? = saved["home.speed"]
    private val mutable = MutableStateFlow(HomeComposerState(draft = saved["home.draft"] ?: ""))
    val state = mutable.asStateFlow()
    init {
        scope.launch {
            combine(selection, remote.connections, remote.projects, remote.capabilities, remote.sessions) { selected, connections, projects, caps, sessions ->
                val visible = connections.filter { it.enabled && (selected == null || it.id == selected) }
                val connection = visible.firstOrNull { it.phase == ConnectionPhase.Connected } ?: visible.firstOrNull()
                val project = projects[connection?.id].orEmpty().let { ps -> ps.firstOrNull { it.pinned } ?: ps.firstOrNull() }
                val capabilities = caps[connection?.id] ?: RemoteCapabilities()
                val last = sessions[connection?.id].orEmpty().filter { it.projectId == project?.id && !it.modelId.isNullOrBlank() }.maxByOrNull { it.updatedAt }
                val model = capabilities.availableModels.find { it.id == last?.modelId } ?: capabilities.preferredModel()
                Triple(connection, project, capabilities to model)
            }.collect { (connection, project, choice) ->
                val (caps, model) = choice
                mutable.update { it.copy(connectionId = connection?.id, connected = connection?.phase == ConnectionPhase.Connected,
                    defaults = caps.defaults, model = model, speed = speedOverride ?: caps.defaults.speed) }
                skills.context(connection?.id, project?.allPaths?.firstOrNull(), connection?.phase == ConnectionPhase.Connected)
            }
        }
    }
    fun selectConnection(id: String?) { selection.value = id; saved["home.device"] = id }
    fun edit(text: String) {
        if (skills.state.value.draft.text != text) skills.edit(text)
        mutable.update { it.copy(draft = text) }
        if (text.length <= 20_000) saved["home.draft"] = text else saved.remove<String>("home.draft")
    }
    fun toggleSpeed() {
        if (!state.value.connected || state.value.model?.supportsFast != true) return
        speedOverride = if (state.value.speed == "fast") "standard" else "fast"
        saved["home.speed"] = speedOverride
        mutable.update { it.copy(speed = speedOverride!!) }
    }
    fun submit() {
        val current = state.value
        if (current.connectionId == null) return
        val payload = skills.payload(current.defaults)
        if (payload.text.isEmpty()) return
        if (newThread.prefill(current.connectionId, payload.text, payload.validReferences, speedOverride)) {
            skills.replace("", emptyList()); edit("")
        }
    }
    fun modelSettings() {
        val current = state.value
        if (!current.connected) return
        newThread.openModelSettings(current.connectionId, speedOverride)
    }
}
