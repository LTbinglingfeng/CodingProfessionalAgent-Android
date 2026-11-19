package dev.cpa.android.feature.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cpa.android.R

@Composable
internal fun agentStatusLabel(status: String): String = when (status) {
    "queued" -> stringResource(R.string.agent_queued)
    "running" -> stringResource(R.string.agent_running)
    "completed" -> stringResource(R.string.agent_completed)
    "error" -> stringResource(R.string.agent_failed)
    "aborted" -> stringResource(R.string.agent_aborted)
    else -> status
}

@Composable
internal fun agentPillLabel(pill: RemoteAgentPill): String = when {
    pill.queued -> stringResource(R.string.agent_queued)
    pill.running -> stringResource(R.string.agent_running)
    pill.record != null -> agentStatusLabel(pill.record.status)
    else -> stringResource(R.string.agent_waiting_start)
}
