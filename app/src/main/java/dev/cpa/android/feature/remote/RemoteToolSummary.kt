package dev.cpa.android.feature.remote

import kotlinx.serialization.json.*
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cpa.android.R

internal fun isSkillRead(name: String, arguments: JsonElement): Boolean = name == "read" &&
    argument(arguments, "path")?.replace('\\', '/')?.endsWith("skill.md", ignoreCase = true) == true

private fun argument(args: JsonElement, key: String): String? = ((args as? JsonObject)?.get(key) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
private val graphemes = Regex("\\X")
private fun shorten(text: String, limit: Int, prefix: Int = limit - 3): String {
    val clusters = graphemes.findAll(text).take(limit + 1).toList()
    return if (clusters.size > limit) text.substring(0, clusters[prefix - 1].range.last + 1) + "…" else text
}
private fun firstLine(text: String?, limit: Int): String? = text?.trim()?.substringBefore('\n')?.trim()
    ?.takeIf(String::isNotEmpty)?.let { shorten(it, limit) }
internal fun humanizeTool(value: String): String {
    val words = value.replace(Regex("[-_]+"), " ").split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
    var previousWord = false
    return buildString {
        for (char in words) {
            val word = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_'
            append(if (word && !previousWord) char.uppercaseChar() else char)
            previousWord = word
        }
    }
}

internal data class ToolSummary(@param:StringRes val resource: Int, val arguments: List<String> = emptyList()) {
    @Composable fun localized(): String = stringResource(resource, *arguments.toTypedArray())
}

/** iOS ToolActivity.summarize; no side effects and no mutation of full argument/result details. */
internal fun toolSummary(name: String, args: JsonElement, running: Boolean): ToolSummary {
    fun string(key: String) = argument(args, key)
    fun phase(active: ToolSummary, done: ToolSummary) = if (running) active else done
    if (isSkillRead(name, args)) {
        val parts = string("path")!!.replace('\\', '/').split('/').filter(String::isNotEmpty)
        val last = parts.lastOrNull().orEmpty()
        val skill = if (last.equals("skill.md", true) && parts.size >= 2) humanizeTool(parts[parts.lastIndex - 1])
            else humanizeTool(if (last.endsWith(".md", true)) last.dropLast(3) else last).ifEmpty { "skill" }
        return ToolSummary(R.string.summary_skill, listOf(skill))
    }
    when (name) {
        "bash", "shell", "pwsh", "powershell" -> return if (running) firstLine(string("command") ?: string("cmd"), 64)?.let { ToolSummary(R.string.summary_command_running, listOf(it)) } ?: ToolSummary(R.string.summary_command_done) else ToolSummary(R.string.summary_command_done)
        "read", "edit", "write" -> {
            val path = string("path") ?: string("file")
            val file = path?.replace('\\', '/')?.split('/')?.lastOrNull(String::isNotEmpty) ?: path ?: name
            return when (name) {
                "read" -> phase(ToolSummary(R.string.summary_read_running, listOf(file)), ToolSummary(R.string.summary_read_done, listOf(file)))
                "edit" -> phase(ToolSummary(R.string.summary_edit_running, listOf(file)), ToolSummary(R.string.summary_edit_done, listOf(file)))
                else -> phase(ToolSummary(R.string.summary_write_running, listOf(file)), ToolSummary(R.string.summary_write_done, listOf(file)))
            }
        }
        "ask", "ask_user" -> {
            val question = shorten(string("question") ?: string("prompt") ?: "", 40, 37)
            return if (question.isEmpty()) phase(ToolSummary(R.string.summary_ask_waiting), ToolSummary(R.string.summary_ask_done)) else phase(ToolSummary(R.string.summary_question_running, listOf(question)), ToolSummary(R.string.summary_question_done, listOf(question)))
        }
        "todo", "manage_todo_list" -> return if (string("operation") == "read") phase(ToolSummary(R.string.summary_todo_read_running), ToolSummary(R.string.summary_todo_read_done)) else phase(ToolSummary(R.string.summary_todo_update_running), ToolSummary(R.string.summary_todo_update_done))
        "exec" -> return firstLine(string("description"), 80)?.let { ToolSummary(if (running) R.string.summary_exec_running_detail else R.string.summary_exec_done_detail, listOf(it)) } ?: phase(ToolSummary(R.string.summary_exec_running), ToolSummary(R.string.summary_exec_done))
        "title", "set_session_title" -> return firstLine(string("title"), 64)?.let { ToolSummary(if (running) R.string.summary_title_running_detail else R.string.summary_title_done_detail, listOf(it)) } ?: phase(ToolSummary(R.string.summary_title_running), ToolSummary(R.string.summary_title_done))
    }
    return when (name) {
        "memories_read" -> phase(ToolSummary(R.string.summary_memories_read_running), ToolSummary(R.string.summary_memories_read_done))
        "memories_search" -> phase(ToolSummary(R.string.summary_memories_search_running), ToolSummary(R.string.summary_memories_search_done))
        "memories_add" -> phase(ToolSummary(R.string.summary_memories_add_running), ToolSummary(R.string.summary_memories_add_done))
        "web_search" -> phase(ToolSummary(R.string.summary_web_search_running), ToolSummary(R.string.summary_web_search_done))
        "spawn_agent", "delegate_agent", "subagent" -> phase(ToolSummary(R.string.summary_spawn_agent_running), ToolSummary(R.string.summary_spawn_agent_done))
        "send_message", "send_input" -> phase(ToolSummary(R.string.summary_send_message_running), ToolSummary(R.string.summary_send_message_done))
        "stop_agent" -> phase(ToolSummary(R.string.summary_stop_agent_running), ToolSummary(R.string.summary_stop_agent_done))
        "session_search" -> phase(ToolSummary(R.string.summary_session_search_running), ToolSummary(R.string.summary_session_search_done))
        "session_create", "create_session" -> phase(ToolSummary(R.string.summary_session_create_running), ToolSummary(R.string.summary_session_create_done))
        else -> ToolSummary(if (running) R.string.summary_unknown_running else R.string.summary_unknown_done, listOf(humanizeTool(name).ifEmpty { name }))
    }
}
