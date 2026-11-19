package dev.cpa.android.data.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RemoteCapabilitiesTest {
    private val models = listOf(RemoteModel("a", reasoningLevels = listOf(RemoteReasoningOption("high"), RemoteReasoningOption("ultra"))), RemoteModel("b"))
    @Test fun imageCapabilityUsesDesktopInputModalities() {
        val parsed = parseRemoteModels(Json.parseToJsonElement("""[{"id":"vision","input":["text","image"]},{"id":"text","supportsImages":true}]"""))
        assertTrue(parsed[0].supportsImages)
        assertFalse(parsed[1].supportsImages)
    }
    @Test fun enableAllIgnoresOldPerModelRestrictions() {
        val defaults = parseRemoteDefaults(Json.parseToJsonElement("""{"settings":{"modelSettings":{"enableAll":true,"models":{"a":{"enabled":false,"enabledReasoningLevels":[]}}}}}"""))
        val caps = RemoteCapabilities(models, defaults)
        assertEquals(listOf("a", "b"), caps.availableModels.map { it.id })
        assertEquals(listOf("high"), caps.reasoningChoices("a").map { it.id })
    }
    @Test fun disabledFilteringPreservesExplicitEmptyReasoning() {
        val defaults = parseRemoteDefaults(Json.parseToJsonElement("""{"settings":{"modelSettings":{"enableAll":false,"models":{"b":{"enabled":false},"a":{"enabledReasoningLevels":[]}}}}}"""))
        val caps = RemoteCapabilities(models, defaults)
        assertEquals(listOf("a"), caps.availableModels.map { it.id })
        assertTrue(caps.reasoningChoices("a").isEmpty())
    }
    @Test fun explicitlyDisabledModelsAreNotSilentlyReenabled() {
        val caps = RemoteCapabilities(models, RemoteDefaults(disabledModels = setOf("a", "b"), enableAll = false))
        assertTrue(caps.availableModels.isEmpty())
        assertNull(caps.preferredModel())
    }
    @Test fun emptyDefaultsAndLabelsAreCompatible() {
        val defaults = parseRemoteDefaults(Json.parseToJsonElement("""{"settings":{"modelId":"","reasoningLevel":"","speed":""}}"""))
        assertNull(defaults.modelId); assertNull(defaults.reasoningLevel)
        assertEquals("standard", defaults.speed)
        val parsed = parseRemoteModels(Json.parseToJsonElement("""[{"id":"a","reasoningLevels":[{"id":"custom","fallbackLabel":"Custom label"}]}]"""))
        assertEquals("Custom label", parsed.single().reasoningLevels.single().label)
    }
}
