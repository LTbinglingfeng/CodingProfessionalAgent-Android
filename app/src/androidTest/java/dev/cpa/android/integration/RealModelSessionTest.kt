package dev.cpa.android.integration

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
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
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/**
 * Deliberately not a fixture test. Provision an isolated desktop with its model configured THERE,
 * pre-grant Android's local-network permission, and push {origin,password,runId} to the target
 * app's files/real-model-test.json. Opt in with instrumentation argument CPA_REAL_MODEL=isolated.
 * No model key belongs on Android. No shell, tool, project, or file-operation prompt is sent.
 *
 * The extra real socket is read-only: it observes run identities and reads authoritative
 * terminal reasons (the repository projection drops them). Aborted is not always relayed as
 * a stream event by the desktop local cleanup path, so stopping requires an aborted snapshot. It never sends
 * delegateRun/abortRun. Only the real Activity's two Send clicks can start runs; no retries.
 *
 * Limitations: isolation/model provenance must be established by the desktop operator. Two user
 * entries plus two observed run identities detect replay, but are not a server RPC audit log.
 * moveToState exercises real Activity + delayed ProcessLifecycleOwner background/foreground,
 * not process death, Activity recreation, or a network fault DURING an in-flight send/stop.
 * Word/line bounds are prompt instructions, not a server-side token/billing cap.
 * A fast second completion is an inconclusive stop test and therefore FAILS, never passes.
 * The desktop session is intentionally retained for inspection; only our local connection and
 * the one-shot config are removed. Evidence contains booleans/counts only, never model content.
 */
@RunWith(AndroidJUnit4::class)
class RealModelSessionTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    // Guard OUTSIDE the Activity rule: a normal suite neither launches MainActivity nor reads config.
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Requires an explicitly isolated real-model desktop",
                    InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL") == "isolated")
                try {
                    try {
                        base.evaluate()
                    } finally {
                        // Also consume the one-shot config if Activity launch/rule setup fails.
                        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                            "real-model-test.json")
                        check(!file.exists() || file.delete())
                    }
                } catch (_: Throwable) {
                    // Compose failures can dump semantics (including credentials/model text).
                    // Never retain the original exception as a cause or suppressed exception.
                    throw AssertionError("Real-model E2E failed; inspect sanitized evidence only")
                }
            }
        }
    }

    @get:Rule val rules: TestRule = RuleChain.outerRule(optIn).around(compose)

    private var allowSpawnTool = false
    private var allowAskTool = false
    private var allowReadTool = false

    @Test fun realChatStopAndLifecycleWithoutReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configFile = File(context.filesDir, "real-model-test.json")
        val evidenceFile = File(context.filesDir, "real-model-test-evidence.json")
        var repository: RemoteDesktopRepository? = null
        var connectionId: String? = null
        var connectionName: String? = null
        var observer: DesktopRpcClient? = null
        var observerJob: Job? = null
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queueMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_QUEUE") == "true"
        var queueConfirmed = false
        val steerMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_STEER") == "true"
        var steerConfirmed = false
        val followUpStatus = if (steerMode) "steer" else "queue"
        val followUpMarker = if (steerMode) "ANDROID_STEER_CONFIRMED" else "ANDROID_QUEUE_CONFIRMED"
        val queuedPrompt = "Reply with exactly $followUpMarker. No tools, files, commands, web or subagents."
        val askMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_ASK") == "true"
        val fileMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_FILE") == "true"
        allowAskTool = askMode
        allowReadTool = fileMode
        val subagentMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_SUBAGENT") == "true"
        allowSpawnTool = subagentMode
        var subagentConfirmed = false
        val trace = Trace(askMode, fileMode, subagentMode)
        var fileUri: android.net.Uri? = null
        val editMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_EDIT") == "true"
        val unreadMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_UNREAD") == "true"
        var unreadConfirmed = false
        var baselineTime: Long? = null
        var clickTime: Long? = null
        var clientTime: Long? = null
        var finalTime: Long? = null
        val workspaceMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_WORKSPACE") == "true"
        var workspaceConfirmed = false
        val gitMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_GIT") == "true"
        val forkMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_FORK") == "true"
        val boundaryMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_FORK_BOUNDARY") == "true"
        val worktreeMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_WORKTREE") == "true"
        var worktreeConfirmed = false
        var forkBoundaryConfirmed = false
        var forkConfirmed = false
        var forkSourceUnchanged = false
        var forkSourceId: String? = null
        var forkSourceEntries = emptyList<JsonObject>()
        var gitWorkingConfirmed = false
        var gitBranchConfirmed = false
        var gitPreviewConfirmed = false
        var gitTreeConfirmed = false
        var editConfirmed = false
        var editClicks = 0
        var fileBytesConfirmed = false
        var fileModelConfirmed = false
        var askPresented = false
        var askCountdownCancelled = false
        var askCustomConfirmed = false
        val newThreadMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_NEW_THREAD") == "true"
        val resumeMode = InstrumentationRegistry.getArguments().getString("CPA_REAL_MODEL_RESUME") == "true"
        var resumeConfirmed = false
        var resumeClicks = 0
        var newThreadBackClean = false
        var passed = false
        var assistantCompleted = false
        var stopConfirmed = false
        var backgroundObserved = false
        var reconnected = false
        var cleanupOk = true
        var sendClicks = 0
        var finalUsers = 0
        var finalRuns = 0
        var phase = "config"
        var failureType = ""
        var failureLine = 0
        try {
            // Deliberately strict schema: reject accidental provisioning of a model API key.
            require(configFile.isFile && configFile.length() in 1..8192)
            val config = Json.parseToJsonElement(configFile.readText()).jsonObject
            require(config.keys == (if (askMode || fileMode || gitMode || worktreeMode || subagentMode || workspaceMode) setOf("origin", "password", "runId", "projectId") else setOf("origin", "password", "runId")))
            val testProjectId = if (askMode || fileMode || gitMode || worktreeMode || subagentMode || workspaceMode) requireNotNull(config.string("projectId")) else null
            val origin = config.string("origin") ?: error("Invalid config")
            val password = config.string("password") ?: error("Invalid config")
            val runId = config.string("runId") ?: error("Invalid config")
            require(password.isNotBlank() && Regex("[A-Za-z0-9_-]{1,64}").matches(runId))
            DirectTransport.validateOrigin(origin)
            require(context.hasLocalNetworkPermission())
            val remote = (context.applicationContext as CpaApplication).remote
            repository = remote
            val name = "real-model-$runId"
            compose.waitForIdle()
            require(remote.connections.value.none { it.name == name })

            phase = "observer-connect"
            val client = DesktopRpcClient(DirectTransport(origin))
            observer = client
            observerJob = observerScope.launch {
                try { client.events.collect { event ->
                    val payload = event.payload as? JsonObject
                    if (unreadMode && event.kind == "session:meta-updated" && payload != null) trace.trackMetadata(payload)
                    if (event.kind == "session:stream-event" && payload != null) trace.accept(payload)
                } } catch (_: Throwable) { trace.failed() }
            }
            rpc { client.connect(password, 15_000) }

            phase = "save-connection-ui"
            compose.onNodeWithTag("home-settings").performClick()
            compose.onNodeWithTag("settings-add-connection").performScrollTo().performClick()
            compose.onNodeWithText("直连桌面端").performClick()
            compose.onNodeWithContentDescription("显示名称").performTextInput(name)
            compose.onNodeWithContentDescription("主机").performTextReplacement(origin)
            compose.onNodeWithContentDescription("密码").performTextInput(password)
            // Explicit HTTP-risk consent is required before an http:// desktop can be saved.
            if (origin.startsWith("http://", ignoreCase = true)) compose.onNodeWithTag("connection-cleartext").performScrollTo().performClick()
            connectionName = name
            compose.onNodeWithTag("connection-save").assertIsEnabled().performClick()
            await(15_000) { remote.connections.value.count { it.name == name } == 1 }
            val id = remote.connections.value.single { it.name == name }.id
            connectionId = id
            // Saving currently auto-connects. Disconnect ONLY our connection, then exercise the settings switch.
            rpc { remote.disconnect(id) }
            compose.onNodeWithTag(dev.cpa.android.feature.remote.homeKey("connection-toggle", id)).performScrollTo().assertIsOff().performClick()
            await(20_000) { remote.connections.value.any { it.id == id && it.phase == ConnectionPhase.Connected } }
            compose.onNodeWithTag("settings-close").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("settings-close").fetchSemanticsNodes().isEmpty() }
            if (workspaceMode) {
                phase = "workspace-setup"
                val projectId = requireNotNull(testProjectId)
                await(10_000) { remote.projects.value[id].orEmpty().any { it.id == projectId } }
                val project = remote.projects.value[id].orEmpty().single { it.id == projectId }
                val root = requireNotNull(project.path)
                require(root.isNotBlank())
                require(rpc { remote.readRemoteFile(id, "$root/keep.txt") } == "workspace preservation sentinel\n")
                val activeSession = rpc { remote.createSession(id, projectId, "Workspace active") }
                val archivedSession = rpc { remote.createSession(id, projectId, "Workspace archived") }
                rpc { remote.archiveSession(id, archivedSession) }
                rpc { remote.refresh(id) }
                fun projectsSnapshot() = rpc { client.call("kvstore:get", listOf(JsonPrimitive("projects"))) }.jsonArray
                val initialProjects = projectsSnapshot()
                fun editProject() {
                    val header = hasTestTag(dev.cpa.android.feature.remote.homeKey("group", id, projectId))
                    compose.onNodeWithTag("home-list").performScrollToNode(header)
                    compose.onNode(header).performTouchInput { longClick() }
                    compose.onNodeWithText("编辑项目").performClick()
                }
                phase = "workspace-directory"
                editProject()
                compose.onNodeWithTag("project-add-folder").performScrollTo().performClick()
                compose.onNodeWithTag("directory-menu").performClick()
                compose.onNodeWithText("前往路径…").performClick()
                compose.onNodeWithContentDescription("/Users/name/Projects").performTextReplacement(root)
                compose.onNodeWithText("前往", substring = false).performClick()
                compose.waitUntil(10_000) { compose.onAllNodes(hasText(root) and hasAnyAncestor(hasTestTag("desktop-directory-list"))).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("desktop-directory-list").performScrollToNode(hasText("source-folder"))
                compose.onNodeWithText("source-folder").assertIsDisplayed()
                compose.onNodeWithText(".hidden-folder").assertDoesNotExist()
                compose.onNodeWithTag("directory-menu").performClick()
                compose.onNodeWithText("显示隐藏文件夹").performClick()
                compose.onNodeWithTag("desktop-directory-list").performScrollToNode(hasText(".hidden-folder"))
                compose.onNodeWithText(".hidden-folder").assertExists()
                compose.onNodeWithTag("desktop-directory-list").performScrollToNode(hasText("source-folder"))
                compose.onNodeWithText("source-folder").performClick()
                compose.waitUntil(10_000) { compose.onAllNodes(hasText("$root/source-folder")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("directory-select").assertIsEnabled().performClick()
                val newName = "Workspace renamed $runId"
                compose.onNodeWithContentDescription("项目名称").performTextReplacement(newName)
                compose.onNodeWithTag("project-save").performClick()
                await(10_000) { remote.projects.value[id].orEmpty().any { it.id == projectId && it.name == newName } }
                phase = "workspace-save-authority"
                val saved = projectsSnapshot().map { it.jsonObject }.single { it.string("id") == projectId }
                require(saved.string("name") == newName)
                require(saved["paths"]!!.jsonArray.map { it.jsonPrimitive.content } == listOf(root, "$root/source-folder"))
                require(saved.string("path") == root)
                require(projectsSnapshot().filter { it.jsonObject.string("id") != projectId } == initialProjects.filter { it.jsonObject.string("id") != projectId })
                phase = "workspace-remove"
                editProject()
                compose.onNodeWithText("移除项目", substring = false).performScrollTo().performClick()
                compose.onAllNodesWithText("取消").onLast().performClick()
                require(projectsSnapshot().any { it.jsonObject.string("id") == projectId })
                compose.onNodeWithText("移除项目", substring = false).performScrollTo().performClick()
                compose.onAllNodesWithText("移除项目", substring = false).onLast().performClick()
                await(10_000) { remote.projects.value[id].orEmpty().none { it.id == projectId } }
                require(projectsSnapshot().none { it.jsonObject.string("id") == projectId })
                val sessions = rpc { client.call("session:listSessions") }.jsonArray.map { it.jsonObject }
                for (sessionId in listOf(activeSession, archivedSession)) {
                    val retained = sessions.single { it.string("id") == sessionId }
                    require(retained["projectId"] == null || retained["projectId"] == JsonNull)
                    require(snapshot(client, sessionId, SystemClock.elapsedRealtime() + 10_000).isEmpty())
                }
                require(sessions.single { it.string("id") == archivedSession }["archivedAt"]?.let { it != JsonNull } == true)
                require(rpc { remote.readRemoteFile(id, "$root/keep.txt") } == "workspace preservation sentinel\n")
                require(rpc { remote.directoryEntries(id, root) }.filter { it.isDirectory }.map { it.name }.toSet().containsAll(setOf("source-folder", ".hidden-folder")))
                require(trace.runCount() == 0)
                workspaceConfirmed = true
                phase = "complete"
                passed = true
                return
            }
            val fileToken = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            val fileName = "android-verification-$runId.txt"
            val fileContent = "Verification token: $fileToken\n"
            var first = if (subagentMode) "This is an integration test of the sub-agent tool and its mobile reader. " +
                "Call spawn_agent exactly once, named AndroidChild, using the sole available model from the catalog. " +
                "Its prompt must be: 'This is a pure chat verification. Reply with exactly ANDROID_CHILD_CONFIRMED. " +
                "Do not call tools, spawn agents, access files, run commands, or use the web.' " +
                "After it finishes reply with exactly ANDROID_PARENT_CONFIRMED. Only spawn_agent and title are allowed. " +
                "Do not use any other tools, files, commands or web. Reference: $runId." else if (fileMode) "Read only the attached text file using the read tool. Reply with exactly its verification token. " +
                "Do not use other tools except title; do not read other files, run commands, use web or subagents." else if (askMode) "This is a controlled UI interaction test. Call the ask tool exactly once now with question 'Android verification choice', " +
                "options [{title:'Alpha',recommended:true},{title:'Beta'}], allowCustom true, customPrompt 'Android test answer', allowSkip false. " +
                "Wait for my response, then acknowledge it in at most 12 words. Do not call any other tools except title. Do not use files, commands, web or subagents. Reference: $runId." else "Pure chat only. Do not use any tools, files, commands, web, or subagents. " +
                "Reply with one short friendly greeting, at most 12 words. Reference: $runId."
            var session = if (newThreadMode) {
                phase = "home-prefill-new-thread"
                val before = remote.sessions.value[id].orEmpty().map { it.id }.toSet()
                compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("home-composer"))).performTextReplacement(first)
                compose.onNodeWithTag("composer-send").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("new-thread-composer").fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("new-thread-composer"))).assertTextContains(first)
                require(remote.sessions.value[id].orEmpty().map { it.id }.toSet() == before)
                require(trace.runCount() == 0)
                phase = "new-thread-create-send"
                compose.onNodeWithTag("composer-send").assertIsEnabled().performClick()
                sendClicks++
                await(20_000) { remote.sessions.value[id].orEmpty().count { it.id !in before } == 1 }
                remote.sessions.value.getValue(id).single { it.id !in before }
            } else {
                phase = "prepare-empty-session"
                // The current new-thread UI creates and sends together. Prepare an empty session
                // through production repository for the independent thread recovery variant.
                val preparedId = rpc { remote.createSession(id, testProjectId, "Prepared-$runId") }
                await(15_000) { remote.sessions.value[id].orEmpty().any { it.id == preparedId } }
                val prepared = remote.sessions.value.getValue(id).single { it.id == preparedId }
                val preparedRow = hasTestTag(dev.cpa.android.feature.remote.homeKey("open", id, preparedId))
                compose.onNodeWithTag("home-list").performScrollToNode(preparedRow)
                compose.onNode(preparedRow).performClick()
                prepared
            }
            // A home-prefilled chat opens on the desktop's pinned (else first) project, as on iOS.
            val expectedProject = if (newThreadMode) remote.projects.value[id].orEmpty().let { list -> (list.firstOrNull { it.pinned } ?: list.firstOrNull())?.id } else testProjectId
            require(session.projectId == expectedProject)
            trace.session(session.id)
            var thread = remote.observeThread(id, session.id)
            await(15_000) { !thread.value.loading && thread.value.error == null }
            await(15_000) { compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("thread-composer"))).fetchSemanticsNodes().size == 1 }
            if (gitMode) {
                phase = "real-git-working-tree"
                val project = remote.projects.value[id].orEmpty().single { it.id == testProjectId }
                val path = project.allPaths.first()
                val working = rpc { remote.gitReview(id, path, null, null) }
                require(working.workingTree && working.files.size == 3)
                require(working.files.any { it.path == "tracked.txt" && it.additions == 1 })
                require(working.files.any { it.path == "renamed name.txt" && it.status == "renamed" })
                require(working.files.any { it.path == "未跟踪.txt" && it.status == "untracked" })
                compose.onNodeWithTag("thread-more").performClick()
                compose.onNodeWithText("更改").performClick()
                await(15_000) { compose.onAllNodesWithText("tracked.txt", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("open:tracked.txt").performScrollTo().performClick()
                await(10_000) { compose.onAllNodesWithText("working addition").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("preview-copy").performClick()
                compose.runOnIdle {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    require(clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "$path/tracked.txt")
                }
                compose.onNodeWithTag("preview-close").performClick()
                gitPreviewConfirmed = true
                gitWorkingConfirmed = true
                phase = "real-git-branch"
                val branch = rpc { remote.gitReview(id, path, "main", "feature") }
                require(!branch.workingTree && branch.comparison == "origin/main...feature")
                require(branch.files.single().path == "branch.txt" && branch.files.single().additions == 1)
                compose.onNodeWithTag("panel-base").performClick()
                compose.onAllNodesWithText("origin/main").onLast().performClick()
                await(15_000) { compose.onAllNodesWithText("branch.txt", substring = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("branch addition", substring = true).assertExists()
                gitBranchConfirmed = true
                compose.onNodeWithTag("panel-close").performClick()
                phase = "real-file-tree"
                compose.onNodeWithTag("thread-more").performClick()
                compose.onNodeWithText("文件").performClick()
                val nestedDir = hasTestTag("file:$path/nested")
                await(10_000) { compose.onAllNodes(nestedDir).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(nestedDir).performClick()
                await(10_000) { compose.onAllNodesWithText("nested.txt").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("搜索文件").performTextInput("nested")
                compose.onNodeWithText("tracked.txt").assertDoesNotExist()
                compose.onNodeWithText("nested.txt").performScrollTo().performClick()
                await(10_000) { compose.onAllNodesWithText("nested preview content").fetchSemanticsNodes().isNotEmpty() }
                gitTreeConfirmed = true
                require(trace.runCount() == 0 && thread.value.entries.isEmpty())
                phase = "complete"
                passed = true
                return
            }
            phase = "first-model-response"
            val responseDeadline = SystemClock.elapsedRealtime() + 90_000
            if (!newThreadMode) {
                rpc { remote.openThread(id, session.id) }
                require(thread.value.entries.isEmpty() && !thread.value.running)
                if (fileMode) {
                    phase = "pick-and-upload-file"
                    fileUri = pickTestDocument(fileName, fileContent.toByteArray())
                    val info = rpc { client.call("native:runtimeInfo") }.jsonObject
                    val home = requireNotNull(info.string("homeDir"))
                    val configDir = requireNotNull(info.string("appConfigDirName"))
                    val uploaded = "$home/$configDir/attachments/${session.id}/$fileName"
                    send(first)
                    first = dev.cpa.android.core.attachments.attachmentPrompt(first, listOf(uploaded))
                    awaitUntil(responseDeadline) {
                        val exists = rpc { client.call("native:fileExists", listOf(JsonPrimitive(uploaded))) } as? JsonPrimitive
                        exists?.booleanOrNull == true
                    }
                    val result = rpc { client.call("native:readFile", listOf(JsonPrimitive(uploaded))) }.jsonObject
                    require(java.util.Base64.getDecoder().decode(requireNotNull(result.string("dataBase64"))).contentEquals(fileContent.toByteArray()))
                    fileBytesConfirmed = true
                } else send(first)
                sendClicks++
            }
            if (askMode) {
                phase = "ask-auto-present"
                var request: JsonObject? = null
                awaitUntil(responseDeadline) {
                    val result = rpc { client.call("ask:getRequests", listOf(buildJsonObject { put("sessionId", session.id) })) }.jsonObject
                    request = result["request"] as? JsonObject
                    request != null && compose.onAllNodesWithText("Android verification choice").fetchSemanticsNodes().isNotEmpty()
                }
                val toolCall = requireNotNull(request?.string("toolCallId"))
                require(request?.get("countdownDeadline") is JsonPrimitive)
                compose.onNodeWithTag("ask-custom").performScrollTo().assertIsDisplayed()
                askPresented = true
                phase = "ask-type-cancels-countdown"
                val answer = "Android confirmed $runId"
                compose.onNodeWithTag("ask-custom").performTextInput(answer)
                awaitUntil(responseDeadline) {
                    val result = rpc { client.call("ask:getRequests", listOf(buildJsonObject { put("sessionId", session.id); put("toolCallId", toolCall) })) }.jsonObject
                    val pending = result["request"] as? JsonObject
                    pending?.string("toolCallId") == toolCall && (pending["countdownDeadline"] == null || pending["countdownDeadline"] == JsonNull)
                }
                askCountdownCancelled = true
                if (queueMode) {
                    phase = "$followUpStatus-during-ask"
                    compose.onNodeWithTag("ask-later").performClick()
                    // The isolated desktop selects the mode and confirms it via follow-up-pending.
                    require(thread.value.running && trace.runCount() == 1)
                    send(queuedPrompt)
                    sendClicks++
                    await(10_000) { thread.value.entries.any { it.kind == "user" && it.text == queuedPrompt && it.pendingStatus == followUpStatus } }
                    var queued = emptyList<JsonObject>()
                    val savedDeadline = SystemClock.elapsedRealtime() + 10_000
                    awaitUntil(savedDeadline) {
                        queued = snapshot(client, session.id, savedDeadline)
                        queued.count { it.string("kind") == "user" } == 2
                    }
                    verifyUsers(queued, listOf(first, queuedPrompt))
                    val pendingId = thread.value.entries.single { it.kind == "user" && it.text == queuedPrompt && it.pendingStatus == followUpStatus }.id
                    require(queued.any { it.string("id") == pendingId && it.string("kind") == "user" })
                    require(trace.runCount() == 1 && thread.value.running && !thread.value.outcomeUnknown)
                    compose.onNodeWithTag("ask-banner").performClick()
                    compose.onNodeWithTag("ask-custom").assertTextContains(answer)
                }
                phase = "ask-submit-custom"
                compose.onNodeWithTag("ask-send").performScrollTo().assertIsEnabled().performClick()
                awaitUntil(responseDeadline) {
                    val result = rpc { client.call("ask:getRequests", listOf(buildJsonObject { put("sessionId", session.id); put("toolCallId", toolCall) })) }.jsonObject
                    val decision = (result["resolved"] as? JsonObject)?.get("decision") as? JsonObject
                    (result["request"] == null || result["request"] == JsonNull) && decision?.string("type") == "custom" && decision.string("text") == answer
                }
                askCustomConfirmed = true
                phase = "ask-model-continues"
            }
            if (queueMode) {
                phase = "$followUpStatus-execution"
                val deadline = SystemClock.elapsedRealtime() + 90_000
                var completed = emptyList<JsonObject>()
                awaitUntil(deadline) {
                    completed = snapshot(client, session.id, deadline)
                    !thread.value.running && trace.runCount() == (if (steerMode) 1 else 2) && trace.secondEnded() &&
                        completed.lastOrNull { it.string("kind") == "assistant" }?.let { entry ->
                            entry.string("status") == "done" && (entry["content"] as? JsonArray).orEmpty().any {
                                (it as? JsonObject)?.string("text")?.contains(followUpMarker) == true
                            }
                        } == true
                }
                await(10_000) {
                    val users = thread.value.entries.filter { it.kind == "user" }
                    users.size == 2 && users.all { it.pendingStatus == null }
                }
                verifyUsers(completed, listOf(first, queuedPrompt))
                require(completed.filter { it.string("kind") == "user" }.all { it["pendingStatus"] == null || it["pendingStatus"] == JsonNull })
                require(!trace.unhealthy() && !thread.value.outcomeUnknown && thread.value.error == null)
                rpc { remote.openThread(id, session.id) }
                verifyUsers(snapshot(client, session.id, SystemClock.elapsedRealtime() + 10_000), listOf(first, queuedPrompt))
                finalUsers = 2
                finalRuns = trace.runCount()
                assistantCompleted = true
                queueConfirmed = !steerMode
                steerConfirmed = steerMode
                phase = "complete"
                passed = true
                return
            }
            awaitUntil(responseDeadline) {
                !thread.value.running && thread.value.entries.any {
                    it.kind == "assistant" && it.text.isNotBlank()
                } && trace.runCount() == 1
            }
            var firstSnapshot = emptyList<JsonObject>()
            awaitUntil(responseDeadline) {
                firstSnapshot = snapshot(client, session.id, responseDeadline)
                firstSnapshot.any { it.string("kind") == "assistant" &&
                    it.string("status") == "done" && nonblankText(it) }
            }
            verifyUsers(firstSnapshot, listOf(first))
            if (fileMode) {
                require(trace.readObserved())
                require(firstSnapshot.filter { it.string("kind") == "assistant" }.any { entry ->
                    (entry["content"] as? JsonArray).orEmpty().any { block ->
                        (block as? JsonObject)?.takeIf { it.string("type") == "text" }?.string("text")?.contains(fileToken) == true
                    }
                })
                compose.onNodeWithText(fileName).assertDoesNotExist()
                fileModelConfirmed = true
            }
            require(thread.value.error == null && !thread.value.outcomeUnknown)
            assistantCompleted = true
            if (subagentMode) {
                phase = "subagent-record"
                await(15_000) { trace.secondEnded() && thread.value.subAgents.size == 1 && thread.value.subAgents.single().status == "completed" }
                require(trace.spawnObserved() && !trace.unhealthy())
                val agent = thread.value.subAgents.single()
                require(agent.parentSessionId == session.id && agent.conversationId != session.id)
                val childEntries = snapshot(client, agent.conversationId, SystemClock.elapsedRealtime() + 10_000)
                require(childEntries.any { entry -> entry.string("kind") == "assistant" && entry.string("status") == "done" &&
                    (entry["content"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.string("text")?.contains("ANDROID_CHILD_CONFIRMED") == true } })
                phase = "subagent-reader-ui"
                // Expand the finished turn so its sub-agent badge is visible, then open the read-only child.
                compose.onNodeWithTag("remote-timeline").performScrollToNode(hasTestTag("turn-header"))
                compose.onNodeWithTag("turn-header").performClick()
                compose.onNodeWithTag("remote-timeline").performScrollToNode(hasTestTag("agent-pill:${agent.id}"))
                compose.onNodeWithTag("agent-pill:${agent.id}").performClick()
                compose.waitForIdle()
                await(15_000) { !remote.observeThread(id, agent.conversationId).value.loading &&
                    remote.observeThread(id, agent.conversationId).value.entries.any { it.text.contains("ANDROID_CHILD_CONFIRMED") && it.kind == "assistant" } }
                compose.onNodeWithTag("subagent-back").assertIsDisplayed()
                compose.onNodeWithTag("thread-composer").assertDoesNotExist()
                // The child prompt also quotes the marker; the reply is the exact marker text.
                compose.onNodeWithTag("remote-timeline").performScrollToNode(hasText("ANDROID_CHILD_CONFIRMED"))
                compose.onNode(hasText("ANDROID_CHILD_CONFIRMED")).assertIsDisplayed()
                require(remote.observeThread(id, agent.conversationId).value.subAgents.isEmpty())
                compose.onNodeWithTag("subagent-back").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("thread-composer").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("subagent-back").assertDoesNotExist()
                require(snapshot(client, agent.conversationId, SystemClock.elapsedRealtime() + 10_000) == childEntries)
                finalUsers = snapshot(client, session.id, SystemClock.elapsedRealtime() + 10_000).count { it.string("kind") == "user" }
                finalRuns = trace.runCount()
                require(finalUsers == 1 && finalRuns == 1)
                subagentConfirmed = true
                phase = "complete"
                passed = true
                return
            }
            if (forkMode) {
                phase = "ordinary-fork-ui"
                // A done assistant can precede the final agent-end/usage snapshot.
                await(10_000) { trace.secondEnded() }
                firstSnapshot = snapshot(client, session.id, SystemClock.elapsedRealtime() + 10_000)
                forkSourceId = session.id
                forkSourceEntries = firstSnapshot
                rpc { remote.refresh(id) }
                val sourceMeta = remote.sessions.value.getValue(id).single { it.id == session.id }
                val beforeIds = remote.sessions.value[id].orEmpty().map { it.id }.toSet()
                val expectedCopy = if (worktreeMode) {
                    // iOS parity: worktree continuation lives in the home row menu and copies the whole chat.
                    compose.onNodeWithTag("thread-back").performClick()
                    val sourceRow = hasTestTag(dev.cpa.android.feature.remote.homeKey("open", id, session.id))
                    compose.onNodeWithTag("home-list").performScrollToNode(sourceRow)
                    compose.onNode(sourceRow).performTouchInput { longClick() }
                    compose.onNodeWithText("在新工作树中继续").performClick()
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("分支").fetchSemanticsNodes().isNotEmpty() }
                    compose.waitUntil(10_000) { compose.onAllNodesWithText("feature").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("分支").performClick()
                    compose.onNodeWithText("main").performClick()
                    compose.onNodeWithTag("worktree-continue").assertIsEnabled().performClick()
                    firstSnapshot
                } else {
                    // Message fork (desktop onForkMessage): copies through the assistant reply it is attached to.
                    val boundary = firstSnapshot.indexOfLast { it.string("kind") == "assistant" }
                    require(boundary >= 0)
                    compose.onNodeWithTag("remote-timeline").performScrollToNode(hasContentDescription("fork-here"))
                    compose.onNodeWithContentDescription("fork-here").performClick()
                    firstSnapshot.take(boundary + 1)
                }
                await(15_000) { remote.sessions.value[id].orEmpty().count { it.id !in beforeIds } == 1 }
                phase = "fork-copy-check"
                val forked = remote.sessions.value.getValue(id).single { it.id !in beforeIds }
                require(forked.id != session.id && forked.modelId == sourceMeta.modelId && forked.projectId == sourceMeta.projectId)
                if (worktreeMode) require(forked.workLocation == "worktree" && forked.worktreePath == null && forked.branch == "main")
                val copied = snapshot(client, forked.id, SystemClock.elapsedRealtime() + 10_000)
                require(copied.size == expectedCopy.size)
                fun withoutIdentity(entries: List<JsonObject>) = entries.map { JsonObject(it - "id" - "sessionId") }
                require(withoutIdentity(copied) == withoutIdentity(expectedCopy))
                val oldIds = firstSnapshot.mapNotNull { it.string("id") }.toSet()
                require(copied.mapNotNull { it.string("id") }.toSet().size == copied.size)
                require(copied.none { it.string("id") in oldIds } && copied.all { it.string("sessionId") == forked.id })
                require(snapshot(client, session.id, SystemClock.elapsedRealtime() + 10_000) == firstSnapshot)
                phase = "fork-target-navigation"
                compose.waitForIdle()
                session = forked
                thread = remote.observeThread(id, session.id)
                trace.session(session.id)
                await(15_000) { !thread.value.loading && thread.value.entries.count { it.kind == "user" } == 1 }
                await(15_000) { compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("thread-composer"))).fetchSemanticsNodes().size == 1 }
                require(trace.runCount() == 1)
                forkBoundaryConfirmed = boundaryMode && copied.size <= firstSnapshot.size && copied.last().string("kind") == "assistant"
                forkConfirmed = true
            }
            if (editMode) {
                phase = "edit-completed-message-ui"
                val originalUserId = firstSnapshot.single { it.string("kind") == "user" }.string("id")
                val originalAnswers = firstSnapshot.filter { it.string("kind") == "assistant" }.mapNotNull { it.string("id") }.toSet()
                val replacement = "Pure chat only. No tools except title, no files, commands, web or subagents. " +
                    "Reply with exactly ANDROID_EDIT_CONFIRMED. Reference: $runId."
                val bubble = hasTestTag("bubble:$originalUserId")
                compose.onNodeWithTag("remote-timeline").performScrollToNode(bubble)
                compose.onNode(bubble).performTouchInput { longClick() }
                compose.onNodeWithText("编辑").performClick()
                compose.onNodeWithContentDescription("编辑历史消息").performTextReplacement(replacement)
                compose.onNodeWithTag("edit-send").assertIsEnabled().performClick()
                editClicks++
                val editDeadline = SystemClock.elapsedRealtime() + 90_000
                awaitUntil(editDeadline) { trace.runCount() == 2 && !thread.value.editAwaitingStart }
                var edited = emptyList<JsonObject>()
                awaitUntil(editDeadline) {
                    edited = snapshot(client, session.id, editDeadline)
                    val last = edited.lastOrNull { it.string("kind") == "assistant" }
                    !thread.value.running && last?.string("status") == "done" && nonblankText(last)
                }
                verifyUsers(edited, listOf(replacement))
                require(edited.single { it.string("kind") == "user" }.string("id") == originalUserId)
                require(edited.none { it.string("id") in originalAnswers })
                require(edited.filter { it.string("kind") == "assistant" }.any { entry ->
                    (entry["content"] as? JsonArray).orEmpty().any { block ->
                        (block as? JsonObject)?.takeIf { it.string("type") == "text" }?.string("text")?.contains("ANDROID_EDIT_CONFIRMED") == true
                    }
                })
                compose.onNodeWithTag("message-editor").assertDoesNotExist()
                require(!thread.value.outcomeUnknown && thread.value.error == null)
                first = replacement
                editConfirmed = true
            }
            val secondRunCount = if (editMode) 3 else 2

            val second = "Pure chat only. Never use tools, files, commands, web, or subagents. " +
                "Write exactly 80 numbered lines, each a different short sentence about imaginary " +
                "clouds. Maximum 1600 words total. No preamble. Reference: $runId."
            phase = "second-model-stream"
            val secondDeadline = SystemClock.elapsedRealtime() + 90_000
            send(second)
            sendClicks++
            awaitUntil(secondDeadline) { trace.runCount() == secondRunCount && thread.value.running }
            // Require actual model text after the second prompt, not merely agent-start before
            // the provider request begins. A response completed too quickly is inconclusive.
            awaitUntil(secondDeadline) {
                val entries = thread.value.entries
                val lastUser = entries.indexOfLast { it.kind == "user" }
                (entries.count { it.kind == "user" } == 2 && entries.drop(lastUser + 1).any {
                    it.kind == "assistant" && it.text.isNotBlank()
                }) || !thread.value.running
            }
            // UI must be ready to stop the still-running request, not just a cached idle state.
            awaitUntil(secondDeadline) {
                compose.onAllNodes(hasTestTag("composer-stop") and isEnabled()).fetchSemanticsNodes().size == 1 ||
                    !thread.value.running
            }
            require(thread.value.running && !trace.secondEnded())
            if (unreadMode) {
                phase = "unread-leave-running"
                compose.onNodeWithTag("thread-back").performClick()
                compose.waitForIdle()
                require(thread.value.running && !trace.secondEnded())
                phase = "unread-complete-away"
                val deadline = SystemClock.elapsedRealtime() + 120_000
                fun meta() = rpc { client.call("session:listSessions") }.jsonArray.map { it.jsonObject }.single { it.string("id") == session.id }
                awaitUntil(deadline) { !thread.value.running && trace.secondEnded() && meta()["unread"] == JsonPrimitive(true) }
                val unseen = meta()
                baselineTime = unseen["updatedAt"]?.jsonPrimitive?.longOrNull
                val completed = snapshot(client, session.id, deadline)
                require(completed.last { it.string("kind") == "assistant" }.string("status") == "done")
                verifyUsers(completed, listOf(first, second))
                require(remote.sessions.value[id].orEmpty().single { it.id == session.id }.unread == "unread")
                phase = "unread-open-read"
                val row = hasTestTag(dev.cpa.android.feature.remote.homeKey("open", id, session.id))
                compose.onNodeWithTag("home-list").performScrollToNode(row)
                compose.onNode(row).assert(hasContentDescription("，未读", substring = true))
                clickTime = meta()["updatedAt"]?.jsonPrimitive?.longOrNull
                clientTime = remote.sessions.value[id].orEmpty().single { it.id == session.id }.updatedAt
                compose.onNode(row).performClick()
                compose.waitForIdle()
                compose.onNodeWithTag("remote-timeline").assertExists()
                // SQLite maps read (0) to undefined, omitted from the desktop JSON list.
                await(15_000) {
                    "unread" !in meta() &&
                        remote.sessions.value[id].orEmpty().single { it.id == session.id }.unread == "read"
                }
                finalTime = meta()["updatedAt"]?.jsonPrimitive?.longOrNull
                require(finalTime != null && finalTime == baselineTime)
                require(snapshot(client, session.id, SystemClock.elapsedRealtime() + 10_000) == completed)
                require(trace.runCount() == 2 && !thread.value.outcomeUnknown)
                compose.onNodeWithTag("thread-back").performClick()
                compose.onNodeWithTag("home-list").performScrollToNode(row)
                compose.onNode(row).assert(!hasContentDescription("，未读", substring = true))
                finalUsers = 2; finalRuns = 2
                unreadConfirmed = true
                phase = "complete"; passed = true
                return
            }
            phase = "stop-model-ui"
            trace.requestStop(secondRunCount)
            val stopDeadline = SystemClock.elapsedRealtime() + 30_000
            compose.onNodeWithTag("composer-stop").assertIsEnabled().performClick()
            awaitUntil(stopDeadline) { !thread.value.running }
            var stopped = emptyList<JsonObject>()
            awaitUntil(stopDeadline) {
                stopped = snapshot(client, session.id, stopDeadline)
                val lastAssistant = stopped.lastOrNull { it.string("kind") == "assistant" }
                lastAssistant?.string("status") == "aborted" && lastAssistant.string("stopReason") == "aborted"
            }
            verifyUsers(stopped, listOf(first, second))
            require(!thread.value.outcomeUnknown && thread.value.error == null)
            stopConfirmed = true
            if (worktreeMode) {
                phase = "verify-created-worktree"
                rpc { remote.refresh(id) }
                val current = remote.sessions.value.getValue(id).single { it.id == session.id }
                val path = requireNotNull(current.worktreePath)
                val sourcePath = remote.projects.value.getValue(id).single { it.id == testProjectId }.allPaths.first()
                val home = rpc { remote.homeDirectory(id) }
                require(path.startsWith("$home/") && path != sourcePath && current.workLocation == "worktree")
                require(rpc { remote.readRemoteFile(id, "$path/.git") }.contains("$sourcePath/.git/worktrees/"))
                require(rpc { remote.readRemoteFile(id, "$path/tracked.txt") } == "base line\n")
                require(rpc { remote.readRemoteFile(id, "$sourcePath/tracked.txt") } == "base line\nworking addition\n")
                val review = rpc { remote.gitReview(id, path, null, null) }
                require(review.repo.root == path && review.repo.clean && review.files.isEmpty())
                worktreeConfirmed = true
            }

            if (resumeMode) {
                phase = "resume-stopped-ui"
                compose.onNodeWithTag("composer-resume").assertIsEnabled().performClick()
                resumeClicks++
                val resumeDeadline = SystemClock.elapsedRealtime() + 100_000
                awaitUntil(resumeDeadline) { trace.runCount() == 3 }
                var resumed = emptyList<JsonObject>()
                awaitUntil(resumeDeadline) {
                    resumed = snapshot(client, session.id, resumeDeadline)
                    val last = resumed.lastOrNull { it.string("kind") == "assistant" }
                    !thread.value.running && last?.string("status") == "done" && nonblankText(last)
                }
                verifyUsers(resumed, listOf(first, second))
                require(!thread.value.outcomeUnknown && thread.value.error == null)
                compose.onNodeWithTag("composer-resume").assertDoesNotExist()
                resumeConfirmed = true
            }
            val expectedRuns = if (resumeMode) 3 else secondRunCount

            // Real Activity lifecycle, not a fake composition or direct repository lifecycle callback.
            phase = "background-foreground"
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            await(10_000) { remote.connections.value.any { it.id == id && it.phase == ConnectionPhase.Disconnected } }
            backgroundObserved = true
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            await(20_000) { remote.connections.value.any { it.id == id && it.phase == ConnectionPhase.Connected } }
            reconnected = true
            rpc { remote.refresh(id); remote.openThread(id, session.id) }
            // A short stability window plus authoritative reload catches lifecycle-triggered replays.
            val stableUntil = SystemClock.elapsedRealtime() + 2_000
            while (SystemClock.elapsedRealtime() < stableUntil) {
                require(!thread.value.running && trace.runCount() == expectedRuns)
                Thread.sleep(50)
            }
            val finalSnapshot = snapshot(client, session.id, SystemClock.elapsedRealtime() + 10_000)
            verifyUsers(finalSnapshot, listOf(first, second))
            rpc { remote.openThread(id, session.id) }
            finalUsers = thread.value.entries.count { it.kind == "user" }
            finalRuns = trace.runCount()
            require(finalUsers == 2 && finalRuns == expectedRuns && sendClicks == 2)
            require(!thread.value.running && !thread.value.loading && !thread.value.outcomeUnknown)
            require(thread.value.entries.any { it.kind == "assistant" && it.text.isNotBlank() })
            require(!trace.unhealthy())
            if (forkMode) {
                require(snapshot(client, requireNotNull(forkSourceId), SystemClock.elapsedRealtime() + 10_000) == forkSourceEntries)
                forkSourceUnchanged = true
            }
            if (newThreadMode) {
                phase = "new-thread-back-navigation"
                compose.onNodeWithTag("thread-back").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("home-composer").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("new-thread-composer").assertDoesNotExist()
                require(remote.sessions.value[id].orEmpty().count { it.id == session.id } == 1)
                newThreadBackClean = true
            }
            phase = "complete"
            passed = true
        } catch (failure: Throwable) {
            failureType = failure.javaClass.simpleName
            failureLine = failure.stackTrace.firstOrNull { it.className == javaClass.name }?.lineNumber ?: 0
            throw failure
        } finally {
            // Never stop/resend in cleanup: that could disguise a failed stop assertion.
            try { fileUri?.let { context.contentResolver.delete(it, null, null) } }
            catch (_: Throwable) { cleanupOk = false }
            observerJob?.cancel()
            observer?.close()
            observerScope.cancel()
            try {
                val remote = repository
                if (remote != null) {
                    // Also recover ownership if save succeeded just before a UI wait failed.
                    val owned = connectionId ?: connectionName?.let { name ->
                        remote.connections.value.singleOrNull { it.name == name }?.id
                    }
                    if (owned != null) rpc { remote.removeConnection(owned) }
                }
            } catch (_: Throwable) { cleanupOk = false }
            try { if (configFile.exists() && !configFile.delete()) cleanupOk = false }
            catch (_: Throwable) { cleanupOk = false }
            try {
                evidenceFile.writeText(buildJsonObject {
                    put("phase", phase)
                    put("failureType", failureType)
                    put("failureLine", failureLine)
                    put("passed", passed && cleanupOk)
                    put("askPresented", askPresented)
                    put("askCountdownCancelled", askCountdownCancelled)
                    put("askCustomConfirmed", askCustomConfirmed)
                    put("unreadConfirmed", unreadConfirmed)
                    if (unreadMode && baselineTime != null) {
                        val baseline = requireNotNull(baselineTime)
                        clickTime?.let { put("preClickDeltaMs", it - baseline) }
                        clientTime?.let { put("preClickClientDeltaMs", it - baseline) }
                        finalTime?.let { put("postReadDeltaMs", it - baseline) }
                        put("metadataDeltas", trace.metadataDeltas(baseline))
                    }
                    put("workspaceConfirmed", workspaceConfirmed)
                    put("worktreeConfirmed", worktreeConfirmed)
                    put("subagentConfirmed", subagentConfirmed)
                    put("queueConfirmed", queueConfirmed)
                    put("steerConfirmed", steerConfirmed)
                    put("forkBoundaryConfirmed", forkBoundaryConfirmed)
                    put("forkConfirmed", forkConfirmed)
                    put("forkSourceUnchanged", forkSourceUnchanged)
                    put("gitWorkingConfirmed", gitWorkingConfirmed)
                    put("gitBranchConfirmed", gitBranchConfirmed)
                    put("gitPreviewConfirmed", gitPreviewConfirmed)
                    put("gitTreeConfirmed", gitTreeConfirmed)
                    put("editConfirmed", editConfirmed)
                    put("editClicks", editClicks)
                    put("fileBytesConfirmed", fileBytesConfirmed)
                    put("fileModelConfirmed", fileModelConfirmed)
                    put("resumeConfirmed", resumeConfirmed)
                    put("resumeClicks", resumeClicks)
                    put("newThreadFlow", newThreadMode)
                    put("newThreadBackClean", newThreadBackClean)
                    put("assistantCompleted", assistantCompleted)
                    put("stopConfirmed", stopConfirmed)
                    put("observedAbortedEvent", trace.abortedAfterStop())
                    put("backgroundObserved", backgroundObserved)
                    put("reconnected", reconnected)
                    put("sendClicks", sendClicks)
                    put("finalUserCount", finalUsers)
                    put("observedRunCount", finalRuns)
                    put("cleanupOk", cleanupOk)
                }.toString())
            } catch (_: Throwable) { cleanupOk = false }
            check(cleanupOk) { "Real-model cleanup failed" }
        }
    }

    private fun pickTestDocument(name: String, bytes: ByteArray): android.net.Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val uri = requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            compose.onNodeWithTag("composer-attach").performClick()
            compose.onNode(hasText("文件") and hasAnyAncestor(hasTestTag("attachment-pickers"))).performClick()
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?, match: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (match(node)) return node
                for (i in 0 until node.childCount) find(node.getChild(i), match)?.let { return it }
                return null
            }
            fun click(node: android.view.accessibility.AccessibilityNodeInfo) {
                var target: android.view.accessibility.AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                check(target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
            }
            await(10_000) { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            if (find(automation.rootInActiveWindow) { it.text?.toString() == name } == null) {
                click(requireNotNull(find(automation.rootInActiveWindow) { it.contentDescription?.toString() == "Show roots" }))
                await(5_000) { find(automation.rootInActiveWindow) { it.text?.toString() == "Downloads" } != null }
                click(requireNotNull(find(automation.rootInActiveWindow) { it.text?.toString() == "Downloads" }))
            }
            await(10_000) { find(automation.rootInActiveWindow) { it.text?.toString() == name } != null }
            click(requireNotNull(find(automation.rootInActiveWindow) { it.text?.toString() == name }))
            await(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("composer-send").assertIsEnabled()
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun send(text: String) {
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("thread-composer"))).performTextReplacement(text)
        compose.onNodeWithTag("composer-send").assertIsEnabled().performClick()
    }

    private fun await(milliseconds: Long, predicate: () -> Boolean) =
        awaitUntil(SystemClock.elapsedRealtime() + milliseconds, predicate)

    private fun awaitUntil(deadline: Long, predicate: () -> Boolean) {
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        error("Real-model bounded wait expired")
    }

    private fun <T> rpc(block: suspend () -> T): T = runBlocking {
        withTimeout(30_000) { block() }
    }

    private fun snapshot(client: DesktopRpcClient, session: String, deadline: Long): List<JsonObject> {
        val remaining = deadline - SystemClock.elapsedRealtime()
        require(remaining > 0)
        val result = rpc {
            client.call("session:get", listOf(JsonPrimitive(session)), minOf(remaining, 10_000))
        }.jsonObject
        val entries = (result["entries"] as? JsonArray)?.map { it.jsonObject } ?: error("Missing entries")
        // Desktop system instructions request the built-in title metadata tool on a new chat.
        // Explicit variants permit ask, the test-owned attachment read, or one subagent launch.
        // Process and web tools remain excluded.
        require(entries.none { (it.string("kind").orEmpty().contains("tool", ignoreCase = true) ||
            !it.string("toolName").isNullOrBlank()) && it.string("toolName") !in (setOf("title") + (if (allowAskTool) setOf("ask") else emptySet()) + (if (allowReadTool) setOf("read") else emptySet()) + (if (allowSpawnTool) setOf("spawn_agent") else emptySet())) })
        return entries
    }

    private fun verifyUsers(entries: List<JsonObject>, prompts: List<String>) {
        val users = entries.filter { it.string("kind") == "user" }
        require(users.size == prompts.size && users.map { it.string("id") }.distinct().size == prompts.size)
        require(users.map { entry ->
            (entry["content"] as? JsonArray).orEmpty().mapNotNull { block ->
                (block as? JsonObject)?.takeIf { it.string("type") == "text" }?.string("text")
            }.joinToString("\n")
        } == prompts)
    }

    private fun nonblankText(entry: JsonObject): Boolean =
        (entry["content"] as? JsonArray).orEmpty().any { block ->
            val obj = block as? JsonObject
            obj != null && obj.string("type") == "text" && !obj.string("text").isNullOrBlank()
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Stores identities/flags only, never event content, exception details, or credentials. */
    private class Trace(private val allowAsk: Boolean = false, private val allowRead: Boolean = false, private val allowSpawn: Boolean = false) {
        private val allowedTools = setOf("title") + (if (allowAsk) setOf("ask") else emptySet()) + (if (allowRead) setOf("read") else emptySet()) + (if (allowSpawn) setOf("spawn_agent") else emptySet())
        private var spawned = false
        @Synchronized fun spawnObserved() = spawned
        private var read = false
        @Synchronized fun readObserved() = read
        private var session: String? = null
        private val metadata = ArrayDeque<Pair<Boolean, Long>>()
        @Synchronized fun trackMetadata(data: JsonObject) {
            if (data["id"]?.jsonPrimitive?.content != session) return
            val time = data["updatedAt"]?.jsonPrimitive?.longOrNull ?: return
            if (metadata.size == 16) metadata.removeFirst()
            metadata.addLast((data["unread"] == JsonPrimitive(true)) to time)
        }
        @Synchronized fun metadataDeltas(baseline: Long) = buildJsonArray {
            metadata.forEach { (unread, time) -> add(buildJsonObject { put("unread", unread); put("deltaMs", time - baseline) }) }
        }
        private val runs = linkedSetOf<String>()
        private val ended = mutableSetOf<String>()
        private var stopping: String? = null
        private var aborted = false
        private var bad = false
        private val early = mutableListOf<JsonObject>()
        @Synchronized fun session(id: String) {
            session = id
            val buffered = early.toList(); early.clear()
            buffered.forEach(::accept)
        }
        @Synchronized fun failed() { bad = true }
        @Synchronized fun unhealthy() = bad
        @Synchronized fun runCount() = runs.size
        @Synchronized fun secondEnded() = runs.lastOrNull() in ended
        @Synchronized fun requestStop(expectedRuns: Int) {
            check(runs.size == expectedRuns && runs.last() !in ended)
            stopping = runs.last()
        }
        @Synchronized fun abortedAfterStop() = aborted
        @Synchronized fun accept(payload: JsonObject) {
            fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
            if (session == null) {
                val event = payload["event"] as? JsonObject ?: return
                val type = event.str("type") ?: return
                if (type !in setOf("agent-start", "agent-end", "aborted", "error", "tool-start")) return
                if (event["nested"] == JsonPrimitive(true) || event.str("parentToolCallId") != null) return
                // Buffer identities/flags only until the create response identifies the new session.
                if (early.size >= 1000) { bad = true; return }
                early += buildJsonObject {
                    put("sessionId", payload.str("sessionId")); put("runId", payload.str("runId"))
                    put("event", buildJsonObject { put("type", type); if (type == "tool-start") put("toolName", event.str("toolName").takeIf { it in allowedTools } ?: "unexpected") })
                }
                return
            }
            if (payload.str("sessionId") != session) return
            val event = payload["event"] as? JsonObject ?: return
            if (event["nested"] == JsonPrimitive(true) || event.str("parentToolCallId") != null) return
            val run = payload.str("runId") ?: return
            when (event.str("type")) {
                "agent-start" -> runs.add(run)
                "agent-end" -> ended.add(run)
                "aborted" -> if (run == stopping) aborted = true
                "error" -> bad = true
                "tool-start" -> { if (event.str("toolName") !in allowedTools) bad = true; if (event.str("toolName") == "read") read = true; if (event.str("toolName") == "spawn_agent") spawned = true }
            }
        }
    }
}
