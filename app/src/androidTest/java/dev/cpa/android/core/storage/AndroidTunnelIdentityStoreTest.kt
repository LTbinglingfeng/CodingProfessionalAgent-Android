package dev.cpa.android.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AndroidTunnelIdentityStoreTest {
    @Test fun identitySurvivesNewInstancesAndPersistsRotation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "identity-test-${UUID.randomUUID()}"
        try {
            val first = AndroidTunnelIdentityStore(context, name)
            val old = first.current()
            assertEquals(old, AndroidTunnelIdentityStore(context, name).current())
            val rotated = first.rotate()
            assertNotEquals(old, rotated)
            assertEquals(rotated, AndroidTunnelIdentityStore(context, name).current())
        } finally { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }
}
