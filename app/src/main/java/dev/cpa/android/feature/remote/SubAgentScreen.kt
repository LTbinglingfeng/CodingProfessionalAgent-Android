package dev.cpa.android.feature.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cpa.android.R
import dev.cpa.android.core.designsystem.*
import dev.cpa.android.core.storage.ConnectionIcon
import dev.cpa.android.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Read-only run view of one sub-agent (desktop SubAgentConversation). [vm] belongs to the parent session;
 * the record is looked up by [agentId] in the parent's sub-agent list and the child conversation is observed.
 */
@Composable
internal fun SubAgentRoute(vm: RemoteThreadViewModel, agentId: String, icons: Map<String, String>, onSubAgent: (RemoteSubAgent) -> Unit, onBack: () -> Unit) {
    val c = Cpa.colors
    val parent by vm.thread.collectAsStateWithLifecycle()
    val record = parent.subAgents.firstOrNull { it.id == agentId }
    val sessionId = record?.conversationId
    val childFlow = remember(sessionId) { sessionId?.let { vm.observeChild(it) } }
    val child = childFlow?.collectAsStateWithLifecycle()?.value
    val connections by vm.connections.collectAsStateWithLifecycle()
    val capabilities by vm.capabilities.collectAsStateWithLifecycle()
    val wrapCode by vm.wrapCode.collectAsStateWithLifecycle()
    val connection = connections.find { it.id == vm.connectionId }
    val connected = connection?.phase == ConnectionPhase.Connected
    val caps = capabilities[vm.connectionId] ?: RemoteCapabilities()
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember(sessionId) { mutableIntStateOf(0) }
    LaunchedEffect(sessionId, connected, retry) {
        if (!connected || sessionId == null) return@LaunchedEffect
        loadFailed = false
        try { vm.loadChild(sessionId) } catch (e: CancellationException) { throw e } catch (_: Exception) { loadFailed = true }
    }
    var now by remember { mutableDoubleStateOf(System.currentTimeMillis().toDouble()) }
    LaunchedEffect(record?.inProgress) { while (record?.inProgress == true) { now = System.currentTimeMillis().toDouble(); delay(1000) } }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(CpaIcons.ChevronLeft, stringResource(R.string.ui_x_back), onBack, modifier = Modifier.testTag("subagent-back"))
                Spacer(Modifier.width(12.dp))
                SubAgentAvatar(record?.icon, record?.color, 28)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(record?.name ?: stringResource(R.string.ui_subagent_title), Modifier.semantics { heading() }, style = cpaText(17, FontWeight.SemiBold),
                        color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (record != null) {
                        val model = caps.models.find { it.id == record.modelId }
                        val parts = listOf("${subAgentStatus(record.status)} ${formatTurnDuration(record.elapsed(now))}", model?.label ?: record.modelId,
                            record.reasoningEffort?.takeIf(String::isNotEmpty)?.let { effort -> reasoningLabel(effort, model?.reasoningLevels?.find { it.id == effort }?.label ?: effort) }.orEmpty())
                            .filter(String::isNotEmpty)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(parts.joinToString(" · "), style = cpaText(13), color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (model?.supportsFast == true && (record.speed ?: "standard") != "standard") CpaIcon(CpaIcons.Bolt, stringResource(R.string.ui_subagent_fast), size = 12.dp, tint = c.skillChip)
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (record != null && child != null) key(sessionId, child.entries.isEmpty()) {
                    RemoteTimeline(child.entries, child.loading, Modifier.fillMaxSize(), wrapCode = wrapCode, running = record.inProgress,
                        startsFollowing = record.inProgress, runningTools = child.runningTools, subAgents = child.subAgents, onSubAgent = onSubAgent,
                        skillTrigger = caps.defaults.skillTrigger, emptyText = false,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 24.dp))
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val name = connection?.displayName().orEmpty()
                    when {
                        child?.entries?.isNotEmpty() == true -> Unit
                        connection != null && !connected && connection.phase != ConnectionPhase.Connecting -> EmptyState(CpaIcons.BoltOutline,
                            stringResource(R.string.ui_thread_cannot_connect_title), stringResource(R.string.ui_thread_cannot_connect_detail, name),
                            actionTitle = stringResource(R.string.ui_common_retry), onAction = vm::refreshCapabilities)
                        loadFailed || child?.error != null -> EmptyState(CpaIcons.Warning, stringResource(R.string.ui_thread_load_failed_title),
                            child?.error?.let { repositoryProblem(it) } ?: stringResource(R.string.subagent_load_failed),
                            actionTitle = stringResource(R.string.ui_common_retry), onAction = { retry++ })
                        record == null && !parent.loading && parent.entries.isNotEmpty() -> Text(stringResource(R.string.subagent_not_loaded), style = cpaText(13), color = c.secondaryLabel)
                        child == null || child.loading || !connected -> ConversationLoading(ConnectionIcon.of(icons[vm.connectionId]), name, connected)
                        record?.inProgress != true -> Text(stringResource(R.string.ui_subagent_no_messages), style = cpaText(13), color = c.secondaryLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun subAgentStatus(status: String): String = when (status) {
    "queued" -> stringResource(R.string.ui_subagent_status_queued)
    "running" -> stringResource(R.string.ui_subagent_status_running)
    "completed" -> stringResource(R.string.ui_subagent_status_completed)
    "error" -> stringResource(R.string.ui_subagent_status_error)
    "aborted" -> stringResource(R.string.ui_subagent_status_aborted)
    else -> status
}
