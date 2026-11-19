package dev.cpa.android.integration

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import dev.cpa.android.core.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections

/** Real source service + synthetic business fixtures; never reads app settings or launches UI. */
@RunWith(AndroidJUnit4::class)
class DeviceDesktopContractTest {
    @Test fun realDesktopProtocol() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val origin = arguments.getString("CPA_CONTRACT_URL")
        val password = arguments.getString("CPA_CONTRACT_PASSWORD")
        assumeTrue("Opt-in source fixture only: scripts/test-desktop-device-contract.sh",
            arguments.getString("CPA_CONTRACT_FIXTURE") == "source-service" &&
                !origin.isNullOrBlank() && !password.isNullOrBlank())
        require(Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}").matches(origin!!)) { "Expected reversed loopback fixture" }
        val http = OkHttpClient()
        fun request(path: String, body: JsonObject? = null, cookie: String? = null): Pair<Int, JsonObject> {
            val builder = Request.Builder().url(origin + path)
            body?.let { builder.post(it.toString().toRequestBody("application/json".toMediaType())) }
            cookie?.let { builder.header("Cookie", it) }
            return http.newCall(builder.build()).execute().use {
                it.code to Json.parseToJsonElement(it.body!!.string()).jsonObject
            }
        }
        try {
            val status = request("/api/auth/status")
            assertEquals(200, status.first)
            assertEquals(JsonPrimitive(true), status.second["required"])
            assertEquals(JsonPrimitive(false), status.second["authenticated"])
            assertEquals(401, request("/api/auth/login", buildJsonObject { put("password", "wrong-fixture-password") }).first)
            val rpc = buildJsonObject { put("id", "http-1"); put("method", "fixture.echo"); put("args", JsonArray(emptyList())); put("clientId", "http-fixture") }
            assertEquals(401, request("/api/rpc", rpc).first)
            assertEquals(401, request("/api/rpc", rpc, "cpa_web_session=invalid").first)
            val login = Request.Builder().url("$origin/api/auth/login").post(
                buildJsonObject { put("password", password) }.toString().toRequestBody("application/json".toMediaType())
            ).build()
            val cookie = http.newCall(login).execute().use {
                assertEquals(200, it.code)
                val header = it.header("Set-Cookie")!!
                assertTrue(header.contains("HttpOnly", ignoreCase = true))
                assertTrue(header.startsWith("cpa_web_session="))
                header.substringBefore(';')
            }
            assertEquals(JsonPrimitive(true), request("/api/auth/status", cookie = cookie).second["authenticated"])
            val httpResult = request("/api/rpc", rpc, cookie)
            assertEquals(200, httpResult.first)
            assertEquals(JsonPrimitive("http-1"), httpResult.second["id"])
            assertEquals(JsonPrimitive("http-fixture"), httpResult.second["result"]!!.jsonObject["context"]!!.jsonObject["clientId"])

            // Unauthorized WebSocket upgrade is HTTP 401, NOT close 4401.
            val unauthenticated = DirectTransport(origin)
            try {
                val denied = CompletableDeferred<Throwable>()
                unauthenticated.openSocket(object : DesktopTransport.Listener {
                    override fun onText(text: String) { denied.complete(AssertionError("Unexpected unauthenticated WS frame")) }
                    override fun onClosed(cause: Throwable) { denied.complete(cause) }
                })
                val failure = withTimeout(5000) { denied.await() }
                assertTrue(failure is HttpFailure)
                assertEquals(401, (failure as HttpFailure).statusCode)
            } finally { unauthenticated.close() }

            DesktopRpcClient(DirectTransport(origin)).use { rejected ->
                try {
                    rejected.connect("wrong-fixture-password", 5000)
                    fail("Wrong password must fail before hello")
                } catch (failure: HttpFailure) { assertEquals(401, failure.statusCode) }
            }

            val trace = RecordingTransport(DirectTransport(origin))
            DesktopRpcClient(trace).use { client ->
                client.connect(password!!, 5000)
                val hello = trace.frames.first { it["type"] == JsonPrimitive("hello") }
                val clientId = hello["clientId"]!!.jsonPrimitive.content
                assertTrue(clientId.startsWith("client_ws_"))
                val args = listOf(buildJsonObject { put("text", "跨端"); put("number", 7) })
                val result = client.call("fixture.echo", args, 5000).jsonObject
                assertEquals(JsonArray(args), result["args"])
                val context = result["context"]!!.jsonObject
                assertEquals(JsonPrimitive(clientId), context["clientId"])
                assertEquals(JsonPrimitive("web:$clientId"), context["documentId"])
                assertEquals(JsonPrimitive("web"), context["transport"])
                assertEquals(JsonPrimitive("web-ws"), context["pluginId"])
                val sent = trace.sent.first()
                val envelope = trace.frames.first { it["type"] == JsonPrimitive("rpc_result") }
                assertEquals(sent["id"], envelope["id"])
                assertEquals(result, envelope["result"])
                // RecordingTransport overwrites the outgoing identity; server must use its WS identity.
                assertNotEquals(JsonPrimitive("forged-client"), context["clientId"])
                try {
                    client.call("fixture.fail", timeoutMillis = 5000)
                    fail("Expected rpc_error")
                } catch (failure: RemoteRpcFailure) { assertEquals("fixture failure", failure.message) }
                assertTrue(trace.frames.any { it["type"] == JsonPrimitive("rpc_error") && it["error"] == JsonPrimitive("fixture failure") })
                val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { client.events.first() } }
                client.call("fixture.event", timeoutMillis = 5000)
                val received = event.await()
                assertEquals("fixture.contract", received.kind)
                assertEquals(Json.parseToJsonElement("""{"text":"跨端 ✓","nested":{"count":2},"fixture":true}"""), received.payload)
                val wireEvent = trace.frames.first { it["type"] == JsonPrimitive("event") }["event"]!!.jsonObject
                assertTrue(wireEvent["data"]!!.jsonPrimitive.isString)
                assertEquals(received.payload, Json.parseToJsonElement(wireEvent["data"]!!.jsonPrimitive.content))
                client.call("fixture.rotate", timeoutMillis = 5000)
                val closed = withTimeout(5000) { trace.closed.await() }
                assertTrue(closed is WebSocketCloseFailure)
                assertEquals(4401, (closed as WebSocketCloseFailure).code)
                assertEquals(401, request("/api/rpc", rpc, cookie).first)
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    /** Observes genuine frames; changes only an untrusted clientId to test server authority. */
    private class RecordingTransport(private val delegate: DesktopTransport) : DesktopTransport {
        private val recordedFrames = Collections.synchronizedList(mutableListOf<JsonObject>())
        private val recordedSent = Collections.synchronizedList(mutableListOf<JsonObject>())
        val frames: List<JsonObject> get() = synchronized(recordedFrames) { recordedFrames.toList() }
        val sent: List<JsonObject> get() = synchronized(recordedSent) { recordedSent.toList() }
        val closed = CompletableDeferred<Throwable>()
        override suspend fun authenticate(password: String) = delegate.authenticate(password)
        override fun openSocket(listener: DesktopTransport.Listener): DesktopTransport.Socket {
            val socket = delegate.openSocket(object : DesktopTransport.Listener {
                override fun onText(text: String) {
                    recordedFrames.add(Json.parseToJsonElement(text).jsonObject)
                    listener.onText(text)
                }
                override fun onClosed(cause: Throwable) {
                    closed.complete(cause)
                    listener.onClosed(cause)
                }
            })
            return object : DesktopTransport.Socket {
                override fun send(text: String): Boolean {
                    val original = Json.parseToJsonElement(text).jsonObject
                    recordedSent.add(original)
                    return socket.send(JsonObject(original + ("clientId" to JsonPrimitive("forged-client"))).toString())
                }
                override fun close() = socket.close()
            }
        }
        override fun close() = delegate.close()
    }
}
