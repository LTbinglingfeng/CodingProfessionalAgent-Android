package dev.cpa.android.feature.remote

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.attachments.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAttachmentsTest {
    private fun item(id: String) = LocalAttachment(id, "$id.txt", "text/plain", false, "/cache/$id", 1)
    @Test fun batchesReadSeriallyKeepSuccessesAndDoNotAllowSendWhileProcessing() = runTest {
        val gate = CompletableDeferred<Unit>(); val order = mutableListOf<String>()
        val controller = RemoteAttachments(SavedStateHandle(), "files", backgroundScope)
        controller.enqueue(listOf(suspend { order += "a"; gate.await(); item("a") }, suspend { error("unreadable") }))
        controller.enqueue(listOf(suspend { order += "b"; item("b") }))
        runCurrent()
        assertFalse(controller.lock()); assertEquals(listOf("a"), order)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("a", "b"), order)
        assertEquals(listOf("a", "b"), controller.state.value.items.map { it.id })
        assertEquals(0, controller.state.value.processing); assertNotNull(controller.state.value.error)
        assertTrue(controller.lock())
        controller.remove("a"); controller.enqueue(listOf(suspend { item("c") })); runCurrent()
        assertEquals(2, controller.state.value.items.size)
    }
    @Test fun restoredDraftContainsDescriptorsNotFileBytesAndRecoversInterruptedReads() = runTest {
        val saved = SavedStateHandle()
        val controller = RemoteAttachments(saved, "files", backgroundScope)
        controller.enqueue(listOf(suspend { item("a") })); runCurrent()
        saved["files:reading"] = true
        val restored = RemoteAttachments(saved, "files", backgroundScope)
        assertEquals(listOf(item("a")), restored.state.value.items)
        assertEquals(0, restored.state.value.processing)
        assertNotNull(restored.state.value.error)
        assertTrue(saved.get<String>("files")!!.length < 300)
    }
    @Test fun imageValidationPreservesTypedReasonsAndRetainsAttachments() = runTest {
        val controller = RemoteAttachments(SavedStateHandle(), "files", backgroundScope)
        controller.enqueue(listOf(suspend { item("image").copy(image = true, size = MAX_ATTACHMENT_BYTES + 1) }))
        runCurrent()
        val unsupported = assertThrows(AttachmentValidationException::class.java) {
            controller.requireImageSupport(dev.cpa.android.data.repository.RemoteCapabilities(), "missing")
        }
        assertEquals(dev.cpa.android.R.string.attachment_image_unsupported, unsupported.messageResource)
        val tooLarge = assertThrows(AttachmentValidationException::class.java) {
            controller.requireImageSupport(dev.cpa.android.data.repository.RemoteCapabilities(
                models = listOf(dev.cpa.android.data.repository.RemoteModel("vision", supportsImages = true))), "vision")
        }
        assertEquals(dev.cpa.android.R.string.attachment_images_too_large, tooLarge.messageResource)
        assertEquals(1, controller.state.value.items.size)
        assertFalse(controller.state.value.locked)
    }
    @Test fun pathsAreAppendedAndFilenamesCannotEscapeRemoteFolder() {
        assertEquals("explain\n\n@/home/report.txt @C:/data/file.csv", attachmentPrompt("explain", listOf("/home/report.txt", "C:/data/file.csv")))
        assertEquals("@/home/file", attachmentPrompt("", listOf("/home/file")))
        assertEquals(".._.._secret_", safeAttachmentName("../../secret\n"))
        assertEquals("file", safeAttachmentName(" .. "))
        assertEquals("archive.tar (2).gz", numberedAttachmentName("archive.tar.gz", 2))
    }
}
