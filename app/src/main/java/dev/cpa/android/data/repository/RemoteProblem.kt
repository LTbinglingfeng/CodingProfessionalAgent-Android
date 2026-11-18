package dev.cpa.android.data.repository

/** Client-generated repository diagnostics. Remote entry/agent error messages remain raw strings. */
enum class RemoteProblem {
    StorageReadFailed,
    RecentGatewaySaveFailed,
    StorageWriteFailed,
    ConnectionRemoved,
    ConnectionTimeout,
    TunnelCredentials,
    DesktopPassword,
    DesktopUnavailable,
    ConnectionSetupFailed,
    PartialRefresh,
    OutcomeUnknown,
    SessionLoadFailed,
    RequestRejected,
    SessionDeleted,
    RunError,
}
