package dev.cpa.android.core.storage

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import java.io.File

internal class AndroidRecentTunnelStore(context: Context, namespace: String = "recent-tunnel") : RecentTunnelStore {
    init { require(namespace.matches(Regex("[a-z0-9-]+"))) }
    private val appContext = context.applicationContext
    private val delegate = EncryptedRecentTunnelStore(AndroidVaultKeys("dev.cpa.android.$namespace.v1"),
        AndroidVaultFile { File(appContext.noBackupFilesDir, "$namespace.v1.enc") }, mutex)
    override suspend fun load() = delegate.load()
    override suspend fun save(value: RecentTunnel) = delegate.save(value)
    private companion object { val mutex = Mutex() }
}
