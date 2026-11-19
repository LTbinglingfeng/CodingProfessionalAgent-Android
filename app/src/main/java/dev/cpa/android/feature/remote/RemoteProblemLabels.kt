package dev.cpa.android.feature.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.cpa.android.R
import dev.cpa.android.data.repository.RemoteProblem

@Composable
internal fun repositoryProblem(problem: RemoteProblem): String = stringResource(when (problem) {
    RemoteProblem.StorageReadFailed -> R.string.storage_read_failed
    RemoteProblem.RecentGatewaySaveFailed -> R.string.recent_gateway_save_failed
    RemoteProblem.StorageWriteFailed -> R.string.storage_write_failed
    RemoteProblem.ConnectionRemoved -> R.string.connection_removed
    RemoteProblem.ConnectionTimeout -> R.string.connection_timeout
    RemoteProblem.TunnelCredentials -> R.string.tunnel_credentials
    RemoteProblem.DesktopPassword -> R.string.desktop_password
    RemoteProblem.DesktopUnavailable -> R.string.desktop_unavailable
    RemoteProblem.ConnectionSetupFailed -> R.string.connection_setup_failed
    RemoteProblem.PartialRefresh -> R.string.partial_refresh
    RemoteProblem.OutcomeUnknown -> R.string.repository_outcome_unknown
    RemoteProblem.SessionLoadFailed -> R.string.repository_session_load_failed
    RemoteProblem.RequestRejected -> R.string.repository_request_rejected
    RemoteProblem.SessionDeleted -> R.string.session_deleted
    RemoteProblem.RunError -> R.string.repository_run_error
})
