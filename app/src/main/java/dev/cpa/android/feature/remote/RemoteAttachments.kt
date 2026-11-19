package dev.cpa.android.feature.remote

import androidx.annotation.StringRes
import dev.cpa.android.R

import androidx.lifecycle.SavedStateHandle
import dev.cpa.android.core.attachments.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AttachmentState(val items: List<LocalAttachment> = emptyList(), val processing: Int = 0,
    val locked: Boolean = false, @param:StringRes val error: Int? = null)
class RemoteAttachments(private val saved: SavedStateHandle, private val key: String, private val scope: CoroutineScope) {
    private val serial = Mutex()
    private val mutable = MutableStateFlow(AttachmentState(
        items = runCatching { Json.decodeFromString<List<LocalAttachment>>(saved.get<String>(key) ?: "[]") }.getOrDefault(emptyList()),
        error = if (saved.get<Boolean>("$key:reading") == true) R.string.attachment_read_interrupted else null))
    val state = mutable.asStateFlow()
    init { saved["$key:reading"] = false }
    private fun persist() { saved[key] = Json.encodeToString(mutable.value.items); saved["$key:reading"] = mutable.value.processing > 0 }
    fun error(@StringRes message: Int) { mutable.update { it.copy(error = message) } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun enqueue(reads: List<suspend () -> LocalAttachment>): Boolean {
        if (mutable.value.locked || reads.isEmpty()) return false
        mutable.update { it.copy(processing = it.processing + reads.size, error = null) }; persist()
        scope.launch {
            var remaining = reads.size
            try {
                serial.withLock {
                    for (read in reads) {
                        try {
                            val item = read()
                            mutable.update { it.copy(items = it.items + item) }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error(R.string.attachment_partial_failure) }
                        remaining--; mutable.update { it.copy(processing = it.processing - 1) }; persist()
                    }
                }
            } finally { mutable.update { it.copy(processing = (it.processing - remaining).coerceAtLeast(0)) }; persist() }
        }
        return true
    }
    fun requireImageSupport(caps: dev.cpa.android.data.repository.RemoteCapabilities, model: String?) {
        val images = mutable.value.items.filter { it.image }
        if (images.isEmpty()) return
        if (caps.availableModels.none { it.id == model && it.supportsImages }) throw AttachmentValidationException(R.string.attachment_image_unsupported)
        if (images.sumOf { it.size } > MAX_ATTACHMENT_BYTES) throw AttachmentValidationException(R.string.attachment_images_too_large)
    }
    fun lock(): Boolean {
        if (mutable.value.processing > 0 || mutable.value.locked) return false
        mutable.update { it.copy(locked = true) }; return true
    }
    fun unlock() { mutable.update { it.copy(locked = false) } }
    fun remove(id: String) {
        if (mutable.value.locked) return
        val removed = mutable.value.items.filter { it.id == id }
        mutable.update { it.copy(items = it.items.filterNot { item -> item.id == id }) }; persist()
        scope.launch(Dispatchers.IO) { removed.forEach(LocalAttachment::deleteOwnedFiles) }
    }
    fun clearSent() {
        val removed = mutable.value.items
        mutable.update { it.copy(items = emptyList(), error = null) }; persist()
        scope.launch(Dispatchers.IO) { removed.forEach(LocalAttachment::deleteOwnedFiles) }
    }
}

internal class AttachmentValidationException(@param:StringRes val messageResource: Int) : IllegalArgumentException("Attachment validation failed")
