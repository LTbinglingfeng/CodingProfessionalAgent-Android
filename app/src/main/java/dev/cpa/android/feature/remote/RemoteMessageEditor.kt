package dev.cpa.android.feature.remote

import androidx.annotation.StringRes
import dev.cpa.android.R

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class MessageEditState(val open: Boolean = false, val entryId: String? = null, val draft: String = "",
    val pending: Boolean = false, val unknown: Boolean = false, val inspected: Boolean = false, @param:StringRes val error: Int? = null)

/** Independent from the send composer: editing must never overwrite an unsent prompt or its attachments. */
class RemoteMessageEditor(private val saved: SavedStateHandle, private val key: String,
    private val connectionId: String, private val sessionId: String, private val remote: RemoteDesktopRepository,
    private val scope: CoroutineScope, private val blocked: () -> Boolean = { false }, private val knownSkills: () -> List<RemoteSkill> = { emptyList() }) {
    private val mutable = MutableStateFlow(MessageEditState(open = saved["$key:open"] ?: false,
        entryId = saved["$key:entry"], draft = saved["$key:text"] ?: "", unknown = saved["$key:uncertain"] ?: false))
    val state = mutable.asStateFlow()
    private val completed = MutableStateFlow(0)
    val completedEdits = completed.asStateFlow()
    val skills = RemoteSkillsController(saved, "$key:skills", remote, scope, mutable.value.draft)
    private val thread = remote.observeThread(connectionId, sessionId)
    init {
        scope.launch { combine(state, remote.connections, remote.sessions, remote.projects) { s, cs, sessions, ps ->
            val projectId = sessions[connectionId].orEmpty().find { it.id == sessionId }?.projectId
            Triple(connectionId, ps[connectionId].orEmpty().find { it.id == projectId }?.allPaths?.firstOrNull(),
                s.open && cs.any { it.id == connectionId && it.phase == ConnectionPhase.Connected })
        }.distinctUntilChanged().collect { skills.context(it.first, it.second, it.third) } }
    }
    private fun persist() {
        val s = mutable.value
        saved["$key:open"] = s.open; saved["$key:entry"] = s.entryId
        if (s.draft.length <= 20_000) saved["$key:text"] = s.draft else saved.remove<String>("$key:text")
        saved["$key:uncertain"] = s.pending || s.unknown
    }
    private fun connected() = remote.connections.value.any { it.id == connectionId && it.phase == ConnectionPhase.Connected }
    private fun available() = connected() && !blocked() && !thread.value.running && !thread.value.loading && !thread.value.outcomeUnknown && !thread.value.editAwaitingStart && !mutable.value.pending && !mutable.value.unknown
    fun open(entry: RemoteEntry) {
        if (mutable.value.unknown && mutable.value.entryId != null) { resume(); return }
        if (!available() || entry.kind != "user" || entry.pendingStatus != null) return
        val current = thread.value.entries.find { it.id == entry.id && it.kind == "user" && it.pendingStatus == null } ?: return
        if (mutable.value.entryId != current.id) {
            val draft = current.promptParts(knownSkills()).promptDraft()
            skills.replace(draft.text, draft.references)
            mutable.value = MessageEditState(open = true, entryId = current.id, draft = draft.text)
        } else mutable.update { it.copy(open = true, error = null) }
        persist()
    }
    fun resume() { if (mutable.value.entryId != null) { mutable.update { it.copy(open = true) }; persist() } }
    fun close() { if (!mutable.value.pending) { mutable.update { it.copy(open = false) }; persist() } }
    fun discard() {
        if (mutable.value.pending || mutable.value.unknown) return
        skills.edit(""); mutable.value = MessageEditState(); persist()
    }
    fun edit(text: String) {
        if (mutable.value.pending) return
        if (skills.state.value.draft.text != text) skills.edit(text)
        mutable.update { it.copy(draft = text) }; persist()
    }
    fun submit() {
        if (!available()) return
        val id = mutable.value.entryId ?: return
        val entry = thread.value.entries.find { it.id == id && it.kind == "user" && it.pendingStatus == null }
        if (entry == null) { mutable.update { it.copy(error = R.string.edit_original_missing) }; return }
        val payload = skills.payload(remote.capabilities.value[connectionId]?.defaults ?: RemoteDefaults())
        if (payload.text.isBlank()) return
        mutable.update { it.copy(pending = true, inspected = false, error = null) }; persist()
        scope.launch {
            try {
                remote.editMessage(connectionId, sessionId, id, payload.text, payload.references)
                check(!thread.value.outcomeUnknown)
                skills.sent(payload.references); skills.edit("")
                mutable.value = MessageEditState()
                completed.update { it + 1 }
            } catch (e: TimeoutCancellationException) {
                mutable.update { it.copy(unknown = true, error = R.string.edit_start_unconfirmed) }
            } catch (e: CancellationException) {
                mutable.update { it.copy(unknown = true) }; throw e
            } catch (e: Exception) {
                val rejected = e is IllegalArgumentException || e.message == "Remote request rejected"
                mutable.update { it.copy(unknown = !rejected || thread.value.outcomeUnknown,
                    error = if (rejected) R.string.edit_rejected else R.string.edit_outcome_unknown) }
            } finally { mutable.update { it.copy(pending = false) }; persist() }
        }
    }
    fun inspect() {
        if (mutable.value.pending || !connected()) return
        mutable.update { it.copy(pending = true, inspected = false) }; persist()
        scope.launch {
            try {
                remote.openThread(connectionId, sessionId)
                check(!thread.value.loading && thread.value.error == null)
                mutable.update { it.copy(inspected = true, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.edit_refresh_failed) } }
            finally { mutable.update { it.copy(pending = false) }; persist() }
        }
    }
    fun acknowledge() {
        if (!mutable.value.inspected || mutable.value.pending || !connected()) return
        mutable.update { it.copy(pending = true) }; persist()
        scope.launch {
            try {
                remote.acknowledgeOutcome(connectionId, sessionId)
                mutable.update { it.copy(unknown = false, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(error = R.string.edit_verify_required, inspected = false) } }
            finally { mutable.update { it.copy(pending = false) }; persist() }
        }
    }
}
