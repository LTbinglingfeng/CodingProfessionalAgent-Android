package dev.cpa.android.feature.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAskTest {
    private fun request(tool: String = "t1", custom: Boolean = true, skip: Boolean = true, countdown: Boolean = false): JsonObject =
        buildJsonObject {
            put("id", "q-$tool"); put("sessionId", "s1"); put("toolCallId", tool)
            put("question", "Question?"); put("allowCustom", custom); put("allowSkip", skip)
            put("options", buildJsonArray { add(buildJsonObject {
                put("title", "Yes"); put("description", "detail"); put("recommended", true)
            }) })
            if (countdown) put("countdownDeadline", 1000)
        }
    private fun reply(request: JsonElement? = request(), resolved: JsonElement = JsonNull) = buildJsonObject {
        put("request", request ?: JsonNull); put("resolved", resolved)
    }
    private data class Call(val method: String, val args: List<JsonElement>, val mutation: Boolean)

    @Test fun discoveryPollsEveryThreeSecondsAndCloseStopsIt() = runTest {
        val calls = mutableListOf<Call>()
        val controller = RemoteAskController("s1", backgroundScope, { method, args, mutation ->
            calls += Call(method, args, mutation); reply(null)
        })
        controller.start(); controller.start(); runCurrent()
        assertEquals(1, calls.size)
        assertEquals(Call("ask:getRequests", listOf(buildJsonObject { put("sessionId", "s1") }), false), calls.single())
        advanceTimeBy(2999); runCurrent(); assertEquals(1, calls.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls.size)
        controller.close(); advanceTimeBy(9000); runCurrent(); assertEquals(2, calls.size)
        controller.start(); runCurrent(); assertEquals(3, calls.size)
        controller.close()
    }

    @Test fun errorsAndMalformedResponsesNeverMeanEmptyRequest() = runTest {
        var result: JsonElement = reply()
        var fail = false
        val controller = RemoteAskController("s1", backgroundScope, { _, _, _ ->
            if (fail) error("offline") else result
        })
        controller.start(); runCurrent(); controller.setCustomText("draft")
        fail = true; controller.refresh(); runCurrent()
        assertNotNull(controller.state.value.request)
        assertEquals("draft", controller.state.value.customText)
        assertNotNull(controller.state.value.lookupError)
        fail = false; result = buildJsonObject {}; controller.refresh(); runCurrent()
        assertNotNull(controller.state.value.request)
        assertNotNull(controller.state.value.lookupError)
        result = reply(null); controller.refresh(); runCurrent()
        assertNull(controller.state.value.request)
        assertNull(controller.state.value.lookupError)
        controller.close()
    }

    @Test fun validationAndFalseKeepFormAndExactWireArguments() = runTest {
        val writes = mutableListOf<Call>()
        val controller = RemoteAskController("s1", backgroundScope, { method, args, mutation ->
            if (mutation) { writes += Call(method, args, true); JsonPrimitive(false) } else reply()
        })
        controller.start(); runCurrent()
        controller.select(-1); controller.select(1); controller.submitCustom(); runCurrent()
        assertTrue(writes.isEmpty())
        controller.setCustomText("  answer  "); controller.submitCustom(); controller.skip(); runCurrent()
        assertEquals(1, writes.size) // Single-flight even before the coroutine starts.
        assertEquals(listOf(JsonPrimitive("s1"), JsonPrimitive("t1"), buildJsonObject {
            put("type", "custom"); put("text", "answer")
        }), writes.single().args)
        assertNotNull(controller.state.value.request)
        assertEquals("  answer  ", controller.state.value.customText)
        controller.select(0); runCurrent()
        assertEquals(request()["options"]!!.jsonArray[0], writes.last().args[2].jsonObject["option"])
        assertEquals(JsonPrimitive(0), writes.last().args[2].jsonObject["index"])
        controller.skip(); runCurrent()
        assertEquals(JsonPrimitive("skipped"), writes.last().args[2].jsonObject["type"])
        controller.close()
    }

    @Test fun disallowedCustomAndSkipDoNotReachRpc() = runTest {
        var writes = 0
        val controller = RemoteAskController("s1", backgroundScope, { _, _, mutation ->
            if (mutation) writes++
            reply(request(custom = false, skip = false))
        })
        controller.start(); runCurrent(); controller.setCustomText("answer")
        controller.submitCustom(); controller.skip(); runCurrent()
        assertEquals(0, writes)
        controller.close()
    }

    @Test fun countdownIsDisplayOnlyAndCancellationRequiresBooleanTrue() = runTest {
        val writes = mutableListOf<Call>()
        var response: JsonElement = JsonPrimitive("true")
        val controller = RemoteAskController("s1", backgroundScope, { method, args, mutation ->
            if (mutation) { writes += Call(method, args, true); response } else reply(request(countdown = true))
        }, clock = { testScheduler.currentTime })
        controller.start(); runCurrent(); advanceTimeBy(2000); runCurrent()
        assertTrue(writes.isEmpty()) // Even after deadline elapsed, never timeout-submit locally.
        controller.cancelCountdown(); runCurrent()
        assertEquals(Call("ask:cancelCountdown", listOf(JsonPrimitive("s1"), JsonPrimitive("t1")), true), writes.single())
        assertNotNull(controller.state.value.request!!.countdownDeadline)
        assertTrue(controller.state.value.outcomeUnknown)
        controller.refresh(); runCurrent(); controller.acknowledgeOutcome()
        response = JsonPrimitive(false); controller.cancelCountdown(); runCurrent()
        assertNotNull(controller.state.value.request!!.countdownDeadline)
        response = JsonPrimitive(true); controller.cancelCountdown(); runCurrent()
        assertNull(controller.state.value.request!!.countdownDeadline)
        controller.close()
    }

    @Test fun unknownOutcomeIsNeverReplayedAndRefreshQueriesResolutionForExactTool() = runTest {
        val calls = mutableListOf<Call>()
        val resolution = buildJsonObject {
            put("sessionId", "s1"); put("toolCallId", "t1"); put("action", "submitAnswer")
        }
        var after = false
        val controller = RemoteAskController("s1", backgroundScope, { method, args, mutation ->
            calls += Call(method, args, mutation)
            if (mutation) { after = true; error("confirmation lost") }
            if (after) reply(null, resolution) else reply()
        })
        controller.start(); runCurrent(); controller.setCustomText("saved")
        controller.submitCustom(); runCurrent()
        assertEquals("saved", controller.state.value.customText)
        assertTrue(controller.state.value.outcomeUnknown)
        controller.acknowledgeOutcome(); assertTrue(controller.state.value.outcomeUnknown)
        controller.submitCustom(); advanceTimeBy(9000); runCurrent()
        assertEquals(1, calls.count { it.mutation })
        assertEquals(JsonPrimitive("t1"), calls.last().args.single().jsonObject["toolCallId"])
        assertEquals(resolution, controller.state.value.resolved)
        assertTrue(controller.state.value.checkedAfterFailure)
        controller.close(); controller.start(); runCurrent()
        assertEquals(1, calls.count { it.mutation })
        assertTrue(controller.state.value.outcomeUnknown)
        controller.acknowledgeOutcome(); assertFalse(controller.state.value.outcomeUnknown)
        controller.close()
    }

    @Test fun oldLookupCannotResurrectSubmittedQuestion() = runTest {
        var reads = 0
        val stale = CompletableDeferred<JsonElement>()
        val controller = RemoteAskController("s1", backgroundScope, { _, _, mutation ->
            if (mutation) JsonPrimitive(true)
            else if (++reads == 2) stale.await() else reply()
        })
        controller.start(); runCurrent(); controller.refresh(); runCurrent()
        controller.select(0); runCurrent(); assertNull(controller.state.value.request)
        stale.complete(reply()); runCurrent()
        assertNull(controller.state.value.request)
        controller.close()
    }

    @Test fun newerLookupWinsAndOldGenerationCannotOverwriteRestart() = runTest {
        var reads = 0
        val stale = CompletableDeferred<JsonElement>()
        val controller = RemoteAskController("s1", backgroundScope, { _, _, _ ->
            if (++reads == 1) withContext(NonCancellable) { stale.await() } else reply(request("t2"))
        })
        controller.start(); runCurrent(); controller.close(); controller.start(); runCurrent()
        assertEquals("t2", controller.state.value.request?.toolCallId)
        stale.complete(reply()); runCurrent()
        assertEquals("t2", controller.state.value.request?.toolCallId)
        controller.close()
    }

    @Test fun overlappingRefreshUsesLatestResponseAndRejectsOtherSessions() = runTest {
        var reads = 0
        val stale = CompletableDeferred<JsonElement>()
        var otherSession = false
        val controller = RemoteAskController("s1", backgroundScope, { _, _, _ ->
            if (++reads == 2) stale.await()
            else if (otherSession) reply(JsonObject(request().toMutableMap().apply { put("sessionId", JsonPrimitive("other")) }))
            else reply(request(if (reads > 2) "t2" else "t1"))
        })
        controller.start(); runCurrent(); controller.refresh(); runCurrent()
        controller.refresh(); runCurrent(); assertEquals("t2", controller.state.value.request?.toolCallId)
        stale.complete(reply()); runCurrent(); assertEquals("t2", controller.state.value.request?.toolCallId)
        otherSession = true; controller.refresh(); runCurrent()
        assertEquals("t2", controller.state.value.request?.toolCallId)
        assertNotNull(controller.state.value.lookupError)
        controller.close()
    }

    @Test fun returningToForegroundRequiresFreshSnapshotBeforeWriting() = runTest {
        var fail = false
        var writes = 0
        val controller = RemoteAskController("s1", backgroundScope, { _, _, mutation ->
            if (mutation) { writes++; JsonPrimitive(true) }
            else { if (fail) error("offline"); reply() }
        })
        controller.start(); runCurrent(); controller.close()
        fail = true
        controller.start(); controller.select(0); runCurrent()
        controller.select(0); runCurrent()
        assertEquals(0, writes)
        assertFalse(controller.state.value.snapshotFresh)
        fail = false; controller.refresh(); runCurrent(); controller.select(0); runCurrent()
        assertEquals(1, writes)
        controller.close()
    }
    @Test fun restoredIdentityAndAcknowledgementAreCoordinated() = runTest {
        var acknowledged = 0
        var blocked = true
        var query: JsonElement? = null
        val controller = RemoteAskController("s1", backgroundScope, { _, args, _ ->
            query = args.single(); reply(null)
        }, initialOutcomeUnknown = true, initialToolCallId = "old-tool", onOutcomeAcknowledged = {
            check(!blocked); acknowledged++
        })
        controller.start(); runCurrent()
        assertEquals(JsonPrimitive("old-tool"), query!!.jsonObject["toolCallId"])
        controller.acknowledgeOutcome()
        assertTrue(controller.state.value.outcomeUnknown)
        blocked = false; controller.acknowledgeOutcome()
        assertEquals(1, acknowledged)
        assertFalse(controller.state.value.outcomeUnknown)
        controller.close()
    }
    @Test fun closeDuringMutationLocksUnknownOutcomeAndNeverSendsAgain() = runTest {
        val pending = CompletableDeferred<JsonElement>()
        var writes = 0
        val controller = RemoteAskController("s1", backgroundScope, { _, _, mutation ->
            if (mutation) { writes++; pending.await() } else reply()
        })
        controller.start(); runCurrent(); controller.select(0); runCurrent(); controller.close()
        assertTrue(controller.state.value.outcomeUnknown)
        controller.start(); runCurrent(); controller.select(0); runCurrent()
        assertEquals(1, writes)
        controller.close()
    }
    @Test fun typingCancelsCountdownOnceKeepsTypingAndWaitsForDesktopConfirmation() = runTest {
        val gate = CompletableDeferred<JsonElement>()
        val writes = mutableListOf<Call>()
        var question = request(countdown = true)
        val controller = RemoteAskController("s1", backgroundScope, { method, args, mutation ->
            if (!mutation) reply(question) else { writes += Call(method, args, mutation); gate.await() }
        })
        controller.start(); runCurrent()
        controller.setCustomText("first"); runCurrent()
        assertEquals("ask:cancelCountdown", writes.single().method)
        assertEquals(listOf(JsonPrimitive("s1"), JsonPrimitive("t1")), writes.single().args)
        assertTrue(controller.state.value.cancellingCountdown)
        assertNotNull(controller.state.value.request?.countdownDeadline)
        controller.setCustomText("first and more")
        controller.submitCustom(); runCurrent()
        assertEquals(1, writes.size)
        assertEquals("first and more", controller.state.value.customText)
        question = request(countdown = false)
        gate.complete(JsonPrimitive(true)); runCurrent()
        assertNull(controller.state.value.request?.countdownDeadline)
        assertFalse(controller.state.value.busy)
        controller.setCustomText("another edit"); runCurrent()
        assertEquals(1, writes.size)
        controller.close()
    }

    @Test fun rejectedTypingCancellationDoesNotRetryOnTypingOrRestartButNewQuestionCanCancel() = runTest {
        var question = request(countdown = true)
        var writes = 0
        val controller = RemoteAskController("s1", backgroundScope, { _, _, mutation ->
            if (!mutation) reply(question) else { writes++; JsonPrimitive(false) }
        })
        controller.start(); runCurrent()
        controller.setCustomText("a"); runCurrent()
        assertEquals(1, writes)
        assertNotNull(controller.state.value.request?.countdownDeadline)
        controller.setCustomText("ab"); runCurrent()
        controller.close(); controller.start(); runCurrent()
        controller.setCustomText("abc"); runCurrent()
        assertEquals(1, writes)
        question = request(tool = "new", countdown = true)
        controller.refresh(); runCurrent()
        controller.setCustomText("new answer"); runCurrent()
        assertEquals(2, writes)
        controller.close()
    }

}
