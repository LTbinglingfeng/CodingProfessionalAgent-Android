package dev.cpa.android.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RemoteSkillsTest {
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun file(text: String) = buildJsonObject { put("dataBase64", Base64.getEncoder().encodeToString(text.toByteArray())) }
    @Test fun rootPrecedenceSymlinksFiltersAndUsageMatchDesktop() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        val skills = scanRemoteSkills("/project") { method, args ->
            val path = (args.firstOrNull() as? JsonPrimitive)?.content.orEmpty(); calls += method to path
            when (method) {
                "native:runtimeInfo" -> json("""{"userConfigDir":"/config","homeDir":"/home","appConfigDirName":".app"}""")
                "native:readDir" -> if (path.endsWith("agent/skills")) json("""[{"name":"first","isDir":true},{"name":"linked","isDir":true,"isSymbolicLink":true},{"name":"node_modules","isDir":true},{"name":".git","isDir":true},{"name":"../escape","isDir":true}]""") else JsonArray(emptyList())
                "native:readFileIfExists" -> when {
                    path.contains("/first/") -> file("---\nname: 'Shared'\ndescription: \"first: description\"\n---")
                    path.startsWith("/home") -> file("---\nname: shared\n---")
                    path.startsWith("/project") -> file("---\nname: project\n---")
                    else -> JsonNull
                }
                else -> error(method)
            }
        }
        val catalog = RemoteSkillCatalog(skills, parseSkillUsage(json("""{"summary":{"topSkills":[{"name":"PROJECT","count":12},{"name":"shared","count":1}]}}""")))
        assertEquals(listOf("Shared", "project"), catalog.skills.map { it.name })
        assertEquals("first: description", catalog.skills.first().description)
        assertEquals(listOf("project", "Shared"), catalog.enabled(RemoteDefaults()).map { it.name })
        assertFalse(calls.any { (_, path) -> listOf("linked", "node_modules", ".git", "escape").any(path::contains) })
    }
    @Test fun directoryBudgetIsPerRootAndMissingFoldersDoNotHideProject() = runTest {
        val skills = scanRemoteSkills("/project", maxDirectoriesPerRoot = 2) { method, args ->
            val path = (args.firstOrNull() as? JsonPrimitive)?.content.orEmpty()
            when (method) {
                "native:runtimeInfo" -> json("""{"userConfigDir":"/config","homeDir":"/home"}""")
                "native:readDir" -> if (path.startsWith("/config")) json("""[{"name":"child","isDir":true}]""") else throw IllegalStateException("missing")
                "native:readFileIfExists" -> if (path.startsWith("/project")) file("plain document") else JsonNull
                else -> JsonNull
            }
        }
        assertEquals(listOf("skills"), skills.map { it.name })
        assertEquals("/project/.cpa/skills/SKILL.md", skills.single().filePath)
    }
    @Test fun cancellationStopsScanningInsteadOfPublishingPartialResults() = runTest {
        var reads = 0
        try {
            scanRemoteSkills("/project") { method, _ ->
                if (method == "native:runtimeInfo") json("""{"homeDir":"/home"}""") else { reads++; throw CancellationException("cancel") }
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { assertTrue(reads in 1..2) }
    }
    @Test fun settingsRespectDefaultOverrideAndTrigger() {
        val defaults = parseRemoteDefaults(json("""{"settings":{"skills":{"defaultMode":"disabled","trigger":"/","skills":{"chosen":"explicit","inherit":"default","off":"disabled"}}}}"""))
        assertEquals('/', defaults.skillTrigger)
        assertTrue(defaults.isSkillEnabled("chosen")); assertFalse(defaults.isSkillEnabled("inherit")); assertFalse(defaults.isSkillEnabled("off")); assertFalse(defaults.isSkillEnabled("missing"))
        assertEquals('$', parseRemoteDefaults(json("""{"settings":{"skills":{"trigger":"invalid"}}}""")).skillTrigger)
        assertEquals("folder", parseRemoteSkill("---\nname: ''\n---", "C:\\skills\\folder")!!.name)
    }
}
