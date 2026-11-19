package dev.cpa.android.core.storage

enum class ConnectionMode { Direct, Tunnel }

/** Secrets never enter UI state, SavedStateHandle, logging or backup. */
data class StoredConnection(
    val id: String, val name: String, val address: String,
    val password: String, val allowCleartext: Boolean, val enabled: Boolean = true,
    val mode: ConnectionMode = ConnectionMode.Direct,
    val tunnelBaseUrl: String = "", val tunnelApiKey: String = "",
    val tunnelServerId: String = "", val tunnelServerName: String = ""
) {
    override fun toString(): String = "StoredConnection(id=$id, credentials=REDACTED)"
}
interface ConnectionVault {
    suspend fun load(): List<StoredConnection>
    suspend fun upsert(connection: StoredConnection)
    suspend fun replaceAll(connections: List<StoredConnection>): Unit = throw UnsupportedOperationException()
    suspend fun remove(id: String)
}
