package dev.cpa.android.feature.remote

import androidx.annotation.StringRes
import dev.cpa.android.R

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

internal fun newThreadTitle(text: String): String {
    val line = text.lineSequence().firstOrNull { it.isNotEmpty() }.orEmpty()
    val iterator = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
    iterator.setText(line)
    var end = iterator.first()
    repeat(50) {
        val next = iterator.next()
        if (next == java.text.BreakIterator.DONE) return line
        end = next
    }
    return if (iterator.next() == java.text.BreakIterator.DONE) line else line.substring(0, end) + "…"
}

data class NewThreadState(val open: Boolean = false, val connectionId: String? = null, val projectId: String? = null,
    val workLocation: String = "local", val branch: String? = null, val branches: List<String> = emptyList(),
    val loadingBranches: Boolean = false, val modelId: String? = null, val reasoning: String? = null, val speed: String = "standard",
    val draft: String = "", val pending: Boolean = false, val unknown: Boolean = false, val target: String? = null,
    val created: Boolean = false, @param:StringRes val error: Int? = null, val openedSession: String? = null,
    val inspected: Boolean = false, val preview: List<RemoteEntry> = emptyList(), val modelSettingsRequested: Boolean = false)

/** A single explicit create → send attempt. A failed send retries the same session, never creates another. */
class RemoteNewThreadController(private val saved: SavedStateHandle, private val remote: RemoteDesktopRepository, private val scope: CoroutineScope) {
    val attachments = RemoteAttachments(saved, "new.attachments", scope)
    val skills = RemoteSkillsController(saved, "new.skills", remote, scope, saved["new.draft"] ?: "")
    val connections = remote.connections
    val projects = remote.projects
    val capabilities = remote.capabilities
    private val mutable = MutableStateFlow(NewThreadState(
        open = saved["new.open"] ?: false, connectionId = saved["new.connection"], projectId = saved.get<String>("new.project")?.takeIf { it.isNotEmpty() },
        workLocation = saved["new.location"] ?: "local", branch = saved["new.branch"], modelId = saved["new.model"], reasoning = saved["new.reasoning"],
        speed = saved["new.speed"] ?: "standard", draft = saved["new.draft"] ?: "", unknown = saved["new.uncertain"] ?: false,
        target = saved["new.target"], created = saved["new.created"] ?: false, openedSession = saved["new.opened"]))
    val state = mutable.asStateFlow()
    private var selectionKey: Pair<String?, String?>? = saved.get<String>("new.selection.connection")?.let { it to saved.get<String>("new.selection.project") }
    private var branchKey: Pair<String?, String?>? = null
    private var branchGeneration = 0L
    private var branchJob: Job? = null
    private var speedInitialized = saved.get<Boolean>("new.speedInitialized") == true
    init {
        scope.launch { combine(remote.connections, remote.projects, remote.capabilities, remote.sessions) { _, _, _, _ -> Unit }.collect { reconcile() } }
        scope.launch { combine(state, connections, projects) { s, cs, ps ->
            Triple(s.connectionId, ps[s.connectionId].orEmpty().find { it.id == s.projectId }?.allPaths?.firstOrNull(),
                s.open && cs.any { it.id == s.connectionId && it.phase == ConnectionPhase.Connected })
        }.distinctUntilChanged().collect { skills.context(it.first, it.second, it.third) } }
    }
    private fun persist() {
        val s = mutable.value
        saved["new.open"] = s.open; saved["new.connection"] = s.connectionId
        saved["new.location"] = s.workLocation; saved["new.branch"] = s.branch
        saved["new.model"] = s.modelId; saved["new.reasoning"] = s.reasoning; saved["new.speed"] = s.speed
        if (s.draft.length <= 20_000) saved["new.draft"] = s.draft else saved.remove<String>("new.draft")
        saved["new.target"] = s.target; saved["new.created"] = s.created; saved["new.opened"] = s.openedSession
        saved["new.uncertain"] = s.pending || s.unknown; saved["new.speedInitialized"] = speedInitialized
    }
    fun open(connectionId: String? = null, projectId: String? = null, autoProject: Boolean = false) {
        if (mutable.value.pending) return
        // Keep an unresolved attempt tied to its original desktop and target.
        if (mutable.value.target != null) { mutable.update { it.copy(open = true) }; persist(); return }
        saved["new.project"] = if (autoProject) null else projectId.orEmpty()
        mutable.update { it.copy(open = true, connectionId = connectionId ?: it.connectionId, error = null) }
        selectionKey = null; reconcile(); persist()
    }
    fun prefill(connectionId: String, text: String, references: List<SkillReference>, speed: String?): Boolean {
        if (!editable() || mutable.value.unknown || mutable.value.draft.isNotEmpty() || attachments.state.value.processing > 0 || attachments.state.value.items.isNotEmpty()) {
            if (!mutable.value.pending) { mutable.update { it.copy(open = true) }; persist() }
            return false
        }
        // A home launch uses the selected desktop's default project/model, not an old form's choices.
        speedInitialized = speed != null
        mutable.update { it.copy(modelId = null, reasoning = null, speed = speed ?: "standard", workLocation = "local", branch = null) }
        open(connectionId, autoProject = true)
        skills.replace(text, references); draft(text)
        return true
    }
    fun openModelSettings(connectionId: String?, speed: String?) {
        if (!editable() || mutable.value.unknown) { open(); return }
        open(connectionId, autoProject = true)
        speed?.let(::speed)
        mutable.update { it.copy(modelSettingsRequested = true) }
    }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun consumedModelSettings() { mutable.update { it.copy(modelSettingsRequested = false) } }
    fun close() { if (!mutable.value.pending) { mutable.update { it.copy(open = false) }; stopBranches(); persist() } }
    private fun editable() = !mutable.value.pending && mutable.value.target == null
    fun connection(id: String) {
        if (!editable()) return
        mutable.update { it.copy(connectionId = id) }; reconcile(); persist()
    }
    fun project(id: String?) {
        if (!editable()) return
        saved["new.project"] = id.orEmpty(); reconcile(); persist()
    }
    fun location(value: String) { if (editable() && value in setOf("local", "worktree")) { mutable.update { it.copy(workLocation = value) }; persist() } }
    fun branch(value: String) { if (editable() && value in mutable.value.branches) { mutable.update { it.copy(branch = value) }; persist() } }
    fun model(value: String) {
        if (!editable()) return
        val caps = capabilities.value[mutable.value.connectionId] ?: return
        if (caps.availableModels.none { it.id == value }) return
        mutable.update { it.copy(modelId = value) }; reconcile(); persist()
    }
    fun reasoning(value: String) {
        if (!editable()) return
        val caps = capabilities.value[mutable.value.connectionId] ?: return
        if (caps.reasoningChoices(mutable.value.modelId).none { it.id == value }) return
        mutable.update { it.copy(reasoning = value) }; persist()
    }
    fun speed(value: String) {
        if (!editable() || value !in setOf("standard", "fast")) return
        mutable.update { it.copy(speed = value) }; speedInitialized = true; reconcile(); persist()
    }
    fun draft(value: String) { if (!mutable.value.pending) { if (skills.state.value.draft.text != value) skills.edit(value); mutable.update { it.copy(draft = value) }; persist() } }
    private fun reconcile() {
        val previous = mutable.value
        if (!previous.open || previous.pending || previous.target != null) return
        if (connections.value.isEmpty()) return // Vault loading is not evidence that a saved desktop disappeared.
        val connection = connections.value.find { it.id == previous.connectionId } ?: connections.value.firstOrNull()
        val id = connection?.id
        if (selectionKey?.first == id && (connection?.phase != ConnectionPhase.Connected ||
                capabilities.value[id]?.defaultsLoaded != true ||
                (!saved.get<String>("new.project").isNullOrEmpty() && !projects.value.containsKey(id)))) {
            // Preserve restored user choices while the connection's independent snapshots arrive.
            stopBranches()
            return
        }
        val projects = projects.value[id].orEmpty()
        val requested = saved.get<String>("new.project")
        val project = if (requested == "") null else projects.find { it.id == requested } ?: projects.firstOrNull { it.pinned } ?: projects.firstOrNull()
        val key = id to project?.id
        val changed = selectionKey != key
        val caps = capabilities.value[id] ?: RemoteCapabilities()
        val last = remote.sessions.value[id].orEmpty().filter { it.projectId == project?.id && !it.modelId.isNullOrBlank() }.maxByOrNull { it.updatedAt }
        val model = previous.modelId.takeIf { !changed && caps.availableModels.any { m -> m.id == it } }
            ?: caps.availableModels.find { it.id == last?.modelId }?.id ?: caps.preferredModel()?.id
        val choices = caps.reasoningChoices(model)
        val reasoning = previous.reasoning.takeIf { !changed && choices.any { option -> option.id == it } }
            ?: last?.reasoningEffort?.takeIf { last.modelId == model && choices.any { option -> option.id == it } }
            ?: caps.preferredReasoning(model) ?: model?.let { "off" }
        var speed = previous.speed
        if (!speedInitialized && caps.defaultsLoaded) { speed = caps.defaults.speed; speedInitialized = true }
        if (speed == "fast" && caps.availableModels.none { it.id == model && it.supportsFast }) speed = "standard"
        if (caps.defaultsLoaded) {
            selectionKey = key
            saved["new.selection.connection"] = id; saved["new.selection.project"] = project?.id
        }
        mutable.value = previous.copy(connectionId = id, projectId = project?.id, modelId = model, reasoning = reasoning, speed = speed)
        val path = project?.allPaths?.firstOrNull()?.takeIf { connection?.phase == ConnectionPhase.Connected }
        val query = id to path
        if (query != branchKey) {
            val previousBranch = previous.branch.takeIf { saved.get<String>("new.branch.connection") == id && saved.get<String>("new.branch.path") == path }
            saved["new.branch.connection"] = id; saved["new.branch.path"] = path
            branchKey = query; branchJob?.cancel(); val token = ++branchGeneration
            mutable.update { it.copy(branch = null, branches = emptyList(), loadingBranches = path != null) }
            if (id != null && path != null) branchJob = scope.launch {
                try {
                    val result = remote.gitBranches(id, path)
                    if (token == branchGeneration && mutable.value.target == null) {
                        mutable.update { it.copy(branches = result.names, branch = previousBranch?.takeIf { it in result.names } ?: result.current ?: result.names.firstOrNull(), loadingBranches = false) }; persist()
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (token == branchGeneration) mutable.update { it.copy(loadingBranches = false, error = R.string.branches_read_failed) } }
            }
        }
        persist()
    }
    fun start() { branchKey = null; reconcile() }
    fun stopBranches() { branchGeneration++; branchJob?.cancel(); branchKey = null; mutable.update { it.copy(loadingBranches = false) } }
    fun refresh() {
        val id = mutable.value.connectionId ?: return
        if (mutable.value.pending) return
        scope.launch {
            try { remote.refresh(id); branchKey = null; reconcile() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.project_refresh_failed) } }
        }
    }
    fun submit() {
        val s = mutable.value
        val connection = s.connectionId ?: return
        if (s.pending || s.unknown || s.loadingBranches || (s.draft.isBlank() && attachments.state.value.items.isEmpty())) return
        if (connections.value.none { it.id == connection && it.phase == ConnectionPhase.Connected }) return
        val caps = capabilities.value[connection] ?: RemoteCapabilities()
        if (caps.models.isNotEmpty() && caps.availableModels.none { it.id == s.modelId }) return
        try { attachments.requireImageSupport(caps, s.modelId) }
        catch (e: AttachmentValidationException) { mutable.update { it.copy(error = e.messageResource) }; return }
        if (!attachments.lock()) return
        val picked = attachments.state.value.items
        val payload = skills.payload(caps.defaults)
        val target = s.target ?: UUID.randomUUID().toString()
        mutable.update { it.copy(pending = true, target = target, error = null, inspected = false, preview = emptyList()) }; persist()
        scope.launch {
            try {
                val files = picked.filterNot { it.image }
                val paths = if (files.isEmpty()) emptyList() else remote.uploadAttachments(connection, target, files)
                if (!s.created) {
                    remote.createConfiguredSession(connection, target, newThreadTitle(payload.text.ifEmpty { picked.firstOrNull()?.name.orEmpty() }), RemoteNewSessionOptions(
                        s.projectId, s.branch?.takeIf { it in s.branches }, s.workLocation, s.modelId, s.reasoning, s.speed))
                    mutable.update { it.copy(created = true) }; persist()
                }
                val prompt = dev.cpa.android.core.attachments.attachmentPrompt(payload.text, paths)
                val images = picked.filter { it.image }
                if (payload.references.isNotEmpty()) remote.sendWithSkills(connection, target, prompt, payload.references, images)
                else if (images.isEmpty()) remote.send(connection, target, prompt)
                else remote.sendWithAttachments(connection, target, prompt, images)
                check(!remote.observeThread(connection, target).value.outcomeUnknown)
                skills.sent(payload.references)
                attachments.clearSent()
                mutable.update { it.copy(pending = false, unknown = false, openedSession = target) }; persist()
            } catch (e: CancellationException) {
                mutable.update { it.copy(pending = false, unknown = true) }; persist(); throw e
            } catch (e: Exception) {
                val rejected = e is IllegalArgumentException || e.message == "Remote request rejected"
                mutable.update { it.copy(pending = false, unknown = !rejected,
                    target = if (rejected && !it.created) null else it.target,
                    error = if (rejected) R.string.new_thread_rejected
                        else R.string.new_thread_unknown) }; persist()
            } finally { attachments.unlock() }
        }
    }
    fun inspectTarget() {
        val s = mutable.value; val id = s.connectionId ?: return; val target = s.target ?: return
        if (s.pending) return
        mutable.update { it.copy(pending = true, error = null, inspected = false, preview = emptyList()) }; persist()
        scope.launch {
            try {
                remote.openThread(id, target)
                val thread = remote.observeThread(id, target).value
                check(!thread.loading && thread.error == null) { "Snapshot unavailable" }
                mutable.update { it.copy(created = true, inspected = true, preview = thread.entries) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.new_thread_unconfirmed) } }
            finally { mutable.update { it.copy(pending = false) }; persist() }
        }
    }
    fun acknowledge() {
        val s = mutable.value; val id = s.connectionId ?: return; val target = s.target ?: return
        if (s.pending || !s.inspected) return
        mutable.update { it.copy(pending = true) }; persist()
        scope.launch {
            try {
                remote.acknowledgeOutcome(id, target)
                mutable.update { it.copy(unknown = false, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.new_thread_verify_required, inspected = false) } }
            finally { mutable.update { it.copy(pending = false) }; persist() }
        }
    }
    /** Explicit abandonment only; may leave a created remote session. Never delete it as rollback. */
    fun abandonAttempt() {
        if (mutable.value.pending) return
        mutable.update { it.copy(target = null, created = false, unknown = false, error = null, inspected = false, preview = emptyList()) }; persist(); reconcile()
    }
    fun consumedNavigation() {
        skills.edit("")
        mutable.value = NewThreadState(connectionId = mutable.value.connectionId)
        speedInitialized = false; selectionKey = null
        saved.remove<String>("new.selection.connection"); saved.remove<String>("new.selection.project")
        stopBranches(); persist()
    }
}
