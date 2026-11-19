package dev.cpa.android.feature.remote

import android.content.ContentValues
import android.provider.MediaStore
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import dev.cpa.android.core.designsystem.CpaTheme
import dev.cpa.android.core.localization.AppLocaleRule
import dev.cpa.android.core.attachments.deleteOwnedFiles
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses the installed DocumentsUI activity and its real URI grant, with a test-owned download. */
class RemoteDocumentPickerTest {
    @get:Rule(order = 0) val language = AppLocaleRule("en")
    @get:Rule(order = 1) val compose = createComposeRule()

    @Test fun systemDocumentSelectionReadsBytesAndCancellationPreservesDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val resolver = context.contentResolver
        val name = "cpa-picker-${UUID.randomUUID()}.txt"
        val bytes = "Android document picker 中文 😀".toByteArray()
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = RemoteAttachments(SavedStateHandle(), "picker", scope)
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            compose.setContent { CpaTheme { Column { RemoteAttachmentComposer(controller, true) } } }
            compose.onNodeWithText("Files").performClick()
            waitFor { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            val fileNode = find(automation.rootInActiveWindow) { it.text?.toString() == name }
            if (fileNode == null) {
                click(requireNotNull(find(automation.rootInActiveWindow) { it.contentDescription?.toString() == "Show roots" }))
                waitFor { find(automation.rootInActiveWindow) { it.text?.toString() == "Downloads" } != null }
                click(requireNotNull(find(automation.rootInActiveWindow) { it.text?.toString() == "Downloads" }))
            }
            waitFor { find(automation.rootInActiveWindow) { it.text?.toString() == name } != null }
            click(requireNotNull(find(automation.rootInActiveWindow) { it.text?.toString() == name }))
            compose.waitUntil(10_000) { controller.state.value.items.size == 1 && controller.state.value.processing == 0 }
            val item = controller.state.value.items.single()
            assertEquals(name, item.name)
            assertFalse(item.image)
            assertArrayEquals(bytes, File(item.path).readBytes())
            assertNull(controller.state.value.error)
            compose.onNodeWithText(name).assertExists()
            compose.onNodeWithText("Files").performClick()
            waitFor { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui") == true }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor { automation.rootInActiveWindow?.packageName?.toString() == context.packageName }
            compose.runOnIdle {
                assertEquals(listOf(item), controller.state.value.items)
                assertEquals(0, controller.state.value.processing)
                assertNull(controller.state.value.error)
            }
        } finally {
            controller.state.value.items.forEach { it.deleteOwnedFiles() }
            scope.cancel()
            resolver.delete(uri, null, null)
        }
    }

    @Test fun systemPhotoPickerImportsImageAndCancellationPreservesAttachment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "cpa-picker-${UUID.randomUUID()}.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        val bitmap = android.graphics.Bitmap.createBitmap(32, 24, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = RemoteAttachments(SavedStateHandle(), "photo-picker", scope)
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            compose.setContent { CpaTheme { Column { RemoteAttachmentComposer(controller, true) } } }
            compose.onNodeWithText("Photos").performClick()
            waitFor { find(automation.rootInActiveWindow) { it.contentDescription?.toString()?.startsWith("Photo taken") == true } != null }
            click(requireNotNull(find(automation.rootInActiveWindow) { it.contentDescription?.toString()?.startsWith("Photo taken") == true }))
            waitFor { find(automation.rootInActiveWindow) { it.text?.toString()?.startsWith("Add") == true && it.isEnabled } != null }
            click(requireNotNull(find(automation.rootInActiveWindow) { it.text?.toString()?.startsWith("Add") == true && it.isEnabled }))
            compose.waitUntil(10_000) { controller.state.value.items.size == 1 && controller.state.value.processing == 0 }
            val item = controller.state.value.items.single()
            assertTrue(item.image)
            assertEquals("image/png", item.mimeType)
            assertTrue(File(requireNotNull(item.thumbnail)).length() > 0)
            val decoded = requireNotNull(android.graphics.BitmapFactory.decodeFile(item.path))
            try { assertEquals(32, decoded.width); assertEquals(24, decoded.height) } finally { decoded.recycle() }
            compose.onNodeWithText("Photos").performClick()
            waitFor { automation.rootInActiveWindow?.packageName?.toString() != context.packageName }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor { automation.rootInActiveWindow?.packageName?.toString() == context.packageName }
            compose.runOnIdle {
                assertEquals(listOf(item), controller.state.value.items)
                assertEquals(0, controller.state.value.processing)
                assertNull(controller.state.value.error)
            }
        } finally {
            bitmap.recycle()
            controller.state.value.items.forEach { it.deleteOwnedFiles() }
            scope.cancel()
            resolver.delete(uri, null, null)
        }
    }

    @Test fun systemCameraConfirmImportsPhotoAndCancelCleansTemporaryOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val folder = File(context.cacheDir, "composer-camera")
        val baseline = folder.listFiles().orEmpty().map { it.name }.toSet()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = RemoteAttachments(SavedStateHandle(), "camera", scope)
        fun cameraNode(label: String) = find(automation.rootInActiveWindow) {
            it.contentDescription?.toString()?.contains(label, ignoreCase = true) == true ||
                it.text?.toString()?.equals(label, ignoreCase = true) == true
        }
        fun awaitCameraControl(label: String) {
            waitFor {
                find(automation.rootInActiveWindow) {
                    it.viewIdResourceName?.endsWith("permission_allow_foreground_only_button") == true ||
                        it.viewIdResourceName?.endsWith("permission_allow_button") == true
                }?.let { click(it) }
                cameraNode(label) != null
            }
        }
        try {
            compose.setContent { CpaTheme { Column { RemoteAttachmentComposer(controller, true) } } }
            compose.onNodeWithText("Camera").performClick()
            awaitCameraControl("Shutter")
            click(requireNotNull(cameraNode("Shutter")))
            awaitCameraControl("Done")
            click(requireNotNull(cameraNode("Done")))
            compose.waitUntil(10_000) { controller.state.value.items.size == 1 && controller.state.value.processing == 0 }
            val item = controller.state.value.items.single()
            assertTrue(item.image)
            assertEquals("image/jpeg", item.mimeType)
            assertTrue(File(item.path).length() > 0)
            assertTrue(File(requireNotNull(item.thumbnail)).length() > 0)
            waitFor { folder.listFiles().orEmpty().map { it.name }.toSet() == baseline }
            compose.onNodeWithText("Camera").performClick()
            awaitCameraControl("Shutter")
            assertTrue(folder.listFiles().orEmpty().any { it.name !in baseline })
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor { automation.rootInActiveWindow?.packageName?.toString() == context.packageName }
            waitFor { folder.listFiles().orEmpty().map { it.name }.toSet() == baseline }
            compose.runOnIdle {
                assertEquals(listOf(item), controller.state.value.items)
                assertEquals(0, controller.state.value.processing)
                assertNull(controller.state.value.error)
            }
        } finally {
            controller.state.value.items.forEach { it.deleteOwnedFiles() }
            scope.cancel()
            folder.listFiles().orEmpty().filter { it.name !in baseline }.forEach { it.delete() }
        }
    }

    @Test fun unavailableExternalAppsReportErrorsAndCameraFileIsRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "composer-camera")
        val baseline = folder.listFiles().orEmpty().map { it.name }.toSet()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = RemoteAttachments(SavedStateHandle(), "unavailable", scope)
        var attempts = 0
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner {
            override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int,
                    contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                    input: I, options: androidx.core.app.ActivityOptionsCompat?) {
                    attempts++
                    throw android.content.ActivityNotFoundException("Controlled missing external app")
                }
            }
        }
        try {
            compose.setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides owner
                ) { CpaTheme { Column { RemoteAttachmentComposer(controller, true) } } }
            }
            compose.onNodeWithText("Files").performClick()
            compose.onNodeWithText("File picker unavailable.").assertExists()
            compose.onNodeWithText("Photos").performClick()
            compose.onNodeWithText("Photo picker unavailable.").assertExists()
            compose.onNodeWithText("Camera").performClick()
            compose.waitUntil(5_000) { controller.state.value.error == dev.cpa.android.R.string.camera_unavailable }
            compose.onNodeWithText("Could not open the camera. Check the camera app and its permissions.").assertExists()
            compose.onNodeWithText("Camera").assertIsEnabled()
            compose.runOnIdle {
                assertEquals(3, attempts)
                assertTrue(controller.state.value.items.isEmpty())
                assertEquals(0, controller.state.value.processing)
                assertEquals(baseline, folder.listFiles().orEmpty().map { it.name }.toSet())
            }
        } finally {
            scope.cancel()
            folder.listFiles().orEmpty().filter { it.name !in baseline }.forEach { it.delete() }
        }
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "System document picker did not reach expected state" }
            Thread.sleep(100)
        }
    }
}
