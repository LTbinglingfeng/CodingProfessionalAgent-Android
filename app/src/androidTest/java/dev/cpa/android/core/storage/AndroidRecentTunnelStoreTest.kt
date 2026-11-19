package dev.cpa.android.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AndroidRecentTunnelStoreTest {
    @Test fun recentGatewayPersistsEncryptedAcrossStoreRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "recent-test-${UUID.randomUUID()}"
        val file = File(context.noBackupFilesDir, "$namespace.v1.enc")
        try {
            val store = AndroidRecentTunnelStore(context, namespace)
            store.save(RecentTunnel("wss://gateway.test/v1/tunnel", "private-key"))
            assertEquals(RecentTunnel("https://gateway.test", "private-key"), AndroidRecentTunnelStore(context, namespace).load())
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("private-key"))
        } finally {
            AndroidVaultFile { file }.delete()
            AndroidVaultKeys("dev.cpa.android.$namespace.v1").delete()
        }
    }
}
