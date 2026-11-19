package dev.cpa.android.feature.remote

import androidx.annotation.StringRes
import dev.cpa.android.R

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


data class SkillsState(val draft: SkillDraft, val catalog: RemoteSkillCatalog = RemoteSkillCatalog(emptyList(), emptyMap()),
    val loading: Boolean = false, @param:StringRes val error: Int? = null)

/** Owns reference persistence and a cancellable scan tied to the selected desktop and project. */
class RemoteSkillsController(private val saved: SavedStateHandle, private val key: String,
    private val remote: RemoteDesktopRepository, private val scope: CoroutineScope, initialText: String) {
    private val restored = runCatching { Json.decodeFromString<List<SkillReference>>(saved.get<String>(key) ?: "[]") }.getOrDefault(emptyList())
    private val mutable = MutableStateFlow(SkillsState(SkillDraft(initialText, restored)))
    val state = mutable.asStateFlow()
    private var context: Triple<String?, String?, Boolean>? = null
    private var generation = 0L
    private var job: Job? = null
    init {
        scope.launch {
            remote.skillUsage.state.collect { counts ->
                val id = context?.takeIf { it.third }?.first ?: return@collect
                mutable.update { it.copy(catalog = it.catalog.copy(usage = counts[id].orEmpty())) }
            }
        }
    }
    fun context(connectionId: String?, projectPath: String?, connected: Boolean) {
        val next = Triple(connectionId, projectPath, connected)
        if (context == next) return
        context = next
        refresh()
    }
    fun refresh() {
        val token = ++generation; job?.cancel()
        val selected = context
        mutable.update { it.copy(catalog = RemoteSkillCatalog(emptyList(), emptyMap()), loading = selected?.third == true, error = null) }
        val id = selected?.first ?: return
        if (!selected.third) return
        job = scope.launch {
            try {
                val result = remote.loadSkills(id, selected.second)
                if (token == generation) {
                    remote.skillUsage.merge(id, result.usage)
                    mutable.update { it.copy(catalog = result.copy(usage = remote.skillUsage.state.value[id].orEmpty()), loading = false) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (token == generation) mutable.update { it.copy(loading = false, error = R.string.skills_read_failed) } }
        }
    }
    fun replace(text: String, references: List<SkillReference>) { setDraft(SkillDraft(text, validSkillReferences(text, references))) }
    fun edit(text: String, cursor: Int = text.length) { setDraft(mutable.value.draft.edited(text, cursor)) }
    internal fun editFromEditor(value: androidx.compose.ui.text.input.TextFieldValue) {
        val edited = mutable.value.draft.edited(value.text, value.selection.end)
        setDraft(edited.copy(references = validSkillReferences(value.text,
            edited.validReferences + value.editorSkillReferences())))
    }
    private fun setDraft(draft: SkillDraft) {
        mutable.update { it.copy(draft = draft) }
        if (draft.text.length <= 20_000) saved[key] = Json.encodeToString(draft.validReferences) else saved.remove<String>(key)
    }
    fun select(skill: RemoteSkill, trigger: Char): String {
        val current = mutable.value.draft
        val insertion = current.insertingSkill(skill.name, trigger) ?: run {
            val caret = current.cursor.coerceIn(0, current.text.length)
            val prefix = if (caret > 0 && !current.text[caret - 1].isWhitespace()) " " else ""
            current.edited(current.text.substring(0, caret) + prefix + trigger + current.text.substring(caret), caret + prefix.length + 1)
                .insertingSkill(skill.name, trigger)!!
        }
        setDraft(insertion)
        return insertion.text
    }
    fun payload(defaults: RemoteDefaults): SkillDraft = mutable.value.draft.payload(mutable.value.catalog.enabled(defaults), defaults.skillTrigger)
    fun sent(references: List<SkillReference>) {
        val id = context?.first ?: return
        remote.skillUsage.sent(id, references)
    }
}
