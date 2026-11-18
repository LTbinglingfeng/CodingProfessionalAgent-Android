package dev.cpa.android.feature.remote

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import dev.cpa.android.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Keep the wire option intact: selected.index is zero-based in the original options array. */
data class RemoteAskRequest(
    val id: String,
    val sessionId: String,
    val toolCallId: String,
    val question: String,
    val options: List<JsonObject>,
    val allowCustom: Boolean,
    val allowSkip: Boolean,
    val customPrompt: String?,
    val countdownDeadline: Long?,
)

data class RemoteAskState(
    val request: RemoteAskRequest? = null,
    val customText: String = "",
    val active: Boolean = false,
    val snapshotFresh: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val cancellingCountdown: Boolean = false,
    @param:StringRes val lookupError: Int? = null,
    @param:StringRes val actionMessage: Int? = null,
    val outcomeUnknown: Boolean = false,
    val checkedAfterFailure: Boolean = false,
    val lastToolCallId: String? = null,
    val resolved: JsonElement? = null,
    val nowMillis: Long = 0,
)

/**
 * One controller per connection/session. RPC must be bound to that same connection/session.
 * No mutation retries, including after restart. close() cancels only this controller's children;
 * start() can restart it. Parent scope cancellation also cancels all work.
 * All state transitions are serialized by lock; no dispatcher confinement is required.
 */
class RemoteAskController(
    val sessionId: String,
    private val scope: CoroutineScope,
    private val rpc: suspend (method: String, args: List<JsonElement>, mutation: Boolean) -> JsonElement,
    private val clock: () -> Long = System::currentTimeMillis,
    initialOutcomeUnknown: Boolean = false,
    initialToolCallId: String? = null,
    private val onOutcomeAcknowledged: () -> Unit = {},
) : AutoCloseable {
    init { require(sessionId.isNotBlank()) }
    private val lock = Any()
    private val mutableState = MutableStateFlow(RemoteAskState(outcomeUnknown = initialOutcomeUnknown, lastToolCallId = initialToolCallId))
    val state: StateFlow<RemoteAskState> = mutableState.asStateFlow()
    private var worker: CoroutineScope? = null
    private var generation = 0L
    private var lookupSequence = 0L
    private var typingCountdownAttempt: Pair<String, String>? = null

    fun start() = synchronized(lock) {
        if (worker?.isActive == true) return@synchronized
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        worker = child
        val epoch = ++generation
        mutableState.value = state.value.copy(active = true, snapshotFresh = false, checkedAfterFailure = false)
        child.launch {
            while (isActive) {
                lookup(epoch)
                delay(if (state.value.request?.countdownDeadline != null) 500L else 3_000L)
            }
        }
        Unit
    }

    override fun close() = synchronized(lock) {
        ++generation
        ++lookupSequence
        worker?.cancel()
        worker = null
        val s = state.value
        mutableState.value = s.copy(
            active = false, snapshotFresh = false, loading = false, busy = false, cancellingCountdown = false,
            outcomeUnknown = s.outcomeUnknown || s.busy,
            checkedAfterFailure = if (s.busy) false else s.checkedAfterFailure,
            actionMessage = if (s.busy) R.string.ask_interrupted_unknown else s.actionMessage,
        )
    }

    fun refresh() = synchronized(lock) {
        val epoch = generation
        worker?.launch { lookup(epoch) }
        Unit
    }

    private suspend fun lookup(epoch: Long) {
        val token: Long
        val tool: String?
        synchronized(lock) {
            if (epoch != generation || !state.value.active || state.value.busy) return
            token = ++lookupSequence
            tool = state.value.lastToolCallId ?: state.value.request?.toolCallId
            mutableState.value = state.value.copy(loading = true, nowMillis = clock())
        }
        try {
            val query = buildJsonObject {
                put("sessionId", sessionId)
                tool?.let { put("toolCallId", it) }
            }
            val result = rpc("ask:getRequests", listOf(query), false) as? JsonObject
                ?: error("Ask 查询响应不是对象")
            require(result.containsKey("request")) { "Ask 查询缺少 request" }
            val request = result["request"].let { if (it == JsonNull) null else parseRequest(it) }
            val resolved = result["resolved"]?.takeUnless { it == JsonNull }
            if (resolved != null) {
                val obj = resolved as? JsonObject ?: error("Ask resolved 不是对象")
                require(obj["sessionId"] == JsonPrimitive(sessionId) && obj["toolCallId"] == tool?.let(::JsonPrimitive)) {
                    "Ask resolved 身份不匹配"
                }
            }
            synchronized(lock) {
                if (epoch != generation || token != lookupSequence) return
                val s = state.value
                val same = request?.id == s.request?.id && request?.toolCallId == s.request?.toolCallId
                if (!same) typingCountdownAttempt = null
                mutableState.value = s.copy(
                    request = request, customText = if (same) s.customText else "",
                    loading = false, snapshotFresh = true, lookupError = null, resolved = resolved,
                    checkedAfterFailure = s.outcomeUnknown, nowMillis = clock(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                if (epoch == generation && token == lookupSequence) {
                    mutableState.value = state.value.copy(loading = false, snapshotFresh = false, checkedAfterFailure = false, lookupError = R.string.ask_lookup_failed)
                }
            }
        }
    }

    fun setCustomText(text: String) = synchronized(lock) {
        val current = state.value
        if (current.busy && !current.cancellingCountdown) return@synchronized
        mutableState.value = current.copy(customText = text)
        val request = current.request
        // Match iOS: first nonempty custom input attempts cancellation once for this question.
        // A failed/unknown mutation must never be replayed by subsequent keystrokes or reopening.
        if (request != null && request.allowCustom && text.isNotEmpty() && current.active &&
            current.snapshotFresh && !current.outcomeUnknown && !current.busy) {
            val identity = request.id to request.toolCallId
            if (typingCountdownAttempt != identity) {
                typingCountdownAttempt = identity
                if (request.countdownDeadline != null) cancelCountdown()
            }
        }
    }

    /** Human acknowledgement only; a missing resolved record is NOT proof of non-delivery. */
    fun acknowledgeOutcome() = synchronized(lock) {
        if (state.value.checkedAfterFailure && state.value.snapshotFresh && !state.value.busy) {
            try {
                onOutcomeAcknowledged()
                mutableState.value = state.value.copy(outcomeUnknown = false, actionMessage = R.string.ask_manually_verified)
            } catch (_: Exception) {
                mutableState.value = state.value.copy(actionMessage = R.string.ask_verify_session_first)
            }
        }
    }

    fun select(index: Int) = submit { request ->
        requireAsk(index in request.options.indices, R.string.ask_invalid_index)
        buildJsonObject { put("type", "selected"); put("option", request.options[index]); put("index", index) }
    }

    fun submitCustom() = submit { request ->
        requireAsk(request.allowCustom, R.string.ask_custom_disallowed)
        val text = state.value.customText.trim()
        requireAsk(text.isNotEmpty(), R.string.ask_empty_answer)
        buildJsonObject { put("type", "custom"); put("text", text) }
    }

    fun skip() = submit { request ->
        requireAsk(request.allowSkip, R.string.ask_skip_disallowed)
        buildJsonObject { put("type", "skipped") }
    }

    private fun submit(decision: (RemoteAskRequest) -> JsonObject) = mutate("ask:submitAnswer", decision)
    fun cancelCountdown() = mutate("ask:cancelCountdown", null)

    private fun mutate(method: String, decision: ((RemoteAskRequest) -> JsonObject)?) = synchronized(lock) {
        val s = state.value
        val request = s.request ?: return@synchronized
        val child = worker ?: return@synchronized
        if (!child.isActive || !s.snapshotFresh || s.busy || s.outcomeUnknown) return@synchronized
        val args = try {
            requireAsk(method != "ask:cancelCountdown" || request.countdownDeadline != null, R.string.ask_no_countdown)
            listOf(JsonPrimitive(sessionId), JsonPrimitive(request.toolCallId)) + listOfNotNull(decision?.invoke(request))
        } catch (e: AskValidationException) {
            mutableState.value = s.copy(actionMessage = e.messageResource)
            return@synchronized
        }
        val epoch = generation
        ++lookupSequence // Invalidates every read started before this mutation.
        mutableState.value = s.copy(busy = true, cancellingCountdown = decision == null, loading = false, actionMessage = null,
            lastToolCallId = request.toolCallId, resolved = null, checkedAfterFailure = false)
        child.launch {
            try {
                val result = rpc(method, args, true)
                synchronized(lock) {
                    if (epoch != generation) return@synchronized
                    val current = state.value
                    if (result == JsonPrimitive(true)) {
                        mutableState.value = current.copy(busy = false, cancellingCountdown = false,
                            request = if (decision != null) null else request.copy(countdownDeadline = null),
                            customText = if (decision != null) "" else current.customText,
                            actionMessage = if (decision != null) R.string.ask_answer_accepted else R.string.ask_countdown_stopped)
                    } else if (result == JsonPrimitive(false)) {
                        mutableState.value = current.copy(busy = false, cancellingCountdown = false, actionMessage = R.string.ask_operation_rejected)
                    } else {
                        markUnknown(R.string.ask_unknown_invalid_response)
                    }
                }
            } catch (e: CancellationException) {
                synchronized(lock) { if (epoch == generation) markUnknown(R.string.ask_unknown_cancelled) }
                throw e
            } catch (e: Exception) {
                synchronized(lock) { if (epoch == generation) markUnknown(R.string.ask_unknown_rpc_failed) }
            }
        }
        Unit
    }

    private fun markUnknown(@StringRes message: Int) {
        mutableState.value = state.value.copy(busy = false, cancellingCountdown = false, outcomeUnknown = true, checkedAfterFailure = false,
            actionMessage = message)
    }

    private fun parseRequest(value: JsonElement?): RemoteAskRequest {
        val obj = value as? JsonObject ?: error("Ask request 不是对象")
        fun text(key: String): String = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Ask 缺少字符串 $key")
        fun flag(key: String): Boolean = (obj[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: error("Ask 缺少布尔值 $key")
        require(text("sessionId") == sessionId) { "Ask sessionId 不匹配" }
        val id = text("id"); val tool = text("toolCallId")
        require(id.isNotBlank() && tool.isNotBlank()) { "Ask 身份为空" }
        val options = (obj["options"] as? JsonArray ?: error("Ask options 不是数组")).map {
            val option = it as? JsonObject ?: error("Ask option 不是对象")
            require((option["title"] as? JsonPrimitive)?.isString == true) { "Ask option 缺少 title" }
            option["description"]?.takeUnless { it == JsonNull }?.let { description ->
                require((description as? JsonPrimitive)?.isString == true) { "Ask option description 无效" }
            }
            option["recommended"]?.takeUnless { it == JsonNull }?.let { recommended ->
                require(recommended == JsonPrimitive(true) || recommended == JsonPrimitive(false)) { "Ask option recommended 无效" }
            }
            option
        }
        val deadline = obj["countdownDeadline"]?.takeUnless { it == JsonNull }?.let {
            val primitive = it as? JsonPrimitive ?: error("倒计时无效")
            require(!primitive.isString) { "倒计时不是数字" }
            val number = primitive.doubleOrNull ?: error("倒计时无效")
            require(number.isFinite()) { "倒计时无效" }
            number.toLong()
        }
        return RemoteAskRequest(id, sessionId, tool, text("question"), options, flag("allowCustom"),
            flag("allowSkip"), (obj["customPrompt"] as? JsonPrimitive)?.contentOrNull, deadline)
    }
}

/** Minimal test surface; lifecycle stop/disposal stops polling and cancels pending work. */
@Composable
fun RemoteAskPanel(controller: RemoteAskController, manageLifecycle: Boolean = true) {
    if (manageLifecycle) LifecycleStartEffect(controller) {
        controller.start()
        onStopOrDispose { controller.close() }
    }
    val state by controller.state.collectAsStateWithLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Button(onClick = controller::refresh, enabled = state.active && !state.busy) { Text(stringResource(R.string.ask_refresh_verify)) }
        state.lookupError?.let { Text(stringResource(it)) }
        state.actionMessage?.let { Text(stringResource(it)) }
        state.lastToolCallId?.let { Text(stringResource(R.string.verify_tool_call, it)) }
        state.resolved?.let { Text(stringResource(R.string.ask_resolved_record, it.toString())) }
        if (state.outcomeUnknown) {
            Button(onClick = controller::acknowledgeOutcome, enabled = state.checkedAfterFailure) { Text(stringResource(R.string.ask_acknowledge)) }
        }
        val request = state.request
        if (request == null) {
            Text(if (state.loading) stringResource(R.string.ask_querying) else if (state.lookupError != null) stringResource(R.string.ask_unknown_status) else stringResource(R.string.ask_none))
        } else {
            Text(request.question)
            val enabled = state.active && state.snapshotFresh && !state.busy && !state.outcomeUnknown
            if (!state.snapshotFresh) Text(stringResource(R.string.ask_wait_fresh))
            request.options.forEachIndexed { index, option ->
                Button(onClick = { controller.select(index) }, enabled = enabled) {
                    Text("${index + 1}. ${option["title"]?.jsonPrimitive?.content.orEmpty()}")
                }
                option["description"]?.takeUnless { it == JsonNull }?.let { Text(it.jsonPrimitive.content) }
                if (option["recommended"] == JsonPrimitive(true)) Text(stringResource(R.string.recommended))
            }
            request.countdownDeadline?.let { deadline ->
                Text(stringResource(R.string.ask_countdown, ((deadline - state.nowMillis).coerceAtLeast(0) + 999) / 1000))
                Button(onClick = controller::cancelCountdown, enabled = enabled) { Text(stringResource(R.string.stop_countdown)) }
            }
            if (request.allowCustom) {
                OutlinedTextField(value = state.customText, onValueChange = controller::setCustomText,
                    enabled = !state.busy || state.cancellingCountdown, label = { Text(request.customPrompt ?: stringResource(R.string.custom_answer)) })
                Button(onClick = controller::submitCustom, enabled = enabled && state.customText.isNotBlank()) { Text(stringResource(R.string.submit_custom_answer)) }
            }
            if (request.allowSkip) Button(onClick = controller::skip, enabled = enabled) { Text(stringResource(R.string.skip)) }
        }
    }
}

private class AskValidationException(@param:StringRes val messageResource: Int) : IllegalArgumentException("Ask validation failed")
private fun requireAsk(condition: Boolean, @StringRes message: Int) {
    if (!condition) throw AskValidationException(message)
}
