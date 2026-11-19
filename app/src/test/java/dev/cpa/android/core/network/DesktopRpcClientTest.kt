package dev.cpa.android.core.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopRpcClientTest {
    private class Fake : DesktopTransport {
        lateinit var listener: DesktopTransport.Listener
        val sent = mutableListOf<JsonObject>()
        var closed = false
        var accepts = true
        var greet = true
        override suspend fun authenticate(password: String) = Unit
        override fun openSocket(listener: DesktopTransport.Listener): DesktopTransport.Socket {
            this.listener = listener
            if (greet) listener.onText("""{"type":"hello","clientId":"desktop-cid"}""")
            return object : DesktopTransport.Socket {
                override fun send(text: String): Boolean { sent.add(Json.parseToJsonElement(text).jsonObject); return accepts }
                override fun close() = Unit
            }
        }
        fun reply(index: Int, result: String) = listener.onText("""{"type":"rpc_result","id":${sent[index]["id"]},"result":$result}""")
        override fun close() { closed = true }
    }

    @Test fun matchesOutOfOrderAndSeparatesEvents() = runTest {
        val transport = Fake(); val client = DesktopRpcClient(transport); client.connect("")
        val first = async { client.call("session:get", listOf(JsonPrimitive("s1"))) }
        val second = async { client.call("session:listSessions") }
        runCurrent()
        assertEquals(JsonPrimitive("rpc"), transport.sent[0]["type"])
        assertEquals(JsonPrimitive("desktop-cid"), transport.sent[0]["clientId"])
        assertEquals(JsonArray(listOf(JsonPrimitive("s1"))), transport.sent[0]["args"])
        transport.listener.onText("not-json")
        transport.listener.onText("""{"type":"rpc_result","id":"unknown","result":0}""")
        transport.listener.onText("""{"type":"event","event":{"kind":"future:event","data":"{\"value\":1}"}}""")
        val event = client.events.first()
        assertEquals("future:event", event.kind)
        assertEquals(JsonPrimitive(1), event.payload.jsonObject["value"])
        assertEquals(2, client.pendingCount)
        transport.reply(1, "2"); transport.reply(0, "1")
        assertEquals(JsonPrimitive(1), first.await()); assertEquals(JsonPrimitive(2), second.await())
        assertEquals(0, client.pendingCount); client.close()
    }

    @Test fun timeoutCancellationAndLateRepliesNeverReplay() = runTest {
        val transport = Fake(); val client = DesktopRpcClient(transport); client.connect("")
        val timed = async { runCatching { client.call("write", timeoutMillis = 100) } }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertTrue(timed.await().exceptionOrNull() is TimeoutCancellationException)
        val cancelled = launch { client.call("anotherWrite") }
        runCurrent(); cancelled.cancelAndJoin()
        assertEquals(0, client.pendingCount)
        transport.reply(0, "true"); transport.reply(1, "true")
        assertEquals(2, transport.sent.size); client.close()
    }

    @Test fun errorsDisconnectAndOldCallbacksAreTerminal() = runTest {
        val transport = Fake(); val client = DesktopRpcClient(transport); client.connect("")
        val failed = async { runCatching { client.call("bad") } }; runCurrent()
        transport.listener.onText("""{"type":"rpc_error","id":${transport.sent[0]["id"]},"errorDto":{"message":"denied"}}""")
        assertEquals("denied", failed.await().exceptionOrNull()?.message)
        val pending = async { runCatching { client.call("pending") } }; runCurrent()
        transport.listener.onClosed(Disconnected())
        assertTrue(pending.await().exceptionOrNull() is Disconnected)
        transport.listener.onText("""{"type":"hello","clientId":"stale"}""")
        assertTrue(runCatching { client.call("noReplay") }.exceptionOrNull() is Disconnected)
        assertTrue(transport.closed); assertEquals(0, client.pendingCount)
    }

    @Test fun missingHelloTimesOutAndConnectCancellationCloses() = runTest {
        val transport = Fake().apply { greet = false }; val client = DesktopRpcClient(transport)
        val connecting = async { runCatching { client.connect("", 100) } }
        advanceUntilIdle()
        assertTrue(connecting.await().exceptionOrNull() is TimeoutCancellationException)
        assertTrue(transport.closed)
        val second = Fake().apply { greet = false }; val other = DesktopRpcClient(second)
        val job = launch { other.connect("") }; runCurrent(); job.cancelAndJoin()
        assertTrue(second.closed)
    }

    @Test fun missingResultIsNullAndBothErrorShapesAreSupported() = runTest {
        val transport = Fake(); val client = DesktopRpcClient(transport); client.connect("")
        val empty = async { client.call("void") }; runCurrent()
        transport.listener.onText("""{"type":"rpc_result","id":${transport.sent[0]["id"]}}""")
        assertEquals(JsonNull, empty.await())
        for (error in listOf(JsonPrimitive("failure"), buildJsonObject { put("message", "failure") })) {
            val result = async { runCatching { client.call("bad") } }; runCurrent()
            transport.listener.onText(buildJsonObject {
                put("type", "rpc_error"); put("id", transport.sent.last().getValue("id")); put("error", error)
            }.toString())
            assertEquals("failure", result.await().exceptionOrNull()?.message)
        }
        client.close()
    }

    @Test fun eventOverflowFailsConnectionRatherThanSilentlyDroppingDeltas() = runTest {
        val transport = Fake(); val client = DesktopRpcClient(transport); client.connect("")
        val waiting = async { runCatching { client.call("waiting") } }; runCurrent()
        repeat(257) {
            transport.listener.onText("""{"type":"event","event":{"kind":"session:stream-event","data":{}}}""")
        }
        assertTrue(waiting.await().exceptionOrNull() is ProtocolFailure)
        assertTrue(transport.closed); assertEquals(0, client.pendingCount)
    }

    @Test fun disconnectFailsAllImmediatelyResumingCalls() = runTest {
        val transport = Fake(); val client = DesktopRpcClient(transport); client.connect("")
        val calls = List(3) {
            async(UnconfinedTestDispatcher(testScheduler)) { runCatching { client.call("waiting") } }
        }
        assertEquals(3, client.pendingCount)
        transport.listener.onClosed(NetworkFailure())
        assertTrue(calls.all { it.await().exceptionOrNull() is NetworkFailure })
        assertEquals(0, client.pendingCount)
        assertTrue(transport.closed)
    }

    @Test fun rejectedSendCleansPending() = runTest {
        val transport = Fake().apply { accepts = false }; val client = DesktopRpcClient(transport); client.connect("")
        assertTrue(runCatching { client.call("write") }.exceptionOrNull() is Disconnected)
        assertEquals(0, client.pendingCount)
    }
}
