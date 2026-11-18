package dev.cpa.android.core.storage

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AndroidComposerPreferencesTest {
    @Test fun composerPreferencesPersistAcrossReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-composer-${UUID.randomUUID()}"
        try {
            val first = AndroidComposerPreferences(context, name)
            assertTrue(first.wrapCode.value)
            assertTrue(first.collapseKeyboardAfterSend.value); assertFalse(first.showContextUsage.value)
            first.setCollapseKeyboardAfterSend(false); first.setShowContextUsage(true)
            val reopened = AndroidComposerPreferences(context, name)
            assertFalse(reopened.collapseKeyboardAfterSend.value); assertTrue(reopened.showContextUsage.value)
            first.setWrapCode(false)
            assertFalse(first.wrapCode.value)
            assertFalse(AndroidComposerPreferences(context, name).wrapCode.value)
        } finally { context.deleteSharedPreferences(name) }
    }
}
