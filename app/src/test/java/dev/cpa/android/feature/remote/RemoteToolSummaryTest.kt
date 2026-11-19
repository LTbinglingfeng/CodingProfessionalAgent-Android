package dev.cpa.android.feature.remote

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import dev.cpa.android.R

class RemoteToolSummaryTest {
    private fun args(vararg values: Pair<String, String>) = buildJsonObject { values.forEach { put(it.first, it.second) } }
    @Test fun filesSkillsAndWindowsPaths() {
        assertEquals(ToolSummary(R.string.summary_read_running, listOf("Main.kt")), toolSummary("read", args("path" to "C:\\src\\Main.kt"), true))
        assertEquals(ToolSummary(R.string.summary_edit_done, listOf("Main.kt")), toolSummary("edit", args("file" to "/src/Main.kt"), false))
        assertEquals(ToolSummary(R.string.summary_write_running, listOf("write")), toolSummary("write", JsonNull, true))
        assertTrue(isSkillRead("read", args("path" to "C:\\skills\\gh-issue\\SKILL.md")))
        assertEquals(ToolSummary(R.string.summary_skill, listOf("Gh Issue")), toolSummary("read", args("path" to "C:\\skills\\gh-issue\\SKILL.md"), true))
        assertFalse(isSkillRead("read_file", args("path" to "SKILL.md")))
    }
    @Test fun commandsUseOnlyFirstLineAndDoNotSplitGraphemes() {
        val emoji = "👨‍👩‍👧‍👦"
        assertEquals(ToolSummary(R.string.summary_command_running, listOf(emoji.repeat(61) + "…")), toolSummary("bash", args("cmd" to emoji.repeat(65)), true))
        assertEquals(ToolSummary(R.string.summary_command_running, listOf("ls")), toolSummary("pwsh", args("command" to "  ls \n pwd"), true))
        assertEquals(ToolSummary(R.string.summary_command_done), toolSummary("shell", args("command" to "secret argument"), false))
        assertEquals(ToolSummary(R.string.summary_command_done), toolSummary("powershell", JsonNull, true))
    }
    @Test fun questionsDescriptionsTitlesAndTodo() {
        assertEquals(ToolSummary(R.string.summary_question_running, listOf("中".repeat(37) + "…")), toolSummary("ask_user", args("prompt" to "中".repeat(41)), true))
        assertEquals(ToolSummary(R.string.summary_ask_done), toolSummary("ask", JsonNull, false))
        assertEquals(ToolSummary(R.string.summary_exec_done_detail, listOf("inspect")), toolSummary("exec", args("description" to "inspect\nmore"), false))
        assertEquals(ToolSummary(R.string.summary_title_done_detail, listOf("Test")), toolSummary("set_session_title", args("title" to "Test"), false))
        assertEquals(ToolSummary(R.string.summary_todo_read_running), toolSummary("manage_todo_list", args("operation" to "read"), true))
        assertEquals(ToolSummary(R.string.summary_todo_update_done), toolSummary("todo", JsonNull, false))
    }
    @Test fun builtinsAliasesAndUnknownTools() {
        for (name in listOf("spawn_agent", "delegate_agent", "subagent")) assertEquals(ToolSummary(R.string.summary_spawn_agent_done), toolSummary(name, JsonNull, false))
        assertEquals(ToolSummary(R.string.summary_send_message_running), toolSummary("send_input", JsonNull, true))
        assertEquals(ToolSummary(R.string.summary_memories_search_done), toolSummary("memories_search", JsonNull, false))
        assertEquals(ToolSummary(R.string.summary_unknown_running, listOf("Read File")), toolSummary("read_file", JsonNull, true))
        assertEquals(ToolSummary(R.string.summary_unknown_done, listOf("Plugin.Tool Name")), toolSummary("plugin.tool-name", JsonNull, false))
        assertEquals(ToolSummary(R.string.summary_read_running, listOf("read")), toolSummary("read", buildJsonObject { put("path", 12) }, true))
    }
}
