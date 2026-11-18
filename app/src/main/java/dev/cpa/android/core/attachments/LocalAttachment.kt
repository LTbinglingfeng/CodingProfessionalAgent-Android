package dev.cpa.android.core.attachments

import kotlinx.serialization.Serializable
import java.io.File

const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

/** Bytes stay in private cache files, never in SavedState/Bundle or a long-lived bitmap list. */
@Serializable
data class LocalAttachment(val id: String, val name: String, val mimeType: String, val image: Boolean,
    val path: String, val size: Long, val thumbnail: String? = null)

fun LocalAttachment.checkedFile(): File {
    val file = File(path)
    require(size in 0..MAX_ATTACHMENT_BYTES && file.isFile && file.length() == size) { "附件已不可用，请重新选择" }
    return file
}

fun LocalAttachment.deleteOwnedFiles() {
    listOfNotNull(path, thumbnail).forEach { path ->
        val file = File(path)
        if (file.parentFile?.name == "composer-attachments" && (file.name == id || file.name == "$id.thumb.jpg")) file.delete()
    }
}

fun attachmentPrompt(text: String, paths: List<String>): String =
    if (paths.isEmpty()) text else (if (text.isEmpty()) "" else "$text\n\n") + paths.joinToString(" ") { "@$it" }

fun safeAttachmentName(name: String): String = name.map { if (it == '/' || it == '\\' || it == ':' || it.isISOControl()) '_' else it }
    .joinToString("").trim().takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "file"

fun numberedAttachmentName(name: String, number: Int): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0 && dot < name.lastIndex) "${name.take(dot)} ($number)${name.substring(dot)}" else "$name ($number)"
}
