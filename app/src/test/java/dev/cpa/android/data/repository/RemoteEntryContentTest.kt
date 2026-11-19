package dev.cpa.android.data.repository

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteEntryContentTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    @Test fun contentKeepsBlockOrderAndNeverMixesThinkingIntoReplyOrOffsets() {
        val entry = parseRemoteEntry(obj("""{"id":"a","kind":"assistant","content":[{"type":"thinking","thinking":"private reasoning"},{"type":"text","text":"😀 reply"},{"type":"toolCall","id":"call|suffix","name":"shell","arguments":{"cmd":"pwd"}},{"type":"future","new":true},{"type":"image","data":"YWJj"}]}"""))!!
        assertEquals("😀 reply", entry.text)
        assertEquals(listOf("Thinking", "Text", "ToolCall", "Unknown", "Image"), entry.details.content.map { it.javaClass.simpleName })
        assertEquals(obj("""{"cmd":"pwd"}"""), (entry.details.content[2] as RemoteContent.ToolCall).arguments)
        assertEquals("image/png", entry.images.single().mimeType)
        assertEquals(JsonPrimitive(true), (entry.details.content[3] as RemoteContent.Unknown).raw.jsonObject["new"])
    }
    @Test fun entryStatusUsageAndDisplayMetadataSurviveProjection() {
        val entry = parseRemoteEntry(obj("""{"id":"t","kind":"toolResult","toolCallId":"c|x","createdAt":1000.5,"completedAt":2000,"pausedMs":100,"status":"error","stopReason":"aborted","interrupted":true,"isError":true,"errorMessage":"failure","usage":{"totalTokens":42,"extra":2},"displayMetadata":{"nestedTools":[],"other":"preserved"},"summary":"output"}"""))!!
        assertEquals("output", entry.text); assertTrue(entry.details.interrupted); assertTrue(entry.details.isError)
        assertEquals(1000.5, entry.details.createdAt!!, 0.0); assertEquals(100.0, entry.details.pausedMs, 0.0)
        assertEquals("error", entry.details.status); assertEquals("failure", entry.details.errorMessage)
        assertEquals(JsonPrimitive(2), entry.details.usage!!["extra"])
        assertEquals(JsonPrimitive("preserved"), entry.details.displayMetadata!!["other"])
    }
    @Test fun malformedOptionalFieldsDoNotDropValidEntries() {
        val entry = parseRemoteEntry(obj("""{"id":"x","content":[false,{"type":"toolCall","arguments":[1,2]},{"type":"text"}],"createdAt":"NaN","completedAt":"Infinity","pausedMs":-1,"usage":false,"displayMetadata":[]} """))!!
        assertNull(entry.details.createdAt); assertNull(entry.details.completedAt); assertEquals(0.0, entry.details.pausedMs, 0.0)
        assertNull(entry.details.usage); assertNull(entry.details.displayMetadata)
        assertEquals("", (entry.details.content[1] as RemoteContent.ToolCall).id)
    }
    @Test fun liveToolsNormalizeIdsAndFinalResultEndsRunningState() {
        var thread = RemoteThread("c", "s")
        thread = thread.withToolEvent(obj("""{"type":"tool-start","toolCallId":"call|v1","toolName":"code"}"""))
        assertEquals("code", thread.runningTools["call"]!!.name)
        thread = thread.withToolEvent(obj("""{"type":"tool-update","toolCallId":"call|v2","result":{"details":{"nestedTools":[{"id":"child"}],"extra":1}}}"""))
        assertEquals(JsonPrimitive(1), thread.runningTools["call"]!!.details!!["extra"])
        thread = thread.withToolEvent(obj("""{"type":"tool-end","toolCallId":"call"}"""))
        assertTrue(thread.runningTools.isEmpty())
    }
    @Test fun displayUpdateMergesOnlyLatestMatchingResultAndPreservesOtherFields() {
        val old = parseRemoteEntry(obj("""{"id":"old","kind":"toolResult","toolCallId":"call","displayMetadata":{"keep":true}}"""))!!
        val latest = old.copy(id = "new")
        val thread = RemoteThread("c", "s", entries = listOf(old, latest))
            .withToolEvent(obj("""{"type":"tool-display-update","toolCallId":"call|suffix","displayMetadata":{"nestedTools":[1]}}"""))
        assertNull(thread.entries.first().details.displayMetadata!!["nestedTools"])
        assertEquals(JsonPrimitive(true), thread.entries.last().details.displayMetadata!!["keep"])
        assertEquals(JsonArray(listOf(JsonPrimitive(1))), thread.entries.last().details.displayMetadata!!["nestedTools"])
    }
}
