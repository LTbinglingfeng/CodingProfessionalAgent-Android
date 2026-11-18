package dev.cpa.android.data.repository

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteContextUsageTest {
    private fun entry(kind: String, tokens: JsonElement) = RemoteEntry("id", kind, "", details = RemoteEntryDetails(usage = buildJsonObject { put("totalTokens", tokens) }))
    private val catalog = parseRemoteModels(Json.parseToJsonElement("""[{"id":"model","contextWindow":2000}]"""))
    @Test fun latestPositiveAssistantUsageIgnoresUserAndZeroUsage() {
        val entries = listOf(entry("assistant", JsonPrimitive(500)), entry("assistant", JsonPrimitive(700)),
            entry("user", JsonPrimitive(9999)), entry("assistant", JsonPrimitive(0)))
        assertEquals(0.35, remoteContextUsage(entries, "model", RemoteCapabilities(catalog))!!, 0.00001)
    }
    @Test fun modelOverrideAppliesOnlyWhenEnableAllIsOffAndIsFloored() {
        fun defaults(enabled: Boolean) = parseRemoteDefaults(Json.parseToJsonElement("""{"settings":{"modelId":"model","modelSettings":{"enableAll":$enabled,"models":{"model":{"contextWindow":1000.8}}}}}"""))
        val entries = listOf(entry("assistant", JsonPrimitive(500)))
        assertEquals(0.5, remoteContextUsage(entries, "unknown", RemoteCapabilities(catalog, defaults(false)))!!, 0.00001)
        assertEquals(0.25, remoteContextUsage(entries, "unknown", RemoteCapabilities(catalog, defaults(true)))!!, 0.00001)
    }
    @Test fun missingOrInvalidContextAndUsageStayHiddenRatherThanInventingPercent() {
        val caps = RemoteCapabilities(catalog)
        assertNull(remoteContextUsage(emptyList(), "model", caps))
        assertNull(remoteContextUsage(listOf(entry("assistant", JsonPrimitive(500))), null, caps))
        for (invalid in listOf(JsonPrimitive(-2), JsonPrimitive("500"), JsonNull, JsonPrimitive(Double.NaN))) {
            assertNull(remoteContextUsage(listOf(entry("assistant", invalid)), "model", caps))
        }
        assertNull(remoteContextUsage(listOf(entry("assistant", JsonPrimitive(500))), "model", RemoteCapabilities(listOf(RemoteModel("model")))))
    }
    @Test fun overflowIsClampedToOneAndSessionModelWinsOverDefault() {
        val caps = RemoteCapabilities(catalog + RemoteModel("small", contextWindow = 100.0), RemoteDefaults(modelId = "model"))
        assertEquals(1.0, remoteContextUsage(listOf(entry("assistant", JsonPrimitive(10000))), "small", caps)!!, 0.0)
    }
}
