package dev.cpa.android.core.storage

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AndroidHomePreferencesTest {
    @Test fun cancellationOnReturnFromIoKeepsFlowInSyncWithDisk() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-cancel-${UUID.randomUUID()}"
        val queue = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        try {
            val store = AndroidHomePreferences(context, name)
            val write = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { store.toggleGroup("g") }
            // IO has finished; hold the dispatched return until the original caller is cancelled.
            val resume = requireNotNull(queue.poll(10, TimeUnit.SECONDS))
            write.cancel(); resume.run(); write.join()
            assertEquals(setOf("g"), AndroidHomePreferences(context, name).collapsedGroups.value)
            assertEquals(setOf("g"), store.collapsedGroups.value)
            store.toggleGroup("g")
            assertTrue(AndroidHomePreferences(context, name).collapsedGroups.value.isEmpty())
        } finally { context.deleteSharedPreferences(name) }
    }
    @Test fun collapsedGroupsSurviveReopenAndTogglingOneDoesNotEraseOthers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-home-${UUID.randomUUID()}"
        try {
            val store = AndroidHomePreferences(context, name)
            store.toggleGroup("device-a/project")
            store.toggleGroup("device-b/project")
            store.toggleGroup("device-a/unassigned")
            val reopened = AndroidHomePreferences(context, name)
            assertEquals(store.collapsedGroups.value, reopened.collapsedGroups.value)
            reopened.toggleGroup("device-a/project")
            assertEquals(setOf("device-b/project", "device-a/unassigned"), AndroidHomePreferences(context, name).collapsedGroups.value)
        } finally { context.deleteSharedPreferences(name) }
    }
}
