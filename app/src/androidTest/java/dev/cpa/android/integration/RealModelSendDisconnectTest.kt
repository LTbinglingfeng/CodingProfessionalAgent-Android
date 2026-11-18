package dev.cpa.android.integration

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cpa.android.CpaApplication
import dev.cpa.android.MainActivity
import dev.cpa.android.core.network.DesktopRpcClient
import dev.cpa.android.core.network.DirectTransport
import dev.cpa.android.core.platform.hasLocalNetworkPermission
import dev.cpa.android.data.repository.ConnectionPhase
import dev.cpa.android.data.repository.RemoteDesktopRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Opt-in real desktop/model test; provision ONLY {origin,password,runId,faultToken} in
 * files/real-model-fault-test.json. origin is the reversed loopback fault proxy, not desktop.
 * The observer uses that SAME proxy, connects first, and never mutates desktop business state.
 * Only the Activity's single Send starts a model request. Never retry/resend/stop in cleanup.
 *
 * This is test-only transport acknowledgement loss, not a physical router outage or process
 * death test. Proxy counters are required evidence, not a model-provider billing audit. Desktop
 * isolation/model provenance remains the operator's responsibility. Title metadata is allowed;
 * other tools are forbidden. Prompt word limits are instructions, not provider token caps.
 * Activity recreation is real, but stop/termination semantics are deliberately NOT tested.
 * The desktop session is retained. Evidence never contains prompts, replies, secrets or errors.
 */
@RunWith(AndroidJUnit4::class)
class RealModelSendDisconnectTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val evidence = Evidence()
    private var deadline = Long.MAX_VALUE
    private val guard = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Requires isolated real-model fault proxy",
                    InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_FAULT") == "send-accepted-drop")
                deadline = SystemClock.elapsedRealtime() + 165_000
                try {
                    try { base.evaluate() }
                    finally {
                        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
                        val config = File(dir, "real-model-fault-test.json")
                        if (config.exists() && !config.delete()) {
                            evidence.cleanupOk = false
                            error("Config cleanup failed")
                        }
                    }
                } catch (failure: Throwable) {
                    evidence.recordFailure(failure)
                    // No original cause/suppressed exceptions: Compose may dump sensitive semantics.
                    throw AssertionError("Real-model fault test failed; inspect sanitized evidence only")
                } finally {
                    val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
                    try { File(dir, "real-model-fault-evidence.json").writeText(evidence.json()) }
                    catch (_: Throwable) { throw AssertionError("Sanitized evidence write failed") }
                }
            }
        }
    }
    @get:Rule val rules: TestRule = RuleChain.outerRule(guard).around(compose)

    @Test fun acceptedSendWithLostReplyRequiresRefreshAndManualConfirmation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var remote: RemoteDesktopRepository? = null
        var ownedId: String? = null
        var ownedName: String? = null
        var control: Control? = null
        var observer: DesktopRpcClient? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val trace = Trace()
        try {
            evidence.phase = "config"
            val file = File(context.filesDir, "real-model-fault-test.json")
            require(file.isFile && file.length() in 1..8192)
            val config = Json.parseToJsonElement(file.readText()).jsonObject
            require(config.keys == setOf("origin", "password", "runId", "faultToken"))
            val origin = requireNotNull(config.string("origin"))
            val password = requireNotNull(config.string("password"))
            val runId = requireNotNull(config.string("runId"))
            val token = requireNotNull(config.string("faultToken"))
            require(password.isNotBlank() && Regex("[a-f0-9]{64}").matches(token))
            require(Regex("http://127\\.0\\.0\\.1:[0-9]{1,5}").matches(origin))
            require(Regex("[A-Za-z0-9_-]{1,64}").matches(runId))
            DirectTransport.validateOrigin(origin)
            require(context.hasLocalNetworkPermission())
            val fault = Control(origin, token)
            control = fault
            fault.status().also {
                require(it.count == 0 && !it.accepted && !it.applied && !it.dropped && !it.offline)
            }
            val repository = (context.applicationContext as CpaApplication).remote
            remote = repository
            val name = "real-model-fault-$runId"
            require(repository.connections.value.none { it.name == name })

            evidence.phase = "observer-connect"
            val client = DesktopRpcClient(DirectTransport(origin))
            observer = client
            scope.launch {
                try { client.events.collect { event ->
                    if (event.kind == "session:stream-event") (event.payload as? JsonObject)?.let(trace::accept)
                } } catch (_: Throwable) { if (isActive) trace.failed() }
            }
            rpc { client.connect(password, 10_000) }

            evidence.phase = "save-connection-ui"
            compose.onNodeWithTag("home-settings").performClick()
            compose.onNodeWithTag("settings-add-connection").performScrollTo().performClick()
            compose.onNodeWithText("直连桌面端").performClick()
            compose.onNodeWithContentDescription("显示名称").performTextInput(name)
            compose.onNodeWithContentDescription("主机").performTextReplacement(origin)
            compose.onNodeWithContentDescription("密码").performTextInput(password)
            if (origin.startsWith("http://", true)) compose.onNodeWithTag("connection-cleartext").performScrollTo().performClick()
            ownedName = name
            compose.onNodeWithTag("connection-save").assertIsEnabled().performClick()
            await(12_000) { repository.connections.value.count { it.name == name } == 1 }
            val id = repository.connections.value.single { it.name == name }.id
            ownedId = id
            await(15_000) { repository.connections.value.any { it.id == id && it.phase == ConnectionPhase.Connected } }
            compose.onNodeWithTag("settings-close").performClick()
            evidence.phase = "prepare-empty-session"
            // The current new-thread UI creates and sends together. Prepare an empty session via
            // the production repository to keep this test focused on thread send/stop/recovery.
            val preparedId = rpc { repository.createSession(id, null, "Prepared-$runId") }
            await(15_000) { repository.sessions.value[id].orEmpty().any { it.id == preparedId } }
            val session = repository.sessions.value.getValue(id).single { it.id == preparedId }
            val preparedRow = hasTestTag(dev.cpa.android.feature.remote.homeKey("open", id, preparedId))
            compose.onNodeWithTag("home-list").performScrollToNode(preparedRow)
            compose.onNode(preparedRow).performClick()
            require(session.projectId == null)
            trace.session(session.id)
            val thread = repository.observeThread(id, session.id)
            await(10_000) { !thread.value.loading && thread.value.error == null && draftVisible() }
            require(thread.value.entries.isEmpty() && !thread.value.running && !thread.value.outcomeUnknown)
            require(snapshot(client, session.id).isEmpty())

            val prompt = "Pure chat only; no tools. Give one greeting, at most 12 words. Reference: $runId."
            evidence.phase = "send-once"
            compose.onNode(DRAFT).performTextReplacement(prompt)
            compose.onNodeWithTag("composer-send").assertIsEnabled().performClick()
            evidence.sendClicks++
            await(15_000) {
                val s = fault.status()
                s.accepted && s.applied && s.dropped && s.offline && thread.value.outcomeUnknown
            }
            val lost = fault.status()
            require(lost.count == 1)
            evidence.accepted = lost.accepted
            evidence.faultApplied = lost.applied
            evidence.droppedRpcReply = lost.dropped
            evidence.phase = "offline-no-early-success"
            await(5_000) {
                repository.connections.value.none { it.id == id && it.phase == ConnectionPhase.Connected } &&
                    disabled(SEND) && disabled(ACK)
            }
            compose.onNode(DRAFT).assertTextContains(prompt)
            // Neither a lost success reply nor observer completion may turn this into UI success.
            require(thread.value.outcomeUnknown)
            var earlyRejected = false
            try { rpc { repository.acknowledgeOutcome(id, session.id) } }
            catch (_: Exception) { earlyRejected = true }
            require(earlyRejected && thread.value.outcomeUnknown)
            evidence.earlyAcknowledgeRejected = true
            evidence.offlineActionsBlocked = true

            evidence.phase = "observer-authoritative-completion"
            var authoritative = emptyList<JsonObject>()
            await(70_000) {
                authoritative = snapshot(client, session.id)
                authoritative.any { it.string("kind") == "assistant" && it.string("status") == "done" && nonblankText(it) }
            }
            verifyUsers(authoritative, prompt)
            require(trace.runCount() == 1 && !trace.unhealthy())
            require(thread.value.outcomeUnknown && disabled(SEND) && disabled(ACK))
            require(fault.status().let { it.count == 1 && it.offline })
            evidence.assistantCompleted = true
            evidence.noEarlySuccess = true

            evidence.phase = "release-reconnect-ui"
            fault.release()
            require(!fault.status().offline)
            // Stay on the real thread so navigation does not discard its SavedStateHandle.
            // Give the remaining 1/2/4-second retries a chance; connect is idempotent while
            // a lifetime exists and starts ONLY our connection if the four tries exhausted.
            val retryUntil = minOf(deadline, SystemClock.elapsedRealtime() + 9_000)
            while (SystemClock.elapsedRealtime() < retryUntil &&
                repository.connections.value.none { it.id == id && it.phase == ConnectionPhase.Connected }) {
                Thread.sleep(50)
            }
            if (repository.connections.value.none { it.id == id && it.phase == ConnectionPhase.Connected }) {
                rpc { repository.connect(id) }
            }
            await(15_000) { repository.connections.value.any { it.id == id && it.phase == ConnectionPhase.Connected } }
            await(8_000) { draftVisible() }
            compose.onNodeWithTag("send-reload").performClick()
            await(10_000) {
                !thread.value.loading && thread.value.error == null && thread.value.entries.count { it.kind == "user" } == 1 &&
                    thread.value.entries.any { it.kind == "assistant" && it.text.isNotBlank() } && !thread.value.running
            }
            require(thread.value.outcomeUnknown && disabled(SEND))
            evidence.reloadedStillUnknown = true

            evidence.phase = "recreate-while-unknown"
            compose.activityRule.scenario.recreate()
            await(10_000) { draftVisible() && !thread.value.loading && disabled(SEND) }
            require(thread.value.outcomeUnknown)
            compose.onNode(DRAFT).assertTextContains(prompt)
            evidence.recreatedUnknownBlocked = true
            evidence.draftPreserved = true
            evidence.phase = "manual-confirmation-no-send"
            await(8_000) { enabled(ACK) }
            compose.onNodeWithTag(ACK).assertIsDisplayed().performClick()
            await(5_000) { !thread.value.outcomeUnknown && enabled(SEND) }
            evidence.manualConfirmationEnabledSend = true
            // Confirmation itself must survive another real Activity recreation without replay.
            evidence.phase = "recreate-after-confirmation"
            compose.activityRule.scenario.recreate()
            await(10_000) { draftVisible() && !thread.value.loading && !thread.value.outcomeUnknown && enabled(SEND) }
            require(compose.onAllNodesWithTag(ACK).fetchSemanticsNodes().isEmpty())
            compose.onNode(DRAFT).assertTextContains(prompt)
            evidence.recreatedConfirmationStayedClear = true
            // Never click Send again, even though it is now enabled.
            evidence.phase = "stability"
            val stableUntil = SystemClock.elapsedRealtime() + 2_000
            require(stableUntil < deadline)
            while (SystemClock.elapsedRealtime() < stableUntil) {
                require(!thread.value.outcomeUnknown && !thread.value.running && trace.runCount() == 1 && !trace.unhealthy())
                require(fault.status().count == 1)
                Thread.sleep(100)
            }
            val finalSnapshot = snapshot(client, session.id)
            verifyUsers(finalSnapshot, prompt)
            val finalStatus = fault.status()
            evidence.forwardedDelegateRuns = finalStatus.count
            evidence.observedRunCount = trace.runCount()
            evidence.authoritativeUserCount = finalSnapshot.count { it.string("kind") == "user" }
            require(finalStatus.count == 1 && finalStatus.accepted && finalStatus.applied && finalStatus.dropped && !finalStatus.offline)
            require(evidence.sendClicks == 1 && evidence.observedRunCount == 1 && evidence.authoritativeUserCount == 1)
            require(!trace.unhealthy() && enabled(SEND))
            evidence.phase = "complete"
            evidence.passed = true
        } catch (failure: Throwable) {
            evidence.recordFailure(failure)
            throw failure
        } finally {
            // Release even on assertion failure; no business sends/stops/session deletion.
            try { control?.release() } catch (_: Throwable) { evidence.cleanupOk = false }
            scope.cancel()
            observer?.close()
            try {
                val repository = remote
                val id = ownedId ?: ownedName?.let { name -> repository?.connections?.value?.singleOrNull { it.name == name }?.id }
                if (repository != null && id != null) runBlocking {
                    withTimeout(10_000) { repository.removeConnection(id) }
                }
            } catch (_: Throwable) { evidence.cleanupOk = false }
            check(evidence.cleanupOk) { "Owned resource cleanup failed" }
        }
    }

    private fun draftVisible() = compose.onAllNodes(DRAFT).fetchSemanticsNodes().size == 1
    private fun disabled(tag: String) = compose.onAllNodes(hasTestTag(tag) and isNotEnabled()).fetchSemanticsNodes().size == 1
    private fun enabled(tag: String) = compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().size == 1
    private fun await(ms: Long, predicate: () -> Boolean) {
        val end = minOf(deadline, SystemClock.elapsedRealtime() + ms)
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            Thread.sleep(50)
        }
        error("Bounded wait expired")
    }
    private fun <T> rpc(block: suspend () -> T): T = runBlocking {
        val remaining = deadline - SystemClock.elapsedRealtime()
        check(remaining > 0)
        withTimeout(minOf(remaining, 10_000)) { block() }
    }
    private fun snapshot(client: DesktopRpcClient, session: String): List<JsonObject> {
        val result = rpc { client.call("session:get", listOf(JsonPrimitive(session)), 5_000) }.jsonObject
        val entries = (result["entries"] as? JsonArray)?.map { it.jsonObject } ?: error("Missing entries")
        require(entries.none { (it.string("kind").orEmpty().contains("tool", true) || !it.string("toolName").isNullOrBlank()) && it.string("toolName") != "title" })
        return entries
    }
    private fun verifyUsers(entries: List<JsonObject>, prompt: String) {
        val users = entries.filter { it.string("kind") == "user" }
        require(users.size == 1 && !users.single().string("id").isNullOrBlank())
        require((users.single()["content"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.takeIf { block -> block.string("type") == "text" }?.string("text")
        }.joinToString("\n") == prompt)
    }
    private fun nonblankText(entry: JsonObject) = (entry["content"] as? JsonArray).orEmpty().any {
        val block = it as? JsonObject
        block != null && block.string("type") == "text" && !block.string("text").isNullOrBlank()
    }
    private data class Status(val count: Int, val accepted: Boolean, val applied: Boolean, val dropped: Boolean, val offline: Boolean)
    private inner class Control(private val origin: String, private val token: String) {
        fun release() { request("/__fault/release", "POST") }
        fun status(): Status {
            val obj = Json.parseToJsonElement(request("/__fault/status", "GET")).jsonObject
            fun bool(key: String) = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: error("Missing status flag")
            val count = (obj["forwardedDelegateRuns"] as? JsonPrimitive)?.intOrNull ?: error("Missing counter")
            require(count >= 0)
            return Status(count, bool("accepted"), bool("faultApplied"), bool("droppedRpcReply"), bool("offline"))
        }
        private fun request(path: String, method: String): String {
            val connection = URL(origin.trimEnd('/') + path).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 1_000
                connection.readTimeout = 1_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.setRequestProperty("X-CPA-Test-Token", token)
                require(connection.responseCode in 200..299)
                return connection.inputStream.bufferedReader().use { reader ->
                    val text = reader.readText()
                    require(text.length <= 8192)
                    text
                }
            } finally { connection.disconnect() }
        }
    }
    private class Trace {
        private var session: String? = null
        private val runs = linkedSetOf<String>()
        private var bad = false
        @Synchronized fun session(id: String) { session = id }
        @Synchronized fun failed() { bad = true }
        @Synchronized fun unhealthy() = bad
        @Synchronized fun runCount() = runs.size
        @Synchronized fun accept(payload: JsonObject) {
            if (session == null || payload.string("sessionId") != session) return
            val event = payload["event"] as? JsonObject ?: return
            if (event["nested"] == JsonPrimitive(true) || event.string("parentToolCallId") != null) return
            val run = payload.string("runId") ?: return
            runs.add(run)
            if (event.string("type") == "error" || event.string("type") == "aborted" ||
                (event.string("type") == "tool-start" && event.string("toolName") != "title")) bad = true
        }
    }
    private class Evidence {
        var phase = "activity-setup"
        var failureType = ""
        var failureLine = 0
        var passed = false
        var cleanupOk = true
        var accepted = false
        var faultApplied = false
        var droppedRpcReply = false
        var earlyAcknowledgeRejected = false
        var offlineActionsBlocked = false
        var assistantCompleted = false
        var noEarlySuccess = false
        var reloadedStillUnknown = false
        var recreatedUnknownBlocked = false
        var draftPreserved = false
        var manualConfirmationEnabledSend = false
        var recreatedConfirmationStayedClear = false
        var sendClicks = 0
        var forwardedDelegateRuns = 0
        var observedRunCount = 0
        var authoritativeUserCount = 0
        fun recordFailure(failure: Throwable) {
            if (failureType.isNotEmpty()) return
            failureType = failure.javaClass.simpleName
            failureLine = failure.stackTrace.firstOrNull { it.className.startsWith(RealModelSendDisconnectTest::class.java.name) }?.lineNumber ?: 0
        }
        fun json() = buildJsonObject {
            put("phase", phase); put("failureType", failureType); put("failureLine", failureLine)
            put("passed", passed && cleanupOk && failureType.isEmpty()); put("cleanupOk", cleanupOk)
            put("accepted", accepted); put("faultApplied", faultApplied); put("droppedRpcReply", droppedRpcReply)
            put("earlyAcknowledgeRejected", earlyAcknowledgeRejected); put("offlineActionsBlocked", offlineActionsBlocked)
            put("assistantCompleted", assistantCompleted); put("noEarlySuccess", noEarlySuccess)
            put("reloadedStillUnknown", reloadedStillUnknown); put("recreatedUnknownBlocked", recreatedUnknownBlocked)
            put("draftPreserved", draftPreserved); put("manualConfirmationEnabledSend", manualConfirmationEnabledSend)
            put("recreatedConfirmationStayedClear", recreatedConfirmationStayedClear)
            put("sendClicks", sendClicks); put("forwardedDelegateRuns", forwardedDelegateRuns)
            put("observedRunCount", observedRunCount); put("authoritativeUserCount", authoritativeUserCount)
        }.toString()
    }
    companion object {
        private val DRAFT = hasSetTextAction() and hasAnyAncestor(hasTestTag("thread-composer"))
        private const val SEND = "composer-send"
        private const val ACK = "send-acknowledge"
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
